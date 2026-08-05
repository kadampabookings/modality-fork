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
    private static final String QUANTITY_KEY    = "quantity";

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
        // Decode BOTH fields — the former quantity>0 branch went through the quantity-only
        // constructor, silently dropping the mates names sent with a named headcount
        // (public-talk attendee 2+ names never reached the document lines).
        String[] matesNames = decodeStringArray(serial, MATES_NAMES_KEY);
        return postDecode(new EditShareOwnerInfoDocumentLineEvent(
            decodeDocumentPrimaryKey(serial),
            decodeDocumentLinePrimaryKey(serial),
            matesNames != null ? matesNames : new String[0],
            decodeInteger(serial, QUANTITY_KEY, 0)
        ), serial);
    }
}
