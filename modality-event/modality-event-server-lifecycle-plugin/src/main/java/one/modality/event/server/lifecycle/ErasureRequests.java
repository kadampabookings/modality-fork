package one.modality.event.server.lifecycle;

import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.ReadOnlyAstArray;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitResult;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The Data Protection Officer's register of erasure requests ({@code erasure_request}, V0165), for
 * {@link ErasureRequestsEndpoint} (the list) and {@link SaveErasureRequestEndpoint} (create or update one).
 *
 * <h3>A request, as the page sends it ({@link #parseSave})</h3>
 *
 * One JSON object — as a string (the contract), or as the object the bus decoded — with exactly these keys, each
 * checked here whatever the browser checked:
 * <ul>
 *   <li>{@code id}: null or absent = create; else a positive id.</li>
 *   <li>{@code receivedOn}: {@code YYYY-MM-DD}, not after today, not more than a year before it — unless it is the
 *       date the request already holds (a late request can still be closed).</li>
 *   <li>{@code status}: open | on_hold | done | refused.</li>
 *   <li>{@code onHoldUntil}: a date exactly when on_hold, after today — unless it is the date already held.</li>
 *   <li>{@code verifiedBy}: email | id | other | null.</li>
 *   <li>{@code subjectKind} (person | booker) and {@code subjectId} (a positive id): both or neither.</li>
 *   <li>{@code runId}: a positive id or null; when set, it must be a succeeded commit of {@code person_erasure_run}
 *       for this very subject (person_id = the person, or minus the booking for a booker) — the register's proof
 *       cannot point at somebody else's erasure.</li>
 *   <li>{@code outside}: absent or null leaves it (all false on a new request); else an object with exactly the
 *       {@link #OUTSIDE_KEYS} — the processors outside KBS the DPO ticks off — all booleans.</li>
 * </ul>
 * Any other key is refused, the reply-only ones included: the server sets those, never the client. "Today" is
 * Europe/London's.
 *
 * <p>{@code checked_at} is never sent: a complete, clean check stamps it ({@link ErasureResidueCheck},
 * {@link #stampChecked}), and a save that changes the subject or the run clears it — a check of somebody else, or
 * made before another run, proves nothing about this one.
 *
 * <p>The person who created or closed a request is the caller of the session ({@link EventErasureAccess.Caller}),
 * never a value sent. {@code closed_at} is stamped when the status becomes done or refused, kept while it stays
 * closed, cleared when it reopens. An absent value of a nullable field means null: the page sends the whole request.
 *
 * <p>No personal data passes here: dates, codes, ids and booleans. Refusals are {@code [ErasureRequestError] text}
 * and {@code [ErasureRequestNotFoundError] text}.
 *
 * @author Claude Code
 */
final class ErasureRequests {

    private ErasureRequests() {
    }

    static final String REQUEST_KEY = "ErasureRequestError";
    static final String NOT_FOUND_KEY = "ErasureRequestNotFoundError";

    static final int MAX_LISTED = 200;
    static final int MAX_JSON_LENGTH = 2000;

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Europe/London");

    static final Set<String> STATUSES = Set.of("open", "on_hold", "done", "refused");
    static final Set<String> CLOSED_STATUSES = Set.of("done", "refused");
    static final Set<String> VERIFIED_BY = Set.of("email", "id", "other");
    static final Set<String> SUBJECT_KINDS = Set.of("person", "booker");
    static final Set<String> FIELDS = Set.of("id", "receivedOn", "status", "onHoldUntil", "verifiedBy", "subjectKind",
        "subjectId", "runId", "outside");
    /**
     * The places outside KBS where the person's data may also live, which the DPO ticks off one by one: Jotform (its
     * forms, the volunteering applications among them), the Knack apps (STTP, absence requests), the payment
     * provider, and the DPO's own mailbox and spreadsheets. In display order. Adding one is a line here and one in
     * the back office's list (and its label): the table only checks that the column is a small object.
     */
    static final List<String> OUTSIDE_KEYS = List.of("jotform", "knackSttp", "knackAbsence", "payments", "mailbox");

    /** The outside-KBS checklist: one boolean per {@link #OUTSIDE_KEYS}, in that order. */
    record Outside(List<Boolean> ticks) {
        static final Outside NONE = new Outside(OUTSIDE_KEYS.stream().map(key -> false).toList());

        /** As stored and answered: every key, in order, written here — never the client's text. */
        String toJson() {
            EventErasureJson.ObjectWriter writer = new EventErasureJson.ObjectWriter();
            for (int i = 0; i < OUTSIDE_KEYS.size(); i++)
                writer.bool(OUTSIDE_KEYS.get(i), ticks.get(i));
            return writer.toJson();
        }

        /** What a stored row holds: a key it lacks (added since) or cannot read is unticked; unknown keys are ignored. */
        static Outside stored(Object text) {
            ReadOnlyAstObject object = text instanceof String s ? asObject(s) : null;
            return new Outside(OUTSIDE_KEYS.stream()
                .map(key -> object != null && Boolean.TRUE.equals(object.get(key))).toList());
        }
    }

    /** A save the shape rules accepted ({@link #parseSave}); the date rules come after ({@link #dateRefusal}). */
    record Save(Integer id, LocalDate receivedOn, String status, LocalDate onHoldUntil, String verifiedBy,
                String subjectKind, Integer subjectId, Integer runId, Outside outside) {
        boolean closed() {
            return CLOSED_STATUSES.contains(status);
        }
    }

    /** {@link #parseSave}'s answer: the save, or the refusal's text. */
    record ParsedSave(Save save, String refusal) {
        static ParsedSave refused(String text) {
            return new ParsedSave(null, text);
        }
    }

    // ===== SQL — constants only; every value is bound ===============================================================

    private static final String UTC_STAMP = "'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'";

    /** One request as the reply shows it. dueOn = received_on + 1 month (Postgres: 31 Jan + 1 month = 28/29 Feb). */
    private static final String REQUEST_COLUMNS =
        "select r.id, to_char(r.received_on, 'YYYY-MM-DD'), to_char(r.received_on + interval '1 month', 'YYYY-MM-DD'),"
        + " r.status, to_char(r.on_hold_until, 'YYYY-MM-DD'), r.verified_by, r.subject_kind, r.subject_id, r.run_id,"
        + " to_char(r.checked_at at time zone 'UTC', " + UTC_STAMP + "),"
        + " r.outside::text,"
        + " to_char(r.created_at at time zone 'UTC', " + UTC_STAMP + "), r.created_by_person_id,"
        + " to_char(r.closed_at at time zone 'UTC', " + UTC_STAMP + "),"
        // The subject's latest succeeded erasure, whether or not the request recorded it: a commit whose link to the
        // request was lost (the page left, the save failed) can still be recorded instead of leaving the request stuck
        + " (select max(e.id) from person_erasure_run e where e.mode = 'commit' and e.status = 'succeeded'"
        + " and r.subject_id is not null"
        + " and e.person_id = case when r.subject_kind = 'booker' then -r.subject_id else r.subject_id end),"
        // When the recorded run finished: the date the erasure happened, for the reply to the person
        + " (select to_char(e.finished_at at time zone 'UTC', " + UTC_STAMP + ") from person_erasure_run e where e.id = r.run_id)"
        + " from erasure_request r";

    static final String LIST_SQL = REQUEST_COLUMNS + " order by r.received_on desc, r.id desc limit " + MAX_LISTED;

    static final String ONE_SQL = REQUEST_COLUMNS + " where r.id = $1::int";

    /** Whether a run is a succeeded commit for the subject: person_id is the person, or minus the booking (V0163). */
    static final String RUN_OF_SUBJECT_SQL = "select 1 from person_erasure_run where id = $1::int and mode = 'commit'"
        + " and status = 'succeeded' and person_id = $2::int";

    /** What an update's date rules compare with: the dates the request already holds. */
    static final String STORED_SQL =
        "select to_char(r.received_on, 'YYYY-MM-DD'), to_char(r.on_hold_until, 'YYYY-MM-DD') from erasure_request r where r.id = $1::int";

    /**
     * $1 received_on, $2 status, $3 on_hold_until, $4 verified_by, $5 subject_kind, $6 subject_id, $7 run_id,
     * $8 outside (the checklist's JSON, written by {@link Outside#toJson}, or null), $9 the caller. checked_at starts
     * empty: only a check stamps it.
     */
    static final String INSERT_SQL =
        "insert into erasure_request (received_on, status, on_hold_until, verified_by, subject_kind, subject_id, run_id,"
        + " outside, created_by_person_id, closed_at, closed_by_person_id)"
        + " values ($1::date, $2::text, $3::date, $4::text, $5::text, $6::int, $7::int,"
        + " coalesce($8::text::jsonb, '{}'::jsonb), $9::int,"
        + " case when $2::text in ('done', 'refused') then now() end,"
        + " case when $2::text in ('done', 'refused') then $9::int end)"
        + " returning id";

    /**
     * $1 id, then as INSERT_SQL ($2-$8 its $1-$7, $9 outside, $10 the caller). In SET, r.* is the row BEFORE the
     * update: closed_at is kept while the request stays closed, stamped when it closes, cleared when it reopens;
     * checked_at is kept only while the subject and the run stay the same.
     */
    static final String UPDATE_SQL =
        "update erasure_request r set received_on = $2::date, status = $3::text, on_hold_until = $4::date,"
        + " verified_by = $5::text, subject_kind = $6::text, subject_id = $7::int, run_id = $8::int,"
        + " checked_at = case when r.subject_kind is not distinct from $6::text and r.subject_id is not distinct from $7::int"
        + " and r.run_id is not distinct from $8::int then r.checked_at end,"
        + " outside = coalesce($9::text::jsonb, r.outside), updated_at = now(),"
        + " closed_at = case when $3::text in ('done', 'refused')"
        + " then case when r.status in ('done', 'refused') then r.closed_at else now() end end,"
        + " closed_by_person_id = case when $3::text in ('done', 'refused')"
        + " then case when r.status in ('done', 'refused') then r.closed_by_person_id else $10::int end end"
        + " where r.id = $1::int"
        + " returning r.id";

    /** $1 the request: stamped only when it names its subject and the run that erased them; the check decides the rest. */
    static final String STAMP_CHECKED_SQL =
        "update erasure_request set checked_at = now() where id = $1::int and subject_id is not null and run_id is not null"
        + " returning id";

    // ===== The list ================================================================================================

    static Future<String> list(EventErasureAccess.Caller caller) {
        return EventErasureRunner.query(LIST_SQL)
            .map(result -> {
                List<String> requests = new ArrayList<>();
                for (int row = 0; result != null && row < result.getRowCount(); row++)
                    requests.add(requestJson(result, row));
                return new EventErasureJson.ObjectWriter().raw("requests", EventErasureJson.array(requests)).toJson();
            })
            .recover(cause -> DpoErasure.failure(cause, "requests", caller.personId(), "The requests could not be read"));
    }

    // ===== The save: shape =========================================================================================

    /** {@code [{…}]}: one JSON object, as a string or as the object the bus decoded, checked by the class comment. */
    static ParsedSave parseSave(Object argument) {
        Object[] arguments = EventErasureArguments.array(argument);
        if (arguments.length != 1)
            return ParsedSave.refused("Send one request");
        ReadOnlyAstObject request = asObject(arguments[0]);
        if (request == null)
            return ParsedSave.refused("The request is not a JSON object");

        ReadOnlyAstArray keys = request.keys();
        for (int i = 0; i < keys.size(); i++) {
            Object key = keys.getElement(i);
            if (!FIELDS.contains(key))
                return ParsedSave.refused("Unknown field: " + (key instanceof String s && s.matches("[A-Za-z]{1,40}") ? s : "?"));
        }

        Object idValue = request.get("id");
        Integer id = idValue == null ? null : EventErasureArguments.positiveId(idValue);
        if (idValue != null && id == null)
            return ParsedSave.refused("id must be a positive integer or null");

        LocalDate receivedOn = date(request.get("receivedOn"));
        if (receivedOn == null)
            return ParsedSave.refused("receivedOn must be a date, YYYY-MM-DD");

        String status = request.get("status") instanceof String s && STATUSES.contains(s) ? s : null;
        if (status == null)
            return ParsedSave.refused("status must be open, on_hold, done or refused");

        Object holdValue = request.get("onHoldUntil");
        LocalDate onHoldUntil = date(holdValue);
        if (holdValue != null && onHoldUntil == null)
            return ParsedSave.refused("onHoldUntil must be a date, YYYY-MM-DD, or null");
        if ("on_hold".equals(status) != (onHoldUntil != null))
            return ParsedSave.refused("onHoldUntil is required exactly when the request is on hold");

        Object verifiedValue = request.get("verifiedBy");
        String verifiedBy = verifiedValue instanceof String s && VERIFIED_BY.contains(s) ? s : null;
        if (verifiedValue != null && verifiedBy == null)
            return ParsedSave.refused("verifiedBy must be email, id, other or null");

        Object kindValue = request.get("subjectKind"), subjectValue = request.get("subjectId");
        String subjectKind = kindValue instanceof String s && SUBJECT_KINDS.contains(s) ? s : null;
        Integer subjectId = subjectValue == null ? null : EventErasureArguments.positiveId(subjectValue);
        if (kindValue != null && subjectKind == null)
            return ParsedSave.refused("subjectKind must be person, booker or null");
        if (subjectValue != null && subjectId == null)
            return ParsedSave.refused("subjectId must be a positive integer or null");
        if ((subjectKind == null) != (subjectId == null))
            return ParsedSave.refused("subjectKind and subjectId go together: both or neither");

        Object runValue = request.get("runId");
        Integer runId = runValue == null ? null : EventErasureArguments.positiveId(runValue);
        if (runValue != null && runId == null)
            return ParsedSave.refused("runId must be a positive integer or null");
        if (runId != null && subjectId == null)
            return ParsedSave.refused("runId needs the subject it erased");

        Object outsideValue = request.get("outside");
        Outside outside = null;
        if (outsideValue != null) {
            outside = outside(outsideValue);
            if (outside == null)
                return ParsedSave.refused("outside must hold exactly " + String.join(", ", OUTSIDE_KEYS) + ", as booleans");
        }

        return new ParsedSave(new Save(id, receivedOn, status, onHoldUntil, verifiedBy, subjectKind, subjectId, runId,
            outside), null);
    }

    /** The JSON object a save carries: parsed from a string (never logged: the parser's message quotes it), or as decoded. */
    private static ReadOnlyAstObject asObject(Object value) {
        if (value instanceof String text) {
            if (text.length() > MAX_JSON_LENGTH)
                return null;
            try {
                Object parsed = AST.parseAny(text, "json");
                return AST.isObject(parsed) ? (ReadOnlyAstObject) parsed : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
        return AST.isObject(value) ? (ReadOnlyAstObject) value : null;
    }

    /** A strict {@code YYYY-MM-DD} that is a real day, or null. */
    static LocalDate date(Object value) {
        if (!(value instanceof String s) || !s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Outside outside(Object value) {
        if (!AST.isObject(value))
            return null;
        ReadOnlyAstObject object = (ReadOnlyAstObject) value;
        ReadOnlyAstArray keys = object.keys();
        if (keys.size() != OUTSIDE_KEYS.size())
            return null;
        for (int i = 0; i < keys.size(); i++)
            if (!OUTSIDE_KEYS.contains(keys.<Object>getElement(i)) || !(object.get(keys.getElement(i)) instanceof Boolean))
                return null;
        return new Outside(OUTSIDE_KEYS.stream().map(key -> Boolean.TRUE.equals(object.get(key))).toList());
    }

    // ===== The save: dates =========================================================================================

    /**
     * The date rules against {@code today} (Europe/London), or null when they pass. {@code storedReceivedOn} and
     * {@code storedOnHoldUntil} are what an update's request already holds (null for a create): a date it already
     * holds is never refused, so a request received more than a year ago, or held until a day now past, can still be
     * saved — closed, typically.
     */
    static String dateRefusal(Save save, LocalDate today, LocalDate storedReceivedOn, LocalDate storedOnHoldUntil) {
        if (!save.receivedOn().equals(storedReceivedOn)) {
            if (save.receivedOn().isAfter(today))
                return "receivedOn cannot be in the future";
            if (save.receivedOn().isBefore(today.minusYears(1)))
                return "receivedOn cannot be more than a year ago";
        }
        if (save.onHoldUntil() != null && !save.onHoldUntil().equals(storedOnHoldUntil) && !save.onHoldUntil().isAfter(today))
            return "onHoldUntil must be a future date";
        return null;
    }

    // ===== The save ================================================================================================

    static Future<String> save(Object argument, EventErasureAccess.Caller caller) {
        ParsedSave parsed = parseSave(argument);
        if (parsed.save() == null)
            return DpoErasure.refusal(REQUEST_KEY, parsed.refusal(), "save", caller.personId());
        Save save = parsed.save();
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        String operation = save.id() == null ? "save (new)" : "save of request " + save.id();
        Future<Integer> saved = runOfSubject(save).compose(runOk -> runOk ? write(save, today, caller)
            : Future.failedFuture("[%s] %s".formatted(REQUEST_KEY, "The run is not a succeeded erasure of this subject")));
        return saved
            .compose(id -> EventErasureRunner.query(ONE_SQL, id).compose(row -> {
                if (row == null || row.getRowCount() == 0)
                    return notFound(id);
                Console.log(DpoErasure.LOG_PREFIX + "request " + id + " saved by person " + caller.personId()
                            + ": " + save.status());
                return Future.succeededFuture(requestJson(row, 0));
            }))
            .recover(cause -> {
                if ("23514".equals(EventErasureRunner.sqlStateOf(cause))) // a CHECK of V0165: the rules above should have caught it
                    return DpoErasure.refusal(REQUEST_KEY, "The request breaks a rule of the register", operation, caller.personId());
                return DpoErasure.failure(cause, operation, caller.personId(), "The request could not be saved");
            });
    }

    /** True when the save names no run, or a succeeded commit run of its own subject. */
    private static Future<Boolean> runOfSubject(Save save) {
        if (save.runId() == null)
            return Future.succeededFuture(true);
        PersonAnonymisationRunner.Subject subject = "booker".equals(save.subjectKind())
            ? PersonAnonymisationRunner.Subject.booker(save.subjectId()) : PersonAnonymisationRunner.Subject.person(save.subjectId());
        return EventErasureRunner.query(RUN_OF_SUBJECT_SQL, save.runId(), subject.ledgerKey())
            .map(rows -> rows != null && rows.getRowCount() == 1);
    }

    /** Stamps checked_at on the request a complete, clean check was for; true when a row was stamped. */
    static Future<Boolean> stampChecked(int requestId) {
        return write(STAMP_CHECKED_SQL, requestId).map(id -> id != null);
    }

    /** The insert or the update; the id written, or a failed future carrying a refusal. */
    private static Future<Integer> write(Save save, LocalDate today, EventErasureAccess.Caller caller) {
        Future<Integer> saved;
        if (save.id() == null) {
            String refused = dateRefusal(save, today, null, null);
            if (refused != null)
                return Future.failedFuture("[%s] %s".formatted(REQUEST_KEY, refused));
            saved = write(INSERT_SQL, save.receivedOn(), save.status(), save.onHoldUntil(), save.verifiedBy(),
                save.subjectKind(), save.subjectId(), save.runId(),
                (save.outside() == null ? Outside.NONE : save.outside()).toJson(), caller.personId());
        } else
            saved = EventErasureRunner.query(STORED_SQL, save.id()).compose(stored -> {
                if (stored == null || stored.getRowCount() == 0)
                    return notFound(save.id());
                String refused = dateRefusal(save, today, date(stored.getValue(0, 0)), date(stored.getValue(0, 1)));
                if (refused != null)
                    return Future.failedFuture("[%s] %s".formatted(REQUEST_KEY, refused));
                return write(UPDATE_SQL, save.id(), save.receivedOn(), save.status(), save.onHoldUntil(), save.verifiedBy(),
                    save.subjectKind(), save.subjectId(), save.runId(),
                    save.outside() == null ? null : save.outside().toJson(), caller.personId())
                    .compose(id -> id == null ? notFound(save.id()) : Future.succeededFuture(id));
            });
        return saved;
    }

    /** The write, as the server, in autocommit; the id it returned, or null when no row was written (no such id). */
    private static Future<Integer> write(String sql, Object... parameters) {
        return EventErasureRunner.asServer(() -> SubmitService.executeSubmit(new SubmitArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(sql) // a constant; every value is bound
                .setParameters(parameters)
                .setReturnGeneratedKeys(true)
                .build()))
            .map(ErasureRequests::writtenId);
    }

    /** The id {@code returning} gave back — for a statement returning one column, its generated key — or null. */
    static Integer writtenId(SubmitResult result) {
        Object[] keys = result == null ? null : result.getGeneratedKeys();
        return keys == null || keys.length == 0 ? null : EventErasureArguments.positiveId(keys[0]);
    }

    private static <T> Future<T> notFound(Integer id) {
        return Future.failedFuture("[%s] No erasure request %s".formatted(NOT_FOUND_KEY, id));
    }

    // ===== The reply ===============================================================================================

    /** One request: REQUEST_COLUMNS' row as the contract's JSON object. */
    static String requestJson(QueryResult r, int row) {
        return new EventErasureJson.ObjectWriter()
            .number("id", DpoErasure.number(r.getValue(row, 0)))
            .string("receivedOn", DpoErasure.text(r.getValue(row, 1)))
            .string("dueOn", DpoErasure.text(r.getValue(row, 2)))
            .string("status", DpoErasure.text(r.getValue(row, 3)))
            .string("onHoldUntil", DpoErasure.text(r.getValue(row, 4)))
            .string("verifiedBy", DpoErasure.text(r.getValue(row, 5)))
            .string("subjectKind", DpoErasure.text(r.getValue(row, 6)))
            .number("subjectId", DpoErasure.number(r.getValue(row, 7)))
            .number("runId", DpoErasure.number(r.getValue(row, 8)))
            .string("checkedAt", DpoErasure.text(r.getValue(row, 9)))
            .raw("outside", Outside.stored(r.getValue(row, 10)).toJson())
            .string("createdAt", DpoErasure.text(r.getValue(row, 11)))
            .number("createdByPersonId", DpoErasure.number(r.getValue(row, 12)))
            .string("closedAt", DpoErasure.text(r.getValue(row, 13)))
            .number("lastRunId", DpoErasure.number(r.getValue(row, 14)))
            .string("erasedAt", DpoErasure.text(r.getValue(row, 15)))
            .toJson();
    }
}
