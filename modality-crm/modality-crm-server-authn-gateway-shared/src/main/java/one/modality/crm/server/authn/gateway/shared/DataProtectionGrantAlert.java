package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.scheduler.Scheduler;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitResult;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.mail.MailMessage;
import dev.webfx.stack.mail.MailService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import one.modality.base.server.mail.ModalityMailMessage;
import one.modality.base.shared.context.ModalityContext;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Tells the platform super administrators, and the person concerned, whenever somebody NEW holds DataProtection — the
 * Data Protection Officer's operation (admin-tiers plan, D5; V0172).
 *
 * <h3>Why</h3>
 *
 * <p>DataProtection is explicit-only (V0166): no wildcard grant covers it, so a super administrator cannot act as the
 * Data Protection Officer. But a platform super administrator may still ASSIGN the role — to somebody else, or to
 * themself — and anybody who writes the database directly (KBS2, a hand-run script) may too. "Cannot" is really
 * "cannot without a visible step"; this is what makes the step visible.
 *
 * <h3>How: the result, not the writes</h3>
 *
 * <p>Every two minutes the server asks who holds DataProtection through an access row ({@link #HOLDERS_SQL}: the role
 * grants it directly or through the operation's group; a read-only access only if the operation is read-only) and
 * compares the answer with {@code data_protection_grant_seen} in ONE statement ({@link #CLAIM_SQL}): holders no longer
 * there are deleted, new ones inserted, and the inserted ids come back. Comparing the outcome catches every road to it
 * — a new access row, an access row moved to another person, the operation added to a role that people already hold,
 * KBS2, SQL — where a hook on the application's writes would see only the first. The insert is ON CONFLICT DO NOTHING
 * on a unique index, so with several servers running exactly one of them claims a new holder and alerts.
 *
 * <p>What it does not see: grants given by free-text authorization rules, which are not applied while
 * {@code RULE_GRANTS_APPLIED} is off (the grant push and the DPO gate both ignore them, so they grant nothing). And it
 * cannot say WHO made the change: nothing records the writer of an access row. The mail says so.
 *
 * <h3>What is sent, and logged</h3>
 *
 * <ul>
 *   <li>A log line per new holder, ids only, starting {@link #LOG_MARKER} — for a CloudWatch metric filter. Logged
 *       BEFORE the mails, so a mail that cannot be sent still leaves the line.</li>
 *   <li>To every platform super administrator (V0167: organization {@code null}): who, which sign-in, which
 *       organization and scope, which role. No link to act on.</li>
 *   <li>To the holder: that they now hold the role. If their account was taken, they hear it from the system.</li>
 * </ul>
 *
 * <p><b>Personal data:</b> the administrators' mail carries the holder's name and sign-in email, to the people who
 * could have assigned the role and can remove it; the holder's own mail names only the organization. Every value is
 * HTML-escaped (a name is typed by its owner). Mail lands in the {@code mail} table and follows its retention. The table
 * holds ids and a timestamp only. Never fails anything: a check that cannot run is logged and retried.
 *
 * @author Claude Code
 */
public final class DataProtectionGrantAlert {

    /** The start of the log line a CloudWatch metric filter matches. Do not reword it without the filter. */
    public static final String LOG_MARKER = "DataProtection granted";

    static final long INTERVAL_MILLIS = 2 * 60 * 1000;
    /** "At once" for a timer: Vert.x refuses a delay under 1 ms (IllegalArgumentException), so 0 never runs at all. */
    static final long SOON_DELAY_MILLIS = 1;
    private static final long FIRST_DELAY_MILLIS = 60 * 1000;
    private static final String LOG_PREFIX = "[authz] ";
    private static final String MAIL_FROM = "kbs@kadampa.net";
    private static final String MAIL_FROM_NAME = "Kadampa Booking System";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH);

    /** Who holds DataProtection through an access row — the same question V0172 seeded the table with. */
    static final String HOLDERS_SQL =
        "select a.id as access_id, a.user_id, a.organization_id, a.event_id, a.event_type_id" +
        "  from authorization_organization_user_access a" +
        "  join operation op on op.operation_code = 'DataProtection'" +
        " where (not a.read_only or op.read_only)" +
        "   and exists (select 1 from authorization_role_operation ro" +
        "                where ro.role_id = a.role_id and (ro.operation_id = op.id or ro.operation_group_id = op.group_id))";

    /**
     * One statement: forget the holders that no longer hold, record the new ones, return the new rows' ids. A
     * data-modifying CTE runs whether or not the final statement reads it.
     */
    static final String CLAIM_SQL =
        "with current_holders as (" + HOLDERS_SQL + ")," +
        " gone as (" +
        "  delete from data_protection_grant_seen s where not exists (select 1 from current_holders c" +
        "   where c.access_id = s.access_id and c.user_id = s.user_id" +
        "     and c.organization_id is not distinct from s.organization_id" +
        "     and c.event_id is not distinct from s.event_id" +
        "     and c.event_type_id is not distinct from s.event_type_id)" +
        "  returning s.id)" +
        " insert into data_protection_grant_seen (access_id, user_id, organization_id, event_id, event_type_id)" +
        " select access_id, user_id, organization_id, event_id, event_type_id from current_holders" +
        " on conflict do nothing returning id";

    // Every platform super administrator who can still read a mail: the login username, an address the person controls
    private static final String PLATFORM_ADMINS_SQL =
        "select distinct fa.username from authorization_super_admin s" +
        "  join person p on p.id = s.super_admin_id join frontend_account fa on fa.id = p.frontend_account_id" +
        " where s.organization_id is null and p.removed is not true and fa.disabled is not true";

    private static final AtomicBoolean started = new AtomicBoolean();
    private static final AtomicBoolean running = new AtomicBoolean();
    private static final AtomicBoolean rerun = new AtomicBoolean();
    private static volatile boolean failing;

    private DataProtectionGrantAlert() {}

    /** Starts the periodic check, once per server. The first run waits a minute, for the boot migrations. */
    public static void start() {
        if (!started.compareAndSet(false, true))
            return;
        Scheduler.scheduleDelay(FIRST_DELAY_MILLIS, () -> {
            check();
            Scheduler.schedulePeriodic(INTERVAL_MILLIS, DataProtectionGrantAlert::check);
        });
    }

    /**
     * A check now, after a back-office write to the grant tables succeeded: without it, a role given and taken back
     * within the two minutes between checks — long enough to act as the Data Protection Officer — would never be seen.
     * Only for writes this server makes; a direct database write still waits for the next periodic check.
     *
     * <p>Never throws: it is called from the write observer, which also discards cached authorizations, and an
     * exception here once skipped that silently (the registry swallows what an observer throws).
     */
    public static void checkSoon() {
        if (!started.get())
            return;
        try {
            Scheduler.scheduleDelay(SOON_DELAY_MILLIS, DataProtectionGrantAlert::check);
        } catch (RuntimeException e) {
            Console.log(LOG_PREFIX + "⚠️ DataProtection grant check could not be scheduled after a grant write ("
                        + e.getClass().getSimpleName() + ") — the periodic check still runs");
        }
    }

    /** One comparison. While one runs, a second request runs once more after it rather than being dropped. */
    static void check() {
        if (!running.compareAndSet(false, true)) {
            rerun.set(true);
            return;
        }
        Future<SubmitResult> claim;
        try {
            claim = SubmitService.executeSubmit(SubmitArgument.builder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(CLAIM_SQL)
                .build());
        } catch (RuntimeException e) {
            // Thrown rather than failed: the flag must still come down, or no check would ever run again
            claim = Future.failedFuture(e);
        }
        claim
            .compose(result -> {
                if (failing) {
                    failing = false;
                    Console.log(LOG_PREFIX + "DataProtection grant check is running again");
                }
                List<Integer> ids = claimedIds(result);
                return ids.isEmpty() ? Future.succeededFuture() : alert(ids);
            })
            .onFailure(e -> {
                // Once per outage, not every two minutes (the table is missing until V0172 has run)
                if (!failing) {
                    failing = true;
                    Console.log(LOG_PREFIX + "⚠️ DataProtection grant check failed (" + e.getClass().getSimpleName()
                                + ": " + e.getMessage() + ") — retried every 2 minutes");
                }
            })
            .onComplete(ignored -> {
                running.set(false);
                if (rerun.getAndSet(false))
                    check();
            });
    }

    static List<Integer> claimedIds(SubmitResult result) {
        List<Integer> ids = new ArrayList<>();
        Object[] keys = result == null ? null : result.getGeneratedKeys();
        if (keys != null)
            for (Object key : keys) {
                Integer id = Numbers.toInteger(key);
                if (id != null)
                    ids.add(id);
            }
        return ids;
    }

    /** The details of these rows of data_protection_grant_seen. The ids are the database's own, written as integers. */
    static String detailsSql(Collection<Integer> seenIds) {
        return "select s.id, s.access_id, s.user_id, p.first_name, p.last_name, fa.username," +
               " s.organization_id, o.name, s.event_id, e.name, s.event_type_id, et.name, r.name, a.read_only" +
               " from data_protection_grant_seen s" +
               " join authorization_organization_user_access a on a.id = s.access_id" +
               " left join person p on p.id = s.user_id" +
               " left join frontend_account fa on fa.id = p.frontend_account_id" +
               " left join organization o on o.id = s.organization_id" +
               " left join event e on e.id = s.event_id" +
               " left join event_type et on et.id = s.event_type_id" +
               " left join authorization_role r on r.id = a.role_id" +
               " where s.id in (" + seenIds.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")" +
               " order by s.id";
    }

    /** A new holder, as read back. Every field may be null except the ids the table requires. */
    record Holder(Integer seenId, Integer accessId, Integer personId, String firstName, String lastName, String username,
                  Integer organizationId, String organization, Integer eventId, String event,
                  Integer eventTypeId, String eventType, String role, boolean readOnly) {

        String name() {
            String name = ((firstName == null ? "" : firstName.trim()) + " " + (lastName == null ? "" : lastName.trim())).trim();
            return name.isEmpty() ? null : name;
        }

        /** Where the role holds: the whole organization, one event, or one event type. */
        String scope() {
            if (eventId != null)
                return "Event #" + eventId + (event == null ? "" : " — " + event);
            if (eventTypeId != null)
                return "Events of type #" + eventTypeId + (eventType == null ? "" : " — " + eventType);
            return "The whole organisation";
        }

        /** The log line: ids only, no name or address — the log is kept longer and read more widely than the mail. */
        String logLine() {
            return LOG_PREFIX + "🔐 " + LOG_MARKER + ": person " + personId + ", organization " + organizationId
                   + (eventId != null ? ", event " + eventId : "") + (eventTypeId != null ? ", event type " + eventTypeId : "")
                   + ", access row " + accessId + (readOnly ? " (read-only access)" : "");
        }
    }

    static List<Holder> holders(QueryResult r) {
        List<Holder> holders = new ArrayList<>();
        for (int row = 0; r != null && row < r.getRowCount(); row++)
            holders.add(new Holder(integer(r, row, 0), integer(r, row, 1), integer(r, row, 2), string(r, row, 3),
                string(r, row, 4), string(r, row, 5), integer(r, row, 6), string(r, row, 7), integer(r, row, 8),
                string(r, row, 9), integer(r, row, 10), string(r, row, 11), string(r, row, 12),
                Boolean.TRUE.equals(r.getValue(row, 13))));
        return holders;
    }

    private static Future<Void> alert(List<Integer> seenIds) {
        return Future.all(List.of(RawSql.query(detailsSql(seenIds)), RawSql.query(PLATFORM_ADMINS_SQL)))
            .compose(both -> {
                List<Holder> holders = holders(both.resultAt(0));
                QueryResult adminRows = both.resultAt(1);
                Set<String> admins = new LinkedHashSet<>();
                for (int row = 0; adminRows != null && row < adminRows.getRowCount(); row++) {
                    String address = string(adminRows, row, 0);
                    if (address != null && !address.isBlank())
                        admins.add(address.trim());
                }
                String when = ZonedDateTime.now(ZoneOffset.UTC).format(TIME);
                List<Future<?>> sends = new ArrayList<>();
                for (Holder holder : holders) {
                    Console.log(holder.logLine() + " — mailing " + admins.size() + " platform super administrator(s)"
                                + (holder.username() == null ? "" : " and the holder"));
                    if (holder.username() != null && !holder.username().isBlank())
                        sends.add(send(holder.username().trim(), "You now hold the Data Protection Officer role", holderBody(holder, when)));
                }
                // One mail per administrator per check, however many holders it found: a role that gains the operation
                // gives it to everybody holding the role at once, and that must not become one mail per person each
                if (!holders.isEmpty()) {
                    String subject = adminSubject(holders), body = adminBody(holders, when);
                    for (String admin : admins)
                        sends.add(send(admin, subject, body));
                }
                if (holders.size() < seenIds.size())
                    Console.log(LOG_PREFIX + "⚠️ " + LOG_MARKER + ": " + (seenIds.size() - holders.size())
                                + " new holder(s) could not be read back (rows " + seenIds + ") — check the access rows");
                return Future.all(sends).<Void>mapEmpty();
            })
            .recover(e -> {
                // The rows are claimed: say so loudly, with the ids, so the alert is not lost with the mail
                Console.log(LOG_PREFIX + "⚠️ " + LOG_MARKER + ": new holder rows " + seenIds + " — the alert could not be"
                            + " prepared (" + e.getClass().getSimpleName() + ")");
                return Future.succeededFuture();
            });
    }

    /** The administrators' subject: the holder's name, or how many. Plain text, so line breaks and format characters go. */
    static String adminSubject(List<Holder> holders) {
        if (holders.size() > 1)
            return "Data Protection Officer role assigned to " + holders.size() + " people";
        Holder holder = holders.get(0);
        String name = holder.name() == null ? "person #" + holder.personId()
            : holder.name().replaceAll("[\\r\\n\\t]", " ").replaceAll("\\p{Cf}", "");
        return "Data Protection Officer role assigned: " + name;
    }

    static String adminBody(List<Holder> holders, String when) {
        StringBuilder tables = new StringBuilder();
        for (Holder holder : holders)
            tables.append(holderTable(holder, when));
        return FactorApprovalNotice.page("The Data Protection Officer role was assigned",
            "<p>" + (holders.size() == 1 ? "This person" : "These " + holders.size() + " people") + " can now erase people's"
            + " personal data, remove events' sensitive details and set how long data is kept, in the scope shown.</p>"
            + tables
            + "<p><b>If nobody meant to assign it</b>, remove the role in the back office (<b>Authorizations</b>) and find out"
            + " who changed it: this alert cannot tell you — the change may have been made in the back office, in KBS2 or"
            + " directly in the database.</p>"
            + "<p style=\"font-size:12px;color:#888\">Sent to every platform super administrator.</p>");
    }

    private static String holderTable(Holder holder, String when) {
        StringBuilder rows = new StringBuilder();
        row(rows, "Person", (holder.name() == null ? "" : holder.name() + " ") + "(#" + holder.personId() + ")");
        row(rows, "Sign-in email", holder.username());
        row(rows, "Organisation", holder.organization() == null ? "#" + holder.organizationId() : holder.organization());
        row(rows, "Scope", holder.scope());
        row(rows, "Role", holder.role());
        if (holder.readOnly())
            row(rows, "Access", "Read-only");
        row(rows, "Noticed", when);
        return "<table style=\"border-collapse:collapse;margin:12px auto;text-align:left\">" + rows + "</table>";
    }

    static String holderBody(Holder holder, String when) {
        String organization = holder.organization() == null ? "an organisation" : holder.organization();
        return FactorApprovalNotice.page("You are now a Data Protection Officer",
            "<p>On " + FactorApprovalNotice.esc(when) + " your Kadampa Booking System account was given the Data Protection"
            + " Officer role for " + FactorApprovalNotice.esc(organization) + ".</p>"
            + "<p><b>If you did not expect this</b>, tell a super administrator at once: somebody may be using your account,"
            + " or the role was given by mistake.</p>");
    }

    private static void row(StringBuilder rows, String label, String value) {
        if (value == null || value.isBlank())
            return;
        rows.append("<tr><td style=\"padding:4px 12px;color:#606060\">").append(FactorApprovalNotice.esc(label))
            .append("</td><td style=\"padding:4px 12px;color:#303030\"><b>").append(FactorApprovalNotice.esc(value)).append("</b></td></tr>");
    }

    private static Future<Void> send(String to, String subject, String body) {
        MailMessage message = MailMessage.create(MAIL_FROM, to, subject, body);
        ModalityContext context = new ModalityContext(1 /* default organizationId, as for the second-factor notices */, null, null, null);
        return MailService.sendMail(new ModalityMailMessage(message, context, MAIL_FROM_NAME))
            .<Void>map(ignored -> null)
            .recover(e -> {
                Console.log(LOG_PREFIX + "⚠️ A " + LOG_MARKER + " alert could not be sent (" + e.getClass().getSimpleName() + ")");
                return Future.succeededFuture();
            });
    }

    private static Integer integer(QueryResult r, int row, int column) {
        return Numbers.toInteger(r.getValue(row, column));
    }

    private static String string(QueryResult r, int row, int column) {
        Object value = r.getValue(row, column);
        return value == null ? null : value.toString();
    }
}
