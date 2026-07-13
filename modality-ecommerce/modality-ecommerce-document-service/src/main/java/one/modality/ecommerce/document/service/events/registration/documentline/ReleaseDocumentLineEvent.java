package one.modality.ecommerce.document.service.events.registration.documentline;

import one.modality.base.shared.entities.DocumentLine;
import one.modality.ecommerce.document.service.events.AbstractDocumentLineEvent;

/**
 * Releases (or un-releases) a document line's held bed. A cancelled line
 * keeps HOLDING its bed for a possible waiting-list guest until the
 * registration team releases it; releasing sets BOTH flags at once —
 * backend_released and frontend_released — matching KBS2's "Release
 * (backend and frontend)". The availability engine reads the flags
 * directly (a released line counts 0), so nothing is re-allocated.
 *
 * @author Bruno Salmon
 */
public final class ReleaseDocumentLineEvent extends AbstractDocumentLineEvent {

    private final boolean released;

    public ReleaseDocumentLineEvent(Object documentPrimaryKey, Object documentLinePrimaryKey, boolean released) {
        super(documentPrimaryKey, documentLinePrimaryKey);
        this.released = released;
    }

    public ReleaseDocumentLineEvent(DocumentLine documentLine, boolean released) {
        super(documentLine);
        this.released = released;
    }

    public boolean isReleased() {
        return released;
    }

    @Override
    public void replayEventOnDocumentLine() {
        super.replayEventOnDocumentLine();
        documentLine.setBackendReleased(released);
        documentLine.setFrontendReleased(released);
    }
}
