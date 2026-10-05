package one.modality.ecommerce.document.service.spi.impl.server;

import one.modality.ecommerce.document.service.spi.impl.server.MateLinkRules.LineFacts;

/**
 * Check for {@link MateLinkRules} — what the server requires before it writes a roommate link.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits non-zero
 * on failure, following ProtectedEntityWritesCheck and the webfx-stack session-token checks.
 *
 * <p>The security cases come first and matter most. The session's back-office flag is client-asserted,
 * so the checks pin that it is never sufficient on its own: a client claiming to be the back office
 * with an account that is not must be refused.
 */
public class MateLinkRulesCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    /** A share-mate line on booking 700, event 5. */
    static final LineFacts MATE = new LineFacts(true, false, 700L, 5, false);
    /** A share-owner line on booking 90, same event (as a Long — ids compare by value). */
    static final LineFacts OWNER = new LineFacts(false, true, 90, 5L, false);

    /** A link from a verified back-office account in the back-office app, naming an owner line. */
    static String backofficeLink(LineFacts mate, LineFacts owner) {
        return MateLinkRules.check(true, true, mate, true, owner, false, null);
    }

    // ── Front office: a booker adding their own roommate (room-mate plan Part C) ──

    static final Object MY_ACCOUNT = 42L;

    /** My own room line: booking 90, event 5, mine. */
    static final LineFacts MY_ROOM = new LineFacts(false, true, 90, 5, true);
    /** The same room booked by somebody else — what a guessed line id lands on. */
    static final LineFacts THEIR_ROOM = new LineFacts(false, true, 90, 5, false);
    /** My roommate's line: booking 700, event 5, made by me (or for a member of my account). */
    static final LineFacts MY_MATE = new LineFacts(true, false, 700L, 5, true);
    /** A STRANGER's sharing line — the one an attacker would try to drag into their own room. */
    static final LineFacts THEIR_MATE = new LineFacts(true, false, 700L, 5, false);

    /** A link from the front office: no back-office session, no back-office account, just an account. */
    static String bookerLink(LineFacts mate, LineFacts owner, Object submitterAccountId) {
        return MateLinkRules.check(false, false, mate, true, owner, false, submitterAccountId);
    }

    public static void main(String[] args) {
        System.out.println("MateLinkRules");

        check("a back-office account in the back-office app links a mate to a same-event booker",
            backofficeLink(MATE, OWNER) == null);

        // ── Security: the session flag is client-asserted and never enough ──
        check("refused when the session CLAIMS back office but the account is not a back-office account",
            MateLinkRules.check(true, false, MATE, true, OWNER, false, null) != null);
        check("refused for a guest or public submitter, who has no account",
            MateLinkRules.check(true, null, MATE, true, OWNER, false, null) != null);
        check("a back-office account in the front-office app does NOT get the back-office rule",
            MateLinkRules.check(false, true, MATE, true, OWNER, false, null) != null);

        // ── Shape of the link ──
        check("refused when the link names no booker at all",
            MateLinkRules.check(true, true, MATE, false, null, false, null) != null);
        check("refused when the roommate's line does not exist",
            backofficeLink(null, OWNER) != null);
        check("refused when the roommate's line is not a sharing booking",
            backofficeLink(new LineFacts(false, false, 700L, 5, false), OWNER) != null);
        check("refused when the named booker line does not exist",
            backofficeLink(MATE, null) != null);
        check("refused when the named line is not a room booking that can be shared",
            backofficeLink(MATE, new LineFacts(false, false, 90, 5, false)) != null);
        check("refused across events",
            backofficeLink(MATE, new LineFacts(false, true, 90, 6, false)) != null);
        check("refused when the booker's event is unknown",
            backofficeLink(MATE, new LineFacts(false, true, 90, null, false)) != null);
        check("refused when a booking is linked to itself",
            backofficeLink(MATE, new LineFacts(false, true, 700, 5, false)) != null);

        // ── The owner-person form (booker has not booked yet) ──
        check("the owner-person form passes for the back office",
            MateLinkRules.check(true, true, MATE, false, null, true, null) == null);
        check("the owner-person form still needs a real sharing line",
            MateLinkRules.check(true, true, new LineFacts(false, false, 700, 5, false), false, null, true, null) != null);
        check("the owner-person form is still back-office only",
            MateLinkRules.check(true, false, MATE, false, null, true, null) != null);

        // ── Ids and messages ──
        check("ids compare by value across Integer and Long", MateLinkRules.sameId(5, 5L));
        check("a null id never matches, not even another null", !MateLinkRules.sameId(null, null));
        String refusal = backofficeLink(MATE, new LineFacts(false, true, 90, 6, false));
        check("every refusal carries the recognisable prefix",
            refusal != null && refusal.startsWith(MateLinkRules.ERROR_PREFIX));

        // ── Front office: a booker adds their own roommate ──

        check("a booker links their own mate into a room their own account booked",
            bookerLink(MY_MATE, MY_ROOM, MY_ACCOUNT) == null);

        // The regression this rule shipped with for one review cycle, and the reason BOTH lines are
        // checked. Owning a room was treated as enough, so an account that owned any room could name a
        // STRANGER's sharing line as the mate — and the link trigger deletes that stranger's attendances
        // and replaces them with the room's, destroying their stay and moving them into the attacker's
        // room. It mattered more than an ordinary gap because the back-office branch used to refuse every
        // front-office link outright: half-checking here is worse than the refusal it replaced.
        check("refused when the MATE line is a stranger's, however legitimately the room is mine",
            bookerLink(THEIR_MATE, MY_ROOM, MY_ACCOUNT) != null);

        // The one that matters most. Part C switches the bed count off for this path, so without the
        // ownership check a guessed line id would be GUARANTEED to attach a mate to a stranger's room,
        // not merely likely. Line ids are sequential and shown in the UI.
        check("refused when the room belongs to another account — a guessed line id",
            bookerLink(MY_MATE, THEIR_ROOM, MY_ACCOUNT) != null);
        check("refused when the room's booking is not mine at all",
            bookerLink(MY_MATE, new LineFacts(false, true, 90, 5, false), MY_ACCOUNT) != null);
        check("refused for a guest, who owns nothing to link into",
            bookerLink(MY_MATE, MY_ROOM, null) != null);
        check("refused when the named line is not a room booking that can be shared",
            bookerLink(MY_MATE, new LineFacts(false, false, 90, 5, true), MY_ACCOUNT) != null);
        check("refused when the named line does not exist",
            bookerLink(MY_MATE, null, MY_ACCOUNT) != null);
        check("refused when the roommate's line is not a sharing booking",
            bookerLink(new LineFacts(false, false, 700L, 5, true), MY_ROOM, MY_ACCOUNT) != null);
        check("refused when the roommate's line does not exist",
            bookerLink(null, MY_ROOM, MY_ACCOUNT) != null);
        check("refused across events, even within one account",
            bookerLink(MY_MATE, new LineFacts(false, true, 90, 6, true), MY_ACCOUNT) != null);
        check("refused when a booking is linked to itself",
            bookerLink(MY_MATE, new LineFacts(false, true, 700, 5, true), MY_ACCOUNT) != null);
        check("refused when no owner line is named",
            MateLinkRules.check(false, false, MY_MATE, false, null, false, MY_ACCOUNT) != null);
        check("the owner-PERSON form is back-office only: it resolves to no room, so there is nothing to own",
            MateLinkRules.check(false, false, MY_MATE, false, null, true, MY_ACCOUNT) != null);

        // Non-disclosure. A caller able to tell "no such line" from "not yours" from "not a shared room"
        // can walk the sequential id space and learn which rooms exist and which are shared. A successful
        // link already discloses that; the refusals must not.
        String[] refusals = {
            bookerLink(THEIR_MATE, MY_ROOM, MY_ACCOUNT),
            bookerLink(MY_MATE, THEIR_ROOM, MY_ACCOUNT),
            bookerLink(MY_MATE, null, MY_ACCOUNT),
            bookerLink(MATE, new LineFacts(false, false, 90, 5, true), MY_ACCOUNT),
            bookerLink(MATE, new LineFacts(false, true, 90, 6, true), MY_ACCOUNT),
            bookerLink(MY_MATE, MY_ROOM, null),
        };
        boolean allSame = true;
        for (String message : refusals)
            allSame &= message != null && message.equals(refusals[0]);
        check("every front-office refusal says the same thing, so failure discloses nothing", allSame);

        // A back-office account using the FRONT office is a booker like any other -- it is the app that
        // decides which rule applies, and this is the rule for that app.
        check("a back-office account in the front-office app may link into its OWN room",
            MateLinkRules.check(false, true, MY_MATE, true, MY_ROOM, false, MY_ACCOUNT) == null);
        check("...and may not link into somebody else's",
            MateLinkRules.check(false, true, MY_MATE, true, THEIR_ROOM, false, MY_ACCOUNT) != null);

        // ── The flag that decides the price ──────────────────────────────────
        // share_mate alone prices a line at 0 and keeps it out of allocation, so "may this line be a
        // sharing place" IS "may this line cost nothing". Created as one it is judged by the availability
        // guard; acquired afterwards it used to be judged by nothing, which let a booker pay for a whole
        // room and then reprice their own line to nothing in a second submit carrying only this event.
        check("a line created by this submit may be flagged — the availability guard has it",
            MateLinkRules.checkShareMateFlag(false, null, true, null) == null);
        // Defensive rather than needed today: both emitters take the line id from the submit's own
        // AddDocumentLineEvent, so no current flow re-sends this for an existing line. Saying a line is
        // what it already is changes neither its price nor its allocation.
        check("a line that already carries the flag may be re-sent",
            MateLinkRules.checkShareMateFlag(false, null, false, true) == null);
        check("an EXISTING ordinary line may NOT become a sharing place",
            MateLinkRules.checkShareMateFlag(false, null, false, false) != null);
        check("...nor may a line that does not exist",
            MateLinkRules.checkShareMateFlag(false, null, false, null) != null);
        check("the two refusals say the same thing, so failure does not map the id space",
            MateLinkRules.checkShareMateFlag(false, null, false, false)
                .equals(MateLinkRules.checkShareMateFlag(false, null, false, null)));
        // Authoritative for placement, as it is for the allocation check itself — and both flags, so a
        // front-office client cannot reach this branch by asserting the session's.
        check("the back office may convert a line",
            MateLinkRules.checkShareMateFlag(true, true, false, false) == null);
        check("a session claiming the back office without the account cannot",
            MateLinkRules.checkShareMateFlag(true, false, false, false) != null);
        check("...nor can one whose account is unknown",
            MateLinkRules.checkShareMateFlag(true, null, false, false) != null);

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}
