package one.modality.ecommerce.document.service.buscall.serial.registration.documentline;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import one.modality.ecommerce.document.service.buscall.serial.AbstractDocumentLineEventSerialCodec;
import one.modality.ecommerce.document.service.events.book.EditShareOwnerInfoDocumentLineEvent;

/**
 * @author Bruno Salmon
 */
public final class EditShareOwnerInfoDocumentLineEventSerialCodec extends AbstractDocumentLineEventSerialCodec<EditShareOwnerInfoDocumentLineEvent> {

    private static final String CODEC_ID = "EditShareOwnerInfoDocumentLineEvent";

    private static final String MATES_NAMES_KEY = "matesNames";
    private static final String QUANTITY_KEY    = "qty";

    public EditShareOwnerInfoDocumentLineEventSerialCodec() {
        super(EditShareOwnerInfoDocumentLineEvent.class, CODEC_ID);
    }

    @Override
    public void encode(EditShareOwnerInfoDocumentLineEvent o, AstObject serial) {
        super.encode(o, serial);
        encodeStringArray(serial, MATES_NAMES_KEY, o.getMatesNames());
        if (o.getQuantity() > 0)
            encodeInteger(serial, QUANTITY_KEY, o.getQuantity());
    }

    @Override
    public EditShareOwnerInfoDocumentLineEvent decode(ReadOnlyAstObject serial) {
        int quantity = decodeInteger(serial, QUANTITY_KEY, 0);
        if (quantity > 0)
            return postDecode(new EditShareOwnerInfoDocumentLineEvent(
                decodeDocumentPrimaryKey(serial),
                decodeDocumentLinePrimaryKey(serial),
                quantity
            ), serial);
        return postDecode(new EditShareOwnerInfoDocumentLineEvent(
            decodeDocumentPrimaryKey(serial),
            decodeDocumentLinePrimaryKey(serial),
            decodeStringArray(serial, MATES_NAMES_KEY)
        ), serial);
    }
}
