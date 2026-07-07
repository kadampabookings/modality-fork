package one.modality.ecommerce.document.service.events.registration.documentline;

import one.modality.base.shared.entities.DocumentLine;
import one.modality.base.shared.entities.Item;
import one.modality.base.shared.entities.Pool;
import one.modality.base.shared.entities.ResourceConfiguration;
import one.modality.ecommerce.document.service.events.AbstractDocumentLineEvent;

/**
 * Assigns a room (ResourceConfiguration) to a document line.
 *
 * Besides the config row itself, the event optionally carries:
 * - {@code item} — the destination room type. A back-office move can re-point
 *   a line to a config of a DIFFERENT type, and the line's item must follow
 *   rc.item (the same invariant the on_item_changed_cascade_document_lines
 *   trigger maintains when a config is edited in place); the item change also
 *   drives the automatic repricing (defer_compute_document_prices).
 * - {@code reserved} + {@code pool} — the partition markers of the
 *   reserved-bed model: reserved = true means the line consumes a RESERVED
 *   bed (the maxReserved partition), pool is the informative reason
 *   mirroring rc.pool. The two travel as a PAIR keyed on reserved: when
 *   reserved is non-null, pool is authoritative too (possibly null — a
 *   public drop clears it, and rc.pool is optional even on reserved
 *   partitions).
 *
 * All three are null on events recorded before this extension (and on
 * allocations that don't care, e.g. same-type dining-area moves) — replay
 * then leaves the corresponding fields untouched.
 *
 * @author Bruno Salmon
 */
public final class AllocateDocumentLineEvent extends AbstractDocumentLineEvent {

    private final Object resourceConfiguration;
    private final Object item;
    private final Boolean reserved;
    private final Object pool;

    public AllocateDocumentLineEvent(Object documentPrimaryKey, Object documentLinePrimaryKey, Object resourceConfigurationPrimaryKey) {
        this(documentPrimaryKey, documentLinePrimaryKey, resourceConfigurationPrimaryKey, null, null, null);
    }

    public AllocateDocumentLineEvent(Object documentPrimaryKey, Object documentLinePrimaryKey, Object resourceConfigurationPrimaryKey, Object itemPrimaryKey, Boolean reserved, Object poolPrimaryKey) {
        super(documentPrimaryKey, documentLinePrimaryKey);
        this.resourceConfiguration = resourceConfigurationPrimaryKey;
        this.item = itemPrimaryKey;
        this.reserved = reserved;
        this.pool = poolPrimaryKey;
    }

    public AllocateDocumentLineEvent(DocumentLine documentLine, ResourceConfiguration resourceConfiguration) {
        this(documentLine, resourceConfiguration, null, null, null);
    }

    public AllocateDocumentLineEvent(DocumentLine documentLine, ResourceConfiguration resourceConfiguration, Item item, Boolean reserved, Pool pool) {
        super(documentLine);
        this.resourceConfiguration = resourceConfiguration;
        this.item = item;
        this.reserved = reserved;
        this.pool = pool;
    }

    public Object getResourceConfiguration() {
        return resourceConfiguration;
    }

    public Object getItem() {
        return item;
    }

    public Boolean getReserved() {
        return reserved;
    }

    public Object getPool() {
        return pool;
    }

    @Override
    public void replayEventOnDocumentLine() {
        super.replayEventOnDocumentLine();
        if (resourceConfiguration != null)
            documentLine.setResourceConfiguration(resourceConfiguration);
        if (item != null)
            documentLine.setItem(item);
        if (reserved != null) { // the pair rule (see class comment): reserved present => pool authoritative
            documentLine.setReserved(reserved);
            documentLine.setPool(pool);
        }
    }
}
