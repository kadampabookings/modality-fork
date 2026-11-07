package one.modality.crm.server.person;

import java.util.List;

/**
 * Check for the properties {@link InvitationAnswerRules} rests on, read from its statements.
 *
 * <p>No test framework: runs from main() and exits non-zero on failure, like the other checks here.
 *
 * <p>What matters about these writes is textual: each names the party allowed to make it, each acts only
 * on the state it is meant for, and a decline is told from a cancellation by the date alone — so a
 * cancel that dated itself would read to the inviter as the other person's answer.
 */
public class InvitationAnswerCheck {

    private static int pass, fail;

    static void check(String what, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }

    public static void main(String[] args) {
        System.out.println("InvitationAnswerCheck");
        List<String> writes = InvitationAnswerRules.writeStatements();
        String decline = find(writes, "usage_date = now()");
        String cancel = find(writes, "inviter_id = $2 and pending = true");
        String seen = find(writes, "answer_seen_date = now()");

        check("there is a decline, a cancel and a seen write", !decline.isEmpty() && !cancel.isEmpty() && !seen.isEmpty());

        check("only the invitee declines", decline.contains("invitee_id = $2") && !decline.contains("inviter_id"));
        check("only a pending invitation is declined", decline.contains("and pending = true"));
        check("a decline is not an acceptance", decline.contains("accepted = false"));

        check("only the inviter cancels", cancel.contains("inviter_id = $2") && !cancel.contains("invitee_id"));
        check("only a pending invitation is cancelled", cancel.contains("and pending = true"));
        check("a cancel is NOT dated, so it never reads as the other person's answer", !cancel.contains("usage_date"));

        check("only the inviter marks an answer seen", seen.contains("inviter_id = $2"));
        check("only an answer can be seen (dated, closed)",
            seen.contains("pending = false") && seen.contains("usage_date is not null"));
        check("seen once: a later sighting does not move the date", seen.contains("answer_seen_date is null"));
        check("seeing sets nothing else", seen.substring(0, seen.indexOf(" where")).equals(
            "update invitation set answer_seen_date = now()"));

        for (String sql : writes)
            check("names one row: " + sql.substring(0, Math.min(50, sql.length())), sql.contains("where id = $1"));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }

    private static String find(List<String> writes, String marker) {
        return writes.stream().filter(s -> s.contains(marker)).findFirst().orElse("");
    }
}
