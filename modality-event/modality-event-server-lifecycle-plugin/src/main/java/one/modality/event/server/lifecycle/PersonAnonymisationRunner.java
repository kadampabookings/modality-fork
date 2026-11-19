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
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
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
 * <h3>Which organisations: the scope</h3>
 *
 * A person's records span organisations; the Data Protection Officer holds DataProtection in some of them. The run
 * acts in the organisations the caller COVERS — asked of the authorization framework on every preview and commit
 * ({@link #organizationsCovered}: the organisations of the subject's records, each asked fresh), never sent by the client — and passes them to the function
 * as {@code p_organization_ids} (V0175). Two scopes, chosen by the DPO ({@code mode}):
 * <ul>
 *   <li>{@code mine} (the default): the covered organisations' bookings and applications, and the shared account;
 *       every other organisation's booking is KEPT unchanged and detached from the erased rows.</li>
 *   <li>{@code everywhere}: the whole person, as before V0175 ({@code p_organization_ids} NULL). A commit needs, for
 *       every organisation of the subject's records it does not cover, an approval of that organisation's DPO
 *       ({@link ErasureApprovals}, V0176) given to a PROPOSAL — an earlier everywhere preview the caller asked about —
 *       whose facts the commit's own preview must still show.</li>
 * </ul>
 * Either way the caller must cover at least one organisation of the subject's OWN records — the bookings the run
 * erases (or keeps) in full and their volunteering applications, as the run itself sorted them — or the run is
 * refused {@code [DpoNotYourOrganizationError]}; somebody with no record of their own anywhere is any DPO's to erase.
 * The organisations to ASK, and to have approved, are wider: every booking the run reaches or keeps (paid for, cared
 * for, a contact of) and every other row it changes ({@code organisations/touched}, V0175).
 *
 * <h3>Two calls</h3>
 *
 * <ol>
 *   <li><b>Preview</b> ({@code [personId, candidateDocumentIds?, memberIds?, mode?]}, or {@code [documentId,
 *       candidateDocumentIds?, mode?]}): the function runs with the operator's ticks, leniently (a ticked booking that
 *       is no longer a candidate is dropped), and is rolled back. The reply is its grid — the counts, the CANDIDATE
 *       bookings staff may tick, the account's members, the other customers bearing their name, the customers holding
 *       a booker's address, and the ticks it ran with — plus who those people are, every booking it reaches or keeps
 *       with its fate ({@link #bookingJson}), the organisations kept, or a refusal. The dialog re-runs it at every tick
 *       change.</li>
 *   <li><b>Commit</b> ({@code [personId, confirmPhrase, previewRunId, candidateDocumentIds, memberIds, mode?,
 *       proposalRunId?]}, or {@code [documentId, confirmPhrase, previewRunId, candidateDocumentIds, mode?,
 *       proposalRunId?]}): the phrase must be the subject's; the preview's scope must be the commit's, its covered
 *       organisations the ones the caller covers NOW (a role granted or revoked in between refuses it). For
 *       {@code everywhere}, {@code proposalRunId} names the proposal the approvals were given for: the preview must
 *       show the proposal's facts, ticks included, and every organisation it does not cover must have approved. As
 *       ever, the preview must be a succeeded preview of the SAME subject, finished less than {@link #PREVIEW_VALIDITY}
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
 * tell whether a booking is the same human without it — with the organisation the booking belongs to; and who each
 * booking the run reaches is for, in the covered organisations only (another organisation's booking carries its
 * event, dates and state, never a name or an address: admin-tiers plan, decision of 2026-11-19). Read from the
 * bookings themselves, it goes in the reply only: not in the ledger. The same goes for the account's other members,
 * the namesakes and a booker's address owners (name, email). The ledger's grid loses the kept bookings' ids
 * ({@code kept/document}) before it is stored, as it loses the candidates: it would tie {@code #id} to named bookings.
 *
 * <p>One log line per run: its id, the subject, the mode and scope, the outcome, the caller's person id, the number of
 * rows changed and of bookings kept. Never a value.
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
    /** The organisations the caller acts for are not those the preview covered (a DataProtection role granted or removed). */
    static final String SCOPE_CHANGED_KEY = "PersonAnonymiseScopeChangedError";
    static final String CANDIDATE_KEY = "PersonAnonymiseCandidateError";
    static final String MEMBER_KEY = "PersonAnonymiseMemberError";
    static final String BUSY_KEY = "PersonAnonymiseBusyError";
    static final String SQL_KEY = "PersonAnonymiseSqlError";
    static final String CONFLICT_KEY = "PersonAnonymiseConflictError";
    /** An erase-everywhere commit lacks an approval (or its proposal) for an organisation it does not cover. */
    static final String APPROVAL_MISSING_KEY = "PersonAnonymiseApprovalMissingError";

    /** The scope of a run (the DPO's choice): the covered organisations, or the whole person. */
    static final String SCOPE_MINE = "mine";
    static final String SCOPE_EVERYWHERE = "everywhere";
    /** {@code scope/organisations} in the function's grid for an everywhere run (V0175). */
    static final String ALL_ORGANISATIONS = "all";

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
    // V0175 also raises [DpoNotYourOrganizationError] (a booker's seed booking in an organisation the run does not cover)
    static final Pattern FUNCTION_KEY = Pattern.compile("^\\[((?:PersonAnonymise[A-Za-z]+|DpoNotYourOrganization)Error)\\]");

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
    static final String EXPIRED_TICKS_SQL =
        "update person_erasure_run r set candidate_document_ids = '{}', member_ids = '{}'"
        + " where r.mode = 'preview' and r.finished_at < now() - interval '" + PREVIEW_VALIDITY + "'"
        + " and (cardinality(r.candidate_document_ids) > 0 or cardinality(r.member_ids) > 0)"
        // A PROPOSAL (an everywhere preview other DPOs are asked to approve, V0176) keeps its ticks while an approval
        // of it is open: the approval re-runs it with them, and the commit must send them
        + " and not (exists (select 1 from person_erasure_approval a where a.run_id = r.id"
        + " and a.state in ('pending', 'approved') and a.expires_at > now())"
        // ...but not once it is over: withdrawn or declined by anyone, or the subject erased since
        + " and not exists (select 1 from person_erasure_approval a where a.run_id = r.id and a.state in ('withdrawn', 'declined'))"
        + " and not exists (select 1 from person_erasure_run c where c.person_id = r.person_id and c.mode = 'commit'"
        + " and c.status = 'succeeded' and c.id > r.id))";

    /** The preview's facts, and whether a commit of the same subject succeeded since (as EventErasureRunner asks). */
    static final String PREVIEW_SQL =
        "select p.person_id, p.mode, p.status, p.finished_at > now() - interval '" + PREVIEW_VALIDITY + "',"
        + " exists (select 1 from person_erasure_run c where c.person_id = p.person_id and c.mode = 'commit'"
        + " and c.status = 'succeeded' and c.id > p.id),"
        + " array_to_string(p.candidate_document_ids, ','), array_to_string(p.member_ids, ','), p.requested_by_person_id"
        + " from person_erasure_run p where p.id = $1";

    /** The preview's grid as the ledger keeps it, as rows in the function's order: a commit's baseline. */
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

    /**
     * $4 = lenient: a preview drops ticked bookings that are no longer candidates; a commit refuses them. $5 = the
     * organisations the run acts in (V0175), NULL for every organisation.
     */
    static final String ANONYMISE_PERSON_SQL = "select step, metric, value from anonymise_person($1, $2::int[], $3::int[], $4, $5::int[])";

    /** $3 = lenient, $4 = the organisations, as above. A booker has no account, so no members. */
    static final String ANONYMISE_BOOKER_SQL = "select step, metric, value from anonymise_booker($1, $2::int[], $3, $4::int[])";

    // ── What the run reached, read inside its transaction (V0175) ──

    /**
     * The bookings the function sorted, each with its scope (full, contact, payer, carer; 'kept' if V0175 keeps them
     * there too): its own temp table, alive until the transaction ends. Read before the ROLLBACK or the COMMIT.
     */
    static final String PERSON_DOCS_SQL = "select t.id, t.scope from ap_docs t";
    static final String BOOKER_DOCS_SQL = "select t.id, t.scope from ab_docs t";

    /** The organisation of each booking (an event's, else the booking's own): ids only, read inside the transaction. */
    static final String DOC_ORGANIZATIONS_SQL =
        "select d.id, coalesce(e.organization_id, d.organization_id) from document d left join event e on e.id = d.event_id"
        + " where d.id = any($1::int[])";

    /**
     * A customer's volunteering applications by organisation: every one the function deletes or keeps (V0175's
     * ap_applications — person-linked, matched by address without a person, removed duplicates'), in both scopes.
     */
    static final String PERSON_APPLICATIONS_SQL =
        "select a.organization_id, count(*) from ap_applications a group by a.organization_id";

    /** The kept bookings' would-be scope (V0175's ap_kept / ab_kept): 'full' = one of the subject's own. */
    static final String PERSON_KEPT_SQL = "select k.id, k.scope from ap_kept k";
    static final String BOOKER_KEPT_SQL = "select k.id, k.scope from ab_kept k";

    /**
     * The organisations of a customer's records, read BEFORE the run to know which organisations to ask the framework
     * about (fresh, one grant computation each): the bookings of the person, their linked copies and the ticked members
     * (and theirs) — booked, paid or cared for —, the ticked candidates, the bookings without a person under one of
     * their addresses, and their applications. An organisation it misses is only kept, never erased; one too many is
     * only asked about. Ids inlined (the subject's, the ticks: integers the server parsed).
     */
    static String personOrganizationsSql(int personId, Collection<Integer> members, Collection<Integer> candidates) {
        String s = "(select " + personId + " union select q.id from person q where q.account_person_id = " + personId
            + " union select m from unnest(" + ErasureFind.intArray(members) + ") m"
            + " union select q.id from person q where q.account_person_id = any(" + ErasureFind.intArray(members) + "))";
        return "with s as materialized " + s + ","
            + " em as materialized (select distinct lower(trim(q.email)) as e from person q where q.id in (select * from s)"
            + " and position('@' in coalesce(q.email, '')) > 1)"
            + " select distinct x.o from ("
            // The indexed links in branches of their own (person_id: document_person_id_idx; the ticked ids: the primary
            // key), never OR-ed with others: "a in (…) or b in (…)" makes Postgres scan every booking. payer_id and the
            // carer person ids have no index (no new index: David, 17 Nov), so they share ONE scan of document — measured
            // on kbs_local: two full scans, 160 ms, before; one scan after.
            + " select coalesce(e.organization_id, d.organization_id) as o from document d left join event e on e.id = d.event_id"
            + " where d.person_id in (select * from s)"
            + " union select coalesce(e.organization_id, d.organization_id) from document d left join event e on e.id = d.event_id"
            + " where d.id = any(" + ErasureFind.intArray(candidates) + ")"
            + " union select coalesce(e.organization_id, d.organization_id) from document d left join event e on e.id = d.event_id"
            + " where d.payer_id in (select * from s) or d.person_carer1_id in (select * from s) or d.person_carer2_id in (select * from s)"
            + " union select coalesce(e.organization_id, d.organization_id) from document d left join event e on e.id = d.event_id"
            + " where d.person_id is null and lower(d.person_email) in (select e from em)"
            + " union select va.organization_id from volunteering_application va where va.person_id in (select * from s)"
            + " or (va.person_id is null and lower(trim(va.email)) in (select e from em))"
            + ") x where x.o is not null";
    }

    /**
     * A booker's: the seed booking, the ticked candidates, the bookings without a person under the seed's address, and
     * the applications without a person under it.
     */
    static String bookerOrganizationsSql(int documentId, Collection<Integer> candidates) {
        return "with a as materialized (select lower(trim(d.person_email)) as e from document d where d.id = " + documentId
            + " and position('@' in coalesce(trim(d.person_email), '')) > 1)"
            + " select distinct x.o from ("
            + " select coalesce(e.organization_id, d.organization_id) as o from document d left join event e on e.id = d.event_id"
            + " where d.id = " + documentId + " or d.id = any(" + ErasureFind.intArray(candidates) + ")"
            + " union select coalesce(e.organization_id, d.organization_id) from document d left join event e on e.id = d.event_id"
            + " where d.person_id is null and lower(d.person_email) in (select e from a)"
            + " union select va.organization_id from volunteering_application va"
            + " where va.person_id is null and lower(trim(va.email)) in (select e from a)"
            + ") x where x.o is not null";
    }

    /**
     * A booker's applications, by organisation: those the function takes and those it keeps for another organisation
     * (V0175's own temp tables; an application is in one or the other).
     */
    static final String BOOKER_APPLICATIONS_SQL =
        "select va.organization_id, count(*) from volunteering_application va where va.id in (select a.id from ab_applications a"
        + " union select k.id from ab_kept_applications k) group by va.organization_id";

    /**
     * Each booking the run reaches or keeps, as the reply shows it: read after the ROLLBACK (a preview: the data as it
     * stands) or the COMMIT. The person_* columns are sent for covered bookings only ({@link #bookingJson}).
     */
    static final String BOOKING_DETAILS_SQL =
        "select d.id, d.ref, e.name, to_char(e.start_date, 'YYYY-MM-DD'), to_char(e.end_date, 'YYYY-MM-DD'),"
        + " coalesce(e.organization_id, d.organization_id), d.cancelled,"
        + " d.person_first_name, d.person_last_name, d.person_email, d.person_phone"
        + " from document d left join event e on e.id = d.event_id"
        + " where d.id = any($1::int[]) order by e.start_date desc nulls last, d.id desc";

    /** Not over / money owed either way: the very rules of the function's refusals (V0153). */
    static final String BLOCKING_SQL = "select b.document_id, b.kind from anonymise_person_blocking($1::int[]) b";

    static final String ORGANIZATION_NAMES_SQL = "select o.id, o.name from organization o where o.id = any($1::int[])";

    /**
     * The booker runs whose seed booking a commit anonymised — anonymise_person() takes such a booking as one of the
     * customer's and cannot know a run was started from it — lose their ticks, refusal texts and listed steps (V0163).
     */
    private static final String LEDGER_TIDY_SQL = "select anonymise_booker_ledger_tidy()";

    /**
     * Who the account's members, the other customers bearing one of their names, or the customers holding a booker's
     * address are — for the preview's reply only, as {@link #CANDIDATE_DETAILS_SQL}.
     */
    static final String PERSON_DETAILS_SQL =
        "select q.id, q.first_name, q.last_name, q.email from person q where q.id = any($1::int[])"
        // Only people the caller may find: a record in a covered organisation ($2), or none anywhere (ErasureFind's rule)
        + " and (" + ErasureFind.recordsHere("q.id", "$2::int[]") + " or not " + ErasureFind.recordsAnywhere("q.id") + ")"
        + " order by q.id";

    /**
     * Who each candidate booking is for, so staff can tell whether it is the same human: read after the preview's
     * ROLLBACK (the data as it stands), for the candidate ids the function itself listed. Sent in the preview's
     * reply only — never into the ledger, whose grid stays free of personal data, and never into the log.
     */
    static final String CANDIDATE_DETAILS_SQL =
        "select d.id, d.person_first_name, d.person_last_name, d.person_email, d.person_phone, d.cancelled,"
        + " coalesce(eo.name, o.name), coalesce(e.organization_id, d.organization_id)"
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
     * Rows the ledger never keeps, by step/metric: the ids of the bookings another organisation keeps (V0175) — the
     * runner reads them for the reply; stored, they would tie {@code #id} to bookings that still carry the name.
     */
    static final Set<String> UNSTORED_ROWS = Set.of("kept/document");

    /**
     * The runner's own rows, added to the stored grid (not to the reply's): the organisations of the subject's records
     * and the bookings each holds — ids and counts — so that an approval and an erase-everywhere commit compare the
     * same facts as the proposal they were given for ({@link #describeProposalDrift}).
     */
    static final String ORGANISATIONS_STEP = "organisations";

    /**
     * What a proposal's facts are, for {@link #describeProposalDrift}: every row of these steps but the metrics that
     * move on their own (a mail sent to the person). A new booking, a tick gone stale, another organisation involved,
     * a booking moved between organisations: each changes one.
     */
    static final Set<String> PROPOSAL_FACT_STEPS = Set.of("person", "booker", "scope", "kept", ORGANISATIONS_STEP);
    static final Set<String> PROPOSAL_DRIFTING = Set.of("scope/mails", "where/run_at");

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

    /**
     * @param scope     {@link #SCOPE_MINE} or {@link #SCOPE_EVERYWHERE}
     * @param covered   the organisations the caller covers, from fresh grants (never the client's)
     * @param approved  an everywhere commit's approved organisations (empty otherwise)
     */
    private record Request(Subject subject, boolean commit, Integer previewRunId, Integer[] candidates, Integer[] members,
                           Integer callerId, List<EventErasureRunner.GridRow> baseline, String scope, Set<Integer> covered,
                           Set<Integer> approved) {
        String mode() {
            return commit ? MODE_COMMIT : MODE_PREVIEW;
        }

        boolean everywhere() {
            return SCOPE_EVERYWHERE.equals(scope);
        }

        /** The function's p_organization_ids: the covered organisations, or null = every organisation. */
        Integer[] organizationIds() {
            return everywhere() ? null : new TreeSet<>(covered).toArray(new Integer[0]);
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

    static final class Run {
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
        /** Each booking the run reaches or keeps → its fate (erased, contact, payer, carer, kept), in id order. */
        final Map<Integer, String> fates = new TreeMap<>();
        /** Each of those bookings → its organisation (null: none). */
        final Map<Integer, Integer> organizationOf = new LinkedHashMap<>();
        /** Volunteering applications of the subject, by organisation (a null key: none). */
        final Map<Integer, Long> applicationsByOrganization = new LinkedHashMap<>();
        /** Each kept booking → the scope it would have had (V0175's ap_kept/ab_kept): 'full' = the subject's own. */
        final Map<Integer, String> keptScopes = new LinkedHashMap<>();
        /** Organisations of other rows the run changes beyond the subject's bookings (V0175's organisations/touched). */
        Set<Integer> touched = Set.of();
        /** The bookings, as JSON objects, for the reply (personal data in covered ones only). */
        List<String> bookingDetails = List.of();
        /** The organisations whose records are kept, as JSON objects, for the reply. */
        List<String> keptOrganizations = List.of();
        /** The covered organisations, as JSON objects {id, name}, for the reply. */
        List<String> coveredOrganizations = List.of();
        /** The subject's organisations the caller does not cover, whatever the scope ({@link #otherOrganizationsJson}). */
        List<String> otherOrganizations = List.of();
        String status;
        String errorKey;
        String errorText;
        String sqlState;
        /** Set once the COMMIT went through: nothing after it may roll back or record a failure. */
        boolean committed;

        Run(int id, Request request) {
            this.id = id;
            this.request = request;
        }

        /** The grid as the ledger stored it: what a proposal is compared on. */
        List<EventErasureRunner.GridRow> storedGrid() {
            return PersonAnonymisationRunner.storedGrid(grid, own(), involved(), organizationOf);
        }

        /** The organisations of the subject's OWN records: the ownership gate's. */
        Set<Integer> own() {
            return ownOrganizations(fates, keptScopes, organizationOf, applicationsByOrganization);
        }

        /** Every organisation the run reaches, keeps or touches: the ones to ask, and to have approved. */
        Set<Integer> involved() {
            Set<Integer> involved = new TreeSet<>(involvedOrganizations(organizationOf, applicationsByOrganization));
            involved.addAll(touched);
            return involved;
        }
    }

    // ===== Entry points ============================================================================================

    /**
     * {@code [personId, candidateDocumentIds?, memberIds?, mode?]} — a dry run with the operator's ticks (ticked
     * bookings that are no longer candidates are dropped); the reply is the run as JSON, its grid saying which ticks it
     * ran with.
     */
    static Future<String> preview(Object[] arguments, EventErasureAccess.Caller caller) {
        Integer personId = EventErasureArguments.positiveId(EventErasureArguments.at(arguments, 0));
        if (personId == null)
            return refusal(NOT_FOUND_KEY, "No such person, or the request is malformed", "person ?", MODE_PREVIEW, caller.personId());
        return preview(Subject.person(personId), EventErasureArguments.at(arguments, 1), EventErasureArguments.at(arguments, 2),
            EventErasureArguments.at(arguments, 3), caller).map(PersonAnonymisationRunner::replyJson);
    }

    /** {@code [documentId, candidateDocumentIds?, mode?]} — as {@link #preview(Object[], EventErasureAccess.Caller)}, for a booker. */
    static Future<String> previewBooker(Object[] arguments, EventErasureAccess.Caller caller) {
        Integer documentId = EventErasureArguments.positiveId(EventErasureArguments.at(arguments, 0));
        if (documentId == null)
            return refusal(NOT_FOUND_KEY, "No such booking, or the request is malformed", "booking ?", MODE_PREVIEW, caller.personId());
        return preview(Subject.booker(documentId), EventErasureArguments.at(arguments, 1), null,
            EventErasureArguments.at(arguments, 2), caller).map(PersonAnonymisationRunner::replyJson);
    }

    /** {@code [personId, confirmPhrase, previewRunId, candidateDocumentIds, memberIds, mode?, proposalRunId?]} — the real anonymisation. */
    static Future<String> commit(Object[] arguments, EventErasureAccess.Caller caller) {
        Integer personId = EventErasureArguments.positiveId(EventErasureArguments.at(arguments, 0));
        if (personId == null)
            return refusal(NOT_FOUND_KEY, "No such person, or the request is malformed", "person ?", MODE_COMMIT, caller.personId());
        return commit(Subject.person(personId), EventErasureArguments.at(arguments, 1), EventErasureArguments.at(arguments, 2),
            EventErasureArguments.at(arguments, 3), EventErasureArguments.at(arguments, 4), EventErasureArguments.at(arguments, 5),
            EventErasureArguments.at(arguments, 6), caller);
    }

    /** {@code [documentId, confirmPhrase, previewRunId, candidateDocumentIds, mode?, proposalRunId?]} — the real anonymisation of a booker. */
    static Future<String> commitBooker(Object[] arguments, EventErasureAccess.Caller caller) {
        Integer documentId = EventErasureArguments.positiveId(EventErasureArguments.at(arguments, 0));
        if (documentId == null)
            return refusal(NOT_FOUND_KEY, "No such booking, or the request is malformed", "booking ?", MODE_COMMIT, caller.personId());
        return commit(Subject.booker(documentId), EventErasureArguments.at(arguments, 1), EventErasureArguments.at(arguments, 2),
            EventErasureArguments.at(arguments, 3), null, EventErasureArguments.at(arguments, 4),
            EventErasureArguments.at(arguments, 5), caller);
    }

    /** The scope the client chose: absent or null = {@link #SCOPE_MINE}; anything but the two words = null (malformed). */
    static String scopeOf(Object argument) {
        if (argument == null)
            return SCOPE_MINE;
        return SCOPE_MINE.equals(argument) || SCOPE_EVERYWHERE.equals(argument) ? (String) argument : null;
    }

    private static Future<Run> preview(Subject subject, Object candidatesArgument, Object membersArgument, Object scopeArgument,
                                       EventErasureAccess.Caller caller) {
        Integer[] candidates = candidateIds(candidatesArgument);
        if (candidates == null)
            return refusal(CANDIDATE_KEY, "The ticked bookings are malformed", subject.noun(), MODE_PREVIEW, caller.personId());
        Integer[] members = candidateIds(membersArgument);
        if (members == null)
            return refusal(MEMBER_KEY, "The ticked members are malformed", subject.noun(), MODE_PREVIEW, caller.personId());
        String scope = scopeOf(scopeArgument);
        if (scope == null)
            return refusal(NOT_FOUND_KEY, "The mode must be mine or everywhere", subject.noun(), MODE_PREVIEW, caller.personId());
        return previewRun(subject, candidates, members, scope, caller);
    }

    /**
     * A preview run, as a {@link Run}: for the endpoints, and for an approval, which re-runs its proposal
     * ({@link ErasureApprovals}). The covered organisations are asked fresh.
     */
    static Future<Run> previewRun(Subject subject, Integer[] candidates, Integer[] members, String scope,
                                  EventErasureAccess.Caller caller) {
        return organizationsCovered(subject, candidates, members, caller).compose(covered ->
            execute(new Request(subject, false, null, candidates, members, caller.personId(), null, scope, covered, Set.of())));
    }

    /**
     * The organisations the run may act in: the subject's ({@link #personOrganizationsSql}, {@link
     * #bookerOrganizationsSql}), each asked of the framework from FRESH grants. A read that fails covers nothing.
     */
    static Future<Set<Integer>> organizationsCovered(Subject subject, Integer[] candidates, Integer[] members,
                                                     EventErasureAccess.Caller caller) {
        String sql = subject.isBooker() ? bookerOrganizationsSql(subject.id(), Arrays.asList(candidates))
            : personOrganizationsSql(subject.id(), Arrays.asList(members), Arrays.asList(candidates));
        return EventErasureRunner.query(sql)
            .map(rows -> {
                Set<Integer> organizations = new TreeSet<>();
                for (int i = 0; rows != null && i < rows.getRowCount(); i++)
                    if (EventErasureRunner.asInteger(rows.getValue(i, 0)) != null)
                        organizations.add(EventErasureRunner.asInteger(rows.getValue(i, 0)));
                return (Set<Integer>) organizations;
            })
            .compose(organizations -> EventErasureAccess.organizationsHeldFresh(caller, organizations))
            .otherwise(Set.of());
    }

    private static Future<String> commit(Subject subject, Object phraseArgument, Object previewRunArgument,
                                         Object candidatesArgument, Object membersArgument, Object scopeArgument,
                                         Object proposalArgument, EventErasureAccess.Caller caller) {
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
        String scope = scopeOf(scopeArgument);
        if (scope == null)
            return refusal(NOT_FOUND_KEY, "The mode must be mine or everywhere", subject.noun(), MODE_COMMIT, caller.personId());
        Integer proposalRunId = proposalArgument == null ? null : EventErasureArguments.positiveId(proposalArgument);
        boolean everywhere = SCOPE_EVERYWHERE.equals(scope);
        if (everywhere && proposalRunId == null)
            return refusal(APPROVAL_MISSING_KEY, "Erasing everywhere needs the proposal the other organisations approved",
                subject.noun(), MODE_COMMIT, caller.personId());
        Future<Set<Integer>> coveredNow = organizationsCovered(subject, candidates, members, caller);
        return checkPreview(subject, previewRunId, candidates, members, caller.personId()).compose(refused -> refused != null
            ? refusal(refused[0], refused[1], subject.noun(), MODE_COMMIT, caller.personId())
            : loadBaseline(previewRunId).compose(baseline -> coveredNow.compose(covered -> {
                // The preview vouches for this scope only, over the organisations the caller covers NOW (fresh): a role
                // granted or revoked since would make the commit erase more, or less, than the preview showed
                String scopeDrift = describeScopeDrift(baseline, scope, covered);
                // Its own key: the DPO is told their organisations changed (a role granted or removed), not "the data changed"
                if (scopeDrift != null)
                    return refusal(SCOPE_CHANGED_KEY, "Preview " + previewRunId + " no longer describes this erasure ("
                        + scopeDrift + "): preview again", subject.noun(), MODE_COMMIT, caller.personId());
                if (!everywhere)
                    return execute(new Request(subject, true, previewRunId, candidates, members, caller.personId(), baseline,
                        scope, covered, Set.of())).map(PersonAnonymisationRunner::replyJson);
                return checkProposal(subject, proposalRunId, baseline, candidates, members).compose(proposal -> proposal.refusal() != null
                    ? refusal(proposal.refusal()[0], proposal.refusal()[1], subject.noun(), MODE_COMMIT, caller.personId())
                    : execute(new Request(subject, true, previewRunId, candidates, members, caller.personId(), baseline,
                        scope, covered, proposal.approved())).map(PersonAnonymisationRunner::replyJson));
            })));
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
    private static Future<String[]> checkPreview(Subject subject, int previewRunId, Integer[] candidates, Integer[] members,
                                                 Integer callerId) {
        return EventErasureRunner.query(PREVIEW_SQL, previewRunId).compose(preview -> {
            boolean usable = preview != null && preview.getRowCount() == 1
                && preview.getValue(0, 0) instanceof Number n && n.intValue() == subject.ledgerKey()
                && MODE_PREVIEW.equals(preview.getValue(0, 1))
                && STATUS_SUCCEEDED.equals(preview.getValue(0, 2))
                && Boolean.TRUE.equals(preview.getValue(0, 3))   // finished less than PREVIEW_VALIDITY ago
                && Boolean.FALSE.equals(preview.getValue(0, 4))  // no successful commit of the subject since
                // the caller's own: a commit acts on what ITS caller saw (another DPO's preview showed another scope)
                && callerId != null && callerId.equals(EventErasureRunner.asInteger(preview.getValue(0, 7)));
            if (!usable)
                return Future.succeededFuture(new String[] { PREVIEW_REQUIRED_KEY, "Preview " + previewRunId
                    + " cannot vouch for this commit: it must be your own succeeded preview of " + subject.noun()
                    + ", finished less than " + PREVIEW_VALIDITY + " ago, with no commit since" });
            // The commit does exactly what the preview previewed: the same ticks, no more, no fewer
            if (!sameIds(candidates, preview.getValue(0, 5)) || !sameIds(members, preview.getValue(0, 6)))
                return Future.succeededFuture(new String[] { PREVIEW_REQUIRED_KEY, "The ticks differ from those preview "
                    + previewRunId + " ran with: preview again with these ticks" });
            return Future.succeededFuture(null);
        });
    }

    /** The preview's stored grid, as rows (empty when it kept none). */
    static Future<List<EventErasureRunner.GridRow>> loadBaseline(int previewRunId) {
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
     * Where a preview's stored grid does not vouch for a commit of this scope over these covered organisations, or
     * null when it does: its {@code scope/organisations} row (V0175) must say {@link #ALL_ORGANISATIONS} for
     * everywhere, and exactly the covered ids for mine. A preview without the row (before V0175) never vouches.
     */
    static String describeScopeDrift(List<EventErasureRunner.GridRow> preview, String scope, Set<Integer> covered) {
        String value = EventErasureRunner.valueOf(preview, "scope", "organisations");
        if (value == null)
            return "it recorded no organisations";
        boolean all = ALL_ORGANISATIONS.equals(value.trim());
        if (SCOPE_EVERYWHERE.equals(scope))
            return all ? null : "it previewed your organisations only";
        if (all)
            return "it previewed every organisation";
        Set<Integer> previewed = idSet(value);
        Set<Integer> now = new TreeSet<>(covered);
        return previewed != null && previewed.equals(now) ? null
            : "the organisations you act for changed: " + (previewed == null ? "?" : previewed) + " -> " + now;
    }

    /** "1, 151,187" → {1, 151, 187}; "" → {}; anything not ids → null. */
    static Set<Integer> idSet(String value) {
        Set<Integer> ids = new TreeSet<>();
        if (value == null)
            return null;
        for (String id : value.split(","))
            if (!id.isBlank()) {
                if (!id.trim().matches("[0-9]{1,9}"))
                    return null;
                ids.add(Integer.valueOf(id.trim()));
            }
        return ids;
    }

    /** {@link #checkProposal}'s answer: the approved organisations, or {key, text}. */
    record ProposalCheck(Set<Integer> approved, String[] refusal) {
    }

    /** A proposal's facts: whose, which ticks, whether it was asked about. */
    static final String PROPOSAL_SQL =
        "select p.person_id, p.mode, p.status, array_to_string(p.candidate_document_ids, ','), array_to_string(p.member_ids, ','),"
        + " exists (select 1 from person_erasure_approval a where a.run_id = p.id),"
        + " exists (select 1 from person_erasure_approval a where a.run_id = p.id and a.state in ('withdrawn', 'declined'))"
        + " from person_erasure_run p where p.id = $1";

    /** The organisations that approved a proposal and whose approval still holds. */
    static final String APPROVED_SQL =
        "select a.organization_id from person_erasure_approval a where a.run_id = $1 and a.state = 'approved' and a.expires_at > now()";

    /**
     * An everywhere commit's proposal: an everywhere preview of the SAME subject that was asked about, with the
     * same ticks as the commit, whose facts the commit's own (fresh) preview still shows. The approvals are read
     * here; whether they cover every organisation is decided inside the run, over the organisations it finds.
     */
    private static Future<ProposalCheck> checkProposal(Subject subject, int proposalRunId, List<EventErasureRunner.GridRow> preview,
                                                       Integer[] candidates, Integer[] members) {
        return EventErasureRunner.query(PROPOSAL_SQL, proposalRunId).compose(p -> {
            boolean usable = p != null && p.getRowCount() == 1
                && p.getValue(0, 0) instanceof Number n && n.intValue() == subject.ledgerKey()
                && MODE_PREVIEW.equals(p.getValue(0, 1)) && STATUS_SUCCEEDED.equals(p.getValue(0, 2))
                && Boolean.TRUE.equals(p.getValue(0, 5));
            if (!usable)
                return Future.succeededFuture(new ProposalCheck(null, new String[] { APPROVAL_MISSING_KEY,
                    "Run " + proposalRunId + " is not a proposal to erase " + subject.noun() + " everywhere" }));
            // Withdrawn by its asker or declined by an organisation: the proposal is over, whatever else was approved
            if (Boolean.TRUE.equals(p.getValue(0, 6)))
                return Future.succeededFuture(new ProposalCheck(null, new String[] { ErasureApprovals.WITHDRAWN_KEY,
                    "Proposal " + proposalRunId + " was withdrawn or declined: erase your part, or ask again" }));
            if (!sameIds(candidates, p.getValue(0, 3)) || !sameIds(members, p.getValue(0, 4)))
                return Future.succeededFuture(new ProposalCheck(null, new String[] { PREVIEW_CHANGED_KEY,
                    "The ticks differ from those of proposal " + proposalRunId + ": tick what it ticked, or ask again" }));
            return loadBaseline(proposalRunId).compose(proposalGrid -> {
                String drift = describeProposalDrift(proposalGrid, preview);
                if (drift != null)
                    return Future.succeededFuture(new ProposalCheck(null, new String[] { PREVIEW_CHANGED_KEY,
                        "The data changed since proposal " + proposalRunId + " (" + drift + "): ask for approval again" }));
                return EventErasureRunner.query(APPROVED_SQL, proposalRunId).map(rows -> {
                    Set<Integer> approved = new TreeSet<>();
                    for (int i = 0; rows != null && i < rows.getRowCount(); i++)
                        if (rows.getValue(i, 0) instanceof Number id)
                            approved.add(id.intValue());
                    return new ProposalCheck(approved, null);
                });
            });
        });
    }

    /**
     * Where a run's stored grid differs from its proposal's on the {@link #PROPOSAL_FACT_STEPS} (counts, states and
     * ids of organisations — never a text a booking holds), or null when nothing does. A fact present on one side
     * only counts as a difference, so a proposal without them never vouches. Used by an approval (the approver's
     * re-run) and an everywhere commit (the committer's preview).
     */
    static String describeProposalDrift(List<EventErasureRunner.GridRow> proposal, List<EventErasureRunner.GridRow> now) {
        Map<String, String> before = proposalFacts(proposal), after = proposalFacts(now);
        if (before.isEmpty())
            return "the proposal recorded none of the facts to compare with";
        List<String> differences = new ArrayList<>();
        Set<String> facts = new TreeSet<>(before.keySet());
        facts.addAll(after.keySet());
        for (String fact : facts)
            if (!Objects.equals(before.get(fact), after.get(fact)))
                differences.add(fact + " " + before.get(fact) + " -> " + after.get(fact));
        return differences.isEmpty() ? null : String.join(", ", differences);
    }

    private static Map<String, String> proposalFacts(List<EventErasureRunner.GridRow> grid) {
        Map<String, String> facts = new LinkedHashMap<>();
        if (grid != null)
            for (EventErasureRunner.GridRow row : grid) {
                String fact = row.step() + "/" + row.metric();
                if (PROPOSAL_FACT_STEPS.contains(row.step()) && !PROPOSAL_DRIFTING.contains(fact) && !UNSTORED_ROWS.contains(fact))
                    facts.put(fact, row.value());
            }
        return facts;
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

    /** The run, succeeded — or a failed future carrying the {@code [Key] text} the caller is told. */
    private static Future<Run> execute(Request request) {
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
                    return Future.succeededFuture(run);
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
                .addArrayOfInteger(request.organizationIds())
            : Tuple.tuple().addValue(subject.id()).addArrayOfInteger(request.candidates())
                .addArrayOfInteger(request.members()).addValue(!request.commit()).addArrayOfInteger(request.organizationIds());
        return connection.query(BEGIN_SQL).execute()
            .compose(ignored -> connection.preparedQuery(SETTINGS_SQL).execute(Tuple.of(subject.confirmPhrase(),
                auditPerson, "gdpr-anonymise person=" + auditPerson)))
            .compose(ignored -> connection.preparedQuery(subject.isBooker() ? ANONYMISE_BOOKER_SQL : ANONYMISE_PERSON_SQL).execute(call))
            .compose(rows -> {
                run.grid = gridOf(rows);
                return readReach(connection, run);
            })
            .compose(ignored -> {
                // As the run itself sorted them: the caller must cover one organisation of the subject's OWN records;
                // erasing everywhere, every other organisation it reaches, keeps or touches must have approved
                String[] refused = scopeRefusal(request.everywhere(), request.commit(), request.covered(), run.own(),
                    run.involved(), request.approved());
                if (refused != null)
                    return io.vertx.core.Future.failedFuture(new RunRefusal(refused[0], refused[1]));
                if (!request.commit())
                    return connection.query("ROLLBACK").execute()
                        .compose(ignored2 -> readCandidateDetails(connection, run))
                        .compose(ignored2 -> readPersonDetails(connection, run, "member"))
                        .map(details -> { run.memberDetails = details; return null; })
                        .compose(ignored2 -> readPersonDetails(connection, run, "namesake"))
                        .map(details -> { run.namesakeDetails = details; return null; })
                        .compose(ignored2 -> readPersonDetails(connection, run, "addressOwner"))
                        .map(details -> { run.addressOwnerDetails = details; return null; })
                        .compose(ignored2 -> readBookings(connection, run))
                        .compose(ignored2 -> finish(connection, run, STATUS_SUCCEEDED, null, null));
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
                    .compose(ignored2 -> finish(connection, run, STATUS_SUCCEEDED, null, null))
                    .compose(finished -> STATUS_SUCCEEDED.equals(finished.status)
                        ? connection.query("COMMIT").execute().map(ignored2 -> { finished.committed = true; return finished; })
                        : io.vertx.core.Future.failedFuture("The run's ledger row is no longer running"))
                    // Committed: what the reply shows is read afterwards, and may fail without undoing anything
                    .compose(finished -> readBookings(connection, finished)
                        .transform(read -> io.vertx.core.Future.succeededFuture(finished)));
            })
            .recover(cause -> run.committed
                ? io.vertx.core.Future.succeededFuture(run) // committed already: never roll back or re-record
                : failed(connection, run, cause));
    }

    /**
     * Inside the transaction, after the function: the bookings it sorted (its temp table) and those it kept
     * ({@code kept/document}, with their would-be scope), each booking's organisation, the applications by
     * organisation, and the organisations of other rows it changed ({@code organisations/touched}).
     */
    private static io.vertx.core.Future<Void> readReach(PgConnection connection, Run run) {
        boolean booker = run.request.subject().isBooker();
        return connection.query(booker ? BOOKER_DOCS_SQL : PERSON_DOCS_SQL).execute()
            .compose(rows -> {
                for (Row row : rows) {
                    String fate = fateOf(row.getString(1));
                    if (row.getInteger(0) != null && fate != null)
                        run.fates.put(row.getInteger(0), fate);
                }
                Set<Integer> kept = idSet(EventErasureRunner.valueOf(run.grid, "kept", "document"));
                if (kept != null)
                    for (Integer id : kept)
                        run.fates.put(id, FATE_KEPT);
                if (run.fates.isEmpty())
                    return io.vertx.core.Future.succeededFuture();
                return connection.preparedQuery(DOC_ORGANIZATIONS_SQL)
                    .execute(Tuple.tuple().addArrayOfInteger(run.fates.keySet().toArray(new Integer[0])))
                    .map(organizations -> {
                        for (Row row : organizations)
                            run.organizationOf.put(row.getInteger(0), row.getInteger(1));
                        return null;
                    });
            })
            .compose(ignored -> connection.query(booker ? BOOKER_KEPT_SQL : PERSON_KEPT_SQL).execute()
                .map(rows -> {
                    for (Row row : rows)
                        run.keptScopes.put(row.getInteger(0), row.getString(1));
                    return null;
                }))
            .compose(ignored -> connection.query(booker ? BOOKER_APPLICATIONS_SQL : PERSON_APPLICATIONS_SQL).execute()
                .map(rows -> { countsInto(rows, run.applicationsByOrganization); return null; }))
            .map(ignored -> {
                Set<Integer> touched = idSet(EventErasureRunner.valueOf(run.grid, ORGANISATIONS_STEP, "touched"));
                run.touched = touched == null ? Set.of() : touched;
                return null;
            })
            .mapEmpty();
    }

    private static void countsInto(RowSet<Row> rows, Map<Integer, Long> counts) {
        for (Row row : rows)
            counts.merge(row.getInteger(0), row.getLong(1) == null ? 0L : row.getLong(1), Long::sum);
    }

    // ===== The scope rules (pure, for the check) ====================================================================

    static final String FATE_ERASED = "erased";
    static final String FATE_KEPT = "kept";

    /** A booking's fate in the reply, from the function's scope for it; null for a scope this class does not know. */
    static String fateOf(String functionScope) {
        if (functionScope == null)
            return null;
        return switch (functionScope) {
            case "full" -> FATE_ERASED;
            case "contact", "payer", "carer" -> functionScope;
            case "kept" -> FATE_KEPT;
            default -> null;
        };
    }

    /**
     * The organisations of the subject's records: of every booking the run reaches or keeps, and of their
     * volunteering applications. A record with no organisation is not one — no DPO covers it, none can approve
     * for it (none on staging, 19 Nov 2026).
     */
    static Set<Integer> involvedOrganizations(Map<Integer, Integer> organizationOf, Map<Integer, Long> applicationsByOrganization) {
        Set<Integer> involved = new TreeSet<>();
        for (Integer organization : organizationOf.values())
            if (organization != null)
                involved.add(organization);
        for (Map.Entry<Integer, Long> e : applicationsByOrganization.entrySet())
            if (e.getKey() != null && e.getValue() != null && e.getValue() > 0)
                involved.add(e.getKey());
        return involved;
    }

    /**
     * Null when a run of this scope may go on, else {key, text}:
     * <ul>
     *   <li>the subject has records of their OWN ({@link #ownOrganizations}), and the caller covers none of their
     *       organisations → {@code [DpoNotYourOrganizationError]} (preview or commit, either scope) — a booking they
     *       only paid for or cared for elsewhere makes no DPO theirs;</li>
     *   <li>an everywhere COMMIT, and an organisation the run reaches, keeps or touches ({@code involved}) is neither
     *       covered nor approved →
     *       {@link #APPROVAL_MISSING_KEY}, naming the organisations by id.</li>
     * </ul>
     */
    static String[] scopeRefusal(boolean everywhere, boolean commit, Set<Integer> covered, Set<Integer> own,
                                 Set<Integer> involved, Set<Integer> approved) {
        if (!own.isEmpty() && own.stream().noneMatch(covered::contains))
            return new String[] { EventErasureAccess.NOT_YOUR_ORGANIZATION_KEY,
                "None of these records belong to an organisation you are the Data Protection Officer of" };
        if (everywhere && commit) {
            Set<Integer> missing = new TreeSet<>(involved);
            missing.removeAll(covered);
            missing.removeAll(approved == null ? Set.of() : approved);
            if (!missing.isEmpty())
                return new String[] { APPROVAL_MISSING_KEY, "organisations " + joined(missing)
                    + " have not approved erasing everywhere" };
        }
        return null;
    }

    /**
     * The organisations of the subject's OWN records: the bookings whose fate is erased in full — or would be, for one
     * another organisation keeps (its would-be scope 'full') — and their volunteering applications. Not a booking they
     * only paid for, cared for, or are a contact of.
     */
    static Set<Integer> ownOrganizations(Map<Integer, String> fates, Map<Integer, String> keptScopes,
                                         Map<Integer, Integer> organizationOf, Map<Integer, Long> applicationsByOrganization) {
        Set<Integer> own = new TreeSet<>();
        fates.forEach((documentId, fate) -> {
            boolean theirs = FATE_ERASED.equals(fate) || FATE_KEPT.equals(fate) && "full".equals(keptScopes.get(documentId));
            Integer organization = organizationOf.get(documentId);
            if (theirs && organization != null)
                own.add(organization);
        });
        applicationsByOrganization.forEach((organization, n) -> {
            if (organization != null && n != null && n > 0)
                own.add(organization);
        });
        return own;
    }

    static String joined(Collection<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (Integer id : new TreeSet<>(ids))
            sb.append(sb.length() == 0 ? "" : ",").append(id);
        return sb.toString();
    }

    /**
     * The runner's own rows for the stored grid ({@link #ORGANISATIONS_STEP}): the organisations of the subject's own
     * records ({@code own}: the ownership gate), every organisation the run reaches, keeps or touches ({@code
     * involved}: the ones to ask), and how many bookings of each the run reaches or keeps ({@code bookings},
     * {@code "1:3,151:7"}; {@code -} for none). Ids and counts.
     */
    static List<EventErasureRunner.GridRow> organisationRows(Set<Integer> own, Set<Integer> involved,
                                                             Map<Integer, Integer> organizationOf) {
        Map<String, Integer> perOrganization = new TreeMap<>();
        for (Integer organization : organizationOf.values())
            perOrganization.merge(organization == null ? "-" : organization.toString(), 1, Integer::sum);
        StringBuilder bookings = new StringBuilder();
        perOrganization.forEach((organization, n) -> bookings.append(bookings.length() == 0 ? "" : ",").append(organization).append(':').append(n));
        return List.of(
            new EventErasureRunner.GridRow(ORGANISATIONS_STEP, "own", joined(own)),
            new EventErasureRunner.GridRow(ORGANISATIONS_STEP, "involved", joined(involved)),
            new EventErasureRunner.GridRow(ORGANISATIONS_STEP, "bookings", bookings.toString()));
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
                .addValue(EventErasureRunner.gridJson(run.storedGrid()))
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

    /** The grid as the ledger keeps it: without {@link #UNSTORED_STEPS} and {@link #UNSTORED_ROWS}. */
    static List<EventErasureRunner.GridRow> storedGrid(List<EventErasureRunner.GridRow> grid) {
        if (grid == null)
            return null;
        List<EventErasureRunner.GridRow> stored = new ArrayList<>(grid.size());
        for (EventErasureRunner.GridRow row : grid)
            if (!UNSTORED_STEPS.contains(row.step()) && !UNSTORED_ROWS.contains(row.step() + "/" + row.metric()))
                stored.add(row);
        return stored;
    }

    /** {@link #storedGrid(List)}, plus the runner's {@link #organisationRows} (a run that reached the function only). */
    static List<EventErasureRunner.GridRow> storedGrid(List<EventErasureRunner.GridRow> grid, Set<Integer> own,
                                                       Set<Integer> involved, Map<Integer, Integer> organizationOf) {
        List<EventErasureRunner.GridRow> stored = storedGrid(grid);
        if (stored == null)
            return null;
        stored.addAll(organisationRows(own, involved, organizationOf));
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
     * {"runId", "personId", "documentId", "mode", "scope", "coveredOrganizations", "grid": [{step, metric, value}],
     * "candidateDetails", "memberDetails", "namesakeDetails", "addressOwnerDetails", "bookings", "keptOrganizations",
     * "otherOrganizations", "approvals"}: personId for a customer, documentId for a booker (the other null); the grid holds counts, ids, refs
     * and event names; the four details arrays (a preview's only, empty for a commit) hold the names and contact
     * details of the people it lists; {@code bookings} every booking the run reaches or keeps ({@link #bookingJson});
     * {@code approvals} is null (a fresh run is nobody's proposal yet: {@link ErasureApprovals} answers for those).
     */
    static String replyJson(Run run) {
        Subject subject = run.request.subject();
        return new EventErasureJson.ObjectWriter()
            .number("runId", run.id)
            .number("personId", subject.isBooker() ? null : subject.id())
            .number("documentId", subject.isBooker() ? subject.id() : null)
            .string("mode", run.request.mode())
            .string("scope", run.request.scope())
            .raw("coveredOrganizations", EventErasureJson.array(run.coveredOrganizations))
            .raw("grid", EventErasureRunner.gridJson(run.grid))
            .raw("candidateDetails", EventErasureJson.array(run.candidateDetails))
            .raw("memberDetails", EventErasureJson.array(run.memberDetails))
            .raw("namesakeDetails", EventErasureJson.array(run.namesakeDetails))
            .raw("addressOwnerDetails", EventErasureJson.array(run.addressOwnerDetails))
            .raw("bookings", EventErasureJson.array(run.bookingDetails))
            .raw("keptOrganizations", EventErasureJson.array(run.keptOrganizations))
            .raw("otherOrganizations", EventErasureJson.array(run.otherOrganizations))
            .raw("approvals", null)
            .toJson();
    }

    /** The subject of a run, for a proposal's reply: {@code {"kind":"person"|"booker","id":n}}. */
    static String subjectJson(Subject subject) {
        return new EventErasureJson.ObjectWriter().string("kind", subject.isBooker() ? "booker" : "person")
            .number("id", subject.id()).toJson();
    }

    /** What the reply says of one booking ({@link #bookingJson}): read from the booking, then judged. */
    record BookingFacts(int documentId, Integer ref, String eventName, String eventStart, String eventEnd,
                        Integer organizationId, String organizationName, boolean cancelled, boolean upcoming, boolean balance,
                        String firstName, String lastName, String email, String phone) {
    }

    /**
     * One booking of the reply. Its person_* values only when the caller covers its organisation and the run reaches
     * it: a booking another organisation keeps — or, erasing everywhere, one of an organisation the caller does not
     * cover — carries its event, dates and state, never a name or an address (admin-tiers plan, 19 Nov 2026).
     */
    static String bookingJson(BookingFacts b, String fate, boolean covered) {
        boolean named = covered && !FATE_KEPT.equals(fate);
        return new EventErasureJson.ObjectWriter()
            .number("documentId", b.documentId())
            .number("ref", b.ref())
            .string("eventName", b.eventName())
            .string("eventStart", b.eventStart())
            .string("eventEnd", b.eventEnd())
            .number("organizationId", b.organizationId())
            .string("organizationName", b.organizationName())
            .bool("covered", covered)
            .bool("cancelled", b.cancelled())
            .bool("upcoming", b.upcoming())
            .bool("balance", b.balance())
            .string("fate", fate)
            .string("firstName", named ? b.firstName() : null)
            .string("lastName", named ? b.lastName() : null)
            .string("email", named ? b.email() : null)
            .string("phone", named ? b.phone() : null)
            .toJson();
    }

    /**
     * The organisations whose records the run keeps (a mine run; none erasing everywhere): {@code [{id, name,
     * bookings, upcoming, balance, applications}]}, by id (an organisation-less booking last, with a null id).
     * {@code applications}: the kept volunteering applications.
     */
    static List<String> keptOrganizationsJson(boolean everywhere, Set<Integer> covered, List<BookingFacts> bookings,
                                              Map<Integer, String> fates, Map<Integer, Long> applicationsByOrganization,
                                              Map<Integer, String> organizationNames) {
        if (everywhere)
            return List.of();
        Map<Integer, long[]> kept = new TreeMap<>(java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()));
        for (BookingFacts b : bookings)
            if (FATE_KEPT.equals(fates.get(b.documentId()))) {
                long[] n = kept.computeIfAbsent(b.organizationId(), k -> new long[4]);
                n[0]++;
                n[1] += b.upcoming() ? 1 : 0;
                n[2] += b.balance() ? 1 : 0;
            }
        applicationsByOrganization.forEach((organization, n) -> {
            if (n != null && n > 0 && (organization == null || !covered.contains(organization)))
                kept.computeIfAbsent(organization, k -> new long[4])[3] += n;
        });
        List<String> json = new ArrayList<>();
        kept.forEach((organization, n) -> json.add(new EventErasureJson.ObjectWriter()
            .number("id", organization)
            .string("name", organization == null ? null : organizationNames.get(organization))
            .number("bookings", n[0]).number("upcoming", n[1]).number("balance", n[2]).number("applications", n[3])
            .toJson()));
        return json;
    }

    /**
     * The organisations of the subject's records the caller does NOT cover, whatever the scope — what an "erase
     * everywhere" needs approved, and what a mine run keeps: {@code [{id, name, bookings, upcoming, balance,
     * applications}]}, by id. Unlike {@link #keptOrganizationsJson} it is filled erasing everywhere too, so the approver's
     * page (which previews everywhere only) still names an organisation holding only a volunteering application.
     * Organisation-less records are not listed: they never need an approval.
     */
    static List<String> otherOrganizationsJson(Set<Integer> covered, List<BookingFacts> bookings,
                                               Map<Integer, Long> applicationsByOrganization, Map<Integer, String> organizationNames) {
        Map<Integer, long[]> other = new TreeMap<>();
        for (BookingFacts b : bookings)
            if (b.organizationId() != null && !covered.contains(b.organizationId())) {
                long[] n = other.computeIfAbsent(b.organizationId(), k -> new long[4]);
                n[0]++;
                n[1] += b.upcoming() ? 1 : 0;
                n[2] += b.balance() ? 1 : 0;
            }
        applicationsByOrganization.forEach((organization, n) -> {
            if (organization != null && n != null && n > 0 && !covered.contains(organization))
                other.computeIfAbsent(organization, k -> new long[4])[3] += n;
        });
        List<String> json = new ArrayList<>();
        other.forEach((organization, n) -> json.add(new EventErasureJson.ObjectWriter()
            .number("id", organization)
            .string("name", organizationNames.get(organization))
            .number("bookings", n[0]).number("upcoming", n[1]).number("balance", n[2]).number("applications", n[3])
            .toJson()));
        return json;
    }

    /** {@code [{id, name}]} of the covered organisations, by id. */
    static List<String> organizationsJson(Set<Integer> ids, Map<Integer, String> names) {
        List<String> json = new ArrayList<>();
        for (Integer id : new TreeSet<>(ids))
            json.add(new EventErasureJson.ObjectWriter().number("id", id).string("name", names.get(id)).toJson());
        return json;
    }

    /**
     * Fills the reply's bookings, kept organisations and covered organisations: after the ROLLBACK (a preview: the
     * data as it stands, so the names are the person's) or after the COMMIT (the names as anonymised).
     */
    private static io.vertx.core.Future<Void> readBookings(PgConnection connection, Run run) {
        Integer[] ids = run.fates.keySet().toArray(new Integer[0]);
        List<BookingFacts> bookings = new ArrayList<>();
        Map<Integer, Set<String>> blocking = new java.util.HashMap<>();
        Map<Integer, String> names = new java.util.HashMap<>();
        io.vertx.core.Future<Void> read = ids.length == 0 ? io.vertx.core.Future.succeededFuture()
            : connection.preparedQuery(BLOCKING_SQL).execute(Tuple.tuple().addArrayOfInteger(ids))
                .compose(rows -> {
                    for (Row row : rows)
                        blocking.computeIfAbsent(row.getInteger(0), k -> new java.util.HashSet<>()).add(row.getString(1));
                    return connection.preparedQuery(BOOKING_DETAILS_SQL).execute(Tuple.tuple().addArrayOfInteger(ids));
                })
                .map(rows -> {
                    for (Row row : rows) {
                        Set<String> kinds = blocking.getOrDefault(row.getInteger(0), Set.of());
                        bookings.add(new BookingFacts(row.getInteger(0), row.getInteger(1), row.getString(2), row.getString(3),
                            row.getString(4), row.getInteger(5), null, Boolean.TRUE.equals(row.getBoolean(6)),
                            kinds.contains("active"), kinds.contains("balance"),
                            row.getString(7), row.getString(8), row.getString(9), row.getString(10)));
                    }
                    return null;
                });
        return read.compose(ignored -> {
                Set<Integer> organizations = new TreeSet<>(run.request.covered());
                organizations.addAll(run.involved());
                return connection.preparedQuery(ORGANIZATION_NAMES_SQL)
                    .execute(Tuple.tuple().addArrayOfInteger(organizations.toArray(new Integer[0])));
            })
            .map(rows -> {
                for (Row row : rows)
                    names.put(row.getInteger(0), row.getString(1));
                Set<Integer> covered = run.request.covered();
                List<String> json = new ArrayList<>(bookings.size());
                List<BookingFacts> named = new ArrayList<>(bookings.size());
                for (BookingFacts b : bookings) {
                    BookingFacts withName = new BookingFacts(b.documentId(), b.ref(), b.eventName(), b.eventStart(), b.eventEnd(),
                        b.organizationId(), b.organizationId() == null ? null : names.get(b.organizationId()), b.cancelled(),
                        b.upcoming(), b.balance(), b.firstName(), b.lastName(), b.email(), b.phone());
                    named.add(withName);
                    json.add(bookingJson(withName, run.fates.get(b.documentId()),
                        b.organizationId() != null && covered.contains(b.organizationId())));
                }
                run.bookingDetails = json;
                run.keptOrganizations = keptOrganizationsJson(run.request.everywhere(), covered, named, run.fates,
                    run.applicationsByOrganization, names);
                run.coveredOrganizations = organizationsJson(covered, names);
                run.otherOrganizations = otherOrganizationsJson(covered, named, run.applicationsByOrganization, names);
                return null;
            });
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
                    details.add(candidateJson(row.getInteger(0), row.getString(1), row.getString(2), row.getString(3),
                        row.getString(4), Boolean.TRUE.equals(row.getBoolean(5)), row.getString(6),
                        row.getInteger(7) != null && run.request.covered().contains(row.getInteger(7))));
                run.candidateDetails = details;
                return null;
            });
    }

    /**
     * Who the persons of one step of the grid are ({@code member}: the account's members; {@code namesake}: the other
     * customers bearing one of their names; {@code addressOwner}: the customers holding a booker's address) — none
     * listed, nothing is read.
     */
    /**
     * A candidate booking of the reply: who it is for only when the caller covers its organisation (erasing everywhere,
     * the candidates of every organisation are listed; another's carries its organisation and state, no name).
     */
    static String candidateJson(Integer documentId, String firstName, String lastName, String email, String phone,
                                boolean cancelled, String organizationName, boolean covered) {
        return new EventErasureJson.ObjectWriter()
            .number("documentId", documentId)
            .string("firstName", covered ? firstName : null)
            .string("lastName", covered ? lastName : null)
            .string("email", covered ? email : null)
            .string("phone", covered ? phone : null)
            .bool("cancelled", cancelled)
            .string("organizationName", organizationName)
            .toJson();
    }

    /**
     * Only the people the caller could find themselves ({@link ErasureFind}'s rule: a record in a covered organisation,
     * or none anywhere, linked copies counted): another organisation's member, namesake or address owner is left out
     * of the details — the grid still counts them.
     */
    private static io.vertx.core.Future<List<String>> readPersonDetails(PgConnection connection, Run run, String step) {
        List<Integer> ids = new ArrayList<>();
        for (EventErasureRunner.GridRow row : run.grid)
            if (step.equals(row.step()) && row.metric() != null && row.metric().matches("[0-9]{1,9}"))
                ids.add(Integer.valueOf(row.metric()));
        if (ids.isEmpty())
            return io.vertx.core.Future.succeededFuture(List.of());
        return connection.preparedQuery(PERSON_DETAILS_SQL).execute(Tuple.tuple().addArrayOfInteger(ids.toArray(new Integer[0]))
                .addArrayOfInteger(new TreeSet<>(run.request.covered()).toArray(new Integer[0])))
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
            .append(" (").append(run.request.mode()).append(", ").append(run.request.scope()).append(") of ").append(run.request.subject().noun())
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
                .append(", bookings kept ").append(run.fates.values().stream().filter(FATE_KEPT::equals).count())
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
