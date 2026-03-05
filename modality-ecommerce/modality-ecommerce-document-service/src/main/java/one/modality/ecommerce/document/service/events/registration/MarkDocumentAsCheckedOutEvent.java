package one.modality.ecommerce.document.service.events.registration;

import one.modality.base.shared.entities.Document;
import one.modality.ecommerce.document.service.events.AbstractDocumentEvent;

/**
 * @author Bruno Salmon
 */
public class MarkDocumentAsCheckedOutEvent extends AbstractDocumentEvent {

    private final boolean checkedOut;
    private final boolean read;

    public MarkDocumentAsCheckedOutEvent(Object documentPrimaryKey, boolean checkedOut) {
        this(documentPrimaryKey, checkedOut, false);
    }

    public MarkDocumentAsCheckedOutEvent(Object documentPrimaryKey, boolean checkedOut, boolean read) {
        super(documentPrimaryKey);
        this.checkedOut = checkedOut;
        this.read = read;
    }

    public MarkDocumentAsCheckedOutEvent(Document document, boolean checkedOut) {
        this(document, checkedOut, false);
    }

    public MarkDocumentAsCheckedOutEvent(Document document, boolean checkedOut, boolean read) {
        super(document);
        this.checkedOut = checkedOut;
        this.read = read;
    }

    public boolean isCheckedOut() {
        return checkedOut;
    }

    public boolean isRead() {
        return read;
    }

    @Override
    public void replayEventOnDocument() {
        super.replayEventOnDocument();
        document.setCheckedOut(checkedOut);
        document.setRead(read);
    }
}
