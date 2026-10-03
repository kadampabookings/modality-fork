package one.modality.ecommerce.document.service.spi.impl.server;

import dev.webfx.platform.async.Future;

import java.util.List;
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

    /**
     * What an invitation says, read from the booking rather than from the request: who it is for, who is
     * holding the bed, and which event. The caller chooses only the SLOT and the address.
     */
    record InvitationFacts(String mateName, String bookerName, String eventName) { }

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

    /**
     * What a usable invite link may disclose about its room (room-mate plan step 7, agreed deliberately):
     * the room's accommodation item and the first and last day the room booking attends, as a small JSON
     * object such as {@code {"itemId":55,"arrival":"2026-11-27","departure":"2026-12-01"}}. Never a name or
     * a reference.
     *
     * <p>Built by hand from values checked to have the expected shape — digits for the item, ISO dates —
     * and a value of any other shape is left out, so nothing the database returns can widen what is
     * disclosed or break the JSON.
     *
     * @return the JSON, or "" when there is no item to describe
     */
    static String roomDescriptionJson(Object itemId, Object arrival, Object departure) {
        String item = itemId == null ? null : itemId.toString();
        if (item == null || !item.matches("[0-9]+"))
            return "";
        return "{\"itemId\":" + item + ",\"arrival\":" + isoDateJsonOrNull(arrival)
               + ",\"departure\":" + isoDateJsonOrNull(departure) + "}";
    }

    private static String isoDateJsonOrNull(Object date) {
        String text = date == null ? null : date.toString();
        return text != null && text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") ? "\"" + text + "\"" : "null";
    }

    // --- Database operations -----------------------------------------------------------------------

    private static Object dataSourceId() {
        return DataSourceModelService.getDefaultDataSourceModel().getDataSourceId();
    }

    /**
     * The room is still a room: neither the line nor the booking holding it is cancelled. Without it a
     * booker could cancel their room and an invite would go on admitting people to it — and cancelling a
     * BOOKING marks the document, not its lines, so the line alone would still look live.
     *
     * <p>Held apart from the bed count below, deliberately. They were one expression until a caller
     * needed to exempt the count (room-mate plan Part C: a booker putting someone in their OWN room is
     * not refused for a full room, which is their own doing and visible to them). Exempting a combined
     * expression would have exempted this too, and admitted mates to a cancelled room — a far worse
     * outcome than the one being allowed.
     */
    private static final String ROOM_IS_LIVE =
        "exists (select 1 from document_line o join document od on od.id = o.document_id " +
        "        where o.id = $2 and not o.cancelled and not od.cancelled)";

    /**
     * True when the room booked on the line bound to {@code $2} still has a bed free — its item's
     * capacity, less the booker and the mates already linked to it.
     *
     * <p>An unknown capacity reads as "room available": this guard exists to catch the obvious
     * over-fill, not to become the authority on availability, which is the derived-availability
     * work (plan §1c) still to come. Liveness is {@link #ROOM_IS_LIVE}'s job, not this one's.
     */
    private static final String ROOM_HAS_FREE_BED =
        "(select i.capacity is null or (select count(*) from document_line m " +
        "     where m.share_mate_owner_document_line_id = $2 and not m.cancelled) + 1 < i.capacity " +
        " from document_line o join item i on i.id = o.item_id where o.id = $2)";

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
     * may have no account yet — so the reply reaches anyone holding the token, and must never name
     * whose room it is.
     *
     * <p>One further disclosure was agreed for step 7 ("Accept invitation and book"), and it is the
     * whole of it: for a USABLE link only, {@link #describeRoom} tells the holder the room's
     * accommodation item and the room booking's first and last attendance day — never a name or a
     * booking reference. It is a bounded, deliberate exception, not a precedent: anything more (the
     * booker's name, the booking, a friendlier error page naming the room) needs its own decision.
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
     * The room on {@code ownerDocumentLineId}, as {@link #roomDescriptionJson} describes it: its item, and
     * the first and last attendance day of the booking holding it (over that booking's live lines). The
     * caller must already have established that the link naming this room is usable.
     */
    static Future<String> describeRoom(Object ownerDocumentLineId) {
        return QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement("select o.item_id, " +
                              "(select min(a.date) from attendance a join document_line l on l.id = a.document_line_id " +
                              "   where l.document_id = o.document_id and not l.cancelled), " +
                              "(select max(a.date) from attendance a join document_line l on l.id = a.document_line_id " +
                              "   where l.document_id = o.document_id and not l.cancelled) " +
                              "from document_line o where o.id = $1")
                .setParameters(ownerDocumentLineId)
                .build())
            .map(rs -> rs.getRowCount() < 1 ? ""
                : roomDescriptionJson(rs.getValue(0, 0), rs.getValue(0, 1), rs.getValue(0, 2)));
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
        return mint(ownerDocumentLineId, eventId, creatorAccountId, false);
    }

    /**
     * @param emailed whether this token is being posted to an address rather than handed to the booker,
     *                which shortens its life — see the note in the body
     */
    static Future<String> mint(Object ownerDocumentLineId, Object eventId, Object creatorAccountId, boolean emailed) {
        String rawToken = generateToken();
        // expires = event end + 2 days grace, computed in SQL so the app holds no clock of its own.
        //
        // An EMAILED token is capped at a fortnight besides. The copy-link's lifetime assumes the booker
        // chose where it went and can stop choosing; an address typed into a form is one keystroke from a
        // stranger, and nothing revokes a token yet. Minted for a December event in March, the long form
        // would leave a free bed claimable by the wrong inbox for nine months. The shorter window still
        // covers the case it is for — somebody deciding whether to come — and the booker can send again.
        String expiry = emailed
            ? "least(e.end_date + interval '2 days', now() + interval '14 days')"
            : "(e.end_date + interval '2 days')";
        String sql =
            "insert into mate_invite_token (token_hash, owner_document_line_id, event_id, creator_account_id, expires_date) " +
            "select $1, $2, $3, $4, " + expiry + " from event e where e.id = $3";
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
     * Writes the share-mate link: points the mate line at the owner line. A raw UPDATE rather than a
     * LinkMateToOwnerDocumentLineEvent, because that event carries the back-office rule; the two callers
     * here are authorised before they arrive — an invited mate by an unguessable token, a booker by
     * {@link MateLinkRules} proving the room is theirs. The DB trigger on_share_linked_copy_info then
     * copies the owner's site/item/room/attendances onto the mate. Returns true when a row was updated
     * (the mate line existed and was not already pointing elsewhere-unchanged).
     *
     * @param countBeds whether a full room refuses the link. True for an invited mate: a token is emailed
     *                  and can be forwarded, so an unbounded claim there is a stranger's doing. False for
     *                  a booker adding someone to their OWN room (room-mate plan Part C), who is allowed
     *                  to put a third person in their own twin — visible to them, visible to registration,
     *                  and their own doing — and whose capacity would otherwise be refused on the same
     *                  data that produced a false sold-out on prod (camping pitches carry capacity 1, see
     *                  V0103).
     *                  <p>This is NOT where that path is bounded, and it must not be read as unbounded
     *                  because of it: {@code refuseEventSharingLines} asks this room's own capacity before
     *                  it exempts the submit from the event pool, so a request that reaches here with
     *                  countBeds false was told moments earlier that the room had a bed. What is dropped
     *                  is the re-test inside the write, which is what lets a booker exceed the room on
     *                  purpose. The room still has to be live either way; that guard is separate for this
     *                  reason.
     */
    static Future<Boolean> link(Object mateDocumentLineId, Object ownerDocumentLineId, boolean countBeds) {
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
                    "  and " + IS_SHARE_MATE_LINE + " and " + ROOM_IS_LIVE +
                    (countBeds ? " and " + ROOM_HAS_FREE_BED : ""))
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
     * Fills in the room booker's name on the mate's line once the link is made, where the mate left it
     * blank, so the booking still says who it shares with.
     *
     * <p><b>It never overwrites what somebody wrote</b> (Bruno, 2026-10-01). It used to: any non-empty
     * owner name replaced whatever the mate had typed, on the grounds that the mate might have typed it
     * wrong. That reasoning does not survive the link existing — once the mate IS linked, who they share
     * with is a fact of the link, and the typed name stops being the system's way of finding the owner
     * and becomes a record of what that person said. Replacing it destroys that record and gains
     * nothing; keeping it lets registration see a mismatch instead of having it quietly tidied away.
     *
     * <p>The name is taken from the owner's OWN booking, never from the client — the link is the fact,
     * and this only labels it. A blank owner name leaves the line blank rather than writing an empty
     * string, and any failure here is swallowed: the link stands on its own without the label.
     */
    static Future<Void> stampOwnerName(Object mateDocumentLineId, Object ownerDocumentLineId) {
        return SubmitService.executeSubmit(new SubmitArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement(
                    "update document_line set share_mate_owner_name = coalesce(nullif(trim(( " +
                    "    select coalesce(d.person_first_name, '') || ' ' || coalesce(d.person_last_name, '') " +
                    "      from document_line o join document d on d.id = o.document_id where o.id = $2)), ''), " +
                    "  share_mate_owner_name) " +
                    // Only into a blank: the name the mate typed is theirs, and the link already says who
                    // the room belongs to.
                    "where id = $1 and share_mate = true and coalesce(trim(share_mate_owner_name), '') = ''")
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
     *
     * <p><b>A booking can own more than one</b> — 242 of 7,539 on staging, and 221 of those are the SAME
     * room item split across date ranges (an early-arrival night plus the main stay). The tie-break is
     * therefore not cosmetic: {@code on_share_linked_copy_info} DELETES the mate's attendances and
     * replaces them with the chosen line's, so binding to the early-arrival line truncates the mate's
     * whole stay to that one night. It orders by nights for exactly that reason — longest stay first,
     * because that is the field being overwritten — and falls back to the id only to break a true tie.
     * Neither lowest nor highest id is defensible on its own: both are insertion order, which follows
     * whatever order the client emitted the lines in.
     *
     * <p>Still a guess, and a caller that KNOWS which room is meant should name the line instead of
     * relying on this (room-mate plan Part C). One upcoming booking on staging owns two rooms today, and
     * its longest line is also its lowest-id one, so nothing is currently mis-bound.
     */
    /** Who to tell that a bed has been taken, and who took it. */
    record JoinNotice(String bookerName, String bookerEmail, Object bookerPersonId, String mateName,
                      String eventName, String lang, boolean mateIsTheBooker) { }

    /**
     * Everything the "somebody took a bed" note needs, read from the two lines it is about.
     *
     * <p>The booker's address the way the mail trigger resolves one: their own, else their account
     * login, else the address typed on the booking — a guest booker has no person row to carry one. A
     * booking with none of the three is not written to, which {@link MateJoinedMail} treats as nothing
     * to do rather than a failure.
     */
    static Future<JoinNotice> loadJoinNotice(Object ownerDocumentLineId, Object mateDocumentLineId) {
        return QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement(
                    "select coalesce(nullif(trim(coalesce(bp.first_name, '') || ' ' || coalesce(bp.last_name, '')), ''), bp.name), " +
                    "coalesce(nullif(bp.email, ''), nullif(bfa.username, ''), nullif(od.person_email, '')), " +
                    // NOT md.person_email as a last resort: a mate with no person row would then have
                    // their ADDRESS printed to the booker as if it were their name — on the copy-link
                    // route, to somebody who may never have known it. Only the anti-phishing rule in
                    // nameOrNeutral keeps it out today, and that rule is there for a different job.
                    "coalesce(nullif(trim(coalesce(mp.first_name, '') || ' ' || coalesce(mp.last_name, '')), ''), mp.name), " +
                    "e.name, bl.iso_639_1, od.person_id, " +
                    // A booker may follow their own link rather than the cart's own button — the link
                    // stays usable for an account already in the room, to put a second person in a
                    // triple — and telling them somebody took a bed when that somebody was them reads
                    // as a stranger walking in.
                    "(md.person_id is not null and md.person_id = od.person_id) " +
                    "from document_line odl " +
                    "join document od on od.id = odl.document_id " +
                    "left join person bp on bp.id = od.person_id " +
                    "left join frontend_account bfa on bfa.id = bp.frontend_account_id " +
                    "left join language bl on bl.id = bp.language_id " +
                    "join event e on e.id = od.event_id " +
                    "join document_line mdl on mdl.id = $2 " +
                    "join document md on md.id = mdl.document_id " +
                    "left join person mp on mp.id = md.person_id " +
                    "where odl.id = $1")
                .setParameters(ownerDocumentLineId, mateDocumentLineId)
                .build())
            .map(rs -> {
                if (rs.getRowCount() < 1)
                    return null;
                return new JoinNotice(
                    text(rs.getValue(0, 0)), text(rs.getValue(0, 1)), rs.getValue(0, 5),
                    text(rs.getValue(0, 2)), text(rs.getValue(0, 3)), text(rs.getValue(0, 4)),
                    Boolean.TRUE.equals(rs.getValue(0, 6)));
            });
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    /**
     * The names an invitation needs, for one slot of one room line.
     *
     * <p>All seven name columns are selected and the slot picked in Java rather than building the column
     * name into the statement. The slot is validated and the database constrains it to 1-7 besides, so
     * interpolating it would be safe today — but a column name assembled from a parameter is a habit that
     * stops being safe the first time the validation moves, and seven columns of one row costs nothing.
     *
     * <p>The mate name may be null or blank: a booker can leave a slot unnamed, and an invitation to
     * nobody is refused by the caller rather than sent to "Dear ".
     */
    static Future<InvitationFacts> loadInvitationFacts(Object ownerDocumentLineId, int mateSlot) {
        return QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement(
                    "select dl.share_owner_mate1_name, dl.share_owner_mate2_name, dl.share_owner_mate3_name, " +
                    "dl.share_owner_mate4_name, dl.share_owner_mate5_name, dl.share_owner_mate6_name, " +
                    "dl.share_owner_mate7_name, " +
                    "coalesce(nullif(trim(coalesce(p.first_name, '') || ' ' || coalesce(p.last_name, '')), ''), p.name), " +
                    "e.name " +
                    "from document_line dl join document d on d.id = dl.document_id " +
                    "join person p on p.id = d.person_id join event e on e.id = d.event_id " +
                    "where dl.id = $1")
                .setParameters(ownerDocumentLineId)
                .build())
            .map(rs -> {
                if (rs.getRowCount() < 1)
                    return null;
                Object mateName = mateSlot >= 1 && mateSlot <= 7 ? rs.getValue(0, mateSlot - 1) : null;
                Object bookerName = rs.getValue(0, 7), eventName = rs.getValue(0, 8);
                return new InvitationFacts(
                    mateName == null ? null : mateName.toString(),
                    bookerName == null ? "" : bookerName.toString(),
                    eventName == null ? "" : eventName.toString());
            });
    }

    static Future<OwnerLine> loadOwnerLineForBooking(Object documentId) {
        return QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement(
                    "select dl.id, d.event_id, p.frontend_account_id " +
                    "from document_line dl join document d on d.id = dl.document_id " +
                    "join person p on p.id = d.person_id " +
                    "join item i on i.id = dl.item_id join item_family f on f.id = i.family_id " +
                    "where dl.document_id = $1 and dl.share_owner = true and f.code = 'acco' and not dl.cancelled " +
                    "order by (select count(*) from attendance a where a.document_line_id = dl.id) desc, dl.id limit 1")
                .setParameters(documentId)
                .build())
            .map(rs -> rs.getRowCount() < 1 ? null
                : new OwnerLine(rs.getValue(0, 0), rs.getValue(0, 1), rs.getValue(0, 2)));
    }

    /**
     * Of the lines a submit declared to be share-owner lines, the one that is actually a live
     * accommodation room — or null when none of them is, or when more than one is.
     *
     * <p>Judged among the NAMED lines rather than looked up from the booking, which is the whole point of
     * the field this feeds: the caller knows which room it meant, and re-deriving it would reinstate the
     * guess (242 of 7,539 bookings own two room lines). Validated all the same, because the same event
     * stamps a headcount on non-accommodation lines too — a multi-head public talk marks refectory and
     * diet lines, and none of those is a room a mate can join.
     *
     * <p>More than one live room among them returns null rather than a choice. A booking CAN hold two,
     * and nothing here can tell which the booker meant; reporting neither leaves the client to ask, which
     * is better than reporting the wrong one — linking to it would delete the mate's attendances and
     * replace them with that room's.
     */
    static Future<Object> pickRoomLineAmong(List<Object> namedLineIds) {
        if (namedLineIds.isEmpty())
            return Future.succeededFuture(null);
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < namedLineIds.size(); i++)
            placeholders.append(i == 0 ? "$" : ", $").append(i + 1);
        return QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement(
                    "select dl.id from document_line dl " +
                    "join item i on i.id = dl.item_id join item_family f on f.id = i.family_id " +
                    "where dl.id in (" + placeholders + ") and dl.share_owner = true and f.code = 'acco' " +
                    "  and not dl.cancelled limit 2")
                .setParameters(namedLineIds.toArray())
                .build())
            .map(rs -> rs.getRowCount() == 1 ? rs.getValue(0, 0) : null);
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
