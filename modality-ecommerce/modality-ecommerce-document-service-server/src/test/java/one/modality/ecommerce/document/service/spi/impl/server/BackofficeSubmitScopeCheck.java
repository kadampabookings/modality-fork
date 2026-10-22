package one.modality.ecommerce.document.service.spi.impl.server;

import dev.webfx.platform.async.Future;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Check for {@link BackofficeSubmitScope}: a back-office submit may land only where the caller holds SubmitBookings.
 *
 * <p>The world is faked: organization 1 owns event 10, organization 2 owns event 20. Booking 100 (line 110,
 * attendance 120) is at event 10; booking 200 (line 210, attendance 220) is at event 20; booking 300 has no owner.
 * The caller holds SubmitBookings at organization 1 / event 10 and nowhere else. Everything else is the production
 * decision.
 *
 * <p>No test framework: runs from main() and exits non-zero on failure, following DocumentSubmitAuthorizationCheck.
 */
public class BackofficeSubmitScopeCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    static final Map<String, Object> LINE_DOCUMENT = Map.of("110", "100", "210", "200");
    static final Map<String, Object> ATTENDANCE_LINE = Map.of("120", "110", "220", "210", "230", "999");
    static final Map<String, Object[]> DOCUMENT_PLACE = Map.of(
        "100", new Object[] { "100", 1, 10 }, "200", new Object[] { "200", 2, 20 }, "300", new Object[] { "300", null, null },
        "400", new Object[] { "400", 1, 20 }, "500", new Object[] { "500", null, 10 });
    static final Map<String, Object> EVENT_ORGANIZATION = Map.of("10", 1, "20", 2);

    static final BackofficeSubmitScope.Lookup LOOKUP = new BackofficeSubmitScope.Lookup() {
        @Override public Future<List<Object[]>> documentsOfLines(Set<Object> lineIds) { return pairs(lineIds, LINE_DOCUMENT); }
        @Override public Future<List<Object[]>> linesOfAttendances(Set<Object> ids) { return pairs(ids, ATTENDANCE_LINE); }
        @Override public Future<List<Object[]>> placesOfDocuments(Set<Object> ids) {
            List<Object[]> rows = new ArrayList<>();
            for (Object id : ids)
                if (DOCUMENT_PLACE.containsKey(String.valueOf(id)))
                    rows.add(DOCUMENT_PLACE.get(String.valueOf(id)));
            return Future.succeededFuture(rows);
        }
        @Override public Future<Object> eventOrganization(Object eventId) {
            return Future.succeededFuture(EVENT_ORGANIZATION.get(String.valueOf(eventId)));
        }
        @Override public Future<Boolean> holds(BackofficeSubmitScope.Place place) {
            return Future.succeededFuture("1".equals(place.organizationId()) && "10".equals(place.eventId()));
        }
    };

    static Future<List<Object[]>> pairs(Set<Object> ids, Map<String, Object> parents) {
        List<Object[]> rows = new ArrayList<>();
        for (Object id : ids)
            if (parents.containsKey(String.valueOf(id)))
                rows.add(new Object[] { id, parents.get(String.valueOf(id)) });
        return Future.succeededFuture(rows);
    }

    static Set<Object> ids(Object... ids) {
        Set<Object> set = new LinkedHashSet<>();
        for (Object id : ids)
            set.add(id);
        return set;
    }

    static DocumentSubmitAuthorization.Targets targets(Set<Object> documents, Set<Object> lines, Set<Object> attendances) {
        return new DocumentSubmitAuthorization.Targets(documents, lines, attendances);
    }

    static final DocumentSubmitAuthorization.Targets NONE = targets(ids(), ids(), ids());

    /** The refusal reason, synchronously (the fake lookup answers at once). */
    static String refusal(DocumentSubmitAuthorization.Targets targets, boolean newDocument, Object event, Object organization) {
        return refusal(targets, newDocument ? List.of(new BackofficeSubmitScope.Owner(event, organization)) : List.of(), false);
    }

    static String refusal(DocumentSubmitAuthorization.Targets targets, List<BackofficeSubmitScope.Owner> newBookings, boolean payments) {
        Future<String> answer = BackofficeSubmitScope.refusal(targets, newBookings, payments, LOOKUP);
        if (!answer.isComplete())
            return "<did not complete>";
        return answer.failed() ? "<failed: " + answer.cause() + ">" : answer.result();
    }

    static boolean allowed(DocumentSubmitAuthorization.Targets targets, boolean newDocument, Object event, Object organization) {
        return refusal(targets, newDocument, event, organization) == null;
    }

    public static void main(String[] args) {
        System.out.println("BackofficeSubmitScopeCheck");

        // --- existing bookings: placed where they are stored ---
        check("a booking where the caller holds SubmitBookings is allowed", allowed(targets(ids("100"), ids(), ids()), false, null, null));
        check("another organization's booking is refused", !allowed(targets(ids("200"), ids(), ids()), false, null, null));
        check("reaching it through one of its lines is refused", !allowed(targets(ids(), ids("210"), ids()), false, null, null));
        check("or through one of its attendances", !allowed(targets(ids(), ids(), ids("220")), false, null, null));
        check("own line and attendance pass", allowed(targets(ids(), ids("110"), ids("120")), false, null, null));
        check("one foreign row among the caller's own refuses the whole submit",
            !allowed(targets(ids("100"), ids("210"), ids()), false, null, null));
        String reason = refusal(targets(ids("200"), ids(), ids()), false, null, null);
        check("the census line says where: " + reason, reason != null && reason.contains("organization 2 event 20"));

        // --- what cannot be placed refuses ---
        check("a booking that does not exist is refused", !allowed(targets(ids("999"), ids(), ids()), false, null, null));
        // Asserted on the REASON: a grant lookup that answers no for an empty place would refuse it anyway, and
        // the guard is what stops an empty place reaching a lookup that might not.
        String unowned = refusal(targets(ids("300"), ids(), ids()), false, null, null);
        check("a booking with no organization and no event is refused as unplaceable: " + unowned,
            unowned != null && unowned.contains("could not be placed"));
        check("a line that does not exist is refused", !allowed(targets(ids(), ids("998"), ids()), false, null, null));
        check("an attendance whose line does not exist is refused", !allowed(targets(ids(), ids(), ids("230")), false, null, null));

        // --- a new booking: placed where it will be created ---
        check("a new booking at the caller's event is allowed", allowed(NONE, true, "10", 1));
        check("and without an organization written (the event decides)", allowed(NONE, true, "10", null));
        check("a new booking at another organization's event is refused", !allowed(NONE, true, "20", 2));
        check("naming one's own organization does not vouch for another's event", !allowed(NONE, true, "20", 1));
        check("an inconsistent pair is judged on both counts", !allowed(NONE, true, "10", 2));
        check("a new booking at an event that does not exist is refused", !allowed(NONE, true, "99", 1));
        check("a new booking with neither event nor organization is refused", !allowed(NONE, true, null, null));
        check("a submit naming no booking at all has nothing to place", allowed(NONE, false, null, null));

        // --- every new booking, not just the first (review finding 2) ---
        check("a second new booking at another organization's event is refused",
            refusal(NONE, List.of(new BackofficeSubmitScope.Owner("10", 1), new BackofficeSubmitScope.Owner("20", 2)), false) != null);
        check("two new bookings at the caller's event pass",
            refusal(NONE, List.of(new BackofficeSubmitScope.Owner("10", 1), new BackofficeSubmitScope.Owner("10", null)), false) == null);
        check("a change to one's own booking does not carry a new booking elsewhere",
            refusal(targets(ids("100"), ids(), ids()), List.of(new BackofficeSubmitScope.Owner("20", 2)), false) != null);

        // --- payments are the payment service's (review finding 3) ---
        String payment = refusal(targets(ids("100"), ids(), ids()), List.of(), true);
        check("a payment event is refused even on one's own booking: " + payment,
            payment != null && payment.contains("payment"));

        // --- existing bookings are placed like new ones: by their event, and their organization when it differs ---
        check("one's own centre's booking at another centre's event is refused",
            !allowed(targets(ids("400"), ids(), ids()), false, null, null));
        check("a booking with an event but no organization is placed by its event",
            allowed(targets(ids("500"), ids(), ids()), false, null, null));

        // --- the wiring ---
        String provider = readSource("modality-fork/modality-ecommerce/modality-ecommerce-document-service-server/src/main/java/"
                                     + "one/modality/ecommerce/document/service/spi/impl/server/ServerDocumentServiceProvider.java");
        check("the provider source was found", !provider.isEmpty());
        check("a verified back-office CLIENT submit is scoped",
            provider.contains("boolean backofficeClient = ThreadLocalStateHolder.isClientOrigin() && request.backoffice();")
            && provider.contains("backofficeClient ? authorizeBackofficeScope(request, callerState)"));
        check("the caller's state is captured before any async step",
            provider.contains("Object callerState = ThreadLocalStateHolder.getThreadLocalState();"));
        check("the service's own writes run as the server, acting for the caller",
            provider.contains("StateAccessor.setUserId(StateAccessor.createEmptyState(), request.userId()),")
            && provider.contains("() -> DocumentSubmitController.submitDocumentChanges(request)));"));
        check("every AddDocumentEvent is placed, each at its own event",
            provider.contains("if (e instanceof AddDocumentEvent add) {")
            && provider.contains("newBookings.add(new BackofficeSubmitScope.Owner(add.getEventPrimaryKey(),"));
        check("payment events are detected", provider.contains("paymentEvents |= e instanceof AbstractMoneyTransferEvent;"));
        String scope = readSource("modality-fork/modality-ecommerce/modality-ecommerce-document-service-server/src/main/java/"
                                  + "one/modality/ecommerce/document/service/spi/impl/server/BackofficeSubmitScope.java");
        // Review finding 1: an Object[] passed alone is spread as the varargs, binding $1 to the first id only.
        check("the id lists are bound as ONE parameter, not spread as varargs",
            scope.contains("executeQuery(dql, (Object) keysOf(ids))")
            && scope.contains("where id in $1\", (Object) keysOf(documentIds))"));
        check("the census line is distinct from the front-office one",
            provider.contains("\" a back-office booking submit by \""));

        String migration = readSource("modality-fork/modality-base/modality-base-server-dbmigration-plugin/src/main/resources/"
                                      + "one/modality/base/server/services/dbmigration/scripts/V0138__submit_bookings_operation.sql");
        check("V0138 seeds SubmitBookings", migration.contains("SELECT 'SubmitBookings',"));
        check("and grants it to the roles reaching the three submitting pages",
            migration.contains("('/registration'), ('/education-programs'), ('/volunteering')"));
        String index = readSource("modality-fork/modality-base/modality-base-server-dbmigration-plugin/src/main/resources/"
                                  + "one/modality/base/server/services/dbmigration/scripts/index.txt");
        check("V0138 is indexed", index.contains("V0138__submit_bookings_operation.sql"));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }

    /** A file of the aggregate, found by walking up from the working directory; empty when absent. */
    static String readSource(String relative) {
        for (Path at = Path.of(System.getProperty("user.dir")).toAbsolutePath(); at != null; at = at.getParent()) {
            Path candidate = at.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate);
                } catch (Exception e) {
                    return "";
                }
            }
        }
        return "";
    }
}
