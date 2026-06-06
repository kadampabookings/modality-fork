package one.modality.ecommerce.document.service.spi.impl.server;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.EntityStore;
import one.modality.base.shared.entities.Document;
import one.modality.base.shared.entities.Event;
import one.modality.base.shared.entities.EventState;
import one.modality.base.shared.entities.ScheduledItem;
import one.modality.ecommerce.document.service.SubmitDocumentChangesResult;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Bruno Salmon
 */
final class DocumentSubmitController {

    private static final Map<Object, DocumentSubmitEventQueue> eventQueues = new HashMap<>();
    private static final Map<Object, Future<DocumentSubmitEventQueue>> eventQueueCreationFutures = new HashMap<>();
    private static final Map<Object, SubmitDocumentChangesResult> pushingResults = new HashMap<>();

    public static Future<SubmitDocumentChangesResult> submitDocumentChanges(DocumentSubmitRequest request) {
        // When the caller UI is not queue-capable (ex: back-office, or cancel request), we submit the changes immediately
        if (!request.argument().queueCapable())
            return ServerDocumentServiceProvider.submitDocumentChangesNow(request);

        Object eventPrimaryKey = request.eventPrimaryKey();

        if (eventPrimaryKey == null) { // Case when the event primary key must be loaded from the document (ex: booking cancellation)
            return request.document().<Document>onExpressionLoaded("event")
                .compose(document -> {
                    // We reuse the same request but with the resolved eventPrimaryKey, to avoid
                    // re-replaying events into a new UpdateStore (which would cause duplicate inserts).
                    DocumentSubmitRequest resolvedRequest = DocumentSubmitRequest.copyForEvent(request,
                        Entities.getPrimaryKey(document.getEventId())
                    );
                    return submitDocumentChanges(resolvedRequest);
                });
        }

        // Getting the event queue if already exists
        DocumentSubmitEventQueue eventQueue = eventQueues.get(eventPrimaryKey);
        if (eventQueue != null) // If the event queue already exists, we can proceed with the request right now
            return executeOrEnqueueSubmitDocumentChanges(request, eventQueue);

        // No event queue exists yet for this event (an event queue is only ever created for OPEN
        // events — see below). Before creating one, we check the event state: the booking queue is
        // only relevant while the event is OPEN (managing the booking-opening rush). For any other
        // state (e.g. TESTING), we bypass the queue entirely and process the submission immediately —
        // no bookingProcessStart wait, no serialization, and no queue is created. This lets testers
        // book freely while the event is still being prepared, without interfering with (or
        // prematurely creating) the real booking-opening queue.
        return request.updateStore().getOrCreateEntity(Event.class, eventPrimaryKey)
            .<Event>onExpressionLoaded(Event.state)
            .compose(event -> {
                if (event.getState() != EventState.OPEN) {
                    Console.log("Event " + eventPrimaryKey + " is not OPEN (state = " + event.getState() + ") - bypassing event queue, processing submission immediately");
                    return ServerDocumentServiceProvider.submitDocumentChangesNow(request);
                }
                return createOrGetEventQueueAndProcess(request, eventPrimaryKey);
            });
    }

    private static Future<SubmitDocumentChangesResult> createOrGetEventQueueAndProcess(DocumentSubmitRequest request, Object eventPrimaryKey) {
        // A queue may have been created concurrently between our state check and now.
        DocumentSubmitEventQueue eventQueue = eventQueues.get(eventPrimaryKey);
        if (eventQueue != null)
            return executeOrEnqueueSubmitDocumentChanges(request, eventQueue);

        // Otherwise, we first need to create the event queue (or wait if it's already being created)
        Future<DocumentSubmitEventQueue> eventQueueCreationFuture = eventQueueCreationFutures.get(eventPrimaryKey);
        if (eventQueueCreationFuture == null) {
            eventQueueCreationFuture = createEventSubmitQueue(request)
                .onComplete(ar -> eventQueueCreationFutures.remove(eventPrimaryKey));
            eventQueueCreationFutures.put(eventPrimaryKey, eventQueueCreationFuture);
        }
        return eventQueueCreationFuture
            .compose(newQueue -> {
                eventQueues.put(eventPrimaryKey, newQueue);
                // and then proceed with the request
                return executeOrEnqueueSubmitDocumentChanges(request, newQueue);
            });
    }

    private static Future<DocumentSubmitEventQueue> createEventSubmitQueue(DocumentSubmitRequest request) {
        Object eventPk = request.eventPrimaryKey();
        return request.updateStore().getOrCreateEntity(Event.class, eventPk).<Event>onExpressionLoaded("name,openingDate,bookingProcessStart,timezone,organization.timezone")
            .compose(event -> EntityStore.create()
                .<ScheduledItem>executeQuery(
                    // Load all ScheduledItems with resource management (i.e. having at least one ScheduledResource).
                    // The "bound to event" condition matches ServerPolicyServiceProvider: either directly bound to
                    // the event (or its repeatedEvent), or unbound but at the event venue during the event period.
                    "with e as (select coalesce(repeatedEvent,id) as finalEvent,startDate,endDate,preDate,postDate,venue from Event where id=$1)" +
                    ", ep as (select startBoundary,endBoundary from EventPart where event=(select e.finalEvent from e))" +
                    " select site,item from ScheduledItem si, e" +
                    " where (si.event = e.finalEvent" +
                    "        or si.event=null and si.site = e.venue and (si.date >= coalesce(e.preDate, e.startDate) and si.date <= coalesce(e.postDate, e.endDate) or exists(select ep where si.date>=coalesce(ep.startBoundary.date, ep.startBoundary.scheduledItem.date) and si.date<=coalesce(ep.endBoundary.date, ep.endBoundary.scheduledItem.date))))" +
                    " and exists(select ScheduledResource where scheduledItem=si)",
                    eventPk)
                .map(resourceManagedScheduledItems -> new DocumentSubmitEventQueue(event, resourceManagedScheduledItems))
            );
    }

    private static Future<SubmitDocumentChangesResult> executeOrEnqueueSubmitDocumentChanges(DocumentSubmitRequest request, DocumentSubmitEventQueue eventQueue) {
        // Bookings not involving resource-managed SiteItems (e.g. online or day visitor) are added
        // as priority: they don't wait for bookingProcessStart and are processed in FIFO order.
        // Bookings with resource management wait for bookingProcessStart and are processed randomly (fair system).
        // All go through a single serial queue to avoid deadlocks.
        boolean priority = !eventQueue.requiresResourceManagement(request.argument());

        eventQueue.addRequest(request, priority);

        // Process immediately if not already processing, and either it's a priority
        // request (doesn't wait for bookingProcessStart) or the queue is ready.
        if (!eventQueue.isProcessing() && (priority || eventQueue.isReady())) {
            Console.log("Executing immediately " + (priority ? "(priority) " : "") + "request token = " + request.queueToken() + " from client runId = " + request.runId());
            eventQueue.setProcessingRequest(request);
            return processRequest(request, eventQueue, false);
        }

        Console.log("Enqueued " + (priority ? "(priority) " : "") + "request token = " + request.queueToken() + " from client runId = " + request.runId());
        return Future.succeededFuture(SubmitDocumentChangesResult.createEnqueuedResult(request.queueToken(), eventQueue.size(), priority));
    }

    static Future<SubmitDocumentChangesResult> processRequest(DocumentSubmitRequest request, DocumentSubmitEventQueue eventQueue, boolean pushResult) {
        return ServerDocumentServiceProvider.submitDocumentChangesNow(request)
            // And once processed, we inform the queue the request can be removed
            .onComplete(ar -> {
                SubmitDocumentChangesResult result =
                    !pushResult ? null :
                    ar.succeeded() ? SubmitDocumentChangesResult.withQueueToken(ar.result(), request.queueToken()) :
                    // Special result when a technical exception is raised
                    SubmitDocumentChangesResult.technicalErrorResult(ar.cause().getMessage(), request.queueToken());
                eventQueue.removedProcessedRequest(request, result);
            });
    }

    static void notifyClient(DocumentSubmitRequest request, SubmitDocumentChangesResult result, int retryMaxCount) {
        Console.log("Notifying client runId = " + request.runId() + " with result from request token = " + result.queueToken());
        pushingResults.put(request.queueToken(), result);
        DocumentSubmitEventQueue.pushResultToClient(result, request.runId())
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    Console.log("✅ Successfully pushed token " + request.queueToken() + " to client " + request.runId());
                    pushingResults.remove(request.queueToken());
                } else {
                    Console.log("❌ Failed pushing token " + request.queueToken() + " to client " + request.runId());
                    if (!pushingResults.containsKey(request.queueToken())) { // Can happen if fetchEventQueueResult() was called
                        Console.log("🤷 But token " + request.queueToken() + " is not present anymore (maybe fetchEventQueueResult() was called)");
                        return;
                    }
                    if (retryMaxCount > 0) {
                        Console.log("Retrying push of token " + request.queueToken() + " to client " + request.runId() + " (retryMaxCount = " + (retryMaxCount - 1) + ")");
                        notifyClient(request, result, retryMaxCount - 1);
                    } else {
                        Console.log("🤷 Giving up push of token " + request.queueToken() + " to client " + request.runId());
                    }
                }
            });
    }

    static void releaseEventQueue(DocumentSubmitEventQueue eventQueue) {
        eventQueues.remove(eventQueue.getEventPrimaryKey());
    }

    static boolean leaveEventQueue(Object queueToken) {
        for (DocumentSubmitEventQueue eventQueue : eventQueues.values()) {
            if (eventQueue.releaseEventQueue(queueToken)) {
                return true;
            }
        }
        return false;
    }

    static SubmitDocumentChangesResult fetchEventQueueResult(Object queueToken) {
        SubmitDocumentChangesResult result = pushingResults.remove(queueToken);
        Console.log("☀️ Fetched result for token " + queueToken + ": " + result);
        return result;
    }
}