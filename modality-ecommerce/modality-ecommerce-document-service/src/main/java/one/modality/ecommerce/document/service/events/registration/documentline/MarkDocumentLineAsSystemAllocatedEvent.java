package one.modality.ecommerce.document.service.events.registration.documentline;

import one.modality.base.shared.entities.DocumentLine;
import one.modality.ecommerce.document.service.events.AbstractDocumentLineEvent;

/**
 * Marks (or unmarks) a document line as SYSTEM-allocated — the provisional
 * flag the allocation engine stamps on its random room picks. The
 * registration team clears it ("Mark as allocated") to confirm the engine's
 * pick in place, the room assignment itself being untouched. (A manual room
 * move clears the flag too, through the on_not_system_allocated DB trigger —
 * this event is the "keep it where it is" counterpart.)
 *
 * @author Bruno Salmon
 */
public final class MarkDocumentLineAsSystemAllocatedEvent extends AbstractDocumentLineEvent {

    private final boolean systemAllocated;

    public MarkDocumentLineAsSystemAllocatedEvent(Object documentPrimaryKey, Object documentLinePrimaryKey, boolean systemAllocated) {
        super(documentPrimaryKey, documentLinePrimaryKey);
        this.systemAllocated = systemAllocated;
    }

    public MarkDocumentLineAsSystemAllocatedEvent(DocumentLine documentLine, boolean systemAllocated) {
        super(documentLine);
        this.systemAllocated = systemAllocated;
    }

    public boolean isSystemAllocated() {
        return systemAllocated;
    }

    @Override
    public void replayEventOnDocumentLine() {
        super.replayEventOnDocumentLine();
        documentLine.setSystemAllocated(systemAllocated);
    }
}
