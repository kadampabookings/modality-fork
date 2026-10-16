package one.modality.ecommerce.document.service.spi.impl.server;

/**
 * That every way into a booking through this service is limited to the caller.
 *
 * <p>Three query shapes compose their own SQL here, so none of them is seen by ClientReadDenyList or
 * ClientQueryGuard — those guard a client's own DQL. Until 2026-10-16 only one of the three applied a
 * check:
 * <ul>
 *   <li>by booking id: checked;</li>
 *   <li>by person or account and event: ids taken from the REQUEST, then each booking loaded with the
 *       check switched OFF on the grounds that round 1 had filtered. It had filtered by ids the caller
 *       chose. Sending any person id together with a target booking id returned that booking, and ids
 *       are sequential;</li>
 *   <li>the change history: no check at all, on either branch — and History.changes carries the
 *       person's name and email as submitted.</li>
 * </ul>
 *
 * <p>The assertions are on composed TEXT because that is where this fails silently. A clause with the
 * wrong parameter index filters by the wrong value and reports nothing: no error, just the wrong rows.
 */
public class DocumentAccessCheck {

    static int pass = 0, fail = 0;

    private static final DocumentAccess MEMBER = DocumentAccess.of(false, 4242, null);
    private static final DocumentAccess GUEST = DocumentAccess.of(false, null, "guest@example.org");
    private static final DocumentAccess ANONYMOUS = DocumentAccess.of(false, null, null);
    private static final DocumentAccess VERIFIED_BACKOFFICE = DocumentAccess.of(true, null, null);
    /** The server acting on nobody's behalf — same unfiltered state a verified back office gets. */
    private static final DocumentAccess SYSTEM = DocumentAccess.of(true, null, null);

    public static void main(String[] args) {
        // ── Who may read anything, and who nothing ──────────────────────────────────────────────
        check("a verified back office is unfiltered", VERIFIED_BACKOFFICE.readsAnything());
        check("a member is not unfiltered", !MEMBER.readsAnything());
        check("a guest is not unfiltered", !GUEST.readsAnything());
        check("an anonymous caller reads nothing", ANONYMOUS.readsNothing());
        check("a member does not read nothing", !MEMBER.readsNothing());
        check("a guest does not read nothing", !GUEST.readsNothing());
        // The back office is unfiltered rather than unauthenticated: readsNothing must not claim it.
        check("a verified back office does not read nothing", !VERIFIED_BACKOFFICE.readsNothing());

        // The system reads a booking's history to unbook unpaid options when a payment is cancelled,
        // after async hops that leave no principal on the thread. Unfiltered — but it must be the SYSTEM
        // marker that says so, never the mere absence of a principal, which is what anonymous looks like.
        check("the system reads anything", SYSTEM.readsAnything());
        check("the system does not read nothing", !SYSTEM.readsNothing());
        check("but an absent principal is still anonymous, not the system",
              ANONYMOUS.readsNothing() && !ANONYMOUS.readsAnything());

        // ── Round 1: the person/account path, which had no caller in it at all ──────────────────
        // Another person's id is still sent — the client chooses $1 — but the clause decides the rows.
        String memberByPerson = ServerDocumentServiceProvider.findDocsDql(true, false, true, MEMBER);
        check("a member's round 1 carries the account clause",
              memberByPerson.contains("accountCanAccessPersonOrders($3, person)"));
        check("and the account is its own parameter, after the person and event",
              memberByPerson.contains("person=$1 and event=$2) and accountCanAccessPersonOrders($3"));

        // THE HOLE: `or id=$3` admits any booking, because AND binds tighter than OR. The clause has to
        // sit OUTSIDE the parentheses to govern that arm too, and take $4 once the id is a parameter.
        String memberWithDocPk = ServerDocumentServiceProvider.findDocsDql(true, true, true, MEMBER);
        check("the id arm is present when a booking id is supplied",
              memberWithDocPk.contains("or id=$3"));
        check("a stranger's booking id through `or id=$3` is still subject to the clause",
              memberWithDocPk.contains("or id=$3) and accountCanAccessPersonOrders($4, person)"));
        check("the clause is outside the parentheses, so it governs both arms",
              memberWithDocPk.indexOf(')') < memberWithDocPk.indexOf("accountCanAccessPersonOrders"));

        String guestWithDocPk = ServerDocumentServiceProvider.findDocsDql(true, true, true, GUEST);
        check("a guest's round 1 filters by their own email, outside the parentheses",
              guestWithDocPk.contains("or id=$3) and lower(person_email)=lower($4)"));

        String accountVariant = ServerDocumentServiceProvider.findDocsDql(false, false, false, MEMBER);
        check("the account variant filters on the account's own person path",
              accountVariant.contains("person.frontendAccount=$1") && accountVariant.contains("($3, person)"));

        check("a verified back office's round 1 is unchanged",
              ServerDocumentServiceProvider.findDocsDql(true, true, true, VERIFIED_BACKOFFICE)
                  .equals("select id from Document where !cancelled and (person=$1 and event=$2 or id=$3) order by id desc limit 1"));

        // ── The history, which had no check on either branch ────────────────────────────────────
        // The booking is reached by subquery, so the clause keeps the shape already in production: a bare
        // person/person_email on a Document select, never a dot path handed to the inline function.
        String historyByDoc = ServerDocumentServiceProvider.historyByDocumentSelect(MEMBER);
        check("the history of a booking id is gated by a caller-filtered booking lookup",
              historyByDoc.contains("document in (select id from Document where id=$1 and accountCanAccessPersonOrders($3, person))"));
        check("and it navigates nothing from the History row",
              !historyByDoc.contains("document.person"));
        check("a guest's history filters by their own email",
              ServerDocumentServiceProvider.historyByDocumentSelect(GUEST)
                  .contains("id=$1 and lower(person_email)=lower($3)"));

        String historyByPerson = ServerDocumentServiceProvider.historyByPersonAndEventSelect(MEMBER);
        check("the history by person and event carries the clause",
              historyByPerson.contains("accountCanAccessPersonOrders($4, person)"));
        check("and it sits inside the booking lookup, before its limit",
              historyByPerson.indexOf("accountCanAccessPersonOrders") < historyByPerson.indexOf("order by id desc limit 1)"));
        check("a verified back office's history is unchanged",
              ServerDocumentServiceProvider.historyByDocumentSelect(VERIFIED_BACKOFFICE)
                  .equals("select changes from History where document=$1 and id<=$2 order by id"));

        // ── The clause is for a filtered caller only ────────────────────────────────────────────
        check("asking an anonymous caller for a clause is refused, not answered",
              throwsIllegalState(() -> ANONYMOUS.clause(2, "person", "person_email")));
        check("asking a verified back office for a clause is refused too",
              throwsIllegalState(() -> VERIFIED_BACKOFFICE.clause(2, "person", "person_email")));
        check("a member's parameter is the account", Integer.valueOf(4242).equals(MEMBER.parameter()));
        check("a guest's parameter is the email", "guest@example.org".equals(GUEST.parameter()));

        System.out.println(fail == 0 ? "\nALL " + pass + " PASS" : "\n" + fail + " FAILED");
        if (fail > 0)
            throw new AssertionError(fail + " document access checks failed");
    }

    private static boolean throwsIllegalState(Runnable r) {
        try { r.run(); return false; } catch (IllegalStateException e) { return true; } catch (Throwable t) { return false; }
    }

    private static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else    { fail++; System.out.println("  FAIL " + what); }
    }
}
