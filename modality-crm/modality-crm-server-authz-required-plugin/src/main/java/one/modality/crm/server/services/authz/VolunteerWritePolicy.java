package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteRequest;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteVerb;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

import java.util.Set;

/**
 * Outside the back office, the volunteer tables take one write: a new application from the public form. Everything
 * else is the coordinators', or goes through the link endpoints.
 *
 * <p><b>The hole (2026-11-12).</b> The volunteer pages write through the generic submit, and they are public:
 * {@code /book-volunteer/:orgId} (the application form), {@code /volunteer/arrival/:token} and
 * {@code /volunteer/dates/:token} (answers to the coordinators' emails), all usable signed out. Nothing asked what
 * they wrote, so anyone could rewrite any application by id (ids are sequential) — including its medical,
 * criminal-conviction, passport and immigration answers, special-category data — or delete one, which the database
 * cascades to the back office's assignment and communication rows.
 *
 * <p><b>The answers moved to the server</b> (AnswerVolunteerArrivalEndpoint, AnswerVolunteerDatesEndpoint in the
 * person plugin, same day): they take the emailed token, find the row from it, and write as the server, which this
 * guard never sees. The first version of this rule tried to recognise a legitimate answer from the write alone and
 * could not — the write did not carry the token, so a stranger knowing an id passed as well as the volunteer (and a
 * security review found two more ways through). With the endpoints there is nothing left to recognise: a client
 * write that updates an application or a proposal, inserts a proposal or a timeline line, or deletes anything is
 * not a page of ours. Until the new front office is everywhere, an old cached bundle still sends the answers that
 * way — this observes them, and is switched on once they stop.
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

    @Override
    public Future<String> refusalReason(WriteRequest write) {
        // Read on the caller's thread: the guard asks every row rule synchronously.
        Object state = ThreadLocalStateHolder.getThreadLocalState();
        String reason = judge(write, Boolean.TRUE.equals(StateAccessor.getVerifiedBackoffice(state)));
        if (reason == null)
            return Future.succeededFuture(null);
        // Entity, verb and reason only: no row id, no value - these rows hold special-category data.
        Console.log("🛡 " + (ENFORCING ? "REFUSED" : "WOULD REFUSE") + " a volunteer write: "
                    + write.verb() + " " + write.entityName() + " by " + StateAccessor.getUserId(state) + " — " + reason);
        return Future.succeededFuture(ENFORCING ? REFUSED : null);
    }

    /** The decision: why this write may not run, or null when it may. */
    static String judge(WriteRequest write, boolean backoffice) {
        if (write == null || !VOLUNTEER_ENTITIES.contains(write.entityName()) || backoffice)
            return null; // a null write is refused already, by BackofficeOnlyWritePolicy
        if (!"VolunteeringApplication".equals(write.entityName()) || write.verb() != WriteVerb.INSERT)
            return write.verb() == WriteVerb.DELETE ? "deletes a volunteer record"
                : "answers a volunteer link outside its endpoint, or changes what the coordinators keep";
        if (!onlyFields(write))
            return "applies with fields only the coordinators set";
        Object status = write.writtenValues() == null ? null : write.writtenValues().get("status");
        if (status != null && !"new".equals(status))
            return "applies already past 'new'";
        return null;
    }

    private static boolean onlyFields(WriteRequest write) {
        if (write.writtenFields() == null || write.writtenFields().length == 0)
            return false;
        for (String field : write.writtenFields())
            if (!APPLICATION_FORM_FIELDS.contains(field))
                return false;
        return true;
    }
}
