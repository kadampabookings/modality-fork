package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.vertx.VertxAsync;
import io.vertx.pgclient.PgConnection;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs {@code anonymise_person()} (V0153) for one customer, or {@code anonymise_booker()} (V0163) for somebody who
 * only ever booked without an account, from the back office: the GDPR right to erasure.
 *
 * <p>The functions remove everything that identifies the person and keep the financial skeleton (their headers say
 * what, and what stops them). They have the same conditions as {@code erase_past_event()}: an explicit REPEATABLE
 * READ transaction, the call a later statement than the statement of intent, and no commit of their own — so they
 * run here, on a dedicated {@link PgConnection} opened exactly as {@link EventErasureRunner#connect()} opens one.
 * Unlike an event's removal they touch one person's rows and take seconds, so the caller waits for the outcome
 * instead of polling.
 *
 * <h3>Who: a {@link Subject}</h3>
 *
 * A customer is a person id; a booker without an account is the booking staff started from (its document id). Each
 * has its own endpoints, statement of intent ({@code ANONYMISE PERSON <id>} / {@code ANONYMISE BOOKER <id>}) and
 * function; everything else — the ledger, the checks, the reply — is shared.
 *
 * <h3>Two calls</h3>
 *
 * <ol>
 *   <li><b>Preview</b> ({@code [personId, candidateDocumentIds?, memberIds?]}, or {@code [documentId,
 *       candidateDocumentIds?]}): the function runs with the operator's ticks, leniently (a ticked booking that is no
 *       longer a candidate is dropped), and is rolled back. The reply is its grid — the counts, the CANDIDATE
 *       bookings staff may tick, the account's members, the other customers bearing their name, the customers holding
 *       a booker's address, and the ticks it ran with — plus who those people are, or a refusal. The dialog re-runs
 *       it at every tick change.</li>
 *   <li><b>Commit</b> ({@code [personId, confirmPhrase, previewRunId, candidateDocumentIds, memberIds]}, or
 *       {@code [documentId, confirmPhrase, previewRunId, candidateDocumentIds]}): the phrase must be the subject's;
 *       the preview must be a succeeded preview of the SAME subject, finished less than {@link #PREVIEW_VALIDITY}
 *       ago, and the ticks EXACTLY those it ran with (the function checks again against the live data). For a booker
 *       the facts its preview showed must also still hold ({@link #describeBookerDrift}): whether the address is
 *       theirs, which bookings match, the candidates. Then the function runs, the booker runs whose seed is now
 *       anonymised are tidied ({@code anonymise_booker_ledger_tidy()}), the ledger row is finished inside the same
 *       transaction, and it commits — the data and its record land together or not at all.</li>
 * </ol>
 *
 * <p>Every run is a row of {@code person_erasure_run}, keyed by the person id — or minus the booking's id for a
 * booker, so V0153's own scrub of other runs reaches it (V0163) —: who ran it, the grid (counts, booking ids and
 * refs, event names — never personal data), and on failure a key and the database's message, whose wording carries
 * ids, refs and counts only. At most one run per subject at a time (a partial unique index); a row whose server died
 * is marked aborted by the next run, as for {@code event_erasure_run}.
 *
 * <p>Refusals come back as {@code [PersonAnonymise…Error] text}, the key mapped by the React side. The keys the
 * functions raise are passed through; the others are this class's.
 *
 * <p>A preview's reply also names who each candidate booking is for (name, email, phone, cancelled) — staff cannot
 * tell whether a booking is the same human without it — with the organisation the booking belongs to. That is read from the booking itself, by a super
 * administrator who could read it in Registrations anyway, and goes in the reply only: not in the ledger. The same
 * goes for the account's other members, the namesakes and a booker's address owners (name, email).
 *
 * <p>One log line per run: its id, the subject, the mode, the outcome, the caller's person id and the number of
 * rows changed. Never a value.
 *
 * @author Claude Code
 */
final class PersonAnonymisationRunner {

    private PersonAnonymisationRunner() {
    }

    // ===== Keys ====================================================================================================

    static final String NOT_FOUND_KEY = "PersonAnonymiseNotFoundError";
    static final String CONFIRM_MISMATCH_KEY = "PersonAnonymiseConfirmMismatchError";
    static final String PREVIEW_REQUIRED_KEY = "PersonAnonymisePreviewRequiredError";
    static final String PREVIEW_CHANGED_KEY = "PersonAnonymisePreviewChangedError";
    static final String CANDIDATE_KEY = "PersonAnonymiseCandidateError";
    static final String MEMBER_KEY = "PersonAnonymiseMemberError";
    static final String BUSY_KEY = "PersonAnonymiseBusyError";
    static final String SQL_KEY = "PersonAnonymiseSqlError";
    static final String CONFLICT_KEY = "PersonAnonymiseConflictError";

    /**
     * Another transaction changed or locked a row the run touches (serialization failure, deadlock, lock timeout):
     * the run rolled back having written nothing, and running it again works.
     */
    static final Set<String> CONFLICT_SQL_STATES = Set.of("40001", "40P01", "55P03");

    static final String MODE_PREVIEW = "preview";
    static final String MODE_COMMIT = "commit";

    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_REFUSED = "refused";

    private static final String LOG_PREFIX = "[person-anonymise] ";
    static final String APPLICATION_NAME_PREFIX = "kbs-anonymise-";
    /** How long a preview may vouch for a commit: long enough to read it, short enough that it still describes the data. */
    static final String PREVIEW_VALIDITY = "30 minutes";
    /** A key the function raised, at the start of the database's message. */
    private static final Pattern FUNCTION_KEY = Pattern.compile("^\\[(PersonAnonymise[A-Za-z]+Error)\\]");

    // ===== Who =====================================================================================================

    /** Who a run is for: a customer (their person id), or a booker without an account (the booking staff started from). */
    record Subject(Kind kind, int id) {

        enum Kind { PERSON, BOOKER }

        static Subject person(int personId) {
            return new Subject(Kind.PERSON, personId);
        }

        static Subject booker(int documentId) {
            return new Subject(Kind.BOOKER, documentId);
        }

        boolean isBooker() {
            return kind == Kind.BOOKER;
        }

        /** The ledger's person_id: the person, or minus the booking (V0163: V0153's scrub then reaches the row). */
        int ledgerKey() {
            return isBooker() ? -id : id;
        }

        /** The statement of intent the function demands, built from the validated id. */
        String confirmPhrase() {
            return (isBooker() ? "ANONYMISE BOOKER " : "ANONYMISE PERSON ") + id;
        }

        /** What the subject is, in the log and the refusals: "booking" (a booker is reached through one) or "person". */
        String kindName() {
            return isBooker() ? "booking" : "person";
        }

        /** How the log and the refusals name it: ids only. */
        String noun() {
            return kindName() + " " + id;
        }
    }

    // ===== SQL =====================================================================================================

    private static final String STALE_RUNS_SQL =
        "update person_erasure_run set status = 'aborted', finished_at = now(), error_key = 'PersonAnonymiseAbortedError',"
        + " candidate_document_ids = '{}', member_ids = '{}'"
        + " where status = 'running' and coalesce(app_name, '') not in"
        + " (select application_name from pg_stat_activity where application_name like '" + APPLICATION_NAME_PREFIX + "%')";

    /**
     * A preview's ticks serve the commit for {@link #PREVIEW_VALIDITY} only; kept longer they would only tie a
     * person to bookings and members of other people. Emptied at the start of every run.
     */
    private static final String EXPIRED_TICKS_SQL =
        "update person_erasure_run set candidate_document_ids = '{}', member_ids = '{}'"
        + " where mode = 'preview' and finished_at < now() - interval '" + PREVIEW_VALIDITY + "'"
        + " and (cardinality(candidate_document_ids) > 0 or cardinality(member_ids) > 0)";

    /** The preview's facts, and whether a commit of the same subject succeeded since (as EventErasureRunner asks). */
    private static final String PREVIEW_SQL =
        "select p.person_id, p.mode, p.status, p.finished_at > now() - interval '" + PREVIEW_VALIDITY + "',"
        + " exists (select 1 from person_erasure_run c where c.person_id = p.person_id and c.mode = 'commit'"
        + " and c.status = 'succeeded' and c.id > p.id),"
        + " array_to_string(p.candidate_document_ids, ','), array_to_string(p.member_ids, ',')"
        + " from person_erasure_run p where p.id = $1";

    /** The preview's grid as the ledger keeps it, as rows in the function's order: a booker commit's baseline. */
    private static final String BASELINE_SQL =
        "select g.e ->> 'step', g.e ->> 'metric', g.e ->> 'value'"
        + " from person_erasure_run p cross join lateral jsonb_array_elements(p.grid) with ordinality as g(e, ord)"
        + " where p.id = $1 and jsonb_typeof(p.grid) = 'array' order by g.ord";

    private static final String NEXT_RUN_ID_SQL = "select nextval(pg_get_serial_sequence('public.person_erasure_run', 'id'))";

    private static final String APPLICATION_NAME_SQL = "select set_config('application_name', $1, false)";

    private static final String INSERT_RUN_SQL =
        "insert into person_erasure_run (id, person_id, document_id, mode, status, preview_run_id, requested_by_person_id,"
        + " candidate_document_ids, member_ids, app_name) values ($1, $2, $3, $4, 'running', $5, $6, $7, $8, $9)";

    private static final String BEGIN_SQL = "BEGIN ISOLATION LEVEL REPEATABLE READ";

    /**
     * After the BEGIN, before the call (the functions refuse to be the statement that opened the transaction). All
     * local to the transaction. The audit person and note are what the audit triggers and the history markers record.
     */
    private static final String SETTINGS_SQL =
        "select set_config('TimeZone', 'Europe/London', true), set_config('statement_timeout', '2min', true),"
        + " set_config('lock_timeout', '30s', true), set_config('kbs.erase_confirm', $1, true),"
        + " set_config('kbs.audit_person_id', $2, true), set_config('kbs.audit_note', $3, true)";

    /** $4 = lenient: a preview drops ticked bookings that are no longer candidates; a commit refuses them. */
    private static final String ANONYMISE_PERSON_SQL = "select step, metric, value from anonymise_person($1, $2::int[], $3::int[], $4)";

    /** $3 = lenient, as above. A booker has no account, so no members. */
    private static final String ANONYMISE_BOOKER_SQL = "select step, metric, value from anonymise_booker($1, $2::int[], $3)";

    /**
     * The booker runs whose seed booking a commit anonymised — anonymise_person() takes such a booking as one of the
     * customer's and cannot know a run was started from it — lose their ticks, refusal texts and listed steps (V0163).
     */
    private static final String LEDGER_TIDY_SQL = "select anonymise_booker_ledger_tidy()";

    /**
     * Who the account's members, the other customers bearing one of their names, or the customers holding a booker's
     * address are — for the preview's reply only, as {@link #CANDIDATE_DETAILS_SQL}.
     */
    private static final String PERSON_DETAILS_SQL =
        "select q.id, q.first_name, q.last_name, q.email from person q where q.id = any($1::int[]) order by q.id";

    /**
     * Who each candidate booking is for, so staff can tell whether it is the same human: read after the preview's
     * ROLLBACK (the data as it stands), for the candidate ids the function itself listed. Sent in the preview's
     * reply only — never into the ledger, whose grid stays free of personal data, and never into the log.
     */
    private static final String CANDIDATE_DETAILS_SQL =
        "select d.id, d.person_first_name, d.person_last_name, d.person_email, d.person_phone, d.cancelled,"
        + " coalesce(eo.name, o.name)"
        + " from document d left join event e on e.id = d.event_id left join organization eo on eo.id = e.organization_id"
        + " left join organization o on o.id = d.organization_id"
        + " where d.id = any($1::int[]) order by d.id";

    /**
     * $6/$7: a preview's EFFECTIVE ticks (what it ran with, after dropping stale ones) — the very sets a commit must
     * send; null keeps what the row was created with.
     */
    private static final String FINISH_SQL =
        "update person_erasure_run set status = $2, grid = $3::text::jsonb, error_key = $4, error = $5,"
        + " candidate_document_ids = coalesce($6::int[], candidate_document_ids), member_ids = coalesce($7::int[], member_ids),"
        + " finished_at = clock_timestamp() where id = $1 and status = 'running'";

    /**
     * The grid steps the ledger never keeps: who the preview listed (candidate bookings, account members, namesakes,
     * a booker's address owners), its effective ticks, and the photo files of a booker's volunteering applications
     * (V0163: for staff to delete). Kept, they would tie the subject to bookings and people that still carry their
     * name once they are anonymised; the commit's check reads the ticks from the row's own arrays instead.
     */
    static final Set<String> UNSTORED_STEPS = Set.of("candidate", "member", "namesake", "addressOwner", "ticks", "photo");

    /**
     * What a booker's preview showed that its commit must find unchanged (V0163): whether the address is theirs,
     * shared or somebody else's; WHICH bookings are in scope and how, which are candidates and which applications go
     * (booker/scope_digest, over their ids); and the counts the operator read. A new booking or application under the
     * address between the two would otherwise make the commit do more, or less, than the operator saw — silently.
     */
    static final Set<String> BOOKER_FACTS = Set.of("booker/named", "booker/has_address", "booker/address",
        "booker/address_owners", "booker/scope_digest", "scope/guest_bookings_matched", "scope/candidates",
        "scope/address_candidates_unticked", "scope/other_applications_under_address", "scope/volunteering_applications",
        "scope/volunteering_photos_outside_db", "scope/as_carer", "scope/named_on_room_lines");

    // ===== Shapes ==================================================================================================

    private record Request(Subject subject, boolean commit, Integer previewRunId, Integer[] candidates, Integer[] members,
                           Integer callerId, List<EventErasureRunner.GridRow> baseline) {
        String mode() {
            return commit ? MODE_COMMIT : MODE_PREVIEW;
        }
    }

    /** A refusal this class decides inside the run's transaction: rolled back, then recorded as refused. */
    private static final class RunRefusal extends RuntimeException {
        final String key;

        RunRefusal(String key, String text) {
            super(text, null, false, false);
            this.key = key;
        }
    }

    private static final class Run {
        final int id;
        final Request request;
        final long startNanos = System.nanoTime();
        List<EventErasureRunner.GridRow> grid;
        /** The preview's candidate bookings, as JSON objects (personal data: reply only). */
        List<String> candidateDetails = List.of();
        /** The preview's account members, as JSON objects (personal data: reply only). */
        List<String> memberDetails = List.of();
        /** The preview's other customers bearing one of their names, as JSON objects (personal data: reply only). */
        List<String> namesakeDetails = List.of();
        /** The customers holding a booker's address, as JSON objects (personal data: reply only). */
        List<String> addressOwnerDetails = List.of();
        String status;
        String errorKey;
        String errorText;
        String sqlState;

        Run(int id, Request request) {
            this.id = id;
            this.request = request;
        }
    }

    // ===== Entry points ============================================================================================

    /**
     * {@code [personId, candidateDocumentIds?, memberIds?]} — a dry run with the operator's ticks (ticked bookings that
     * are no longer candidates are dropped); the reply is the run as JSON, its grid saying which ticks it ran with.
     */
    static Future<String> preview(Object[] arguments, EventErasureAccess.Caller caller) {
        Integer personId = EventErasureArguments.positiveId(EventErasureArguments.at(arguments, 0));
        if (personId == null)
            return refusal(NOT_FOUND_KEY, "No such person, or the request is malformed", "person ?", MODE_PREVIEW, caller.personId());
        return preview(Subject.person(personId), EventErasureArguments.at(arguments, 1), EventErasureArguments.at(arguments, 2), caller);
    }

    /** {@code [documentId, candidateDocumentIds?]} — as {@link #preview(Object[], EventErasureAccess.Caller)}, for a booker. */
    static Future<String> previewBooker(Object[] arguments, EventErasureAccess.Caller caller) {
        Integer documentId = EventErasureArguments.positiveId(EventErasureArguments.at(arguments, 0));
        if (documentId == null)
            return refusal(NOT_FOUND_KEY, "No such booking, or the request is malformed", "booking ?", MODE_PREVIEW, caller.personId());
        return preview(Subject.booker(documentId), EventErasureArguments.at(arguments, 1), null, caller);
    }

    /** {@code [personId, confirmPhrase, previewRunId, candidateDocumentIds, memberIds]} — the real anonymisation. */
    static Future<String> commit(Object[] arguments, EventErasureAccess.Caller caller) {
        Integer personId = EventErasureArguments.positiveId(EventErasureArguments.at(arguments, 0));
        if (personId == null)
            return refusal(NOT_FOUND_KEY, "No such person, or the request is malformed", "person ?", MODE_COMMIT, caller.personId());
        return commit(Subject.person(personId), EventErasureArguments.at(arguments, 1), EventErasureArguments.at(arguments, 2),
            EventErasureArguments.at(arguments, 3), EventErasureArguments.at(arguments, 4), caller);
    }

    /** {@code [documentId, confirmPhrase, previewRunId, candidateDocumentIds]} — the real anonymisation of a booker. */
    static Future<String> commitBooker(Object[] arguments, EventErasureAccess.Caller caller) {
        Integer documentId = EventErasureArguments.positiveId(EventErasureArguments.at(arguments, 0));
        if (documentId == null)
            return refusal(NOT_FOUND_KEY, "No such booking, or the request is malformed", "booking ?", MODE_COMMIT, caller.personId());
        return commit(Subject.booker(documentId), EventErasureArguments.at(arguments, 1), EventErasureArguments.at(arguments, 2),
            EventErasureArguments.at(arguments, 3), null, caller);
    }

    private static Future<String> preview(Subject subject, Object candidatesArgument, Object membersArgument,
                                          EventErasureAccess.Caller caller) {
        Integer[] candidates = candidateIds(candidatesArgument);
        if (candidates == null)
            return refusal(CANDIDATE_KEY, "The ticked bookings are malformed", subject.noun(), MODE_PREVIEW, caller.personId());
        Integer[] members = candidateIds(membersArgument);
        if (members == null)
            return refusal(MEMBER_KEY, "The ticked members are malformed", subject.noun(), MODE_PREVIEW, caller.personId());
        return run(new Request(subject, false, null, candidates, members, caller.personId(), null));
    }

    private static Future<String> commit(Subject subject, Object phraseArgument, Object previewRunArgument,
                                         Object candidatesArgument, Object membersArgument, EventErasureAccess.Caller caller) {
        if (!subject.confirmPhrase().equals(EventErasureArguments.string(phraseArgument)))
            return refusal(CONFIRM_MISMATCH_KEY, "The confirmation phrase does not match", subject.noun(), MODE_COMMIT, caller.personId());
        Integer previewRunId = EventErasureArguments.positiveId(previewRunArgument);
        if (previewRunId == null)
            return refusal(PREVIEW_REQUIRED_KEY, "A commit needs a recent preview of the same " + subject.kindName(),
                subject.noun(), MODE_COMMIT, caller.personId());
        Integer[] candidates = candidateIds(candidatesArgument);
        if (candidates == null)
            return refusal(CANDIDATE_KEY, "The ticked bookings are malformed", subject.noun(), MODE_COMMIT, caller.personId());
        Integer[] members = candidateIds(membersArgument);
        if (members == null)
            return refusal(MEMBER_KEY, "The ticked members are malformed", subject.noun(), MODE_COMMIT, caller.personId());
        return checkPreview(subject, previewRunId, candidates, members).compose(refused -> refused != null
            ? refusal(refused[0], refused[1], subject.noun(), MODE_COMMIT, caller.personId())
            : (subject.isBooker() ? loadBaseline(previewRunId) : Future.<List<EventErasureRunner.GridRow>>succeededFuture(null))
                .compose(baseline -> run(new Request(subject, true, previewRunId, candidates, members, caller.personId(), baseline))));
    }

    /** Ticked ids (bookings or members): an array (or nothing) of positive ids, de-duplicated; null when malformed. */
    static Integer[] candidateIds(Object argument) {
        if (argument == null)
            return new Integer[0];
        if (!(argument instanceof Object[] array))
            return null;
        Set<Integer> ids = new LinkedHashSet<>();
        for (Object element : array) {
            Integer id = EventErasureArguments.positiveId(element);
            if (id == null)
                return null;
            ids.add(id);
        }
        return ids.toArray(new Integer[0]);
    }

    /** Null when the preview vouches for this commit, else {key, text}. */
    private static Future<String[]> checkPreview(Subject subject, int previewRunId, Integer[] candidates, Integer[] members) {
        return EventErasureRunner.query(PREVIEW_SQL, previewRunId).compose(preview -> {
            boolean usable = preview != null && preview.getRowCount() == 1
                && preview.getValue(0, 0) instanceof Number n && n.intValue() == subject.ledgerKey()
                && MODE_PREVIEW.equals(preview.getValue(0, 1))
                && STATUS_SUCCEEDED.equals(preview.getValue(0, 2))
                && Boolean.TRUE.equals(preview.getValue(0, 3))   // finished less than PREVIEW_VALIDITY ago
                && Boolean.FALSE.equals(preview.getValue(0, 4)); // no successful commit of the subject since
            if (!usable)
                return Future.succeededFuture(new String[] { PREVIEW_REQUIRED_KEY, "Preview " + previewRunId
                    + " cannot vouch for this commit: it must be a succeeded preview of " + subject.noun()
                    + ", finished less than " + PREVIEW_VALIDITY + " ago, with no commit since" });
            // The commit does exactly what the preview previewed: the same ticks, no more, no fewer
            if (!sameIds(candidates, preview.getValue(0, 5)) || !sameIds(members, preview.getValue(0, 6)))
                return Future.succeededFuture(new String[] { PREVIEW_REQUIRED_KEY, "The ticks differ from those preview "
                    + previewRunId + " ran with: preview again with these ticks" });
            return Future.succeededFuture(null);
        });
    }

    /** The preview's stored grid, as rows (empty when it kept none). */
    private static Future<List<EventErasureRunner.GridRow>> loadBaseline(int previewRunId) {
        return EventErasureRunner.query(BASELINE_SQL, previewRunId).map(rows -> {
            List<EventErasureRunner.GridRow> baseline = new ArrayList<>();
            for (int i = 0; i < rows.getRowCount(); i++)
                baseline.add(new EventErasureRunner.GridRow((String) rows.getValue(i, 0), (String) rows.getValue(i, 1), (String) rows.getValue(i, 2)));
            return baseline;
        });
    }

    /** Whether the ids are, as a set, the comma-separated ids a preview's row holds (null or empty = none). */
    static boolean sameIds(Integer[] ids, Object stored) {
        Set<String> expected = new TreeSet<>();
        String text = stored == null ? "" : stored.toString();
        for (String id : text.split(","))
            if (!id.isBlank())
                expected.add(id.trim());
        Set<String> actual = new TreeSet<>();
        for (Integer id : ids)
            actual.add(id.toString());
        return expected.equals(actual);
    }

    /**
     * Where a booker commit's grid differs from its preview's on {@link #BOOKER_FACTS}, as {@code step/metric before
     * -> now} (counts and states — never a text the booking holds), or null when nothing does. A fact present on one
     * side only counts as a difference, so a preview without them can never vouch.
     */
    static String describeBookerDrift(List<EventErasureRunner.GridRow> preview, List<EventErasureRunner.GridRow> now) {
        Map<String, String> before = bookerFacts(preview), after = bookerFacts(now);
        if (before.isEmpty())
            return "the preview recorded none of the facts to compare with";
        List<String> differences = new ArrayList<>();
        for (String fact : new TreeSet<>(BOOKER_FACTS))
            if (!Objects.equals(before.get(fact), after.get(fact)))
                differences.add(fact + " " + before.get(fact) + " -> " + after.get(fact));
        return differences.isEmpty() ? null : String.join(", ", differences);
    }

    private static Map<String, String> bookerFacts(List<EventErasureRunner.GridRow> grid) {
        Map<String, String> facts = new LinkedHashMap<>();
        if (grid != null)
            for (EventErasureRunner.GridRow row : grid)
                if (BOOKER_FACTS.contains(row.step() + "/" + row.metric()))
                    facts.put(row.step() + "/" + row.metric(), row.value());
        return facts;
    }

    // ===== The run =================================================================================================

    private static Future<String> run(Request request) {
        io.vertx.core.Future<Run> done;
        try {
            done = EventErasureRunner.connect().compose(connection -> start(connection, request)
                .compose(run -> anonymise(connection, run))
                .eventually(connection::close));
        } catch (RuntimeException e) {
            done = io.vertx.core.Future.failedFuture(e);
        }
        return VertxAsync.toWebfxFuture(done).transform(result -> {
            if (result.succeeded()) {
                Run run = result.result();
                logRun(run);
                if (STATUS_SUCCEEDED.equals(run.status))
                    return Future.succeededFuture(replyJson(run));
                // A refusal the function (or this class) raised carries its own text (ids, refs, counts); anything
                // else, a generic one
                boolean refused = run.errorKey != null && !SQL_KEY.equals(run.errorKey);
                return Future.failedFuture("[%s] %s".formatted(refused ? run.errorKey : SQL_KEY,
                    refused ? run.errorText : "The anonymisation could not be completed"));
            }
            Throwable cause = result.cause();
            if (cause instanceof PgException pg && "23505".equals(pg.getSqlState())) // person_erasure_run_one_running_idx
                return refusal(BUSY_KEY, "Another anonymisation of this " + request.subject().kindName() + " is running",
                    request.subject().noun(), request.mode(), request.callerId());
            Console.log(LOG_PREFIX + "Could not run a " + request.mode() + " for " + request.subject().noun()
                        + " by person " + request.callerId() + ": " + EventErasureRunner.sqlStateOf(cause));
            return Future.failedFuture("[%s] The anonymisation could not be started".formatted(SQL_KEY));
        });
    }

    /** Autocommit: stale runs, the run's id, the connection's name (before the row — see EventErasureRunner), the row. */
    private static io.vertx.core.Future<Run> start(PgConnection connection, Request request) {
        connection.noticeHandler(notice -> { }); // the function's NOTICEs name tables, not data — but the log line is the record
        return connection.query(STALE_RUNS_SQL).execute()
            .compose(ignored -> connection.query(EXPIRED_TICKS_SQL).execute())
            .compose(ignored -> connection.query(NEXT_RUN_ID_SQL).execute())
            .compose(ids -> {
                int runId = Math.toIntExact(ids.iterator().next().getLong(0));
                String applicationName = APPLICATION_NAME_PREFIX + runId;
                Subject subject = request.subject();
                return connection.preparedQuery(APPLICATION_NAME_SQL).execute(Tuple.of(applicationName))
                    .compose(ignored -> connection.preparedQuery(INSERT_RUN_SQL).execute(Tuple.tuple()
                        .addValue(runId)
                        .addValue(subject.ledgerKey())
                        .addValue(subject.isBooker() ? subject.id() : null)
                        .addValue(request.mode())
                        .addValue(request.previewRunId())
                        .addValue(request.callerId())
                        // A preview records the ticks it RAN with, once it succeeded (finish); a commit, the ticks
                        // it was asked for — emptied by the function once it succeeds, by finish if it does not
                        .addArrayOfInteger(request.commit() ? request.candidates() : new Integer[0])
                        .addArrayOfInteger(request.commit() ? request.members() : new Integer[0])
                        .addValue(applicationName)))
                    .map(ignored -> new Run(runId, request));
            });
    }

    private static io.vertx.core.Future<Run> anonymise(PgConnection connection, Run run) {
        Request request = run.request;
        Subject subject = request.subject();
        String auditPerson = request.callerId() == null ? null : request.callerId().toString();
        Tuple call = subject.isBooker()
            ? Tuple.tuple().addValue(subject.id()).addArrayOfInteger(request.candidates()).addValue(!request.commit())
            : Tuple.tuple().addValue(subject.id()).addArrayOfInteger(request.candidates())
                .addArrayOfInteger(request.members()).addValue(!request.commit());
        return connection.query(BEGIN_SQL).execute()
            .compose(ignored -> connection.preparedQuery(SETTINGS_SQL).execute(Tuple.of(subject.confirmPhrase(),
                auditPerson, "gdpr-anonymise person=" + auditPerson)))
            .compose(ignored -> connection.preparedQuery(subject.isBooker() ? ANONYMISE_BOOKER_SQL : ANONYMISE_PERSON_SQL).execute(call))
            .compose(rows -> {
                run.grid = gridOf(rows);
                if (!request.commit())
                    return connection.query("ROLLBACK").execute()
                        .compose(ignored -> readCandidateDetails(connection, run))
                        .compose(ignored -> readPersonDetails(connection, run, "member"))
                        .map(details -> { run.memberDetails = details; return null; })
                        .compose(ignored -> readPersonDetails(connection, run, "namesake"))
                        .map(details -> { run.namesakeDetails = details; return null; })
                        .compose(ignored -> readPersonDetails(connection, run, "addressOwner"))
                        .map(details -> { run.addressOwnerDetails = details; return null; })
                        .compose(ignored -> finish(connection, run, STATUS_SUCCEEDED, null, null));
                // A booker commit must find what its preview showed, still inside the transaction
                if (subject.isBooker()) {
                    String drift = describeBookerDrift(request.baseline(), run.grid);
                    if (drift != null)
                        return io.vertx.core.Future.failedFuture(new RunRefusal(PREVIEW_CHANGED_KEY,
                            "The data changed since preview " + request.previewRunId() + " (" + drift + "): check again"));
                }
                // The record lands with the data: tidied and finished inside the transaction, then one COMMIT
                // (A row a later run judged dead is no longer 'running': committing would leave an erasure its own
                // record says did not happen. Fail instead; the recover rolls back.)
                return connection.query(LEDGER_TIDY_SQL).execute()
                    .compose(ignored -> finish(connection, run, STATUS_SUCCEEDED, null, null))
                    .compose(finished -> STATUS_SUCCEEDED.equals(finished.status)
                        ? connection.query("COMMIT").execute().map(ignored -> finished)
                        : io.vertx.core.Future.failedFuture("The run's ledger row is no longer running"));
            })
            .recover(cause -> failed(connection, run, cause));
    }

    /** Roll back, then record why: a key the function or this class raised is a refusal, anything else a failure. */
    private static io.vertx.core.Future<Run> failed(PgConnection connection, Run run, Throwable cause) {
        run.grid = null;
        String functionKey = null, functionText = null;
        if (cause instanceof RunRefusal refusal) {
            functionKey = refusal.key;
            functionText = refusal.getMessage();
        } else if (cause instanceof PgException pg && pg.getErrorMessage() != null) {
            Matcher matcher = FUNCTION_KEY.matcher(pg.getErrorMessage());
            if (matcher.find()) {
                functionKey = matcher.group(1);
                functionText = pg.getErrorMessage().substring(functionKey.length() + 2).trim();
            }
        }
        run.sqlState = EventErasureRunner.sqlStateOf(cause);
        boolean conflict = functionKey == null && CONFLICT_SQL_STATES.contains(run.sqlState);
        String error = EventErasureRunner.truncate(functionKey != null ? functionText
            : conflict ? "Another change to the same data got in first: nothing was written, try again"
            : EventErasureRunner.errorText(cause));
        String status = functionKey != null ? STATUS_REFUSED : STATUS_FAILED;
        String key = functionKey != null ? functionKey : conflict ? CONFLICT_KEY : SQL_KEY;
        // A booker's refusal names other bookings (ticked ids, refs) and customers: the reply carries it, the ledger
        // keeps the key only — no scrub could find every booking a ref stands for (V0163)
        String recorded = functionKey != null && run.request.subject().isBooker() ? null : error;
        return connection.query("ROLLBACK").execute()
            .transform(ignored -> finish(connection, run, status, key, recorded))
            .map(finished -> { finished.errorText = error; return finished; });
    }

    private static io.vertx.core.Future<Run> finish(PgConnection connection, Run run, String status, String errorKey, String error) {
        // Succeeded preview: the ticks it ran with. Succeeded commit: keep what the function left (emptied). Refused
        // or failed: none — they vouch for nothing, and may concern a person anonymised since.
        boolean succeeded = STATUS_SUCCEEDED.equals(status) && run.grid != null;
        Integer[] candidates = !succeeded ? new Integer[0] : run.request.commit() ? null : tickedIds(run.grid, "candidates");
        Integer[] members = !succeeded ? new Integer[0] : run.request.commit() ? null : tickedIds(run.grid, "members");
        return connection.preparedQuery(FINISH_SQL).execute(Tuple.tuple()
                .addValue(run.id)
                .addValue(status)
                .addValue(EventErasureRunner.gridJson(storedGrid(run.grid)))
                .addValue(errorKey)
                .addValue(error)
                .addArrayOfInteger(candidates)
                .addArrayOfInteger(members))
            .map(result -> {
                run.status = result.rowCount() == 1 ? status : status + ", unrecorded";
                run.errorKey = errorKey;
                run.errorText = error;
                return run;
            });
    }

    /** The grid as the ledger keeps it: without {@link #UNSTORED_STEPS}. */
    static List<EventErasureRunner.GridRow> storedGrid(List<EventErasureRunner.GridRow> grid) {
        if (grid == null)
            return null;
        List<EventErasureRunner.GridRow> stored = new ArrayList<>(grid.size());
        for (EventErasureRunner.GridRow row : grid)
            if (!UNSTORED_STEPS.contains(row.step()))
                stored.add(row);
        return stored;
    }

    /** The ids of the grid's {@code ticks/<metric>} row ("1003,1004"), as the function ran with them. */
    static Integer[] tickedIds(List<EventErasureRunner.GridRow> grid, String metric) {
        List<Integer> ids = new ArrayList<>();
        for (EventErasureRunner.GridRow row : grid)
            if ("ticks".equals(row.step()) && metric.equals(row.metric()) && row.value() != null)
                for (String id : row.value().split(","))
                    if (id.trim().matches("[0-9]{1,9}"))
                        ids.add(Integer.valueOf(id.trim()));
        return ids.toArray(new Integer[0]);
    }

    // ===== Reply and log ===========================================================================================

    private static List<EventErasureRunner.GridRow> gridOf(RowSet<Row> rows) {
        List<EventErasureRunner.GridRow> grid = new ArrayList<>(rows.size());
        for (Row row : rows)
            grid.add(new EventErasureRunner.GridRow(row.getString(0), row.getString(1), row.getString(2)));
        return grid;
    }

    /**
     * {"runId", "personId", "documentId", "mode", "grid": [{step, metric, value}], "candidateDetails", "memberDetails",
     * "namesakeDetails", "addressOwnerDetails"}: personId for a customer, documentId for a booker (the other null);
     * the grid holds counts, ids, refs and event names; the four details arrays (a preview's only, empty for a commit)
     * hold the names and contact details of the people it lists.
     */
    private static String replyJson(Run run) {
        Subject subject = run.request.subject();
        return new EventErasureJson.ObjectWriter()
            .number("runId", run.id)
            .number("personId", subject.isBooker() ? null : subject.id())
            .number("documentId", subject.isBooker() ? subject.id() : null)
            .string("mode", run.request.mode())
            .raw("grid", EventErasureRunner.gridJson(run.grid))
            .raw("candidateDetails", EventErasureJson.array(run.candidateDetails))
            .raw("memberDetails", EventErasureJson.array(run.memberDetails))
            .raw("namesakeDetails", EventErasureJson.array(run.namesakeDetails))
            .raw("addressOwnerDetails", EventErasureJson.array(run.addressOwnerDetails))
            .toJson();
    }

    /** Fills {@link Run#candidateDetails} for the candidates the grid lists (none: nothing is read). */
    private static io.vertx.core.Future<Void> readCandidateDetails(PgConnection connection, Run run) {
        List<Integer> ids = new ArrayList<>();
        for (EventErasureRunner.GridRow row : run.grid)
            if ("candidate".equals(row.step()) && row.metric() != null && row.metric().matches("[0-9]{1,9}"))
                ids.add(Integer.valueOf(row.metric()));
        if (ids.isEmpty())
            return io.vertx.core.Future.succeededFuture();
        return connection.preparedQuery(CANDIDATE_DETAILS_SQL).execute(Tuple.tuple().addArrayOfInteger(ids.toArray(new Integer[0])))
            .map(rows -> {
                List<String> details = new ArrayList<>(rows.size());
                for (Row row : rows)
                    details.add(new EventErasureJson.ObjectWriter()
                        .number("documentId", row.getInteger(0))
                        .string("firstName", row.getString(1))
                        .string("lastName", row.getString(2))
                        .string("email", row.getString(3))
                        .string("phone", row.getString(4))
                        .bool("cancelled", Boolean.TRUE.equals(row.getBoolean(5)))
                        .string("organizationName", row.getString(6))
                        .toJson());
                run.candidateDetails = details;
                return null;
            });
    }

    /**
     * Who the persons of one step of the grid are ({@code member}: the account's members; {@code namesake}: the other
     * customers bearing one of their names; {@code addressOwner}: the customers holding a booker's address) — none
     * listed, nothing is read.
     */
    private static io.vertx.core.Future<List<String>> readPersonDetails(PgConnection connection, Run run, String step) {
        List<Integer> ids = new ArrayList<>();
        for (EventErasureRunner.GridRow row : run.grid)
            if (step.equals(row.step()) && row.metric() != null && row.metric().matches("[0-9]{1,9}"))
                ids.add(Integer.valueOf(row.metric()));
        if (ids.isEmpty())
            return io.vertx.core.Future.succeededFuture(List.of());
        return connection.preparedQuery(PERSON_DETAILS_SQL).execute(Tuple.tuple().addArrayOfInteger(ids.toArray(new Integer[0])))
            .map(rows -> {
                List<String> details = new ArrayList<>(rows.size());
                for (Row row : rows)
                    details.add(new EventErasureJson.ObjectWriter()
                        .number("personId", row.getInteger(0))
                        .string("firstName", row.getString(1))
                        .string("lastName", row.getString(2))
                        .string("email", row.getString(3))
                        .toJson());
                return details;
            });
    }

    private static void logRun(Run run) {
        long millis = (System.nanoTime() - run.startNanos) / 1_000_000;
        StringBuilder line = new StringBuilder(LOG_PREFIX).append("Run ").append(run.id)
            .append(" (").append(run.request.mode()).append(") of ").append(run.request.subject().noun())
            .append(" by person ").append(run.request.callerId()).append(": ").append(run.status)
            .append(" in ").append(millis).append(" ms");
        if (run.errorKey != null)
            line.append(" (").append(run.errorKey).append(run.sqlState != null ? ", SQLSTATE " + run.sqlState : "").append(')');
        if (run.grid != null) {
            long changed = 0;
            for (EventErasureRunner.GridRow row : run.grid)
                if (Set.of("updated", "deleted", "inserted").contains(row.step()) && row.value() != null && row.value().matches("[0-9]+"))
                    changed += Long.parseLong(row.value());
            line.append(" — rows changed ").append(changed)
                .append(", candidates ").append(EventErasureRunner.valueOf(run.grid, "scope", "candidates"))
                .append(", ticked ").append(run.request.candidates().length)
                .append(", members ticked ").append(run.request.members().length);
        }
        Console.log(line.toString());
    }

    private static <T> Future<T> refusal(String key, String text, String subject, String mode, Integer callerId) {
        Console.log(LOG_PREFIX + "A " + mode + " for " + subject + " by person " + callerId + " refused: " + key);
        return Future.failedFuture("[%s] %s".formatted(key, text));
    }
}
