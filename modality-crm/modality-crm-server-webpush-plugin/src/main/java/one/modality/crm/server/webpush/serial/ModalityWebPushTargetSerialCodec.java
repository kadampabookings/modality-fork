package one.modality.crm.server.webpush.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.server.webpush.ModalityWebPushTarget;

/**
 * Wire codec for {@link ModalityWebPushTarget}. The {@code CODEC_ID} is the
 * {@code $codec} discriminator the React client sends so the
 * {@code SendPushNotification} executor's polymorphic {@code target} field
 * deserialises into this concrete type.
 *
 * @author Bruno Salmon
 */
public final class ModalityWebPushTargetSerialCodec extends SerialCodecBase<ModalityWebPushTarget> {

    private static final String CODEC_ID = "ModalityWebPushTarget";
    private static final String EVENT_KEY = "event";
    private static final String DOCUMENT_KEY = "document";
    private static final String ORGANIZATION_KEY = "organization";

    public ModalityWebPushTargetSerialCodec() {
        super(ModalityWebPushTarget.class, CODEC_ID);
    }

    @Override
    public void encode(ModalityWebPushTarget arg, AstObject serial) {
        encodeObject(serial, EVENT_KEY,        arg.event());
        encodeObject(serial, DOCUMENT_KEY,     arg.document());
        encodeObject(serial, ORGANIZATION_KEY, arg.organization());
    }

    @Override
    public ModalityWebPushTarget decode(ReadOnlyAstObject serial) {
        return new ModalityWebPushTarget(
                decodeObject(serial, EVENT_KEY),
                decodeObject(serial, DOCUMENT_KEY),
                decodeObject(serial, ORGANIZATION_KEY)
        );
    }
}
