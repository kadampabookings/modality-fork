package one.modality.crm.server.person;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.UpdateStore;
import one.modality.base.shared.entities.VolunteeringDateProposal;
import one.modality.base.shared.entities.VolunteeringTimeline;
import one.modality.crm.server.authn.gateway.shared.CallerRateLimit;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * The volunteer's answers to the two links the coordinators email — confirm or cancel an arrival, accept or counter a
 * proposal of dates — taken by the TOKEN in the link, resolved here to the row it answers.
 *
 * <p><b>Why on the server (2026-11-12).</b> The pages used to test the token in a read and then write the rows by id
 * through the generic submit, so the write did not carry the token and nothing could tell the volunteer from anyone
 * who knew an id: any application could be cancelled, its dates changed, its proposal answered.
 * VolunteerWritePolicy narrowed that; this closes it. The token is the volunteer's only proof and the only thing the
 * caller supplies: the server finds the row, checks the link was sent, is unexpired and unused and the row is in a
 * state the link answers, writes, and marks the link used. The client never names a row.
 *
 * <p><b>No sign-in, on purpose.</b> The links are opened from an email, usually signed out; the token is the
 * authorisation, as it is for a password-reset link. The arrival token is a {@code crypto.randomUUID()} the back
 * office mints (use-send-arrival-reminder.ts). The dates token ({@code VolunteeringDateProposal.actionToken}) is
 * minted by nothing at all today (2026-11-12): no code inserts a proposal and staging holds none, so the dates link
 * cannot be reached yet. Whatever proposes dates later must mint it the same way - unguessable, with an expiry.
 * Calls are metered per caller address all the same ({@link #TOKEN_LOOKUP}), so a weak token cannot be guessed at
 * speed.
 *
 * <p>What the volunteer is told never says why a token failed — wrong, used, expired or answering a row in the wrong
 * state all read the same ({@link #LINK_UNUSABLE}), so the endpoint is no oracle for which tokens exist.
 *
 * <p>Notifications stay with the page: it composes the coordinators' and the volunteer's emails after this answers,
 * under MailWritePolicy.
 *
 * @author Claude Code
 */
final class VolunteerLinkAnswers {

    private VolunteerLinkAnswers() {
    }

    /** Told to the caller for every token that cannot be answered, whatever the reason. */
    static final String LINK_UNUSABLE = "[VolunteerLinkUnusable] This link can no longer be used";
    /** Told to the caller for an answer the link does not offer (bad dates, unknown choice). */
    static final String BAD_ANSWER = "[VolunteerLinkBadAnswer] This answer cannot be accepted";
    /** Told to a caller over the rate limit. */
    static final String TOO_MANY = "[VolunteerLinkTooMany] Too many attempts, please try again later";

    /** 20 answers per caller address per 10 minutes, 500 overall - a volunteer answers once. */
    static final CallerRateLimit TOKEN_LOOKUP = new CallerRateLimit("volunteer link", Duration.ofMinutes(10), 20, 500, Clock.systemUTC());

    /** The application states an arrival link answers (as the page has always checked, matching the PHP service). */
    static final Set<String> ARRIVAL_ANSWERABLE_STATUSES = Set.of("pending-validation", "no-response", "validated", "assigned");

    /** Longest free text kept from a volunteer's answer (a reason, a comment). */
    static final int MAX_TEXT = 2000;

    /** A counter-proposal's stay may not exceed this - generous, and it bounds what a stranger could write. */
    static final int MAX_STAY_DAYS = 400;

    static final String LOG_PREFIX = "🙋 Volunteer link: ";

    // ---------------------------------------------------------------------------------------------- the decisions

    /** Whether a link can still be answered: its token sent, its expiry not passed, its use not recorded. */
    static boolean linkOpen(Object token, Object expiresAt, Object usedAt, LocalDateTime now) {
        return token != null && usedAt == null && notExpired(expiresAt, now);
    }

    static boolean notExpired(Object expiresAt, LocalDateTime now) {
        if (expiresAt == null)
            return true; // stored without an expiry: the page never checked one either
        if (expiresAt instanceof LocalDateTime t)
            return t.isAfter(now);
        if (expiresAt instanceof LocalDate d)
            return !d.isBefore(now.toLocalDate());
        return false; // an expiry this code cannot read is treated as passed
    }

    /** The application status an arrival answer writes: "confirm" → validated, "cancel" → cancelled, else null. */
    static String arrivalStatusFor(Object choice) {
        return "confirm".equals(choice) ? "validated" : "cancel".equals(choice) ? "cancelled" : null;
    }

    /**
     * A counter-proposal's dates, or null when they are not two ISO days in order, from today, within a sane stay.
     * "Today" is the server's, so a day of slack lets a volunteer ahead of it pick their own today.
     */
    static LocalDate[] counterDates(Object start, Object end, LocalDate today) {
        LocalDate s = isoDay(start), e = isoDay(end);
        if (s == null || e == null || e.isBefore(s) || s.isBefore(today.minusDays(1)) || s.plusDays(MAX_STAY_DAYS).isBefore(e))
            return null;
        return new LocalDate[] { s, e };
    }

    static LocalDate isoDay(Object value) {
        if (!(value instanceof String text) || text.length() != 10)
            return null;
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Free text as kept: trimmed, at most {@link #MAX_TEXT} characters, null when empty. */
    static String text(Object value) {
        if (value == null)
            return null;
        String t = String.valueOf(value).trim();
        if (t.isEmpty())
            return null;
        return t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) : t;
    }

    static String token(Object value) {
        return value instanceof String t && !t.isBlank() && t.length() <= 128 ? t : null;
    }

    // ---------------------------------------------------------------------------------------------- the answers

    /**
     * Confirms or cancels an arrival. Answers the application's id, which the page needs for the coordinators' email.
     */
    static Future<Object> answerArrival(Object rawToken, Object choice, Object rawReason, Object callerUserId,
                                        String clientAddress) {
        String token = token(rawToken), status = arrivalStatusFor(choice);
        if (token == null || status == null)
            return Future.failedFuture(BAD_ANSWER);
        if (!TOKEN_LOOKUP.tryAcquire(clientAddress))
            return Future.failedFuture(TOO_MANY);
        String reason = text(rawReason);
        EntityStore store = store();
        return ServerWrite.asServer(() -> store.<Entity>executeQuery(
                "select status, arrivalConfirmationToken, arrivalTokenExpiresAt, arrivalTokenUsedAt"
                + " from VolunteeringApplication where arrivalConfirmationToken=$1", token))
            .compose(rows -> {
                Entity application = rows.isEmpty() ? null : rows.get(0);
                LocalDateTime now = LocalDateTime.now();
                if (application == null
                    || !linkOpen(application.getFieldValue("arrivalConfirmationToken"),
                        application.getFieldValue("arrivalTokenExpiresAt"), application.getFieldValue("arrivalTokenUsedAt"), now)
                    || !ARRIVAL_ANSWERABLE_STATUSES.contains(application.getStringFieldValue("status")))
                    return Future.failedFuture(LINK_UNUSABLE);
                return ServerWrite.asServerActingFor(callerUserId, () -> {
                    UpdateStore updateStore = UpdateStore.createAbove(store);
                    Entity update = updateStore.updateEntity(application);
                    update.setFieldValue("status", status);
                    update.setFieldValue("arrivalTokenUsedAt", now);
                    VolunteeringTimeline line = updateStore.insertEntity(VolunteeringTimeline.class);
                    line.setForeignField("application", application);
                    line.setFieldValue("date", now);
                    line.setFieldValue("status", status);
                    line.setFieldValue("event", "validated".equals(status)
                        ? "Arrival: Volunteer confirmed arrival via email link"
                        : reason != null ? "Arrival: Volunteer cancelled via email link — \"" + reason + "\""
                        : "Arrival: Volunteer cancelled via email link");
                    return updateStore.submitChanges().map(ignored -> {
                        Object id = Entities.getPrimaryKey(application);
                        Console.log(LOG_PREFIX + "arrival " + status + " on application " + id);
                        return id;
                    });
                });
            });
    }

    /**
     * Accepts a proposal of dates, or counters it with the volunteer's own. Answers the application's id.
     *
     * <p>On accept, the dates written are the PROPOSAL's, read here - the caller sends none. On counter, the
     * proposal is superseded and the volunteer's dates proposed back as a new pending proposal, which carries no
     * token: it is the coordinators' to answer, from the back office.
     */
    static Future<Object> answerDates(Object rawToken, Object choice, Object start, Object end, Object rawComment,
                                      Object callerUserId, String clientAddress) {
        String token = token(rawToken);
        boolean accept = "accept".equals(choice), counter = "counter".equals(choice);
        LocalDate today = LocalDate.now();
        LocalDate[] counterDates = counter ? counterDates(start, end, today) : null;
        if (token == null || !accept && !counter || counter && counterDates == null)
            return Future.failedFuture(BAD_ANSWER);
        if (!TOKEN_LOOKUP.tryAcquire(clientAddress))
            return Future.failedFuture(TOO_MANY);
        String comment = text(rawComment);
        EntityStore store = store();
        return ServerWrite.asServer(() -> store.<VolunteeringDateProposal>executeQuery(
                "select application, status, version, actionToken, actionTokenExpiresAt, actionTokenUsedAt,"
                + " proposedStartDate, proposedEndDate from VolunteeringDateProposal where actionToken=$1", token))
            .compose(rows -> {
                VolunteeringDateProposal proposal = rows.isEmpty() ? null : rows.get(0);
                LocalDateTime now = LocalDateTime.now();
                Object applicationId = proposal == null ? null : Entities.getPrimaryKey(proposal.getForeignEntityId("application"));
                if (proposal == null || applicationId == null
                    || !"pending".equals(proposal.getStringFieldValue("status"))
                    || !linkOpen(proposal.getFieldValue("actionToken"), proposal.getFieldValue("actionTokenExpiresAt"),
                        proposal.getFieldValue("actionTokenUsedAt"), now))
                    return Future.failedFuture(LINK_UNUSABLE);
                return ServerWrite.asServerActingFor(callerUserId, () -> {
                    UpdateStore updateStore = UpdateStore.createAbove(store);
                    Entity answered = updateStore.updateEntity(proposal);
                    answered.setFieldValue("status", accept ? "accepted" : "superseded");
                    answered.setFieldValue("actionTokenUsedAt", today);
                    Entity application = updateStore.updateEntity(proposal.getForeignEntityId("application"));
                    if (accept) {
                        application.setFieldValue("startDate", proposal.getFieldValue("proposedStartDate"));
                        application.setFieldValue("endDate", proposal.getFieldValue("proposedEndDate"));
                        application.setFieldValue("datesStatus", "confirmed");
                        application.setFieldValue("datesConfirmed", true);
                    } else {
                        VolunteeringDateProposal reply = updateStore.insertEntity(VolunteeringDateProposal.class);
                        reply.setForeignField("application", applicationId);
                        reply.setForeignField("parentProposal", proposal);
                        reply.setFieldValue("proposedStartDate", counterDates[0]);
                        reply.setFieldValue("proposedEndDate", counterDates[1]);
                        reply.setFieldValue("proposedBy", "volunteer");
                        reply.setFieldValue("reason", comment);
                        reply.setFieldValue("status", "pending");
                        Object version = proposal.getFieldValue("version");
                        reply.setFieldValue("version", (version instanceof Number n ? n.intValue() : 1) + 1);
                        application.setFieldValue("datesStatus", "pending-coordinator");
                    }
                    return updateStore.submitChanges().map(ignored -> {
                        Console.log(LOG_PREFIX + "dates " + (accept ? "accepted" : "countered") + " on application " + applicationId);
                        return applicationId;
                    });
                });
            });
    }

    private static EntityStore store() {
        return EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
    }
}
