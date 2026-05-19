package one.modality.ecommerce.document.service.spi.impl.server;

import dev.webfx.platform.util.uuid.Uuid;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.UpdateStore;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.base.shared.entities.Document;
import one.modality.base.shared.entities.DocumentLine;
import one.modality.ecommerce.document.service.SubmitDocumentChangesArgument;
import one.modality.ecommerce.document.service.events.AbstractDocumentEvent;
import one.modality.ecommerce.document.service.events.AbstractDocumentLineEvent;

/**
 * @author Bruno Salmon
 */
record DocumentSubmitRequest(
    SubmitDocumentChangesArgument argument,
    String runId,
    Object userId,  // captured at request time — ThreadLocal is gone when the queue processes this later
    UpdateStore updateStore,
    Document document,
    DocumentLine documentLine,
    Object eventPrimaryKey,
    Object queueToken
) {

    DocumentSubmitRequest(SubmitDocumentChangesArgument argument, String runId, Object userId, UpdateStore updateStore, Document document, DocumentLine documentLine, Object eventPrimaryKey, Object queueToken) {
        this.argument = argument;
        this.runId = runId;
        this.userId = userId;
        this.updateStore = updateStore;
        this.document = document;
        this.documentLine = documentLine;
        this.eventPrimaryKey = eventPrimaryKey;
        this.queueToken = queueToken;
    }

    static DocumentSubmitRequest create(SubmitDocumentChangesArgument argument) {
        // Capturing the required client state info from thread local (before it will be wiped out by the async call)
        String runId = ThreadLocalStateHolder.getRunId();
        Object userId = ThreadLocalStateHolder.getUserId();

        UpdateStore updateStore = UpdateStore.create(DataSourceModelService.getDefaultDataSourceModel());
        Document document = null;
        DocumentLine documentLine = null;
        AbstractDocumentEvent[] documentEvents = argument.documentEvents();
        for (AbstractDocumentEvent e : documentEvents) {
            e.setEntityStore(updateStore); // This indicates it's for submission
            e.replayEvent();
            if (document == null)
                document = e.getDocument();
            if (documentLine == null && e instanceof AbstractDocumentLineEvent) {
                documentLine = ((AbstractDocumentLineEvent) e).getDocumentLine();
            }
        }

        if (document == null && documentLine != null)
            document = documentLine.getDocument();

        Object eventPrimaryKey = document == null ? null : Entities.getPrimaryKey(document.getEventId());
        Object queueToken = Uuid.randomUuid();

        return new DocumentSubmitRequest(argument, runId, userId, updateStore, document, documentLine, eventPrimaryKey, queueToken);
    }


    static DocumentSubmitRequest copyForEvent(DocumentSubmitRequest request, Object providedEventPrimaryKey) {
        return new DocumentSubmitRequest(
            request.argument(), request.runId(), request.userId(), request.updateStore(),
            request.document(), request.documentLine(),
            providedEventPrimaryKey, request.queueToken()
        );
    }

}
