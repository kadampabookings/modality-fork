package one.modality.ecommerce.document.service.spi.impl.server;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Server-internal store for room-share invite tokens (docs/room-mate-booking-plan.md steps 4-5, D5).
 *
 * <p>The table {@code mate_invite_token} is NOT a domain entity: it is read and written here with raw
 * SQL, so it never appears on the client-queryable DQL surface. Under the open-read exposure (security
 * report A1) that is deliberate, and the row stores only a HASH of the token — a leaked read cannot be
 * turned into a working link.
 *
 * <p>Single-use is enforced by the database, not by the application re-reading a flag: {@link #claim}
 * is a conditional {@code UPDATE ... WHERE used_date IS NULL AND expires_date > now()} whose row count
 * is the claim. Two racing consumers cannot both see a row count of 1, so a token resolves at most once.
 */
final class MateInviteTokenStore {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /** 16 bytes = 128 bits of entropy, the same strength MagicLinkService mints. */
    private static final int TOKEN_BYTES = 16;

    /** The room-booking line a booking offers to share, and the facts the mint path checks. */
    record OwnerLine(Object ownerDocumentLineId, Object eventId, Object frontendAccountId) { }

    /** Result of a successful claim: which booking line the mate is now linked to, and its event. */
    record Claim(Object ownerDocumentLineId, Object eventId) { }

    private MateInviteTokenStore() { }

    // --- Pure helpers (unit-tested by MateInviteTokenStoreCheck) ------------------------------------

    /** A fresh URL-safe token: 128 random bits, base64url without padding. */
    static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * The sha-256 of a token, lower-case hex — what the table stores, and what {@link #claim} matches
     * on. Stable across mint and consume so the same token always maps to the same row.
     */
    static String hashToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest)
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (Exception e) { // SHA-256 is always present; a checked exception here would be a JVM fault
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // --- Database operations -----------------------------------------------------------------------

    private static Object dataSourceId() {
        return DataSourceModelService.getDefaultDataSourceModel().getDataSourceId();
    }

    /**
     * Mints a token bound to {@code ownerDocumentLineId} and returns the RAW token (stored only as a
     * hash). {@code expires_date} is the event's end plus a grace window, read from the owner line's
     * event so a token never resolves after the event is over. Caller is responsible for having
     * verified that the account may mint for this line.
     */
    static Future<String> mint(Object ownerDocumentLineId, Object eventId, Object creatorAccountId) {
        String rawToken = generateToken();
        // expires = event end + 2 days grace, computed in SQL so the app holds no clock of its own.
        String sql =
            "insert into mate_invite_token (token_hash, owner_document_line_id, event_id, creator_account_id, expires_date) " +
            "select $1, $2, $3, $4, (e.end_date + interval '2 days') from event e where e.id = $3";
        return SubmitService.executeSubmit(new SubmitArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement(sql)
                .setParameters(hashToken(rawToken), ownerDocumentLineId, eventId, creatorAccountId)
                .build())
            .map(result -> {
                if (result.getRowCount() < 1) // event id did not resolve — nothing inserted
                    throw new IllegalStateException("Could not mint invite token: event not found");
                return rawToken;
            });
    }

    /**
     * Atomically consumes a token: marks it used if and only if it is unused and unexpired, then reads
     * back the booking line and event it points at. Returns null when the token does not resolve
     * (unknown, already used, or expired) — the row count of the conditional update is the single-use
     * gate, so a token yields a Claim at most once.
     */
    static Future<Claim> claim(String rawToken) {
        if (rawToken == null || rawToken.isBlank())
            return Future.succeededFuture(null);
        String hash = hashToken(rawToken);
        String claimSql =
            "update mate_invite_token set used_date = now() " +
            "where token_hash = $1 and used_date is null and expires_date > now()";
        return SubmitService.executeSubmit(new SubmitArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement(claimSql)
                .setParameters(hash)
                .build())
            .compose(result -> {
                if (result.getRowCount() < 1) // not ours to claim: unknown, already used, or expired
                    return Future.succeededFuture(null);
                // We hold the claim; reading the target is now safe.
                return QueryService.executeQuery(new QueryArgumentBuilder()
                        .setDataSourceId(dataSourceId())
                        .setStatement("select owner_document_line_id, event_id from mate_invite_token where token_hash = $1")
                        .setParameters(hash)
                        .build())
                    .map(rs -> rs.getRowCount() < 1 ? null
                        : new Claim(rs.getValue(0, 0), rs.getValue(0, 1)));
            });
    }

    /**
     * Writes the share-mate link: points the mate line at the owner line. This is a raw UPDATE, not a
     * LinkMateToOwnerDocumentLineEvent, because that event is gated to back-office callers
     * (MateLinkRules); here the TOKEN is the authorization. The DB trigger on_share_linked_copy_info
     * then copies the owner's site/item/room/attendances onto the mate. Returns true when a row was
     * updated (the mate line existed and was not already pointing elsewhere-unchanged).
     */
    static Future<Boolean> link(Object mateDocumentLineId, Object ownerDocumentLineId) {
        return SubmitService.executeSubmit(new SubmitArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement("update document_line set share_mate_owner_document_line_id = $2 where id = $1 and share_mate = true")
                .setParameters(mateDocumentLineId, ownerDocumentLineId)
                .build())
            .map(result -> result.getRowCount() >= 1);
    }

    /**
     * Finds the share-owner (room-booking) accommodation line of a booking, with its event and the
     * account that owns the booking (document.person.frontendAccount) — what the mint path needs to
     * check ownership and bind the token. Returns null when the booking has no such line. Read-only
     * raw SQL; the ownership decision is made by the caller.
     */
    static Future<OwnerLine> loadOwnerLineForBooking(Object documentId) {
        return QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement(
                    "select dl.id, d.event_id, p.frontend_account_id " +
                    "from document_line dl join document d on d.id = dl.document_id " +
                    "join person p on p.id = d.person_id " +
                    "join item i on i.id = dl.item_id join item_family f on f.id = i.family_id " +
                    "where dl.document_id = $1 and dl.share_owner = true and f.code = 'acco' and not dl.cancelled " +
                    "order by dl.id limit 1")
                .setParameters(documentId)
                .build())
            .map(rs -> rs.getRowCount() < 1 ? null
                : new OwnerLine(rs.getValue(0, 0), rs.getValue(0, 1), rs.getValue(0, 2)));
    }

    /**
     * The id of the share-mate accommodation line of a just-created booking — the line a consumed
     * token links to its owner. A mate booking has exactly one; returns null if none is found.
     */
    static Future<Object> findMateShareLineId(Object mateDocumentId) {
        return QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement(
                    "select dl.id from document_line dl join item i on i.id = dl.item_id " +
                    "join item_family f on f.id = i.family_id " +
                    "where dl.document_id = $1 and dl.share_mate = true and f.code = 'acco' and dl.share_mate_owner_document_line_id is null " +
                    "order by dl.id desc limit 1")
                .setParameters(mateDocumentId)
                .build())
            .map(rs -> rs.getRowCount() < 1 ? null : rs.getValue(0, 0));
    }

    /** Best-effort audit: record which mate line consumed the token. Failure here never fails a booking. */
    static Future<Void> recordConsumer(String rawToken, Object mateDocumentLineId) {
        if (rawToken == null || mateDocumentLineId == null)
            return Future.succeededFuture();
        return SubmitService.executeSubmit(new SubmitArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement("update mate_invite_token set used_by_document_line_id = $2 where token_hash = $1")
                .setParameters(hashToken(rawToken), mateDocumentLineId)
                .build())
            .map(r -> (Void) null)
            .otherwise(e -> null); // audit only
    }
}
