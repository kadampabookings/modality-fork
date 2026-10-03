package one.modality.ecommerce.document.service.spi.impl.server;

/**
 * Check for the room-share mails — what may reach a stranger's inbox, and how often.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits non-zero
 * on failure, like {@link MateLinkRulesCheck} beside it.
 *
 * <p>Both halves are security cases rather than formatting ones. The invitation goes out under our own
 * name, from our own sending domain, to an address its subject never chose and which the booker typed —
 * so what the booker can put IN it, and how many of them they can send, are the two questions. Nothing
 * here touches a database: the throttle takes its clock as an argument and the name test is pure.
 */
public class MateInvitationMailCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) {
        System.out.println("MateInvitationMailCheck");

        // ── What may be printed ──────────────────────────────────────────────
        // Escaping stops markup. These stop PROSE: ninety characters of free text beside our branding,
        // in a message the reader has no reason to distrust, is a usable phishing slot.
        check("a plain name is printed",
            "Carol White".equals(MateMails.nameOrNeutral("Carol White", "friend")));
        check("markup in a name is escaped rather than printed",
            "&lt;b&gt;Carol&lt;/b&gt;".equals(MateMails.nameOrNeutral("<b>Carol</b>", "friend")));
        check("a name carrying a scheme is not printed",
            "friend".equals(MateMails.nameOrNeutral("https://kbs-refund.example/claim", "friend")));
        check("a name carrying a bare domain is not printed — mail clients autolink it",
            "friend".equals(MateMails.nameOrNeutral("kbs-refund.example", "friend")));
        check("a name carrying www. is not printed",
            "friend".equals(MateMails.nameOrNeutral("www.example", "friend")));
        check("a name carrying an address is not printed",
            "friend".equals(MateMails.nameOrNeutral("pay me at a@b", "friend")));
        // A name cannot name a placeholder the next replace will fill.
        check("a name that looks like a placeholder is not printed",
            "friend".equals(MateMails.nameOrNeutral("[bookerName]", "friend")));
        check("a paragraph is not printed",
            "friend".equals(MateMails.nameOrNeutral("x".repeat(60), "friend")));
        check("an empty name falls back rather than greeting nobody",
            "friend".equals(MateMails.nameOrNeutral("  ", "friend")));
        check("a missing name falls back",
            "friend".equals(MateMails.nameOrNeutral(null, "friend")));

        // ── How often ────────────────────────────────────────────────────────
        long t0 = 1_000_000L;
        check("the first invitation goes",
            MateInvitationMail.maySend("a@example.org", 10, 1, t0));
        check("the same address is refused inside the window",
            !MateInvitationMail.maySend("a@example.org", 11, 2, t0 + 1000));
        check("case and spacing do not buy another send",
            !MateInvitationMail.maySend("  A@Example.ORG ", 12, 3, t0 + 2000));
        check("the same slot is refused inside the window, whatever address is given",
            !MateInvitationMail.maySend("b@example.org", 10, 1, t0 + 3000));
        check("a different address and slot still goes",
            MateInvitationMail.maySend("c@example.org", 20, 1, t0 + 4000));
        check("the address is allowed again once the window has passed",
            MateInvitationMail.maySend("a@example.org", 10, 1, t0 + 11 * 60 * 1000));

        // A refusal on one axis must not start the other's window: otherwise a tight loop of refusals
        // would keep consuming the slot's allowance and the booker would be a send short, silently.
        long t1 = 5_000_000L;
        check("a fresh address and slot go",
            MateInvitationMail.maySend("d@example.org", 30, 1, t1));
        check("that address is refused for another slot",
            !MateInvitationMail.maySend("d@example.org", 31, 1, t1 + 1000));
        check("and that other slot was not spent by the refusal",
            MateInvitationMail.maySend("e@example.org", 31, 1, t1 + 2000));

        // ── The note to the booker ───────────────────────────────────────────
        // One per room per window: the room's capacity normally bounds these, but the free-bed gate
        // treats a room with no capacity as always having one, and a cancelled line frees a bed again.
        long t2 = 9_000_000L;
        check("the first note for a room goes", MateJoinedMail.maySend(500, t2));
        check("a second for the same room waits", !MateJoinedMail.maySend(500, t2 + 1000));
        check("another room is unaffected", MateJoinedMail.maySend(501, t2 + 2000));
        check("and the room is allowed again once the window has passed",
            MateJoinedMail.maySend(500, t2 + 3 * 60 * 1000));

        // Nothing to write to is not a failure: the cart still says every bed is taken.
        check("a note with no address enqueues nothing",
            MateJoinedMail.send("Olive", "  ", 1, "Dermot", "Fall Festival", "en").isComplete());

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}
