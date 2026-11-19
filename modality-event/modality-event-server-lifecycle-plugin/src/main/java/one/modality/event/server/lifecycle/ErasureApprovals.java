package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitResult;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Erasing a person everywhere once every organisation's Data Protection Officer agrees (V0176,
 * {@code person_erasure_approval}), for the four approval endpoints. A DPO erases what their organisations hold
 * ({@link PersonAnonymisationRunner}, scope {@code mine}); to erase the whole person, the records of every other
 * organisation included, each of those organisations' DPO approves first.
 *
 * <h3>A proposal is a preview run</h3>
 *
 * No request is stored: the proposal is an everywhere PREVIEW run that already sits in {@code person_erasure_run} — its
 * stored grid is what every later step is compared with, its ticks what they run with (kept while an approval of it
 * is open: {@link PersonAnonymisationRunner#EXPIRED_TICKS_SQL}). The table holds one row per organisation asked: ids,
 * dates and a state, no free text (a decline carries no reason).
 *
 * <ul>
 *   <li><b>Ask</b> {@code [proposalRunId]}: the caller's own succeeded everywhere preview, less than
 *       {@value PersonAnonymisationRunner#PREVIEW_VALIDITY} old and no commit of the subject since. One {@code pending}
 *       row per organisation of the subject's records (the run's {@code organisations/involved}) the caller does not
 *       cover (fresh), each expiring in {@value #VALIDITY}. Nothing to ask: {@code [ErasureApprovalNotNeededError]}.</li>
 *   <li><b>Decide</b> {@code [proposalRunId, "approve"|"decline"]}: every pending, unexpired row of an organisation the
 *       caller covers (fresh), all at once. To approve, the proposal is first previewed again — everywhere, its
 *       subject, its ticks, as the approver — and must show the same facts
 *       ({@link PersonAnonymisationRunner#describeProposalDrift}), else {@code [ErasureApprovalStaleError]}: what is
 *       approved is what the data says now.</li>
 *   <li><b>Withdraw</b> {@code [proposalRunId]}: the asker's own; pending rows become {@code withdrawn}.</li>
 *   <li><b>List</b> {@code []}: {@code waiting} — proposals with a pending row in an organisation the caller covers
 *       (cached grants), asked by somebody else; {@code mine} — the caller's own, not committed, not withdrawn, not
 *       all expired.</li>
 * </ul>
 * The erase-everywhere commit itself is the runner's ({@code proposalRunId}): every organisation of the records,
 * recomputed then, covered or approved and unexpired.
 *
 * <p><b>Known limit.</b> An approval is checked fresh when given; if that DPO's role is revoked before the commit, the
 * approval stands until it expires: the framework answers for the caller only, and asking about another person would
 * mean hand-written grant SQL.
 *
 * <p>The replies name the subject ({@code subjectName}) and the staff who asked and decided ({@link StaffNames}):
 * reply only. The log says ids and counts, never a name.
 *
 * @author Claude Code
 */
final class ErasureApprovals {

    private ErasureApprovals() {
    }

    static final String NOT_NEEDED_KEY = "ErasureApprovalNotNeededError";
    static final String STALE_KEY = "ErasureApprovalStaleError";
    static final String NOT_FOUND_KEY = "ErasureApprovalNotFoundError";
    static final String EXPIRED_KEY = "ErasureApprovalExpiredError";
    /** The proposal was withdrawn by its asker, or declined by an organisation: it can no longer be decided or committed. */
    static final String WITHDRAWN_KEY = "ErasureApprovalWithdrawnError";

    /** How long an approval is asked for, and holds once given (inside the one-month deadline of Art. 12(3)). */
    static final String VALIDITY = "14 days";

    static final String APPROVE = "approve";
    static final String DECLINE = "decline";

    static final String PENDING = "pending";
    static final String APPROVED = "approved";
    static final String DECLINED = "declined";
    static final String WITHDRAWN = "withdrawn";
    /** Not stored: how a pending or approved row past its expiry is shown. */
    static final String EXPIRED = "expired";

    private static final String UTC_STAMP = "'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'";

    // ===== SQL — constants; ids the server computed are inlined as an int[] literal (ErasureFind.intArray) ==========

    /** A proposal's run: whose, which mode and outcome, asked by whom, fresh, committed since, its ticks. */
    static final String RUN_SQL =
        "select r.person_id, r.document_id, r.mode, r.status, r.requested_by_person_id,"
        + " coalesce(r.finished_at > now() - interval '" + PersonAnonymisationRunner.PREVIEW_VALIDITY + "', false),"
        + " exists (select 1 from person_erasure_run c where c.person_id = r.person_id and c.mode = 'commit'"
        + " and c.status = 'succeeded' and c.id > r.id),"
        + " array_to_string(r.candidate_document_ids, ','), array_to_string(r.member_ids, ',')"
        + " from person_erasure_run r where r.id = $1::int";

    private static final String ROW_COLUMNS =
        "a.run_id, a.organization_id, o.name, a.state, a.asked_by_person_id,"
        + " to_char(a.asked_at at time zone 'UTC', " + UTC_STAMP + "), to_char(a.expires_at at time zone 'UTC', " + UTC_STAMP + "),"
        + " a.decided_by_person_id, to_char(a.decided_at at time zone 'UTC', " + UTC_STAMP + "), a.expires_at <= now(),"
        + " r.person_id, r.document_id,"
        + " exists (select 1 from person_erasure_run c where c.person_id = r.person_id and c.mode = 'commit'"
        + " and c.status = 'succeeded' and c.id > r.id),"
        + " array_to_string(r.candidate_document_ids, ','), array_to_string(r.member_ids, ',')"
        + " from person_erasure_approval a join person_erasure_run r on r.id = a.run_id"
        + " left join organization o on o.id = a.organization_id";

    /** One proposal's rows. */
    static final String ROWS_SQL = "select " + ROW_COLUMNS + " where a.run_id = $1::int order by a.organization_id";

    /**
     * How long an expired request stays in its asker's list, shown "Expired", so they learn it lapsed rather than see it
     * vanish (live test, 19 Nov 2026). It can no longer be approved or used: only seen, then withdrawn or left to go.
     */
    static final String EXPIRED_SHOWN_FOR = "7 days";

    /**
     * Every proposal with a row not expired, or expired for less than {@link #EXPIRED_SHOWN_FOR} — whatever its state:
     * the list's raw material, bounded by the expiry.
     */
    static final String OPEN_ROWS_SQL = "select " + ROW_COLUMNS
        + " where a.run_id in (select x.run_id from person_erasure_approval x where x.expires_at > now() - interval '"
        + EXPIRED_SHOWN_FOR + "'"
        // Only the proposals that concern the caller, BEFORE the limit: asked of an organisation they hold, or theirs
        + " and (x.organization_id = any(" + ErasureFind.COVERED + ") or x.asked_by_person_id = {{caller}}))"
        + " order by a.run_id desc, a.organization_id limit 2000";

    /** {@link #OPEN_ROWS_SQL} for this caller: their organisations and person id written in (integers). */
    static String openRowsSql(Collection<Integer> held, int callerId) {
        return ErasureFind.withCovered(OPEN_ROWS_SQL, held).replace("{{caller}}", Integer.toString(callerId));
    }

    /** $1 the proposal, $2 the asker; the organisations inlined. Asking again adds only the organisations not asked yet. */
    static final String ASK_SQL =
        "insert into person_erasure_approval (run_id, organization_id, state, asked_by_person_id, expires_at)"
        + " select $1::int, o, '" + PENDING + "', $2::int, now() + interval '" + VALIDITY + "'"
        + " from unnest(" + ErasureFind.COVERED + ") o on conflict (run_id, organization_id) do nothing";

    /** $1 the proposal, $2 the decision's state, $3 the decider; the caller's organisations inlined. */
    static final String DECIDE_SQL =
        "update person_erasure_approval set state = $2::text, decided_by_person_id = $3::int, decided_at = now()"
        + " where run_id = $1::int and organization_id = any(" + ErasureFind.COVERED + ")"
        + " and state = '" + PENDING + "' and expires_at > now()";

    /**
     * $1 the proposal, $2 the asker: every row not final yet — pending AND approved — withdrawn, so that no approval
     * given to a withdrawn proposal can be used afterwards.
     */
    static final String WITHDRAW_SQL =
        "update person_erasure_approval set state = '" + WITHDRAWN + "', decided_by_person_id = $2::int, decided_at = now()"
        + " where run_id = $1::int and asked_by_person_id = $2::int and state in ('" + PENDING + "', '" + APPROVED + "')";

    /** A customer's name, or a booker's (the seed booking's), for the list: reply only. Ids inlined. */
    static final String PERSON_NAMES_SQL =
        "select q.id, trim(concat_ws(' ', q.first_name, q.last_name)) from person q where q.id = any(" + ErasureFind.COVERED + ")";
    static final String BOOKER_NAMES_SQL =
        "select d.id, trim(concat_ws(' ', d.person_first_name, d.person_last_name)) from document d where d.id = any("
        + ErasureFind.COVERED + ")";

    // ===== Shapes ==================================================================================================

    /** One row of person_erasure_approval, as read (with its proposal's subject and whether it was committed since). */
    record Row(int runId, Integer organizationId, String organizationName, String state, Integer askedBy, String askedAt,
               String expiresAt, Integer decidedBy, String decidedAt, boolean expired,
               PersonAnonymisationRunner.Subject subject, boolean committed, String candidates, String members) {

        /** A row with no ticks (the checks' rows). */
        Row(int runId, Integer organizationId, String organizationName, String state, Integer askedBy, String askedAt,
            String expiresAt, Integer decidedBy, String decidedAt, boolean expired,
            PersonAnonymisationRunner.Subject subject, boolean committed) {
            this(runId, organizationId, organizationName, state, askedBy, askedAt, expiresAt, decidedBy, decidedAt, expired,
                subject, committed, null, null);
        }

        /** The state shown: a pending or approved row past its expiry is {@link #EXPIRED}. */
        String shownState() {
            return expired && (PENDING.equals(state) || APPROVED.equals(state)) ? EXPIRED : state;
        }

        boolean openPending() {
            return PENDING.equals(state) && !expired;
        }
    }

    static List<Row> rows(QueryResult r) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; r != null && i < r.getRowCount(); i++) {
            Integer personId = EventErasureRunner.asInteger(r.getValue(i, 10));
            Integer documentId = EventErasureRunner.asInteger(r.getValue(i, 11));
            PersonAnonymisationRunner.Subject subject = documentId != null ? PersonAnonymisationRunner.Subject.booker(documentId)
                : personId != null && personId > 0 ? PersonAnonymisationRunner.Subject.person(personId) : null;
            rows.add(new Row(EventErasureRunner.asInteger(r.getValue(i, 0)), EventErasureRunner.asInteger(r.getValue(i, 1)),
                DpoErasure.text(r.getValue(i, 2)), DpoErasure.text(r.getValue(i, 3)), EventErasureRunner.asInteger(r.getValue(i, 4)),
                DpoErasure.text(r.getValue(i, 5)), DpoErasure.text(r.getValue(i, 6)), EventErasureRunner.asInteger(r.getValue(i, 7)),
                DpoErasure.text(r.getValue(i, 8)), DpoErasure.bool(r.getValue(i, 9)), subject, DpoErasure.bool(r.getValue(i, 12)),
                DpoErasure.text(r.getValue(i, 13)), DpoErasure.text(r.getValue(i, 14))));
        }
        return rows;
    }

    // ===== The rules (pure, for the check) ==========================================================================

    /**
     * The organisations to ask: every organisation the run reaches, keeps or touches ({@code involved}) the caller does
     * not cover. Null when the caller covers none of the organisations of the subject's OWN records ({@code own}: they
     * may not ask about somebody none of whose records are theirs).
     */
    static Set<Integer> organisationsToAsk(Set<Integer> involved, Set<Integer> own, Set<Integer> covered) {
        if (!own.isEmpty() && own.stream().noneMatch(covered::contains))
            return null;
        Set<Integer> toAsk = new TreeSet<>(involved);
        toAsk.removeAll(covered);
        return toAsk;
    }

    /** The rows a decision writes: pending, not expired, of an organisation the caller covers (fresh). */
    static Set<Integer> decidable(List<Row> rows, Set<Integer> covered) {
        Set<Integer> organisations = new TreeSet<>();
        for (Row row : rows)
            if (row.openPending() && row.organizationId() != null && covered.contains(row.organizationId()))
                organisations.add(row.organizationId());
        return organisations;
    }

    /** Whether every row is approved and unexpired: the commit may erase everywhere (with any organisation it covers). */
    static boolean complete(List<Row> rows) {
        return !rows.isEmpty() && rows.stream().allMatch(row -> APPROVED.equals(row.shownState()));
    }

    /** A proposal for the caller's "waiting" list: a pending row in an organisation they cover, asked by somebody else. */
    static boolean waitingFor(List<Row> proposal, Integer callerId, Set<Integer> held) {
        if (proposal.isEmpty() || finished(proposal) || Objects.equals(proposal.get(0).askedBy(), callerId))
            return false;
        return proposal.stream().anyMatch(row -> row.openPending() && row.organizationId() != null && held.contains(row.organizationId()));
    }

    /** A proposal for the caller's "mine" list: theirs, not committed, not withdrawn (expired ones shown as such). */
    static boolean mine(List<Row> proposal, Integer callerId) {
        // Expired ones too (the list query keeps them EXPIRED_SHOWN_FOR): the asker sees them lapse, marked expired
        return !proposal.isEmpty() && Objects.equals(proposal.get(0).askedBy(), callerId) && !finished(proposal);
    }

    /** Withdrawn by its asker, or declined by an organisation: no decision or commit can use it any more. */
    static boolean ended(List<Row> proposal) {
        return proposal.stream().anyMatch(row -> WITHDRAWN.equals(row.state()) || DECLINED.equals(row.state()));
    }

    /** Committed since, or withdrawn by its asker. */
    static boolean finished(List<Row> proposal) {
        return proposal.stream().anyMatch(row -> row.committed() || WITHDRAWN.equals(row.state()));
    }

    // ===== The replies =============================================================================================

    /**
     * {@code {"proposalRunId", "complete", "organizations": [{id, name, state, decidedByPersonId, decidedByName,
     * decidedAt}]}}.
     */
    static String stateJson(int proposalRunId, List<Row> rows, Map<Long, String> staff) {
        List<String> organizations = new ArrayList<>();
        for (Row row : rows)
            organizations.add(new EventErasureJson.ObjectWriter()
                .number("id", row.organizationId())
                .string("name", row.organizationName())
                .string("state", row.shownState())
                .number("decidedByPersonId", row.decidedBy())
                .string("decidedByName", row.decidedBy() == null ? null : staff.get(row.decidedBy().longValue()))
                .string("decidedAt", row.decidedAt())
                .toJson());
        return new EventErasureJson.ObjectWriter()
            .number("proposalRunId", proposalRunId)
            .bool("complete", complete(rows))
            .raw("organizations", EventErasureJson.array(organizations))
            .toJson();
    }

    /**
     * {@code {"proposalRunId", "subject": {kind, id}, "subjectName", "askedByPersonId", "askedByName", "askedAt",
     * "expiresAt", "approvals": state}} — the first asking's moment and expiry.
     */
    static String proposalJson(List<Row> rows, Map<Long, String> staff, String subjectName) {
        Row first = rows.get(0);
        String askedAt = null, expiresAt = null;
        for (Row row : rows) {
            if (row.askedAt() != null && (askedAt == null || row.askedAt().compareTo(askedAt) < 0))
                askedAt = row.askedAt();
            if (row.expiresAt() != null && (expiresAt == null || row.expiresAt().compareTo(expiresAt) < 0))
                expiresAt = row.expiresAt();
        }
        return new EventErasureJson.ObjectWriter()
            .number("proposalRunId", first.runId())
            .raw("subject", first.subject() == null ? null : PersonAnonymisationRunner.subjectJson(first.subject()))
            .string("subjectName", subjectName)
            .number("askedByPersonId", first.askedBy())
            .string("askedByName", first.askedBy() == null ? null : staff.get(first.askedBy().longValue()))
            .string("askedAt", askedAt)
            .string("expiresAt", expiresAt)
            // The proposal's ticks (ids only): the asker after a reload, and the approver, re-run the proposal's preview
            // with exactly these, or the server's comparison refuses it as changed
            .raw("candidates", idArrayJson(first.candidates()))
            .raw("members", idArrayJson(first.members()))
            .raw("approvals", stateJson(first.runId(), rows, staff))
            .toJson();
    }

    /** {@code [1,2]} from a comma-separated id list ({@code array_to_string}); {@code []} for none. */
    static String idArrayJson(String ids) {
        Set<Integer> parsed = ids == null || ids.isBlank() ? null : PersonAnonymisationRunner.idSet(ids);
        StringBuilder json = new StringBuilder("[");
        for (Integer id : parsed == null ? Set.<Integer>of() : new TreeSet<>(parsed))
            json.append(json.length() == 1 ? "" : ",").append(id);
        return json.append(']').toString();
    }

    // ===== Ask =====================================================================================================

    static Future<String> ask(Object[] arguments, EventErasureAccess.Caller caller) {
        Integer proposalRunId = EventErasureArguments.positiveId(EventErasureArguments.at(arguments, 0));
        String operation = "approval ask of run " + proposalRunId;
        if (proposalRunId == null)
            return DpoErasure.refusal(NOT_FOUND_KEY, "No such proposal", operation, caller.personId());
        return EventErasureRunner.query(RUN_SQL, proposalRunId).compose(run -> {
                if (run == null || run.getRowCount() != 1
                    || !Objects.equals(EventErasureRunner.asInteger(run.getValue(0, 4)), caller.personId())
                    || !PersonAnonymisationRunner.MODE_PREVIEW.equals(run.getValue(0, 2)) || !"succeeded".equals(run.getValue(0, 3)))
                    return Future.<String>failedFuture(framed(NOT_FOUND_KEY, "Run " + proposalRunId + " is not a preview of yours"));
                if (!DpoErasure.bool(run.getValue(0, 5)) || DpoErasure.bool(run.getValue(0, 6)))
                    return Future.<String>failedFuture(framed(STALE_KEY, "Preview " + proposalRunId + " is more than "
                        + PersonAnonymisationRunner.PREVIEW_VALIDITY + " old, or the person was erased since: preview again"));
                return PersonAnonymisationRunner.loadBaseline(proposalRunId).compose(grid -> {
                    String scope = EventErasureRunner.valueOf(grid, "scope", "organisations");
                    Set<Integer> involved = PersonAnonymisationRunner.idSet(
                        EventErasureRunner.valueOf(grid, PersonAnonymisationRunner.ORGANISATIONS_STEP, "involved"));
                    Set<Integer> own = PersonAnonymisationRunner.idSet(
                        EventErasureRunner.valueOf(grid, PersonAnonymisationRunner.ORGANISATIONS_STEP, "own"));
                    if (scope == null || !PersonAnonymisationRunner.ALL_ORGANISATIONS.equals(scope.trim()) || involved == null || own == null)
                        return Future.<String>failedFuture(framed(NOT_FOUND_KEY, "Run " + proposalRunId
                            + " is not a preview of erasing everywhere"));
                    Set<Integer> asked = new TreeSet<>(involved);
                    asked.addAll(own);
                    return EventErasureAccess.organizationsHeldFresh(caller, asked).compose(covered -> {
                        Set<Integer> toAsk = organisationsToAsk(involved, own, covered);
                        if (toAsk == null)
                            return Future.<String>failedFuture(EventErasureAccess.NOT_YOUR_ORGANIZATION_REFUSAL);
                        if (toAsk.isEmpty())
                            return Future.<String>failedFuture(framed(NOT_NEEDED_KEY,
                                "Every organisation of these records is yours: erase without asking"));
                        return submit(ErasureFind.withCovered(ASK_SQL, toAsk), proposalRunId, caller.personId())
                            .compose(written -> {
                                Console.log(DpoErasure.LOG_PREFIX + operation + " by person " + caller.personId()
                                            + ": organisations " + PersonAnonymisationRunner.joined(toAsk) + " asked, "
                                            + written + " new");
                                return stateReply(proposalRunId);
                            });
                    });
                });
            })
            .recover(cause -> DpoErasure.failure(cause, operation, caller.personId(), "The approval could not be asked for"));
    }

    // ===== Decide ==================================================================================================

    static Future<String> decide(Object[] arguments, EventErasureAccess.Caller caller) {
        Integer proposalRunId = EventErasureArguments.positiveId(EventErasureArguments.at(arguments, 0));
        String decision = EventErasureArguments.string(EventErasureArguments.at(arguments, 1));
        String operation = "approval decision on run " + proposalRunId;
        if (proposalRunId == null || !(APPROVE.equals(decision) || DECLINE.equals(decision)))
            return DpoErasure.refusal(NOT_FOUND_KEY, "No such proposal, or the decision is neither approve nor decline",
                operation, caller.personId());
        return EventErasureRunner.query(ROWS_SQL, proposalRunId).compose(result -> {
                List<Row> rows = rows(result);
                // The asker never decides their own proposal, even for an organisation they came to hold since
                if (rows.isEmpty() || rows.stream().anyMatch(row -> Objects.equals(row.askedBy(), caller.personId())))
                    return Future.<String>failedFuture(framed(NOT_FOUND_KEY, "No such proposal"));
                if (ended(rows))
                    return Future.<String>failedFuture(framed(WITHDRAWN_KEY, "The proposal was withdrawn or declined"));
                Set<Integer> asked = new TreeSet<>();
                rows.forEach(row -> { if (row.organizationId() != null) asked.add(row.organizationId()); });
                return EventErasureAccess.organizationsHeldFresh(caller, asked).compose(covered -> {
                    if (covered.isEmpty())
                        return Future.<String>failedFuture(framed(NOT_FOUND_KEY, "No such proposal for your organisations"));
                    Set<Integer> decidable = decidable(rows, covered);
                    if (decidable.isEmpty()) {
                        boolean expired = rows.stream().anyMatch(row -> PENDING.equals(row.state()) && row.expired()
                            && row.organizationId() != null && covered.contains(row.organizationId()));
                        if (expired)
                            return Future.<String>failedFuture(framed(EXPIRED_KEY, "The approval asked of your organisations has expired"));
                        return stateReply(proposalRunId); // already decided: the state as it stands
                    }
                    if (rows.get(0).committed())
                        return Future.<String>failedFuture(framed(STALE_KEY, "The person was erased since"));
                    Future<Void> checked = APPROVE.equals(decision) ? stillTheSame(proposalRunId, caller) : Future.succeededFuture();
                    return checked
                        .compose(ignored -> submit(ErasureFind.withCovered(DECIDE_SQL, decidable), proposalRunId,
                            APPROVE.equals(decision) ? APPROVED : DECLINED, caller.personId()))
                        .compose(written -> {
                            Console.log(DpoErasure.LOG_PREFIX + operation + " by person " + caller.personId() + ": " + decision
                                        + " for organisations " + PersonAnonymisationRunner.joined(decidable) + " (" + written + " rows)");
                            return stateReply(proposalRunId);
                        });
                });
            })
            .recover(cause -> DpoErasure.failure(cause, operation, caller.personId(), "The decision could not be recorded"));
    }

    /**
     * The proposal previewed again — everywhere, its subject, its ticks, as the approver — must show the facts it
     * showed; else {@code [ErasureApprovalStaleError]}, whatever made the new preview fail or differ (a booking added,
     * a tick gone stale, the person erased or refused since).
     */
    private static Future<Void> stillTheSame(int proposalRunId, EventErasureAccess.Caller caller) {
        return EventErasureRunner.query(RUN_SQL, proposalRunId).compose(run -> {
            if (run == null || run.getRowCount() != 1)
                return Future.failedFuture(framed(NOT_FOUND_KEY, "No such proposal"));
            Integer personId = EventErasureRunner.asInteger(run.getValue(0, 0));
            Integer documentId = EventErasureRunner.asInteger(run.getValue(0, 1));
            PersonAnonymisationRunner.Subject subject = documentId != null ? PersonAnonymisationRunner.Subject.booker(documentId)
                : personId != null && personId > 0 ? PersonAnonymisationRunner.Subject.person(personId) : null;
            Integer[] candidates = PersonAnonymisationRunner.idSet(DpoErasure.text(run.getValue(0, 7))) == null ? null
                : PersonAnonymisationRunner.idSet(DpoErasure.text(run.getValue(0, 7))).toArray(new Integer[0]);
            Integer[] members = PersonAnonymisationRunner.idSet(DpoErasure.text(run.getValue(0, 8))) == null ? null
                : PersonAnonymisationRunner.idSet(DpoErasure.text(run.getValue(0, 8))).toArray(new Integer[0]);
            if (subject == null || candidates == null || members == null)
                return Future.failedFuture(framed(NOT_FOUND_KEY, "No such proposal"));
            return PersonAnonymisationRunner.loadBaseline(proposalRunId).compose(proposal ->
                PersonAnonymisationRunner.previewRun(subject, candidates, members, PersonAnonymisationRunner.SCOPE_EVERYWHERE, caller)
                    .transform(result -> {
                        if (result.failed()) {
                            String why = result.cause() == null ? null : result.cause().getMessage();
                            String key = why != null && why.startsWith("[") && why.indexOf(']') > 0 ? why.substring(1, why.indexOf(']')) : "?";
                            // Another run of the same subject at that moment says nothing about the data: try again
                            if (PersonAnonymisationRunner.BUSY_KEY.equals(key))
                                return Future.failedFuture(why);
                            return Future.failedFuture(framed(STALE_KEY, "Previewing proposal " + proposalRunId
                                + " again was refused (" + key + "): it must be asked again"));
                        }
                        String drift = PersonAnonymisationRunner.describeProposalDrift(proposal, result.result().storedGrid());
                        return drift == null ? Future.<Void>succeededFuture()
                            : Future.<Void>failedFuture(framed(STALE_KEY, "The data changed since proposal " + proposalRunId
                                + " (" + drift + "): it must be asked again"));
                    }));
        });
    }

    // ===== Withdraw ================================================================================================

    static Future<String> withdraw(Object[] arguments, EventErasureAccess.Caller caller) {
        Integer proposalRunId = EventErasureArguments.positiveId(EventErasureArguments.at(arguments, 0));
        String operation = "approval withdrawal of run " + proposalRunId;
        if (proposalRunId == null)
            return DpoErasure.refusal(NOT_FOUND_KEY, "No such proposal", operation, caller.personId());
        return EventErasureRunner.query(ROWS_SQL, proposalRunId).compose(result -> {
                List<Row> rows = rows(result);
                if (rows.isEmpty() || rows.stream().noneMatch(row -> Objects.equals(row.askedBy(), caller.personId())))
                    return Future.<String>failedFuture(framed(NOT_FOUND_KEY, "No such proposal of yours"));
                return submit(WITHDRAW_SQL, proposalRunId, caller.personId()).compose(written -> {
                    Console.log(DpoErasure.LOG_PREFIX + operation + " by person " + caller.personId() + ": " + written + " rows");
                    return stateReply(proposalRunId);
                });
            })
            .recover(cause -> DpoErasure.failure(cause, operation, caller.personId(), "The proposal could not be withdrawn"));
    }

    // ===== List ====================================================================================================

    static Future<String> list(EventErasureAccess.Caller caller) {
        return EventErasureAccess.allOrganizationsHeld(caller).compose(held -> EventErasureRunner.query(openRowsSql(held, caller.personId()))
                .compose(result -> {
                    Map<Integer, List<Row>> proposals = new LinkedHashMap<>();
                    for (Row row : rows(result))
                        proposals.computeIfAbsent(row.runId(), k -> new ArrayList<>()).add(row);
                    List<List<Row>> waiting = new ArrayList<>(), mine = new ArrayList<>();
                    for (List<Row> proposal : proposals.values())
                        if (mine(proposal, caller.personId()))
                            mine.add(proposal);
                        else if (waitingFor(proposal, caller.personId(), held))
                            waiting.add(proposal);
                    List<List<Row>> shown = new ArrayList<>(waiting);
                    shown.addAll(mine);
                    return names(shown).map(names -> {
                        Console.log(DpoErasure.LOG_PREFIX + "approvals by person " + caller.personId() + ": " + waiting.size()
                                    + " waiting, " + mine.size() + " of theirs");
                        return new EventErasureJson.ObjectWriter()
                            .raw("waiting", EventErasureJson.array(proposalsJson(waiting, names)))
                            .raw("mine", EventErasureJson.array(proposalsJson(mine, names)))
                            .toJson();
                    });
                }))
            .recover(cause -> DpoErasure.failure(cause, "approvals", caller.personId(), "The approvals could not be read"));
    }

    /** The staff names and the subjects' names the shown proposals need. */
    private record Names(Map<Long, String> staff, Map<Integer, String> persons, Map<Integer, String> bookers) {
    }

    private static Future<Names> names(List<List<Row>> proposals) {
        Set<Integer> staffIds = new TreeSet<>(), personIds = new TreeSet<>(), documentIds = new TreeSet<>();
        for (List<Row> proposal : proposals)
            for (Row row : proposal) {
                if (row.askedBy() != null) staffIds.add(row.askedBy());
                if (row.decidedBy() != null) staffIds.add(row.decidedBy());
                if (row.subject() != null)
                    (row.subject().isBooker() ? documentIds : personIds).add(row.subject().id());
            }
        return StaffNames.lookupMap(staffIds).compose(staff ->
            idNames(PERSON_NAMES_SQL, personIds).compose(persons ->
                idNames(BOOKER_NAMES_SQL, documentIds).map(bookers -> new Names(staff, persons, bookers))));
    }

    private static Future<Map<Integer, String>> idNames(String sql, Collection<Integer> ids) {
        if (ids.isEmpty())
            return Future.succeededFuture(Map.of());
        return EventErasureRunner.query(ErasureFind.withCovered(sql, ids)).map(r -> {
            Map<Integer, String> names = new java.util.HashMap<>();
            for (int i = 0; r != null && i < r.getRowCount(); i++)
                if (EventErasureRunner.asInteger(r.getValue(i, 0)) != null)
                    names.put(EventErasureRunner.asInteger(r.getValue(i, 0)), DpoErasure.text(r.getValue(i, 1)));
            return names;
        });
    }

    private static List<String> proposalsJson(List<List<Row>> proposals, Names names) {
        List<String> json = new ArrayList<>();
        for (List<Row> proposal : proposals) {
            PersonAnonymisationRunner.Subject subject = proposal.get(0).subject();
            String subjectName = subject == null ? null
                : (subject.isBooker() ? names.bookers() : names.persons()).get(subject.id());
            json.add(proposalJson(proposal, names.staff(), subjectName));
        }
        return json;
    }

    // ===== Helpers =================================================================================================

    /** The approval state of one proposal, as it stands. */
    private static Future<String> stateReply(int proposalRunId) {
        return EventErasureRunner.query(ROWS_SQL, proposalRunId).compose(result -> {
            List<Row> rows = rows(result);
            Set<Integer> staffIds = new TreeSet<>();
            rows.forEach(row -> { if (row.decidedBy() != null) staffIds.add(row.decidedBy()); });
            return StaffNames.lookupMap(staffIds).map(staff -> stateJson(proposalRunId, rows, staff));
        });
    }

    /** A write as the server, in autocommit: the number of rows it wrote. */
    private static Future<Integer> submit(String sql, Object... parameters) {
        return EventErasureRunner.asServer(() -> SubmitService.executeSubmit(new SubmitArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(sql) // a constant with server-computed ids inlined; every value is bound
                .setParameters(parameters)
                .build()))
            .map(SubmitResult::getRowCount);
    }

    private static String framed(String key, String text) {
        return "[%s] %s".formatted(key, text);
    }
}
