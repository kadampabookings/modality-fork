package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import io.vertx.pgclient.PgException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The retention periods the Data Protection Officer sets ({@code retention_policy}, V0168): how they resolve for an
 * event, how they are served, and how one is changed.
 *
 * <p><b>Resolution</b> ({@link #monthsSql}), per stage: a finished event takes its own 'event' row, else its
 * organisation's, else the default; an ongoing event (its type is {@code ongoing}) takes its own row only — never
 * inherited (David, 2026-11-06), because a stay's period is not an event's. No row at all = undefined = the stage is
 * locked for that event: every removal is refused ({@link EventErasureRunner#RETENTION_UNDEFINED_KEY}).
 *
 * <p><b>Append-only.</b> A change supersedes the current row for the same stage and target and inserts the new one,
 * in one transaction on a connection of its own; removing an override only supersedes. The default of a stage, once
 * confirmed, can be changed but not removed: that would silently re-lock every event on it. Who set and superseded
 * each row is kept, by person id only.
 *
 * @author Claude Code
 */
final class RetentionPolicy {

    private RetentionPolicy() {
    }

    static final String STAGE_FINALISE = "finalise";
    static final String STAGE_ARCHIVE = "archive";
    static final Set<String> STAGES = Set.of(STAGE_FINALISE, STAGE_ARCHIVE);

    static final String SCOPE_DEFAULT = "default";
    static final String SCOPE_ORGANIZATION = "organization";
    static final String SCOPE_EVENT = "event";
    static final Set<String> SCOPES = Set.of(SCOPE_DEFAULT, SCOPE_ORGANIZATION, SCOPE_EVENT);

    static final int MIN_MONTHS = 1;
    static final int MAX_MONTHS = 240;
    static final int MAX_REASON_LENGTH = 500;

    static final String INVALID_KEY = "RetentionPolicyInvalidError";
    static final String DEFAULT_REQUIRED_KEY = "RetentionPolicyDefaultRequiredError";
    static final String CONFLICT_KEY = "RetentionPolicyConflictError";
    static final String FAILED_KEY = "RetentionPolicyError";

    private static final String LOG_PREFIX = "[data-protection] ";

    /**
     * The months a stage keeps an event's data, as a SQL expression over an {@code event e} and its
     * {@code event_type t} (LEFT JOINed: no type = not ongoing). NULL = undefined.
     *
     * @param stage one of {@link #STAGES} — a constant, never input (it is inlined)
     */
    static String monthsSql(String stage) {
        if (!STAGES.contains(stage))
            throw new IllegalArgumentException(stage);
        String current = "select rp.months from retention_policy rp where rp.superseded_at is null and rp.stage = '" + stage + "'";
        return "coalesce((" + current + " and rp.scope = 'event' and rp.event_id = e.id),"
               + " case when coalesce(t.ongoing, false) then null else coalesce("
               + "(" + current + " and rp.scope = 'organization' and rp.organization_id = e.organization_id),"
               + "(" + current + " and rp.scope = 'default')) end)";
    }

    // ===== Reading =================================================================================================

    /** Every current row: what the pages resolve events against. */
    static final String CURRENT_SQL =
        "select stage, scope, organization_id, event_id, months from retention_policy where superseded_at is null"
        + " order by stage, scope, organization_id, event_id";

    /**
     * The current rows ONE organisation's events resolve against — the defaults, its own override, its events' own
     * periods — for Registration › Event status ({@code $1} = the organisation), whose callers reach one organisation:
     * another organisation's periods are not theirs to read.
     */
    static final String ORGANIZATION_SQL =
        "select stage, scope, organization_id, event_id, months from retention_policy rp where superseded_at is null"
        + " and (scope = 'default' or (scope = 'organization' and organization_id = $1)"
        + " or (scope = 'event' and exists (select 1 from event e where e.id = rp.event_id and e.organization_id = $1)))"
        + " order by stage, scope, organization_id, event_id";

    /** Every row, newest first: the change history the Retention policy page shows. Ids only, never names (see {@link StaffNames}). */
    static final String HISTORY_SQL =
        "select id, stage, scope, organization_id, event_id, months, reason, set_by_person_id,"
        + " to_char(set_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),"
        + " to_char(superseded_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'), superseded_by_person_id"
        + " from retention_policy order by set_at desc, id desc limit 500";

    /**
     * PolicyJson: {@code {"finalise":{"default":n|null,"organizations":[{"id","months"}],"events":[{"id","months"}]},
     * "archive":{…}}} from {@link #CURRENT_SQL}'s rows. Pure, so a check reads it without a database.
     */
    static String policyJson(QueryResult r) {
        return policyJson(r, null);
    }

    /** {@link #policyJson(QueryResult)} keeping only the rows {@code scope} keeps (null: every row). Pure. */
    static String policyJson(QueryResult r, Scope scopeOf) {
        StringBuilder sb = new StringBuilder("{");
        boolean firstStage = true;
        for (String stage : List.of(STAGE_FINALISE, STAGE_ARCHIVE)) {
            Number defaultMonths = null;
            List<String> organizations = new ArrayList<>();
            List<String> events = new ArrayList<>();
            for (int row = 0; r != null && row < r.getRowCount(); row++) {
                if (!stage.equals(r.getValue(row, 0)))
                    continue;
                Object scope = r.getValue(row, 1);
                if (!Scope.keeps(scopeOf, scope, number(r.getValue(row, 2)), number(r.getValue(row, 3))))
                    continue;
                Number months = number(r.getValue(row, 4));
                if (SCOPE_DEFAULT.equals(scope))
                    defaultMonths = months;
                else if (SCOPE_ORGANIZATION.equals(scope))
                    organizations.add(target(number(r.getValue(row, 2)), months));
                else if (SCOPE_EVENT.equals(scope))
                    events.add(target(number(r.getValue(row, 3)), months));
            }
            if (!firstStage)
                sb.append(',');
            firstStage = false;
            sb.append(EventErasureJson.quote(stage)).append(':').append(new EventErasureJson.ObjectWriter()
                .number("default", defaultMonths)
                .raw("organizations", EventErasureJson.array(organizations))
                .raw("events", EventErasureJson.array(events))
                .toJson());
        }
        return sb.append('}').toString();
    }

    private static String target(Number id, Number months) {
        return new EventErasureJson.ObjectWriter().number("id", id).number("months", months).toJson();
    }

    /**
     * {@code {"policy":PolicyJson,"history":[…],"staff":{…}}} for the Retention policy page — this caller's share of it:
     * the default's rows, their organizations' and their organizations' events' ({@link Scope}). Another organization's
     * periods, the reasons its DPO typed and the staff who set them are not theirs to read (admin-tiers plan,
     * 2026-11-18); the page used to receive them all and hide them.
     */
    static Future<String> pageJson(EventErasureAccess.Caller caller) {
        return EventErasureRunner.query(CURRENT_SQL).compose(current -> EventErasureRunner.query(HISTORY_SQL)
            .compose(history -> scopeOf(caller, current, history).compose(scope -> {
                List<Number> staffIds = new ArrayList<>();
                for (int row = 0; history != null && row < history.getRowCount(); row++)
                    if (keepsHistoryRow(history, row, scope)) {
                        staffIds.add(number(history.getValue(row, 7)));
                        staffIds.add(number(history.getValue(row, 10)));
                    }
                // Names are looked up for the kept rows only: another organization's staff are not named either
                return StaffNames.lookup(staffIds).map(staff -> pageJson(policyJson(current, scope), history, staff, scope));
            })))
            .recover(cause -> {
                Console.log(LOG_PREFIX + "Reading the retention policy failed: " + (cause == null ? null : cause.getMessage()));
                return Future.failedFuture("[%s] The retention policy could not be read".formatted(FAILED_KEY));
            });
    }

    /**
     * {@code {"policy":…,"history":[…],"staff":{"<id>":"<name>"}}} from the reads: the change history newest first, ids
     * only in its rows, and the names of the staff they name beside them ({@link StaffNames}). Pure, for the check.
     */
    static String pageJson(String policy, QueryResult history, String staff) {
        return pageJson(policy, history, staff, null);
    }

    /** {@link #pageJson(String, QueryResult, String)} keeping only the history rows {@code scope} keeps. Pure. */
    static String pageJson(String policy, QueryResult history, String staff, Scope scope) {
        List<String> rows = new ArrayList<>();
        for (int row = 0; history != null && row < history.getRowCount(); row++)
            if (keepsHistoryRow(history, row, scope))
                rows.add(new EventErasureJson.ObjectWriter()
                .number("id", number(history.getValue(row, 0)))
                .string("stage", text(history.getValue(row, 1)))
                .string("scope", text(history.getValue(row, 2)))
                .number("organizationId", number(history.getValue(row, 3)))
                .number("eventId", number(history.getValue(row, 4)))
                .number("months", number(history.getValue(row, 5)))
                .string("reason", text(history.getValue(row, 6)))
                .number("setByPersonId", number(history.getValue(row, 7)))
                .string("setAt", text(history.getValue(row, 8)))
                .string("supersededAt", text(history.getValue(row, 9)))
                .number("supersededByPersonId", number(history.getValue(row, 10)))
                .toJson());
        return new EventErasureJson.ObjectWriter()
            .raw("policy", policy)
            .raw("history", EventErasureJson.array(rows))
            .raw("staff", staff == null ? "{}" : staff)
            .toJson();
    }

    private static boolean keepsHistoryRow(QueryResult history, int row, Scope scope) {
        return Scope.keeps(scope, history.getValue(row, 2), number(history.getValue(row, 3)), number(history.getValue(row, 4)));
    }

    // ===== One DPO's share (admin-tiers plan, 2026-11-18) ===========================================================

    /**
     * What one Data Protection Officer's replies keep of the policy and its history: the default's rows (every
     * organization starts from them), the rows of the organizations where they hold DataProtection for the whole
     * organization, and the rows of those organizations' events. Anything that cannot be placed is left out.
     */
    record Scope(java.util.Set<Integer> organizations, java.util.Map<Integer, Integer> organizationByEvent) {

        /** Nothing but the default: what a scope that cannot be read falls back to (fail closed). */
        static final Scope DEFAULT_ONLY = new Scope(java.util.Set.of(), java.util.Map.of());

        /** Whether a row of this scope, organization and event is kept; a null {@code scope} keeps every row. */
        static boolean keeps(Scope scope, Object rowScope, Number organizationId, Number eventId) {
            if (scope == null || SCOPE_DEFAULT.equals(rowScope))
                return true;
            if (SCOPE_ORGANIZATION.equals(rowScope))
                return organizationId != null && scope.organizations.contains(organizationId.intValue());
            if (SCOPE_EVENT.equals(rowScope)) {
                Integer organization = eventId == null ? null : scope.organizationByEvent.get(eventId.intValue());
                return organization != null && scope.organizations.contains(organization);
            }
            return false;
        }
    }

    /**
     * This caller's {@link Scope} over these reads — {@link #CURRENT_SQL}'s rows and {@link #HISTORY_SQL}'s, either may
     * be null: the events they name are placed in one batched read, then each organization is asked once (cached
     * grants: it decides what a reply shows, every change is re-checked fresh). Never fails: {@link Scope#DEFAULT_ONLY}.
     */
    static Future<Scope> scopeOf(EventErasureAccess.Caller caller, QueryResult current, QueryResult history) {
        java.util.Set<Integer> organizationIds = new java.util.LinkedHashSet<>();
        List<Integer> eventIds = new ArrayList<>();
        collect(current, 1, 2, 3, organizationIds, eventIds);
        collect(history, 2, 3, 4, organizationIds, eventIds);
        return EventOrganizations.ofEvents(eventIds).compose(organizationByEvent -> {
                java.util.Set<Integer> asked = new java.util.LinkedHashSet<>(organizationIds);
                asked.addAll(organizationByEvent.values());
                return EventErasureAccess.organizationsHeld(caller, asked).map(held -> new Scope(held, organizationByEvent));
            })
            .otherwise(Scope.DEFAULT_ONLY);
    }

    private static void collect(QueryResult r, int scopeColumn, int organizationColumn, int eventColumn,
                                java.util.Set<Integer> organizationIds, List<Integer> eventIds) {
        for (int row = 0; r != null && row < r.getRowCount(); row++) {
            Object scope = r.getValue(row, scopeColumn);
            if (SCOPE_ORGANIZATION.equals(scope)) {
                Number organization = number(r.getValue(row, organizationColumn));
                if (organization != null)
                    organizationIds.add(organization.intValue());
            } else if (SCOPE_EVENT.equals(scope)) {
                Number event = number(r.getValue(row, eventColumn));
                if (event != null)
                    eventIds.add(event.intValue());
            }
        }
    }

    // ===== Changing ================================================================================================

    /** A validated change: months null = remove the override. */
    record Change(String stage, String scope, Integer targetId, Integer months, String reason) {
    }

    /**
     * {@code [stage, scope, targetId, months, reason]} as the bus decoded it, or null when malformed. targetId is
     * null for the default and a positive id otherwise; months is 1–240, or null to remove an override (never the
     * default's); reason is optional text of at most 500 characters, trimmed, blank = none. Pure.
     */
    static Change changeOf(Object[] arguments) {
        String stage = EventErasureArguments.string(EventErasureArguments.at(arguments, 0));
        String scope = EventErasureArguments.string(EventErasureArguments.at(arguments, 1));
        if (!STAGES.contains(stage) || !SCOPES.contains(scope))
            return null;
        Object targetArgument = EventErasureArguments.at(arguments, 2);
        Integer targetId = EventErasureArguments.positiveId(targetArgument);
        if (SCOPE_DEFAULT.equals(scope) ? targetArgument != null : targetId == null)
            return null;
        Object monthsArgument = EventErasureArguments.at(arguments, 3);
        Integer months = EventErasureArguments.positiveId(monthsArgument);
        if (monthsArgument != null && (months == null || months < MIN_MONTHS || months > MAX_MONTHS))
            return null;
        Object reasonArgument = EventErasureArguments.at(arguments, 4);
        if (reasonArgument != null && !(reasonArgument instanceof String))
            return null;
        String reason = reasonArgument == null ? null : ((String) reasonArgument).strip();
        if (reason != null && reason.length() > MAX_REASON_LENGTH)
            return null;
        return new Change(stage, scope, targetId, months, reason == null || reason.isEmpty() ? null : reason);
    }

    private static final String TARGET_EXISTS_SQL_ORGANIZATION = "select 1 from organization where id = $1";
    private static final String TARGET_EXISTS_SQL_EVENT = "select 1 from event where id = $1";

    /**
     * One statement, so atomic on its own: supersedes the current row of one stage and target, then inserts the new
     * one unless the change removes an override ($5 null). The insert reads the update's output (its count), so the
     * old row is superseded before the new one meets the partial unique index of current rows; two DPOs changing
     * the same target at once still collide there (23505 → {@code RetentionPolicyConflictError}). $1 stage, $2 scope,
     * $3 target id (null for the default), $4 person, $5 months, $6 reason.
     */
    static final String CHANGE_SQL =
        "with superseded as (update retention_policy set superseded_at = now(), superseded_by_person_id = $4::int"
        + " where superseded_at is null and stage = $1::text and scope = $2::text"
        + " and organization_id is not distinct from (case when $2::text = 'organization' then $3::int end)"
        + " and event_id is not distinct from (case when $2::text = 'event' then $3::int end) returning id)"
        + " insert into retention_policy (stage, scope, organization_id, event_id, months, reason, set_by_person_id)"
        + " select $1::text, $2::text, case when $2::text = 'organization' then $3::int end,"
        + " case when $2::text = 'event' then $3::int end, $5::smallint, $6::text, $4::int"
        + " from (select count(*) from superseded) s where $5::smallint is not null";

    /**
     * Applies one change for the DPO {@link EventErasureAccess} allowed, and answers the page's JSON afterwards.
     * Refusals the page explains: {@code RetentionPolicyInvalidError} (malformed, or no such organisation/event),
     * {@code RetentionPolicyDefaultRequiredError} (removing a default), {@code RetentionPolicyConflictError}
     * (somebody changed the same target at the same moment: read again and retry).
     */
    static Future<String> set(Object[] arguments, EventErasureAccess.Caller caller) {
        Change change = changeOf(arguments);
        if (change == null)
            return Future.failedFuture("[%s] The retention period is not valid".formatted(INVALID_KEY));
        if (SCOPE_DEFAULT.equals(change.scope()) && change.months() == null)
            return Future.failedFuture("[%s] A confirmed default can be changed, not removed".formatted(DEFAULT_REQUIRED_KEY));
        Future<Boolean> targetExists = SCOPE_DEFAULT.equals(change.scope())
            ? Future.succeededFuture(true)
            : EventErasureRunner.query(SCOPE_ORGANIZATION.equals(change.scope()) ? TARGET_EXISTS_SQL_ORGANIZATION : TARGET_EXISTS_SQL_EVENT,
                change.targetId()).map(rows -> rows != null && rows.getRowCount() > 0);
        return targetExists
            .compose(exists -> !exists
                ? Future.<Void>failedFuture("[%s] No such %s".formatted(INVALID_KEY, change.scope()))
                : EventErasureRunner.asServer(() -> SubmitService.executeSubmit(new SubmitArgumentBuilder()
                        .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                        .setStatement(CHANGE_SQL)
                        .setParameters(change.stage(), change.scope(), change.targetId(), caller.personId(), change.months(),
                            change.reason())
                        .build()))
                    .<Void>map(ignored -> null))
            .<Void>recover(cause -> {
                String message = cause == null ? null : cause.getMessage();
                if (message != null && message.startsWith("[RetentionPolicy"))
                    return Future.failedFuture(message);
                if (isCurrentRowConflict(cause))
                    return Future.failedFuture("[%s] The period was changed at the same moment; reload and try again"
                        .formatted(CONFLICT_KEY));
                Console.log(LOG_PREFIX + "Setting a retention period failed for person " + caller.personId() + ": "
                            + EventErasureRunner.sqlStateOf(cause));
                return Future.failedFuture("[%s] The retention period could not be saved".formatted(FAILED_KEY));
            })
            .compose(ignored -> {
                // A period decides each continuous event's cutoff: the departed counts are read again
                EventErasureLedger.forgetDepartedCounts();
                Console.log(LOG_PREFIX + "Retention " + change.stage() + "/" + change.scope()
                            + (change.targetId() == null ? "" : " " + change.targetId()) + " set to "
                            + (change.months() == null ? "inherited" : change.months() + " months")
                            + " by person " + caller.personId());
                return pageJson(caller);
            });
    }

    /**
     * Whether a failed change hit the partial unique index of current rows: the database's unique violation, whether it
     * arrives as the driver's exception or as the submit service's message naming the index.
     */
    static boolean isCurrentRowConflict(Throwable cause) {
        if (cause instanceof PgException pg)
            return "23505".equals(pg.getSqlState());
        String message = cause == null ? null : cause.getMessage();
        return message != null && message.contains("retention_policy_current_");
    }

    private static Number number(Object value) {
        return value instanceof Number n ? n : null;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
