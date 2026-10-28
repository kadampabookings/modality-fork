package one.modality.ecommerce.document.service.spi.impl.server;

import dev.webfx.platform.async.Future;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Check for {@link DocumentSubmitAuthorization}: who may submit which booking changes.
 *
 * <p>Until 2026-10-19 the answer was "anybody, anything": the submit replayed whatever events arrived against
 * whatever booking ids they named. The cases that matter are the ones a front-office caller could send with a
 * modified client: another member's booking by id, a line of somebody else's booking named beside their own
 * booking id, a 100% discount, a payment.
 *
 * <p>The ownership lookup is faked — "member 7 owns booking 100, its line 110 and attendance 120" — and everything
 * else is the production decision.
 *
 * <p>No test framework: runs from main() and exits non-zero on failure, following VerifiedBackofficeCheck.
 */
public class DocumentSubmitAuthorizationCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    /** What the member may access: their booking 100, its line 110, its attendance 120. */
    static final Map<String, Set<Object>> OWNED = Map.of(
        "Document", Set.of("100"), "DocumentLine", Set.of("110"), "Attendance", Set.of("120"));

    static final DocumentSubmitAuthorization.OwnershipLookup LOOKUP = (entityName, ids) -> {
        Set<Object> accessible = new LinkedHashSet<>();
        for (Object id : ids)
            if (OWNED.getOrDefault(entityName, Set.of()).contains(String.valueOf(id)))
                accessible.add(String.valueOf(id));
        return Future.succeededFuture(accessible);
    };

    static DocumentSubmitAuthorization.Targets targets(List<String> documents, List<String> lines, List<String> attendances) {
        return new DocumentSubmitAuthorization.Targets(new LinkedHashSet<>(documents), new LinkedHashSet<>(lines),
            new LinkedHashSet<>(attendances));
    }

    static final DocumentSubmitAuthorization.Targets NONE = targets(List.of(), List.of(), List.of());

    /** A member's submit (an account, not the back office). */
    static String member(List<String> kinds, DocumentSubmitAuthorization.Targets targets) {
        return DocumentSubmitAuthorization.refusal(kinds, targets, false, false, LOOKUP).result();
    }

    static String backoffice(List<String> kinds, DocumentSubmitAuthorization.Targets targets) {
        return DocumentSubmitAuthorization.refusal(kinds, targets, true, false, LOOKUP).result();
    }

    static String anonymous(List<String> kinds, DocumentSubmitAuthorization.Targets targets) {
        return DocumentSubmitAuthorization.refusal(kinds, targets, false, true, LOOKUP).result();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("DocumentSubmitAuthorizationCheck");

        System.out.println("\nWhat the front office books, it may still book:");
        check("a member creating a new booking (no existing rows named)",
            member(List.of("AddDocumentEvent", "AddDocumentLineEvent", "AddAttendancesEvent"), NONE) == null);
        check("a guest or anonymous caller creating a new booking — how a guest books",
            anonymous(List.of("AddDocumentEvent", "AddDocumentLineEvent"), NONE) == null);
        check("a member modifying their own booking, its line and its attendance",
            member(List.of("AddDocumentLineEvent", "RemoveAttendancesEvent", "AddRequestEvent"),
                targets(List.of("100"), List.of("110"), List.of("120"))) == null);
        check("a member's edit that undoes a line it added (RemoveDocumentLineEvent — production's history has 44)",
            member(List.of("RemoveDocumentLineEvent"), targets(List.of("100"), List.of("110"), List.of())) == null);
        check("but not on somebody else's line", member(List.of("RemoveDocumentLineEvent"),
            targets(List.of("100"), List.of("210"), List.of())) != null);
        check("a member cancelling their own booking", member(List.of("CancelDocumentEvent"), targets(List.of("100"), List.of(), List.of())) == null);
        check("every event of the front office's flows is allowed it",
            DocumentSubmitAuthorization.forbiddenKinds(List.copyOf(DocumentSubmitAuthorization.FRONT_OFFICE_EVENTS), false).isEmpty());

        System.out.println("\nSomebody else's booking — refused:");
        check("cancelling another member's booking by its id", member(List.of("CancelDocumentEvent"),
            targets(List.of("200"), List.of(), List.of())) != null);
        check("a line of another booking, named beside the member's own booking id", member(List.of("RemoveAttendancesEvent"),
            targets(List.of("100"), List.of("210"), List.of())) != null);
        check("an attendance of another booking", member(List.of("RemoveAttendancesEvent"),
            targets(List.of("100"), List.of("110"), List.of("220"))) != null);
        check("an anonymous caller naming any existing booking", anonymous(List.of("CancelDocumentEvent"),
            targets(List.of("100"), List.of(), List.of())) != null);

        System.out.println("\nWhat only the back office does — refused to the front office, on its OWN booking too:");
        for (String kind : List.of("PriceDocumentLineEvent", "AddMoneyTransferEvent", "UpdateMoneyTransferEvent",
                                   "ConfirmDocumentEvent", "MarkDocumentAsArrivedEvent", "MarkDocumentAsVerifiedEvent",
                                   "AllocateDocumentLineEvent", "CancelOtherMultipleBookingsEvent", "LinkMateToOwnerDocumentLineEvent"))
            check(kind + " on the member's own booking", member(List.of(kind), targets(List.of("100"), List.of(), List.of())) != null);
        check("a 100% discount slipped in among ordinary booking events", member(
            List.of("AddDocumentLineEvent", "PriceDocumentLineEvent"), targets(List.of("100"), List.of(), List.of())) != null);
        check("a payment on a NEW booking (no existing rows to own)", member(List.of("AddDocumentEvent", "AddMoneyTransferEvent"), NONE) != null);

        System.out.println("\nThe back office and the server:");
        check("the back office may submit any kind on any booking (which staff may do what is the next step)",
            backoffice(List.of("PriceDocumentLineEvent", "ConfirmDocumentEvent"), targets(List.of("200"), List.of("210"), List.of())) == null);

        System.out.println("\nAs shipped:");
        check("the rule ships ENFORCING (since 2026-10-28, after a clean census)", DocumentSubmitAuthorization.ENFORCING);
        check("the refusal told the caller names neither booking nor event",
            !DocumentSubmitAuthorization.REFUSED.matches(".*\\d.*") && !DocumentSubmitAuthorization.REFUSED.contains("Event"));
        // Asserted on the call (its own javadoc names the class, so an absence test would be fooled). A reformat
        // that splits the call makes this FAIL falsely, never pass falsely.
        String provider = Files.readString(Path.of("src/main/java/one/modality/ecommerce/document/service/spi/impl/server/ServerDocumentServiceProvider.java"));
        check("submitDocumentChanges asks it before anything is queued",
            provider.contains("return authorizeSubmit(request, access)"));
        check("and a refusal fails the submit while enforcing",
            provider.contains("? Future.failedFuture(DocumentSubmitAuthorization.REFUSED)"));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}
