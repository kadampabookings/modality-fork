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
 * Server-internal store for room-share invite tokens. See docs/room-mate-booking-plan.md — steps 4-5
 * for the original build, and "Plan — one link per room, checked when opened" for the model in force
 * here, which supersedes D5's single-use decision.
 *
 * <p>The table {@code mate_invite_token} is NOT a domain entity: it is read and written here with raw
 * SQL, so it never appears on the client-queryable DQL surface. Under the open-read exposure (security
 * report A1) that is deliberate, and the row stores only a HASH of the token — a leaked read cannot be
 * turned into a working link.
 *
 * <p><b>A link belongs to a ROOM, not to one person.</b> {@link #resolve} is read-only and says only
 * which booking the token names; whether another mate may actually join is the room's remaining
 * capacity, tested inside {@link #link} where it can be enforced against concurrent linkers. The
 * booking's event is part of the resolve test, so a token offered against another event does not
 * resolve.
 *
 * <p>Single use was built first and withdrawn. The reasoning for it was sound — one token, one bed —
 * but it cannot be explained in a button label: a booker filling a triple had to press the button
 * once per person with nothing telling them so, and a followed link was dead with no explanation.
 * Capacity bounds the supply just as well, and bounds it by the fact the booker actually cares
 * about. {@code used_date} and {@code used_by_document_line_id} survive as an audit of the FIRST
 * follower only (see {@link #recordFirstUse}), not as a gate.
 *
 * <p><b>Why nothing here trusts a submit's row count.</b> {@code SubmitResult.getRowCount()} is NOT
 * rows-affected: {@code VertxSqlUtil.toWebFxSubmitResult} derives it by walking the RowSet chain,
 * which for a single statement has one element whatever the statement did. The first version of this
 * class gated the claim on that count, so the gate could never refuse: a spent token went on linking,
 * and a second and third mate were put into a room with one spare bed. Every check here now reads its
 * own effect back through the query path, where the row count is real. The same trap is documented in
 * {@code ModalityAuthSessionStore}.
 */
final class MateInviteTokenStore {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /** 16 bytes = 128 bits of entropy, the same strength MagicLinkService mints. */
    private static final int TOKEN_BYTES = 16;

    /** The room-booking line a booking offers to share, and the facts the mint path checks. */
    record OwnerLine(Object ownerDocumentLineId, Object eventId, Object frontendAccountId) { }

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
     * True when the room booked on the line bound to {@code $2} still has a bed free — its item's
     * capacity, less the booker and the mates already linked to it.
     *
     * <p>An unknown capacity reads as "room available": this guard exists to catch the obvious
     * over-fill, not to become the authority on availability, which is the derived-availability
     * work (plan §1c) still to come.
     */
    private static final String ROOM_HAS_FREE_BED =
        "(select i.capacity is null or (select count(*) from document_line m " +
        "     where m.share_mate_owner_document_line_id = $2 and not m.cancelled) + 1 < i.capacity " +
        // A CANCELLED room is not shareable: the subquery then returns no row, the expression is
        // NULL, and the WHERE fails — which is the answer we want. Without this a booker could
        // cancel their room and the invite would go on admitting people to it.
        // Cancelling a BOOKING marks the document, not its lines — so the room line alone would still look
        // live, and an old invite would go on admitting people to a cancelled booking.
        " from document_line o join item i on i.id = o.item_id where o.id = $2 and not o.cancelled" +
        "   and exists (select 1 from document od where od.id = o.document_id and not od.cancelled))";

    /**
     * A line is a share-mate line by its own flag OR its item's — the rule MateLinkRules already
     * applies. Only EditShareMateInfoDocumentLineEvent writes document_line.share_mate, so a
     * sharing booking made without it is recognisable only by its item.
     */
    private static final String IS_SHARE_MATE_LINE =
        "(share_mate = true or exists (select 1 from item i where i.id = item_id and i.share_mate = true))";

    /**
     * The five things a mate may be told about an invite link, and the whole of what the resolve
     * endpoint may disclose.
     *
     * <p>Deliberately uninformative. The endpoint is unauthenticated — the person following a link
     * may have no account yet — so the reply reaches anyone holding the token. A forwarded link is
     * harmless today precisely because it reveals nothing about whose room it is; adding the
     * booker's name or the room to a friendlier error page is what would end that.
     */
    static final String STATUS_USABLE = "USABLE";
    static final String STATUS_FULL = "FULL";
    /**
     * The room is full AND the signed-in caller's account already holds one of its beds — typically
     * the mate reopening the link after booking, who would otherwise read "the room is now full" as
     * "my booking lost its room". Told only to that account, so it discloses nothing the caller does
     * not already know; anyone else still reads FULL.
     */
    static final String STATUS_JOINED = "JOINED";
    static final String STATUS_EXPIRED = "EXPIRED";
    /** Unknown token — and also a token issued for a DIFFERENT event, which must not be confirmed. */
    static final String STATUS_UNKNOWN = "UNKNOWN";

    /**
     * Maps a resolve result to the status a mate may see.
     *
     * <p>Pure, so the mapping can be checked without a database — it is the kind of logic that
     * silently inverts. Note that "expired" is NOT distinguished from "unknown" by the lookup
     * itself: {@link #resolve} filters on expiry, so an expired token simply fails to resolve. The
     * caller passes {@code expired} only when it has separately established that the token exists
     * but has lapsed; otherwise an unresolved token is UNKNOWN, which is also the right answer for
     * a token belonging to another event.
     *
     * <p>{@code callerHoldsBed} only refines a FULL room. While a bed is free the link stays USABLE
     * even for an account already in the room, because that account may be booking a second person
     * into a triple — and the token is carried into a booking only when the link is USABLE.
     */
    static String statusOf(boolean resolved, boolean expired, boolean hasFreeBed, boolean callerHoldsBed) {
        if (!resolved) return expired ? STATUS_EXPIRED : STATUS_UNKNOWN;
        if (hasFreeBed) return STATUS_USABLE;
        return callerHoldsBed ? STATUS_JOINED : STATUS_FULL;
    }

    /**
     * Whether a token exists for this event but has lapsed — the one case worth separating from
     * "unknown", so an invite that simply ran out of time can say so rather than looking invalid.
     * Discloses nothing beyond that fact.
     */
    static Future<Boolean> isExpired(String rawToken, Object eventId) {
        if (rawToken == null || rawToken.isBlank())
            return Future.succeededFuture(false);
        return QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement("select 1 from mate_invite_token " +
                              "where token_hash = $1 and event_id = $2 and expires_date <= now()")
                .setParameters(hashToken(rawToken), eventId)
                .build())
            .map(rs -> rs.getRowCount() >= 1);
    }

    /** Whether the room on {@code ownerDocumentLineId} can still take another mate. */
    static Future<Boolean> hasFreeBed(Object ownerDocumentLineId) {
        return QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement("select i.capacity is null or (select count(*) from document_line m " +
                              "    where m.share_mate_owner_document_line_id = $1 and not m.cancelled) + 1 < i.capacity " +
                              // A cancelled room is not shareable — no row, so the caller reads false.
                              // As in ROOM_HAS_FREE_BED, a cancelled BOOKING is not shareable either.
                              "from document_line o join item i on i.id = o.item_id where o.id = $1 and not o.cancelled" +
                              "  and exists (select 1 from document od where od.id = o.document_id and not od.cancelled)")
                .setParameters(ownerDocumentLineId)
                .build())
            .map(rs -> rs.getRowCount() >= 1 && Boolean.TRUE.equals(rs.getValue(0, 0)));
    }

    /**
     * Whether {@code accountId} already holds a bed in the room {@code ownerDocumentLineId} names —
     * either the room itself (the booker's own line) or a live mate line linked to it. Drives JOINED.
     */
    static Future<Boolean> accountHoldsBedInRoom(Object ownerDocumentLineId, Object accountId) {
        return QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement("select 1 from document_line l join document d on d.id = l.document_id " +
                              "join person p on p.id = d.person_id " +
                              "where (l.id = $1 or l.share_mate_owner_document_line_id = $1) and not l.cancelled " +
                              "and p.frontend_account_id = $2 limit 1")
                .setParameters(ownerDocumentLineId, accountId)
                .build())
            // A QUERY's row count is real rows, unlike SubmitResult.getRowCount().
            .map(rs -> rs.getRowCount() >= 1);
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
        String hash = hashToken(rawToken);
        return SubmitService.executeSubmit(new SubmitArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement(sql)
                .setParameters(hash, ownerDocumentLineId, eventId, creatorAccountId)
                .build())
            // Read the row back rather than trust the submit's row count (see the class note): if the
            // event id did not resolve, the insert selected no row and there is no token to hand out.
            .compose(ignored -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement("select 1 from mate_invite_token where token_hash = $1")
                .setParameters(hash)
                .build()))
            .map(rs -> {
                if (rs.getRowCount() < 1)
                    throw new IllegalStateException("Could not mint invite token: event not found");
                return rawToken;
            });
    }

    /**
     * Resolves a token to the owner accommodation line it names, or null when it does not resolve —
     * unknown, expired, or issued for a different event than the booking being submitted.
     *
     * <p>Read-only, and deliberately NOT single-use. A link belongs to a ROOM, not to one person:
     * "may another mate join?" is answered by the room's remaining capacity at {@link #link} time,
     * not by whether this token has been followed before. One link per room is the model people
     * actually hold, and capacity bounds the supply just as a one-shot token did — by the fact the
     * booker cares about. See the plan, "one link per room, checked when opened".
     *
     * <p>The event is part of the test, so a token offered against another event does not resolve.
     */
    static Future<Object> resolve(String rawToken, Object eventId) {
        if (rawToken == null || rawToken.isBlank())
            return Future.succeededFuture(null);
        return QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement("select owner_document_line_id from mate_invite_token " +
                              "where token_hash = $1 and event_id = $2 and expires_date > now()")
                .setParameters(hashToken(rawToken), eventId)
                .build())
            .map(rs -> rs.getRowCount() < 1 ? null : rs.getValue(0, 0));
    }

    /**
     * Best-effort audit of the FIRST mate to follow this link.
     *
     * <p>Once a link may be followed several times, one column cannot name "the" consumer — so it
     * names the first and then stops, which still answers "did anyone ever use this?" without a new
     * table. The columns keep their shape deliberately: V0087 is applied and checksummed, so
     * changing it would need its own version. Failure here never fails a booking.
     */
    static Future<Void> recordFirstUse(String rawToken, Object mateDocumentLineId) {
        if (rawToken == null || mateDocumentLineId == null)
            return Future.succeededFuture();
        return SubmitService.executeSubmit(new SubmitArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement("update mate_invite_token set used_date = coalesce(used_date, now()), " +
                              "  used_by_document_line_id = coalesce(used_by_document_line_id, $2) " +
                              "where token_hash = $1")
                .setParameters(hashToken(rawToken), mateDocumentLineId)
                .build())
            .map(r -> (Void) null)
            .otherwise(e -> null); // audit only
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
                // The capacity test is part of the UPDATE rather than a check before it, so the
                // room cannot be over-filled between deciding and writing. Single-use stops one
                // token linking twice; this stops a booker minting a SECOND token and putting a
                // third person in a twin, which nothing else in the booking path would catch — a
                // share-mate line is free and carries no capacity of its own.
                .setStatement(
                    // Lock the owner line for the length of this statement, so concurrent linkers
                    // serialise on it. With one link per room rather than one per person, two people
                    // opening the same message at once stops being a curiosity. The lock is taken
                    // INSIDE the statement because every submit runs in its own transaction — one
                    // taken in a separate call would already have been released by the time this ran.
                    //
                    // ⚠ NOT YET VERIFIED ON POSTGRES, and it should be before this is trusted as the
                    // whole guard: the capacity subquery reads this statement's snapshot, so
                    // serialising the writers may not by itself let the count see a sibling that has
                    // just committed. It is a strict improvement either way; the durable answer is a
                    // database-level constraint, or §1c's derived availability doing this properly.
                    "with owner_locked as (select o.id from document_line o where o.id = $2 for update) " +
                    "update document_line set share_mate_owner_document_line_id = $2 " +
                    "where id = $1 and exists (select 1 from owner_locked) " +
                    "  and " + IS_SHARE_MATE_LINE + " and " + ROOM_HAS_FREE_BED)
                .setParameters(mateDocumentLineId, ownerDocumentLineId)
                .build())
            // Read back rather than trust the submit's row count (see the class note): a line that is
            // not a share-mate line updates nothing, and the caller must be able to tell.
            .compose(ignored -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement("select 1 from document_line where id = $1 and share_mate_owner_document_line_id = $2")
                .setParameters(mateDocumentLineId, ownerDocumentLineId)
                .build()))
            .map(rs -> rs.getRowCount() >= 1);
    }

    /**
     * Records the room booker's name on the mate's line once the link is made, so the booking says
     * who it shares with even where the mate typed nothing or typed it wrong.
     *
     * <p>The name is taken from the owner's OWN booking, never from the client — the link is the
     * fact, and this only labels it. A blank owner name leaves whatever the mate typed in place
     * rather than replacing a real name with an empty string, and any failure here is swallowed:
     * the link stands on its own without the label.
     */
    static Future<Void> stampOwnerName(Object mateDocumentLineId, Object ownerDocumentLineId) {
        return SubmitService.executeSubmit(new SubmitArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement(
                    "update document_line set share_mate_owner_name = coalesce(nullif(trim(( " +
                    "    select coalesce(d.person_first_name, '') || ' ' || coalesce(d.person_last_name, '') " +
                    "      from document_line o join document d on d.id = o.document_id where o.id = $2)), ''), " +
                    "  share_mate_owner_name) " +
                    "where id = $1 and share_mate = true")
                .setParameters(mateDocumentLineId, ownerDocumentLineId)
                .build())
            .map(r -> (Void) null)
            .otherwise(e -> null); // label only — never fail a booking over it
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
                    // The line's own flag OR its item's, the same rule MateLinkRules applies on the
                    // back-office path: only EditShareMateInfoDocumentLineEvent writes
                    // document_line.share_mate, so a sharing booking made without it (or linked by
                    // the back office, which never sets it) is recognisable only by its item.
                    "select dl.id from document_line dl join item i on i.id = dl.item_id " +
                    "join item_family f on f.id = i.family_id " +
                    "where dl.document_id = $1 and (dl.share_mate = true or i.share_mate = true) " +
                    "  and f.code = 'acco' and dl.share_mate_owner_document_line_id is null " +
                    "order by dl.id desc limit 1")
                .setParameters(mateDocumentId)
                .build())
            .map(rs -> rs.getRowCount() < 1 ? null : rs.getValue(0, 0));
    }

}
