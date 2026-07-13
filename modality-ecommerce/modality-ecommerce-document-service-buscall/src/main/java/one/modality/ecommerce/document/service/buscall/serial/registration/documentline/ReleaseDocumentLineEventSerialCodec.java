package one.modality.ecommerce.document.service.buscall.serial.registration.documentline;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import one.modality.ecommerce.document.service.buscall.serial.AbstractDocumentLineEventSerialCodec;
import one.modality.ecommerce.document.service.events.registration.documentline.ReleaseDocumentLineEvent;

/**
 * @author Bruno Salmon
 */
public final class ReleaseDocumentLineEventSerialCodec extends AbstractDocumentLineEventSerialCodec<ReleaseDocumentLineEvent> {

    private static final String CODEC_ID = "ReleaseDocumentLineEvent";

    private static final String RELEASED_KEY = "released";

    public ReleaseDocumentLineEventSerialCodec() {
        super(ReleaseDocumentLineEvent.class, CODEC_ID);
    }

    @Override
    public void encode(ReleaseDocumentLineEvent o, AstObject serial) {
        super.encode(o, serial);
        encodeBoolean(serial, RELEASED_KEY, o.isReleased());
    }

    @Override
    public ReleaseDocumentLineEvent decode(ReadOnlyAstObject serial) {
        return postDecode(new ReleaseDocumentLineEvent(
            decodeDocumentPrimaryKey(serial),
            decodeDocumentLinePrimaryKey(serial),
            decodeBoolean(serial, RELEASED_KEY)
        ), serial);
    }
}
