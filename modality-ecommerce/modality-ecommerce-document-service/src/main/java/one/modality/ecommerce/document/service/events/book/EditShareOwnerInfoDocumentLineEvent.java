package one.modality.ecommerce.document.service.events.book;

import one.modality.base.shared.entities.DocumentLine;
import one.modality.ecommerce.document.service.events.AbstractDocumentLineEvent;

/**
 * @author Bruno Salmon
 */
public final class EditShareOwnerInfoDocumentLineEvent extends AbstractDocumentLineEvent {

    private final String[] matesNames;
    /** Non-zero only when quantity was set directly (no mates list). Zero means derive from matesNames. */
    private final int quantity;

    public EditShareOwnerInfoDocumentLineEvent(Object documentPrimaryKey, Object documentLinePrimaryKey, String[] matesNames) {
        super(documentPrimaryKey, documentLinePrimaryKey);
        this.matesNames = matesNames;
        this.quantity = 0;
    }

    public EditShareOwnerInfoDocumentLineEvent(DocumentLine documentLine, String[] matesNames) {
        super(documentLine);
        this.matesNames = matesNames;
        this.quantity = 0;
    }

    /**
     * Load-path constructor — rebuilds the event from a stored line, carrying its persisted
     * quantity so quantity-based bookings (e.g. public-talk headcount) survive the round trip.
     * Without it the loaded event serializes quantity 0 and the client re-derives the count
     * from the (empty) mates list, i.e. always 1.
     */
    public EditShareOwnerInfoDocumentLineEvent(DocumentLine documentLine, String[] matesNames, int quantity) {
        super(documentLine);
        this.matesNames = matesNames;
        this.quantity = quantity;
    }

    /** Quantity-only constructor — for bookings where mates are not named (e.g. public talk). */
    public EditShareOwnerInfoDocumentLineEvent(Object documentPrimaryKey, Object documentLinePrimaryKey, int quantity) {
        super(documentPrimaryKey, documentLinePrimaryKey);
        this.matesNames = new String[0];
        this.quantity = quantity;
    }

    public String[] getMatesNames() {
        return matesNames;
    }

    public int getQuantity() {
        return quantity;
    }

    @Override
    public void replayEventOnDocumentLine() {
        super.replayEventOnDocumentLine();
        documentLine.setShareMate(false);
        documentLine.setShareOwner(true);
        documentLine.setShareOwnerMatesNames(matesNames);
        documentLine.setShareOwnerQuantity(quantity > 0 ? quantity : 1 + matesNames.length);
    }
}
