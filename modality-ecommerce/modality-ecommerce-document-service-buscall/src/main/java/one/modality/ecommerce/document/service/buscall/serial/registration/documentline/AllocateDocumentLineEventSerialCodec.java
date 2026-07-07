package one.modality.ecommerce.document.service.buscall.serial.registration.documentline;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.orm.entity.Entities;
import one.modality.ecommerce.document.service.buscall.serial.AbstractDocumentLineEventSerialCodec;
import one.modality.ecommerce.document.service.events.registration.documentline.AllocateDocumentLineEvent;

/**
 * @author Bruno Salmon
 */
public final class AllocateDocumentLineEventSerialCodec extends AbstractDocumentLineEventSerialCodec<AllocateDocumentLineEvent> {

    private static final String CODEC_ID = "AllocateDocumentLineEvent";

    private static final String RESOURCE_CONFIGURATION_KEY = "resourceConfiguration";
    // Optional (absent on events recorded before the extension): destination
    // room type + partition markers — see AllocateDocumentLineEvent.
    private static final String ITEM_KEY = "item";
    private static final String RESERVED_KEY = "reserved";
    private static final String POOL_KEY = "pool";

    public AllocateDocumentLineEventSerialCodec() {
        super(AllocateDocumentLineEvent.class, CODEC_ID);
    }

    @Override
    public void encode(AllocateDocumentLineEvent o, AstObject serial) {
        super.encode(o, serial);
        encodeObject(serial, RESOURCE_CONFIGURATION_KEY, Entities.getPrimaryKey(o.getResourceConfiguration()));
        if (o.getItem() != null)
            encodeObject(serial, ITEM_KEY, Entities.getPrimaryKey(o.getItem()));
        if (o.getReserved() != null) {
            encodeBoolean(serial, RESERVED_KEY, o.getReserved());
            // The pair rule: pool is authoritative whenever reserved is present
            // (may still be encoded as absent = null pool).
            if (o.getPool() != null)
                encodeObject(serial, POOL_KEY, Entities.getPrimaryKey(o.getPool()));
        }
    }

    @Override
    public AllocateDocumentLineEvent decode(ReadOnlyAstObject serial) {
        return postDecode(new AllocateDocumentLineEvent(
            decodeDocumentPrimaryKey(serial),
            decodeDocumentLinePrimaryKey(serial),
            decodeObject(serial, RESOURCE_CONFIGURATION_KEY),
            decodeObject(serial, ITEM_KEY),
            serial.has(RESERVED_KEY) ? serial.getBoolean(RESERVED_KEY) : null,
            decodeObject(serial, POOL_KEY)
        ), serial);
    }
}
