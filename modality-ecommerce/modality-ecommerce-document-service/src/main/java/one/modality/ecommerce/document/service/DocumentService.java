package one.modality.ecommerce.document.service;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.service.SingleServiceProvider;
import one.modality.base.shared.entities.Document;
import one.modality.base.shared.entities.Event;
import one.modality.ecommerce.document.service.spi.DocumentServiceProvider;
import one.modality.ecommerce.policy.service.LoadPolicyArgument;
import one.modality.ecommerce.policy.service.PolicyAggregate;
import one.modality.ecommerce.policy.service.PolicyService;

import java.util.ServiceLoader;

/**
 * @author Bruno Salmon
 */
public final class DocumentService {

    private static DocumentServiceProvider getProvider() {
        return SingleServiceProvider.getProvider(DocumentServiceProvider.class, () -> ServiceLoader.load(DocumentServiceProvider.class));
    }

    public static Future<DocumentAggregate> loadDocument(LoadDocumentArgument argument) {
        return getProvider().loadDocument(argument);
    }

    public static Future<DocumentAggregate[]> loadDocuments(LoadDocumentArgument argument) {
        return getProvider().loadDocuments(argument);
    }

    public static Future<SubmitDocumentChangesResult> submitDocumentChanges(SubmitDocumentChangesArgument argument) {
        return getProvider().submitDocumentChanges(argument);
    }

    public static Future<Boolean> leaveEventQueue(Object queueToken) {
        return getProvider().leaveEventQueue(queueToken);
    }

    public static Future<SubmitDocumentChangesResult> fetchEventQueueResult(Object queueToken) {
        return getProvider().fetchEventQueueResult(queueToken);
    }

    public static Future<String> mintMateInviteToken(Object documentId) {
        return getProvider().mintMateInviteToken(documentId);
    }

    /**
     * Invites the roommate named in one slot of the caller's own room to take the bed held for them.
     *
     * <p>Mints, writes and records in one server-side step, so the invitation link is never handed to the
     * browser: the client says WHICH slot and where to write, and the server reads from the booking who
     * that is, builds the link on its own origin and sends it. The alternative — mint, hand over, ask the
     * server to post it — would mean a token on the wire for a message the sender never reads.
     *
     * @param mateSlot which of the room line's seven roommate name slots, 1-based
     * @param email    where to write, as the booker typed it
     * @param lang     the language to write in; English when there is no better guess
     */
    public static Future<Void> sendMateInvitation(Object documentId, int mateSlot, String email, String lang) {
        return getProvider().sendMateInvitation(documentId, mateSlot, email, lang);
    }

    /**
     * The invitations sent for this booking's room, as a JSON array — what the cart shows on a visit
     * later than the one that sent them.
     *
     * <p>Carries no token, no hash and no address: a slot number, a date, and whether the mailer gave up.
     * The cart already holds the names, in the room line it is displaying.
     */
    public static Future<String> listMateInvitations(Object documentId) {
        return getProvider().listMateInvitations(documentId);
    }

    public static Future<String> resolveMateInvite(String token, Object eventId) {
        return getProvider().resolveMateInvite(token, eventId);
    }

    public static Future<String> describeMateInviteRoom(String token, Object eventId) {
        return getProvider().describeMateInviteRoom(token, eventId);
    }

    // Additional top-level utility methods to load a document (not directly implemented by the provider and not directly serialized)

    public static Future<DocumentAggregate> loadDocument(Object event, Object userPerson) {
        return loadDocument(LoadDocumentArgument.ofPerson(userPerson, event));
    }


    // Additional top-level utility methods to load document and policy (not directly implemented by the provider and not directly serialized)

    public static Future<PolicyAndDocumentAggregates> loadDocumentWithPolicy(Document document) {
        return loadDocumentWithPolicyAndHistory(document, null);
    }

    public static Future<PolicyAndDocumentAggregates> loadDocumentWithPolicyAndWholeHistory(Document document) {
        return loadDocumentWithPolicyAndHistory(document, Integer.MAX_VALUE);
    }

    private static Future<PolicyAndDocumentAggregates> loadDocumentWithPolicyAndHistory(Document document, Object history) {
        return loadPolicyAndDocument(
            document.getEvent(),
            LoadDocumentArgument.ofDocumentFromHistory(document, history));
    }

    public static Future<PolicyAndDocumentAggregates> loadPolicyAndDocument(Event event, Object userPerson) {
        return loadPolicyAndDocument(event, userPerson == null ? null : LoadDocumentArgument.ofPerson(userPerson, event));
    }

    private static Future<PolicyAndDocumentAggregates> loadPolicyAndDocument(Event event, LoadDocumentArgument loadDocumentArgument) {
        return Future.all(
            // 0) We load the policy aggregate for this event
            PolicyService.loadPolicy(new LoadPolicyArgument(event)),
            // 1) And eventually the already existing booking of the user (i.e., his last booking for this event)
            loadDocumentArgument == null ? Future.succeededFuture(null) : // unless the user is not provided
                loadDocument(loadDocumentArgument)
        ).compose(compositeFuture -> {
            PolicyAggregate policyAggregate = compositeFuture.resultAt(0); // 0 = policy aggregate (never null)
            policyAggregate.rebuildEntities(event); // we rebuild the entities
            DocumentAggregate documentAggregate = compositeFuture.resultAt(1); // 1 = document aggregate (may be null)
            if (documentAggregate != null) {
                documentAggregate.setPolicyAggregate(policyAggregate); // rebuild the entities at the same time
            }
            // The reason why we return a PolicyAndDocumentAggregates instance (instead of just DocumentAggregate which
            // has a getPolicy() method) is because documentAggregate may be null (either because userPersonPrimaryKey
            // was null, or because this person hasn't booked that event yet).
            return Future.succeededFuture(new PolicyAndDocumentAggregates(policyAggregate, documentAggregate));
        });
    }

    // Note: this method doesn't rebuild the PolicyAggregate entities because no event entity was passed
    public static Future<PolicyAndDocumentAggregates> loadPolicyAndDocument(LoadDocumentArgument loadDocumentArgument) {
        return loadDocument(loadDocumentArgument)
            .compose(documentAggregate -> {
                if (documentAggregate == null) {
                    return Future.succeededFuture(new PolicyAndDocumentAggregates(null, null));
                }
                return PolicyService.loadPolicy(new LoadPolicyArgument(documentAggregate.getEventPrimaryKey()))
                    .compose(policyAggregate -> Future.succeededFuture(new PolicyAndDocumentAggregates(policyAggregate, documentAggregate)));
            });
    }
}