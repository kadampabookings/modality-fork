package one.modality.ecommerce.document.service.events;

import one.modality.base.shared.entities.DocumentLine;

/**
 * @author Bruno Salmon
 */
public class EditDocumentLineEvent extends AbstractDocumentLineEvent {

    public EditDocumentLineEvent(DocumentLine documentLine) {
        super(documentLine);
    }

}
