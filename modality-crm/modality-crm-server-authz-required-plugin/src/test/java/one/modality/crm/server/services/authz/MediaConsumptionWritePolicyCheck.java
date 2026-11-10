package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.submit.GeneratedKeyReference;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteRequest;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteVerb;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Check for {@link MediaConsumptionWritePolicy}: every write the front office's recorders send passes for the account
 * whose attendance it is, and the same writes aimed at somebody else's attendance, or with nobody behind them, are
 * refused.
 *
 * <p>No test framework: runs from main() and exits non-zero on failure, following ChatWritePolicyCheck. The
 * legitimate shapes are copied from use-media-consumption-recorder.ts (insert, heartbeat, final update) and
 * AudioTrackRow.tsx (download), so a change there that this rule would refuse shows up here first.
 */
public class MediaConsumptionWritePolicyCheck {

    static int pass = 0, fail = 0;

    static final int ME = 100, MY_ACCOUNT = 1000, OTHER_ACCOUNT = 2000;
    static final int MY_ATTENDANCE = 11, LINKED_ATTENDANCE = 12, THEIR_ATTENDANCE = 21;
    static final int MY_ROW = 51, THEIR_ROW = 61;

    /** A small database: my attendances (one directly in my account, one through a linked member), and theirs. */
    static final MediaConsumptionWritePolicy.ConsumptionLookup LOOKUP = new MediaConsumptionWritePolicy.ConsumptionLookup() {
        @Override
        public Future<Boolean> attendanceInAccount(Object attendanceId, Object accountId) {
            int a = Integer.parseInt(String.valueOf(attendanceId));
            return Future.succeededFuture(String.valueOf(MY_ACCOUNT).equals(String.valueOf(accountId))
                                          && (a == MY_ATTENDANCE || a == LINKED_ATTENDANCE));
        }

        @Override
        public Future<Boolean> consumptionInAccount(Object consumptionId, Object accountId) {
            return Future.succeededFuture(String.valueOf(MY_ACCOUNT).equals(String.valueOf(accountId))
                                          && Integer.parseInt(String.valueOf(consumptionId)) == MY_ROW);
        }
    };

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    static Map<String, Object> values(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
            map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    static WriteRequest insert(Map<String, Object> values) {
        return new WriteRequest("MediaConsumption", WriteVerb.INSERT, values.keySet().toArray(String[]::new), values, null, false, null);
    }

    static WriteRequest update(Object targetId, Map<String, Object> values) {
        return new WriteRequest("MediaConsumption", WriteVerb.UPDATE, values.keySet().toArray(String[]::new), values, targetId, false, null);
    }

    static WriteRequest delete(Object targetId) {
        return new WriteRequest("MediaConsumption", WriteVerb.DELETE, new String[0], new HashMap<>(), targetId, false, null);
    }

    static String judge(WriteRequest write, Object person, Object account, boolean backoffice) {
        return MediaConsumptionWritePolicy.judge(write, person, account, backoffice, LOOKUP).result();
    }

    static void passes(String what, WriteRequest write, Object person, Object account, boolean backoffice) {
        String reason = judge(write, person, account, backoffice);
        check(what + (reason == null ? "" : " — refused: " + reason), reason == null);
    }

    static void refused(String what, WriteRequest write, Object person, Object account, boolean backoffice) {
        check(what, judge(write, person, account, backoffice) != null);
    }

    /** The play/livestream recorder's insert, on the given attendance. */
    static WriteRequest played(Object attendance) {
        return insert(values("scheduledItem", 7, "media", 8, "attendance", attendance, "played", true,
            "downloaded", false, "livestreamed", false, "durationMillis", 0));
    }

    static String readJob() {
        String path = "modality-crm/modality-crm-server-authz-required-plugin/src/main/java/"
                      + "one/modality/crm/server/services/authz/ProtectedEntityWritesJob.java";
        for (String root : new String[] { "", "modality-fork/", "../../" }) {
            try {
                return java.nio.file.Files.readString(java.nio.file.Path.of(root + path));
            } catch (java.io.IOException ignored) {
            }
        }
        return null;
    }

    public static void main(String[] args) {
        System.out.println("the switch:");
        check("ships OBSERVING", !MediaConsumptionWritePolicy.ENFORCING);
        // A rule nobody registers refuses nothing and logs nothing, which a census reads as "all clean".
        String job = readJob();
        check("is registered among the row rules", job == null || job.contains("new MediaConsumptionWritePolicy()"));
        if (job == null)
            System.out.println("  (registration not checked: run from the aggregate root or modality-fork)");

        System.out.println("a member recording their own viewing, as the front office does:");
        passes("a play", played(MY_ATTENDANCE), ME, MY_ACCOUNT, false);
        passes("a play on a linked member's attendance", played(LINKED_ATTENDANCE), ME, MY_ACCOUNT, false);
        passes("an id that arrived as a string", played(String.valueOf(MY_ATTENDANCE)), ME, MY_ACCOUNT, false);
        passes("a livestream view, no media",
            insert(values("scheduledItem", 7, "attendance", MY_ATTENDANCE, "played", false, "downloaded", false,
                "livestreamed", true, "durationMillis", 0)), ME, MY_ACCOUNT, false);
        passes("a download",
            insert(values("scheduledItem", 7, "media", 8, "attendance", MY_ATTENDANCE, "played", false,
                "downloaded", true, "livestreamed", false, "durationMillis", 0)), ME, MY_ACCOUNT, false);
        passes("the heartbeat", update(MY_ROW, values("durationMillis", 60_000)), ME, MY_ACCOUNT, false);
        passes("the final update, id as a string", update(String.valueOf(MY_ROW), values("durationMillis", 61_000)), ME, MY_ACCOUNT, false);

        System.out.println("a member reaching somebody else's viewing:");
        refused("a play on their attendance", played(THEIR_ATTENDANCE), ME, MY_ACCOUNT, false);
        refused("on an attendance that does not exist", played(999), ME, MY_ACCOUNT, false);
        refused("their row's duration", update(THEIR_ROW, values("durationMillis", 1)), ME, MY_ACCOUNT, false);
        refused("my attendance, from another account", played(MY_ATTENDANCE), ME, OTHER_ACCOUNT, false);

        System.out.println("a member writing what the front office never writes:");
        refused("a row with a date", insert(values("attendance", MY_ATTENDANCE, "date", "t", "durationMillis", 0)), ME, MY_ACCOUNT, false);
        refused("moving their row to another attendance", update(MY_ROW, values("attendance", THEIR_ATTENDANCE)), ME, MY_ACCOUNT, false);
        refused("moving their row to another attendance, with the duration",
            update(MY_ROW, values("durationMillis", 1, "attendance", THEIR_ATTENDANCE)), ME, MY_ACCOUNT, false);
        refused("deleting their own row", delete(MY_ROW), ME, MY_ACCOUNT, false);

        System.out.println("no person to record as (what the census saw: 'by logged-out'):");
        refused("signed out: the heartbeat", update(MY_ROW, values("durationMillis", 60_000)), null, null, false);
        // Ownership alone would refuse it too (no account owns nothing); the census must still say WHY, and the
        // lookup is not worth running for a caller who is nobody.
        check("and the census line says it was nobody",
            "the caller has no person to record viewing as".equals(judge(update(MY_ROW, values("durationMillis", 1)), null, null, false)));
        refused("anonymous: a play", played(MY_ATTENDANCE), null, null, false);
        refused("a person without an account", played(MY_ATTENDANCE), ME, null, false);
        check("the support view acts for no account",
            MediaConsumptionWritePolicy.callerAccountId(new ModalityUserPrincipal(ME, MY_ACCOUNT, 300)) == null);
        check("a member acts for their account",
            MY_ACCOUNT == ((Number) MediaConsumptionWritePolicy.callerAccountId(new ModalityUserPrincipal(ME, MY_ACCOUNT))).intValue());
        check("the logout sentinel acts for no account", MediaConsumptionWritePolicy.callerAccountId("LOGOUT") == null);

        System.out.println("the back office:");
        passes("deletes a session's rows", delete(THEIR_ROW), 300, 3000, true);
        refused("but not a back-office token without a person", delete(THEIR_ROW), null, null, true);

        System.out.println("what the parser could not read:");
        refused("an update whose target is unknown", update(null, values("durationMillis", 1)), ME, MY_ACCOUNT, false);
        refused("an attendance written as an expression", played(ProtectedEntityWriteRegistry.UNRESOLVED_VALUE), ME, MY_ACCOUNT, false);
        refused("an attendance pointing into the batch", played(new GeneratedKeyReference(0)), ME, MY_ACCOUNT, false);
        refused("an insert naming no attendance",
            insert(values("scheduledItem", 7, "played", true, "durationMillis", 0)), ME, MY_ACCOUNT, false);
        refused("an unreadable write", null, ME, MY_ACCOUNT, false);
        check("other tables are not this rule's",
            judge(new WriteRequest("Event", WriteVerb.INSERT, new String[] { "name" }, values("name", "x"), null, false, null),
                null, null, false) == null);

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}
