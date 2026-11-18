package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.submit.SubmitResult;

import java.util.List;

/**
 * The DataProtection grant alert (admin-tiers plan, D5; V0172) without a database: what the claim statement compares,
 * how new holders are read back and described, and that the log line carries ids only while the mails escape what
 * people type. The statements themselves were rehearsed on PostgreSQL 17 (see the plan).
 *
 * <p>No test framework — runs from main() and exits non-zero, like the checks next door.
 *
 * @author Claude Code
 */
public class DataProtectionGrantAlertCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    static boolean noThrow(Runnable runnable) {
        try { runnable.run(); return true; } catch (RuntimeException e) { return false; }
    }

    public static void main(String[] args) {
        System.out.println("DataProtectionGrantAlertCheck");

        // ── Who holds it: the same question V0172 seeded the table with ──
        String holders = DataProtectionGrantAlert.HOLDERS_SQL;
        check("holders are read from access rows, for DataProtection", holders.contains("from authorization_organization_user_access a")
            && holders.contains("op.operation_code = 'DataProtection'"));
        check("...granted by the role directly or through the operation's group",
            holders.contains("ro.operation_id = op.id or ro.operation_group_id = op.group_id"));
        check("...a read-only access only when the operation is read-only", holders.contains("(not a.read_only or op.read_only)"));

        // ── The claim: one statement, forget the gone, record the new, return only the new ──
        String claim = DataProtectionGrantAlert.CLAIM_SQL;
        check("the claim compares against the holders query itself", claim.contains("with current_holders as (" + holders + ")"));
        check("a holder no longer there is forgotten (so a re-grant alerts again)",
            claim.contains("delete from data_protection_grant_seen s where not exists"));
        check("...compared on every key, organization, event and type included (null-safe)",
            claim.contains("c.access_id = s.access_id and c.user_id = s.user_id")
            && claim.contains("c.organization_id is not distinct from s.organization_id")
            && claim.contains("c.event_id is not distinct from s.event_id")
            && claim.contains("c.event_type_id is not distinct from s.event_type_id"));
        check("a holder already alerted is not claimed again, by this server or another",
            claim.endsWith("on conflict do nothing returning id"));

        // ── The check after a grant write ──
        check("'at once' is a delay Vert.x accepts (setTimer refuses under 1 ms)", DataProtectionGrantAlert.SOON_DELAY_MILLIS >= 1);
        check("before start(), asking for a check does nothing and does not throw", noThrow(DataProtectionGrantAlert::checkSoon));

        // ── The claimed ids ──
        check("no keys, nothing to alert", DataProtectionGrantAlert.claimedIds(new SubmitResult(0, null)).isEmpty());
        check("the returned ids, as integers", DataProtectionGrantAlert.claimedIds(new SubmitResult(1, new Object[] { 7L, 9 }))
            .equals(List.of(7, 9)));
        String details = DataProtectionGrantAlert.detailsSql(List.of(7, 9));
        check("details are read for exactly those rows, written as integers", details.contains("where s.id in (7,9) order by s.id"));

        // ── Reading a holder back ──
        // Columns: seenId, accessId, personId, first, last, username, orgId, org, eventId, event, typeId, type, role, readOnly
        QueryResult rows = new QueryResult(3, 14, new Object[] {
            1, 2, 3,                                     // seen id
            10, 11, 12,                                  // access row
            100, 101, 102,                               // person
            "Ada", "<b>Eve</b>", null,                   // first name
            "Lovelace", "‮evil", null,              // last name
            "ada@example.org", "eve@example.org", null,  // sign-in
            151, 151, 187,                               // organization id
            "Org A", "Org A", null,                      // organization
            null, 1839, null,                            // event id
            null, "US Festival", null,                   // event
            null, null, 4,                               // event type id
            null, null, "Festival",                      // event type
            "DPO", "DPO", "DPO",                         // role
            false, false, true                           // read-only
        }, null);
        List<DataProtectionGrantAlert.Holder> read = DataProtectionGrantAlert.holders(rows);
        check("rows read back as holders", read.size() == 3 && read.get(0).personId() == 100 && read.get(2).organizationId() == 187);
        check("scope: the whole organization", "The whole organisation".equals(read.get(0).scope()));
        check("scope: one event", read.get(1).scope().startsWith("Event #1839"));
        check("scope: an event type", read.get(2).scope().startsWith("Events of type #4"));
        check("a person with no name is named by id", read.get(2).name() == null);

        // ── The log line: what CloudWatch matches, ids only ──
        String line = read.get(0).logLine();
        check("the log line carries the marker the metric filter matches", line.contains(DataProtectionGrantAlert.LOG_MARKER + ": person 100"));
        check("...and the organization and access row", line.contains("organization 151") && line.contains("access row 10"));
        check("...but no name and no address", !line.contains("Ada") && !line.contains("Lovelace") && !line.contains("@"));
        check("an event-scoped grant says so", read.get(1).logLine().contains(", event 1839"));
        check("a read-only access says so", read.get(2).logLine().contains("(read-only access)"));

        // ── The mails ──
        String admin = DataProtectionGrantAlert.adminBody(List.of(read.get(1)), "18 Nov 2026, 15:00 UTC");
        check("the administrators' mail escapes a typed name", admin.contains("&lt;b&gt;Eve&lt;/b&gt;") && !admin.contains("<b>Eve</b>"));
        check("...drops a direction override, so a name cannot reorder what is read", !admin.contains("‮"));
        check("...names the sign-in, the organization, the scope and the role",
            admin.contains("eve@example.org") && admin.contains("Org A") && admin.contains("US Festival") && admin.contains("DPO"));
        check("...says it cannot tell who made the change", admin.contains("cannot tell you"));
        check("...and carries no link to act on", !admin.contains("href"));
        String all = DataProtectionGrantAlert.adminBody(read, "18 Nov 2026, 15:00 UTC");
        check("several holders found at once go in ONE administrators' mail, each with their table",
            all.contains("These 3 people") && all.split("<table", -1).length == 4);
        check("its subject counts them", DataProtectionGrantAlert.adminSubject(read).equals("Data Protection Officer role assigned to 3 people"));
        check("one holder's subject names them", DataProtectionGrantAlert.adminSubject(List.of(read.get(0)))
            .equals("Data Protection Officer role assigned: Ada Lovelace"));
        check("...with format characters stripped from the plain-text subject",
            !DataProtectionGrantAlert.adminSubject(List.of(read.get(1))).contains("\u202E"));
        String holder = DataProtectionGrantAlert.holderBody(read.get(0), "18 Nov 2026, 15:00 UTC");
        check("the holder's mail names the organization", holder.contains("Org A"));
        check("...and nobody's address", !holder.contains("@"));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}
