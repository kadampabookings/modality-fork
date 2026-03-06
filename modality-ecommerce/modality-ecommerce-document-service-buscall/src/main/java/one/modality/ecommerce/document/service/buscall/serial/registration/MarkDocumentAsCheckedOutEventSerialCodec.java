package one.modality.ecommerce.document.service.buscall.serial.registration;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import one.modality.ecommerce.document.service.buscall.serial.AbstractDocumentEventSerialCodec;
import one.modality.ecommerce.document.service.events.registration.MarkDocumentAsCheckedOutEvent;

/**
 * @author Bruno Salmon
 */
public final class MarkDocumentAsCheckedOutEventSerialCodec extends AbstractDocumentEventSerialCodec<MarkDocumentAsCheckedOutEvent> {

    private static final String CODEC_ID = "MarkDocumentAsCheckedOutEvent";

    private static final String CHECKED_OUT_KEY = "checkedOut";
    private static final String READ_KEY = "read";

    public MarkDocumentAsCheckedOutEventSerialCodec() {
        super(MarkDocumentAsCheckedOutEvent.class, CODEC_ID);
    }

    @Override
    public void encode(MarkDocumentAsCheckedOutEvent o, AstObject serial) {
        super.encode(o, serial);
        encodeBoolean(serial, CHECKED_OUT_KEY, o.isCheckedOut());
        encodeBoolean(serial, READ_KEY, o.isRead());
    }

    @Override
    public MarkDocumentAsCheckedOutEvent decode(ReadOnlyAstObject serial) {
        return postDecode(new MarkDocumentAsCheckedOutEvent(
            decodeDocumentPrimaryKey(serial),
            decodeBooleanSafe(serial, CHECKED_OUT_KEY),
            decodeBooleanSafe(serial, READ_KEY)
        ), serial);
    }
}
