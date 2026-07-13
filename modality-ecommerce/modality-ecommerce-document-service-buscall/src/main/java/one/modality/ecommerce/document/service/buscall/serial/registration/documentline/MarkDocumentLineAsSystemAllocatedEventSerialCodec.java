package one.modality.ecommerce.document.service.buscall.serial.registration.documentline;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import one.modality.ecommerce.document.service.buscall.serial.AbstractDocumentLineEventSerialCodec;
import one.modality.ecommerce.document.service.events.registration.documentline.MarkDocumentLineAsSystemAllocatedEvent;

/**
 * @author Bruno Salmon
 */
public final class MarkDocumentLineAsSystemAllocatedEventSerialCodec extends AbstractDocumentLineEventSerialCodec<MarkDocumentLineAsSystemAllocatedEvent> {

    private static final String CODEC_ID = "MarkDocumentLineAsSystemAllocatedEvent";

    private static final String SYSTEM_ALLOCATED_KEY = "systemAllocated";

    public MarkDocumentLineAsSystemAllocatedEventSerialCodec() {
        super(MarkDocumentLineAsSystemAllocatedEvent.class, CODEC_ID);
    }

    @Override
    public void encode(MarkDocumentLineAsSystemAllocatedEvent o, AstObject serial) {
        super.encode(o, serial);
        encodeBoolean(serial, SYSTEM_ALLOCATED_KEY, o.isSystemAllocated());
    }

    @Override
    public MarkDocumentLineAsSystemAllocatedEvent decode(ReadOnlyAstObject serial) {
        return postDecode(new MarkDocumentLineAsSystemAllocatedEvent(
            decodeDocumentPrimaryKey(serial),
            decodeDocumentLinePrimaryKey(serial),
            decodeBoolean(serial, SYSTEM_ALLOCATED_KEY)
        ), serial);
    }
}
