package one.modality.ecommerce.document.service.events;

import dev.webfx.stack.orm.entity.Entities;
import one.modality.base.shared.entities.Document;

/**
 * @author Bruno Salmon
 */
public abstract class AbstractDocumentEvent extends AbstractSourceEvent {

    private final Document document;
    private final Object documentPrimaryKey;

    public AbstractDocumentEvent(Object documentPrimaryKey) {
        this.document = null;
        this.documentPrimaryKey = documentPrimaryKey;
    }

    public AbstractDocumentEvent(Document document) {
        this.document = document;
        this.documentPrimaryKey = Entities.getPrimaryKey(document);
    }

    public Document getDocument() {
        return document;
    }

    public Object getDocumentPrimaryKey() {
        return documentPrimaryKey;
    }

}
