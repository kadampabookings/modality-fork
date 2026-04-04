package one.modality.ecommerce.document.service.spi.impl.server;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.scheduler.Scheduled;
import dev.webfx.platform.scheduler.Scheduler;
import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.AstObject;
import dev.webfx.stack.com.bus.BusService;
import dev.webfx.stack.com.bus.DeliveryOptions;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.push.server.PushServerService;
import one.modality.base.shared.entities.Event;
import one.modality.base.shared.entities.ScheduledItem;
import one.modality.base.shared.entities.SiteItem;
import one.modality.ecommerce.document.service.SubmitDocumentChangesArgument;
import one.modality.ecommerce.document.service.SubmitDocumentChangesResult;
import one.modality.ecommerce.document.service.buscall.DocumentServiceBusAddresses;
import one.modality.ecommerce.document.service.events.AbstractDocumentEvent;
import one.modality.ecommerce.document.service.events.book.AddDocumentLineEvent;

import java.time.LocalDateTime;
import java.util.*;

/**
 * @author Bruno Salmon
 */
final class DocumentSubmitEventQueue {

    private final Event event; // Keeping reference for debugging purpose
    private final Set<SiteItem> resourceManagedSiteItems; // SiteItems that require resource management (have ScheduledResources)
    private boolean ready;
    private Scheduled scheduled;
    private DocumentSubmitRequest processingRequest;
    // All requests go into this single queue; priority tokens are tracked separately for ordering
    private final Map<Object, DocumentSubmitRequest> queue = new HashMap<>();
    private final Set<Object> priorityTokens = new LinkedHashSet<>(); // preserves insertion order (FIFO)
    private final Random random = new Random();
    private int processedPriorityRequests;
    private int processedStandardRequests;

    public DocumentSubmitEventQueue(Event event, List<ScheduledItem> resourceManagedScheduledItems) {
        this.event = event;
        // Build set of SiteItems that have resource management (deduplicated via SiteItem.equals/hashCode)
        resourceManagedSiteItems = new HashSet<>();
        for (ScheduledItem si : resourceManagedScheduledItems) {
            resourceManagedSiteItems.add(new SiteItem(si));
        }
        log("Resource-managed SiteItems: " + resourceManagedSiteItems.size());
        LocalDateTime bookingProcessStart = event.getBookingProcessStart();
        if (bookingProcessStart == null)
            bookingProcessStart = event.getOpeningDate();
        long delayMs = bookingProcessStart == null ? 0 : bookingProcessStart.atZone(event.getEventZoneId()).toInstant().toEpochMilli() - System.currentTimeMillis();
        ready = delayMs <= 0;
        if (!ready) {
            scheduled = Scheduler.scheduleDelay(delayMs, this::setReady);
        }
        log("Created - Start delay: " + delayMs + "ms");
    }

    Object getEventPrimaryKey() {
        return event.getPrimaryKey();
    }

    boolean requiresResourceManagement(SubmitDocumentChangesArgument argument) {
        for (AbstractDocumentEvent documentEvent : argument.documentEvents()) {
            if (documentEvent instanceof AddDocumentLineEvent) {
                AddDocumentLineEvent adle = (AddDocumentLineEvent) documentEvent;
                if (isResourceManagedSiteItem(adle.getSitePrimaryKey(), adle.getItemPrimaryKey())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isResourceManagedSiteItem(Object sitePk, Object itemPk) {
        for (SiteItem siteItem : resourceManagedSiteItems) {
            if (Objects.equals(Entities.getPrimaryKey(siteItem.getSite()), sitePk)
             && Objects.equals(Entities.getPrimaryKey(siteItem.getItem()), itemPk)) {
                return true;
            }
        }
        return false;
    }

    boolean isEmpty() {
        return queue.isEmpty();
    }

    int size() {
        return queue.size();
    }

    boolean isReady() {
        return ready;
    }

    private void setReady() {
        ready = true;
        processNextRequestIfNotProcessing();
    }

    void addRequest(DocumentSubmitRequest request, boolean priority) {
        queue.put(request.queueToken(), request);
        if (priority) {
            priorityTokens.add(request.queueToken());
        }
        publishProgress();
    }

    void setProcessingRequest(DocumentSubmitRequest processingRequest) {
        this.processingRequest = processingRequest;
    }

    DocumentSubmitRequest pollProcessingRequest() {
        if (queue.isEmpty())
            return null;
        // Priority requests (no resource management) are always processed first, in FIFO order
        if (!priorityTokens.isEmpty()) {
            Object token = priorityTokens.iterator().next();
            DocumentSubmitRequest request = queue.get(token);
            setProcessingRequest(request);
            return request;
        }
        // Normal requests are processed in random order (fair system for accommodation)
        if (!ready) // but only when the queue is ready (after bookingProcessStart)
            return null;
        int index = random.nextInt(queue.size());
        DocumentSubmitRequest request = queue.values().stream().skip(index).findFirst().orElse(null);
        setProcessingRequest(request);
        return request;
    }

    boolean isProcessing() {
        return processingRequest != null;
    }

    private void processNextRequestIfNotProcessing() {
        if (!isProcessing()) {
            DocumentSubmitRequest nextRequest = pollProcessingRequest();
            if (nextRequest != null) {
                DocumentSubmitController.processRequest(nextRequest, this, true);
            } else if (ready && queue.isEmpty()) {
                DocumentSubmitController.releaseEventQueue(this);
                log("Released after processing " + (processedPriorityRequests + processedStandardRequests) + " request(s)");
            }
        }
    }

    public void removedProcessedRequest(DocumentSubmitRequest request, SubmitDocumentChangesResult result) {
        if (priorityTokens.contains(request.queueToken()))
            processedPriorityRequests++;
        else
            processedStandardRequests++;
        removeRequest(request);
        publishProgressAndResult(request, result);
        if (processingRequest == request) {
            processingRequest = null;
            processNextRequestIfNotProcessing();
        }
    }

    void removeRequest(DocumentSubmitRequest request) {
        removeRequest(request.queueToken());
    }

    DocumentSubmitRequest removeRequest(Object token) {
        priorityTokens.remove(token);
        return queue.remove(token);
    }

    void publishProgress() {
        publishProgressAndResult(null, null);
    }

    void publishProgressAndResult(DocumentSubmitRequest request, SubmitDocumentChangesResult result) {
        int processedRequests = processedPriorityRequests + processedStandardRequests;
        int remainingRequests = queue.size();
        int remainingPriorityRequests = priorityTokens.size();
        int remainingStandardRequests = remainingRequests - remainingPriorityRequests;
        int totalRequests = processedRequests + remainingRequests;
        log("Processed " + processedRequests + " request(s) over " + totalRequests + " (" + remainingRequests + " remaining)"
            + " [priority: " + processedPriorityRequests + ", standard: " + processedStandardRequests + "]");
        // We don't publish the progress for a single request in a non-waiting queue (as it will be processed
        // immediately), and this should be actually most of the cases.
        if (scheduled == null && totalRequests == 1)
            return;
        // Broadcast progress to all subscribed clients via bus publish
        log("Notifying front-office of progress");
        AstObject progressMessage = AST.createObject()
            .set("eventId", event.getPrimaryKey())
            .set("processedPriority", processedPriorityRequests)
            .set("processedStandard", processedStandardRequests)
            .set("processed", processedRequests) // kept for backward compatibility
            .set("total", totalRequests)
            .set("remaining", remainingRequests)
            .set("remainingPriority", remainingPriorityRequests)
            .set("remainingStandard", remainingStandardRequests);
        BusService.bus().publish(DocumentServiceBusAddresses.QUEUE_PROGRESS_CLIENT_PUSH_ADDRESS, progressMessage);
        if (request != null && result != null)
            DocumentSubmitController.notifyClient(request, result, 30);
    }

    static Future<Object> pushResultToClient(SubmitDocumentChangesResult result, Object clientRunId) {
        if (clientRunId == null)
            return Future.succeededFuture("UNKNOWN");
        return PushServerService.push(
            DocumentServiceBusAddresses.SUBMIT_DOCUMENT_CHANGES_FINAL_CLIENT_PUSH_ADDRESS,
            result,
            new DeliveryOptions(),
            clientRunId);
    }

    private void log(String message) {
        Console.log("🪣 [EVENT-QUEUE-" + event.getPrimaryKey() + "-" + event.getName() + "] " + message);
    }

    boolean releaseEventQueue(Object queueToken) {
        if (removeRequest(queueToken) != null) {
            publishProgress();
            return true;
        }
        return false;
    }

}
