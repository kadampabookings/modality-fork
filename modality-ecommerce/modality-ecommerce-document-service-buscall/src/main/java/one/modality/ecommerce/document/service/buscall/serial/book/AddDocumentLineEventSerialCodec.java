package one.modality.ecommerce.document.service.buscall.serial.book;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import one.modality.ecommerce.document.service.buscall.serial.AbstractDocumentLineEventSerialCodec;
import one.modality.ecommerce.document.service.events.book.AddDocumentLineEvent;

/**
 * @author Bruno Salmon
 */
public final class AddDocumentLineEventSerialCodec extends AbstractDocumentLineEventSerialCodec<AddDocumentLineEvent> {

    private static final String CODEC_ID = "AddDocumentLineEvent";

    private static final String SITE_PRIMARY_KEY = "site";
    private static final String ITEM_PRIMARY_KEY = "item";
    private static final String ALLOCATE_KEY = "allocate";
    private static final String POOL_PRIMARY_KEY = "pool";
    private static final String BREAKFAST_INCLUDED_KEY = "breakfastIncluded";

    public AddDocumentLineEventSerialCodec() {
        super(AddDocumentLineEvent.class, CODEC_ID);
    }

    @Override
    public void encode(AddDocumentLineEvent o, AstObject serial) {
        super.encode(o, serial);
        encodeObject( serial, SITE_PRIMARY_KEY,        o.getSitePrimaryKey());
        encodeObject( serial, ITEM_PRIMARY_KEY,        o.getItemPrimaryKey());
        encodeObject( serial, POOL_PRIMARY_KEY,        o.getPoolPrimaryKey());
        encodeBoolean(serial, ALLOCATE_KEY,            o.isAllocate());
        encodeBoolean(serial, BREAKFAST_INCLUDED_KEY,  o.isBreakfastIncluded()); // nullable; ignored when null
    }

    @Override
    public AddDocumentLineEvent decode(ReadOnlyAstObject serial) {
        return postDecode(new AddDocumentLineEvent(
                decodeDocumentPrimaryKey(serial),
                decodeDocumentLinePrimaryKey(serial),
                decodeObject(serial, SITE_PRIMARY_KEY),
                decodeObject(serial, ITEM_PRIMARY_KEY),
            decodeObject(serial, POOL_PRIMARY_KEY), decodeBooleanSafe(serial, ALLOCATE_KEY),
            decodeBoolean(serial, BREAKFAST_INCLUDED_KEY)
        ), serial);
    }
}
