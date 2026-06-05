package one.modality.ecommerce.document.service.events.book;

import dev.webfx.stack.orm.entity.Entities;
import one.modality.base.shared.entities.DocumentLine;
import one.modality.base.shared.entities.Item;
import one.modality.base.shared.entities.Pool;
import one.modality.base.shared.entities.Site;
import one.modality.ecommerce.document.service.events.AbstractDocumentLineEvent;

/**
 * @author Bruno Salmon
 */
public final class AddDocumentLineEvent extends AbstractDocumentLineEvent {

    private final Object sitePrimaryKey;
    private final Object itemPrimaryKey;
    private final Object poolPrimaryKey;
    private final boolean allocate; // must be set to true to ask the database raising a sold-out exception when no resource is available
    // Effective "breakfast included" captured when the line is booked (see WorkingBooking.bookItem):
    // the item value for sharing options, else the room owner's rate value. Read-only, drives the
    // breakfast credit in the price algorithm. Null for lines where it doesn't apply (ex: non-accommodation).
    private final Boolean breakfastIncluded;

    public AddDocumentLineEvent(DocumentLine documentLine, boolean allocate) {
        super(documentLine);
        sitePrimaryKey = Entities.getPrimaryKey(documentLine.getSite());
        itemPrimaryKey = Entities.getPrimaryKey(documentLine.getItem());
        poolPrimaryKey = Entities.getPrimaryKey(documentLine.getPool());
        this.allocate = allocate;
        this.breakfastIncluded = documentLine.isBreakfastIncluded();
    }

    public AddDocumentLineEvent(Object documentPrimaryKey, Object documentLinePrimaryKey, Object sitePrimaryKey, Object itemPrimaryKey, Object poolPrimaryKey, boolean allocate, Boolean breakfastIncluded) {
        super(documentPrimaryKey, documentLinePrimaryKey);
        this.sitePrimaryKey = sitePrimaryKey;
        this.itemPrimaryKey = itemPrimaryKey;
        this.poolPrimaryKey = poolPrimaryKey;
        this.allocate = allocate;
        this.breakfastIncluded = breakfastIncluded;
    }

    public Object getItemPrimaryKey() {
        return itemPrimaryKey;
    }

    public Object getSitePrimaryKey() {
        return sitePrimaryKey;
    }

    public Object getPoolPrimaryKey() {
        return poolPrimaryKey;
    }

    public boolean isAllocate() {
        return allocate;
    }

    public Boolean isBreakfastIncluded() {
        return breakfastIncluded;
    }

    @Override
    protected void createDocumentLine() {
        if (isForSubmit()) {
            documentLine = updateStore.insertEntity(DocumentLine.class, getDocumentLinePrimaryKey());
        } else {
            super.createDocumentLine();
        }
    }

    @Override
    public void replayEventOnDocumentLine() {
        super.replayEventOnDocumentLine();
        // Note: For KBS2/KBS3 mixed events, it's possible that the site or item is not found in the KBS3 policy,
        // so we call entityStore.getOrCreateEntity() to at least prevent possible later NPE
        documentLine.setSite(isForSubmit() ? sitePrimaryKey : entityStore.getOrCreateEntity(Site.class, sitePrimaryKey, true)); // Should be found from PolicyAggregate
        documentLine.setItem(isForSubmit() ? itemPrimaryKey : entityStore.getOrCreateEntity(Item.class, itemPrimaryKey, true)); // Should be found from PolicyAggregate
        documentLine.setPool(isForSubmit() ? poolPrimaryKey : entityStore.getOrCreateEntity(Pool.class, poolPrimaryKey, true));
        documentLine.setAllocate(allocate);
        documentLine.setBreakfastIncluded(breakfastIncluded);
    }
}
