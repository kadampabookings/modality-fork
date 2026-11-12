package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.GeneratedKeyReference;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteRequest;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteVerb;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Outside the back office, the volunteer tables take only what the volunteer pages write: a new application from the
 * public form, and one answer to a link the coordinators emailed, while that link is unused and unexpired. No
 * deletes, no other fields.
 *
 * <p><b>The hole (2026-11-12).</b> The volunteer pages write through the generic submit, and they are public:
 * {@code /book-volunteer/:orgId} (the application form), {@code /volunteer/arrival/:token} and
 * {@code /volunteer/dates/:token} (answers to the coordinators' emails), all usable signed out. Nothing asked what
 * they wrote, so anyone could rewrite any application by id (ids are sequential) — including its medical,
 * criminal-conviction, passport and immigration answers, special-category data — or delete one, which the database
 * cascades to the back office's assignment and communication rows.
 *
 * <p><b>What this cannot do: tell the volunteer from anyone else.</b> The token in the link is the volunteer's only
 * proof, and the write that follows names its row by id without it — the page tests the token in a read, then
 * writes. A rule seeing the write alone cannot know the caller held the token. So this is the interim narrowing, not
 * the fix: a stranger who knows an id can at most give, once, the answer the volunteer could have given to a link
 * that was really sent. <b>The fix is to move the two answers to server endpoints that take the token</b>, resolve it
 * to the row, and write; then this rule refuses those updates outright.
 *
 * <p><b>What "the answer the volunteer could have given" means here</b> (sharpened by the security review of
 * 2026-11-12, which found the first version let anyone cancel applications no link was ever sent for, and answer a
 * dates link with any dates, as often as they liked):
 * <ul>
 *   <li><b>A link must really have been sent</b>: the arrival token, or the proposal's action token, is set, its
 *       expiry has not passed, and it is unused. Only the coordinators' proposals carry a token — the volunteer's own
 *       counter-proposals do not, so they can neither be accepted nor countered from the front office.</li>
 *   <li><b>Each answer marks its link used</b>, in the same statement (a non-null {@code arrivalTokenUsedAt} or
 *       {@code actionTokenUsedAt}), which is what makes it once.</li>
 *   <li><b>The answer is the one the link offers</b>: accepted dates must be the open proposal's dates; a
 *       counter-proposal replies to an open coordinator proposal of the same application, and only once (none may
 *       already reply to it). Repeating a dates update while its proposal is open re-writes the same values.</li>
 * </ul>
 *
 * <p>Judged against the database as it stands before the batch: a rule sees only the batch's inserts, not its
 * updates, so "the proposal answered beside it" cannot be required. What remains open: while an arrival link is
 * open, a stranger knowing the id can add timeline lines to that application (an internal log the coordinators
 * read); the endpoints close it with the rest.
 *
 * <p><b>Observed first</b> ("WOULD REFUSE a volunteer write"); its REFUSED line will need a CloudWatch filter.
 *
 * @author Claude Code
 */
final class VolunteerWritePolicy implements ClientSubmitGuard.WritePolicy {

    /** False while it observes: decisions are logged but nothing is refused. */
    static final boolean ENFORCING = false;

    static final Set<String> VOLUNTEER_ENTITIES = Set.of(
        "VolunteeringApplication", "VolunteeringDateProposal", "VolunteeringTimeline");

    /** Told to the caller. */
    static final String REFUSED = "This change to a volunteer application is made by the coordinators";

    /** The application form's fields (volunteer-apply/utils/application-payload.ts). */
    static final Set<String> APPLICATION_FORM_FIELDS = Set.of(
        "additionalInfo", "age", "applicationSource", "communityLifeAccepted", "dateFlexibility", "dateNotes", "diet",
        "dietItem", "earliestStartDate", "email", "emergencyContactName", "emergencyContactPhone",
        "emergencyContactRelationship", "emergencyDataConsent", "endDate", "englishLevel", "firstName", "fitnessLevel",
        "formType", "gender", "hasCriminalConvictions", "hasMedicalInsurance", "howHeardAboutUs", "immigrationStatus",
        "immigrationStatusOther", "lastName", "latestEndDate", "occupation", "organization", "passportCountry",
        "passportType", "phone", "photo", "preferredAreas", "previousNktDetails", "previousNktVolunteering",
        "priorVolunteerDays", "priorVolunteeredElsewhere", "privacyNoticeAccepted", "refereeDataConsent",
        "referenceEmail", "referenceJobTitle", "referenceName", "shareCode", "skills", "startDate", "status",
        "termsAccepted", "ukEntryDate", "visitReason", "visitReasonOther", "volunteer30dayConfirmed", "whyVolunteer");

    /** The application states an arrival link answers (use-volunteer-arrival-response.ts, matching the PHP service). */
    static final Set<String> ARRIVAL_ANSWERABLE_STATUSES = Set.of("pending-validation", "no-response", "validated", "assigned");

    static final String APPLICATION = "VolunteeringApplication", PROPOSAL = "VolunteeringDateProposal";

    /** An application's arrival link, as stored. */
    record Arrival(String status, boolean tokenSent, Object tokenExpiresAt, Object tokenUsedAt) {
    }

    /** A date proposal, as stored. */
    record Proposal(Object applicationId, String status, boolean tokenSent, Object tokenExpiresAt, Object tokenUsedAt,
                    Object proposedStartDate, Object proposedEndDate) {
    }

    /** The reads the preconditions need. An interface so the check can answer them without a database. */
    interface VolunteerLookup {
        /** The application's arrival link, or a future of null when there is no such row. */
        Future<Arrival> arrival(Object applicationId);

        /** The proposal, or a future of null. */
        Future<Proposal> proposal(Object proposalId);

        /** The application's open coordinator proposal (see {@link #isOpen}), or a future of null. */
        Future<Proposal> openProposalOf(Object applicationId);

        /** Whether some proposal already replies to this one. */
        Future<Boolean> isCountered(Object proposalId);
    }

    static final VolunteerLookup DATABASE_LOOKUP = new VolunteerLookup() {
        // The token itself is selected only to test it is set: it is never logged nor returned. Statuses are compared
        // in Java: a DQL string literal is text, which fails against an enum column (these are varchar today).
        @Override
        public Future<Arrival> arrival(Object applicationId) {
            return first("select status, arrivalConfirmationToken, arrivalTokenExpiresAt, arrivalTokenUsedAt"
                         + " from VolunteeringApplication where id=$1", applicationId)
                .map(row -> row == null ? null : new Arrival(row.getStringFieldValue("status"),
                    row.getFieldValue("arrivalConfirmationToken") != null,
                    row.getFieldValue("arrivalTokenExpiresAt"), row.getFieldValue("arrivalTokenUsedAt")));
        }

        @Override
        public Future<Proposal> proposal(Object proposalId) {
            return first("select application, status, actionToken, actionTokenExpiresAt, actionTokenUsedAt,"
                         + " proposedStartDate, proposedEndDate from VolunteeringDateProposal where id=$1", proposalId)
                .map(row -> row == null ? null : proposalOf(row));
        }

        @Override
        public Future<Proposal> openProposalOf(Object applicationId) {
            return store().<Entity>executeQuery("select application, status, actionToken, actionTokenExpiresAt,"
                    + " actionTokenUsedAt, proposedStartDate, proposedEndDate from VolunteeringDateProposal where application=$1",
                    ProtectedEntityWritesJob.primaryKeyOf(applicationId))
                .map(rows -> rows.stream().map(VolunteerWritePolicy::proposalOf).filter(VolunteerWritePolicy::isOpen)
                    .findFirst().orElse(null));
        }

        @Override
        public Future<Boolean> isCountered(Object proposalId) {
            return store().<Entity>executeQuery("select id from VolunteeringDateProposal where parentProposal=$1",
                    ProtectedEntityWritesJob.primaryKeyOf(proposalId))
                .map(rows -> !rows.isEmpty());
        }

        private EntityStore store() {
            return EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
        }

        private Future<Entity> first(String dql, Object id) {
            return EntityStore.create(DataSourceModelService.getDefaultDataSourceModel())
                .<Entity>executeQuery(dql, ProtectedEntityWritesJob.primaryKeyOf(id))
                .map(rows -> rows.isEmpty() ? null : rows.get(0));
        }
    };

    /** The token itself is read only to test it is set: never logged, never kept. */
    static Proposal proposalOf(Entity row) {
        return new Proposal(Entities.getPrimaryKey(row.getForeignEntityId("application")),
            row.getStringFieldValue("status"), row.getFieldValue("actionToken") != null,
            row.getFieldValue("actionTokenExpiresAt"), row.getFieldValue("actionTokenUsedAt"),
            row.getFieldValue("proposedStartDate"), row.getFieldValue("proposedEndDate"));
    }

    @Override
    public Future<String> refusalReason(WriteRequest write) {
        if (write != null && !VOLUNTEER_ENTITIES.contains(write.entityName()))
            return Future.succeededFuture(null);
        // Read on the caller's thread: the guard asks every row rule synchronously, and the state is gone after the
        // first async hop.
        Object state = ThreadLocalStateHolder.getThreadLocalState();
        Object userId = StateAccessor.getUserId(state);
        boolean backoffice = Boolean.TRUE.equals(StateAccessor.getVerifiedBackoffice(state));
        return judge(write, backoffice, DATABASE_LOOKUP)
            .recover(failure -> Future.succeededFuture("could not check it: " + failure.getMessage()))
            .map(reason -> {
                if (reason == null)
                    return null;
                // Entity, verb and reason only: no row id, no value - these rows hold special-category data.
                Console.log("🛡 " + (ENFORCING ? "REFUSED" : "WOULD REFUSE") + " a volunteer write: "
                            + (write == null ? "?" : write.verb() + " " + write.entityName()) + " by " + userId
                            + " — " + reason);
                return ENFORCING ? REFUSED : null;
            });
    }

    /** The decision, apart from the thread, the switch and the database. */
    static Future<String> judge(WriteRequest write, boolean backoffice, VolunteerLookup lookup) {
        if (write == null)
            return ok(); // refused already, by BackofficeOnlyWritePolicy
        if (!VOLUNTEER_ENTITIES.contains(write.entityName()) || backoffice)
            return ok();
        if (write.verb() == WriteVerb.DELETE)
            return refuse("deletes a volunteer record");
        switch (write.entityName()) {
            case APPLICATION:
                return write.verb() == WriteVerb.INSERT ? judgeApplicationInsert(write) : judgeApplicationUpdate(write, lookup);
            case PROPOSAL:
                return write.verb() == WriteVerb.INSERT ? judgeCounterProposal(write, lookup) : judgeProposalAnswer(write, lookup);
            default:
                return judgeTimeline(write, lookup);
        }
    }

    /** The public form: its fields, and a new application starts as 'new'. */
    private static Future<String> judgeApplicationInsert(WriteRequest write) {
        if (!onlyFields(write, APPLICATION_FORM_FIELDS))
            return refuse("applies with fields only the coordinators set");
        if (writes(write, "status") && !"new".equals(value(write, "status")))
            return refuse("applies already past 'new'");
        return ok();
    }

    /** The three changes the answer pages make to an application. */
    private static Future<String> judgeApplicationUpdate(WriteRequest write, VolunteerLookup lookup) {
        Object id = write.targetId();
        if (!isId(id))
            return refuse("names no application it can be judged on");
        // Arrival link: confirm or cancel, and mark the link used - in this very statement.
        if (onlyFields(write, Set.of("status", "arrivalTokenUsedAt"))) {
            if (!isArrivalAnswer(write))
                return refuse("answers an arrival link without marking it used, or with a status no link sets");
            return arrivalOpen(id, lookup);
        }
        // Dates link, countered: back to the coordinator, while a coordinator proposal is open. Tested before the
        // accepted case, whose field set includes this one.
        if (onlyFields(write, Set.of("datesStatus"))) {
            if (!"pending-coordinator".equals(value(write, "datesStatus")))
                return refuse("sets a dates status no dates link sets");
            return lookup.openProposalOf(id).map(open -> open != null ? null : "changes the dates with no proposal open");
        }
        // Dates link, accepted: the open proposal's dates, and only those, become the application's.
        if (onlyFields(write, Set.of("startDate", "endDate", "datesStatus", "datesConfirmed"))) {
            if (!"confirmed".equals(value(write, "datesStatus")) || !Boolean.TRUE.equals(value(write, "datesConfirmed")))
                return refuse("changes the dates otherwise than by accepting a proposal");
            return lookup.openProposalOf(id).map(open ->
                open == null ? "changes the dates with no proposal open"
                : sameDate(value(write, "startDate"), open.proposedStartDate())
                  && sameDate(value(write, "endDate"), open.proposedEndDate()) ? null
                : "accepts dates other than the proposal's");
        }
        return refuse("changes application fields only the coordinators change");
    }

    /** A dates link answered: an open proposal accepted or superseded, its link marked used in this statement. */
    private static Future<String> judgeProposalAnswer(WriteRequest write, VolunteerLookup lookup) {
        if (!isProposalAnswer(write))
            return refuse("answers a dates link without marking it used, or with a status no link sets");
        if (!isId(write.targetId()))
            return refuse("names no proposal it can be judged on");
        return lookup.proposal(write.targetId()).map(stored ->
            isOpen(stored) ? null : "answers a dates link that was not sent, has expired or was already used");
    }

    /** The volunteer's counter-proposal: to an open proposal of the same application, superseded in the same batch. */
    private static Future<String> judgeCounterProposal(WriteRequest write, VolunteerLookup lookup) {
        if (!onlyFields(write, Set.of("application", "proposedStartDate", "proposedEndDate", "proposedBy", "reason",
                "status", "version", "parentProposal")))
            return refuse("proposes dates with fields only the coordinators set");
        if (!"volunteer".equals(value(write, "proposedBy")) || !"pending".equals(value(write, "status")))
            return refuse("proposes dates as a coordinator would");
        Object application = value(write, "application"), parent = value(write, "parentProposal");
        if (!isId(application) || !isId(parent))
            return refuse("proposes dates naming no application or proposal it can be judged on");
        return lookup.proposal(parent).compose(stored ->
            !isOpen(stored) || !ChatWritePolicy.sameId(stored.applicationId(), application)
                ? refuse("counters a proposal that was not sent, has expired or was answered, or another application's")
                : lookup.isCountered(parent).map(countered -> Boolean.TRUE.equals(countered) ? "counters a proposal twice" : null));
    }

    /** The arrival page's timeline line: only beside the arrival answer it records. */
    private static Future<String> judgeTimeline(WriteRequest write, VolunteerLookup lookup) {
        if (write.verb() != WriteVerb.INSERT)
            return refuse("changes a timeline entry");
        if (!onlyFields(write, Set.of("application", "date", "event", "status")))
            return refuse("writes a timeline entry with fields the arrival page does not set");
        Object application = value(write, "application");
        if (!isId(application))
            return refuse("names no application it can be judged on");
        return arrivalOpen(application, lookup);
    }

    private static boolean isArrivalAnswer(WriteRequest write) {
        Object status = value(write, "status");
        return ("validated".equals(status) || "cancelled".equals(status)) && isSet(value(write, "arrivalTokenUsedAt"));
    }

    private static boolean isProposalAnswer(WriteRequest write) {
        if (!onlyFields(write, Set.of("status", "actionTokenUsedAt")) || !isSet(value(write, "actionTokenUsedAt")))
            return false;
        Object status = value(write, "status");
        return "accepted".equals(status) || "superseded".equals(status);
    }

    private static Future<String> arrivalOpen(Object applicationId, VolunteerLookup lookup) {
        return lookup.arrival(applicationId).map(stored ->
            stored != null && stored.tokenSent() && notExpired(stored.tokenExpiresAt()) && stored.tokenUsedAt() == null
            && ARRIVAL_ANSWERABLE_STATUSES.contains(stored.status()) ? null
                : "answers an arrival link that was not sent, has expired or was already used");
    }

    /** Only a coordinator's proposal carries a token: pending, sent, unexpired and unused. */
    static boolean isOpen(Proposal stored) {
        return stored != null && "pending".equals(stored.status()) && stored.tokenSent()
               && notExpired(stored.tokenExpiresAt()) && stored.tokenUsedAt() == null;
    }

    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** Two dates as the wire and the database carry them ($LD:2026-11-12, LocalDate, "2026-11-12"), compared by day. */
    static boolean sameDate(Object written, Object stored) {
        String a = isoDate(written), b = isoDate(stored);
        return a != null && a.equals(b);
    }

    private static String isoDate(Object value) {
        if (value == null || value == ProtectedEntityWriteRegistry.UNRESOLVED_VALUE)
            return null;
        Matcher m = ISO_DATE.matcher(String.valueOf(value));
        return m.find() ? m.group() : null;
    }

    /** Whether a link's expiry, as stored (a timestamp for arrival, a date for proposals), has not passed. */
    static boolean notExpired(Object expiresAt) {
        if (expiresAt == null)
            return true; // a link stored without an expiry never expires - the pages do not check one either
        if (expiresAt instanceof LocalDateTime t)
            return t.isAfter(LocalDateTime.now());
        if (expiresAt instanceof LocalDate d)
            return !d.isBefore(LocalDate.now());
        if (expiresAt instanceof Instant i)
            return i.isAfter(Instant.now());
        String day = isoDate(expiresAt); // any other shape: compared by day, and unreadable counts as expired
        return day != null && !LocalDate.parse(day).isBefore(LocalDate.now());
    }

    /** A written value that is really there: not null, and not an expression the parser could not read. */
    private static boolean isSet(Object value) {
        return value != null && value != ProtectedEntityWriteRegistry.UNRESOLVED_VALUE;
    }

    private static boolean writes(WriteRequest write, String field) {
        if (write.writtenFields() == null)
            return false;
        for (String written : write.writtenFields())
            if (field.equals(written))
                return true;
        return false;
    }

    private static boolean onlyFields(WriteRequest write, Set<String> allowed) {
        if (write.writtenFields() == null || write.writtenFields().length == 0)
            return false;
        for (String written : write.writtenFields())
            if (!allowed.contains(written))
                return false;
        return true;
    }

    private static Object value(WriteRequest write, String field) {
        Map<String, Object> values = write.writtenValues();
        return values == null ? null : values.get(field);
    }

    private static boolean isId(Object value) {
        return value != null && value != ProtectedEntityWriteRegistry.UNRESOLVED_VALUE
               && !(value instanceof GeneratedKeyReference);
    }

    private static Future<String> ok() {
        return Future.succeededFuture(null);
    }

    private static Future<String> refuse(String reason) {
        return Future.succeededFuture(reason);
    }
}
