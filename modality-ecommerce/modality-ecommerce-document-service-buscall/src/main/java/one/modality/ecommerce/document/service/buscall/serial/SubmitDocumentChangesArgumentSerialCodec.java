package one.modality.ecommerce.document.service.buscall.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.platform.reflect.RArray;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.ecommerce.document.service.SubmitDocumentChangesArgument;
import one.modality.ecommerce.document.service.events.AbstractDocumentEvent;

/**
 * @author Bruno Salmon
 */
public final class SubmitDocumentChangesArgumentSerialCodec extends SerialCodecBase<SubmitDocumentChangesArgument> {

    private static final String CODEC_ID = "SubmitDocumentChangesArgument";

    private static final String HISTORY_COMMENT_KEY = "historyComment";
    private static final String DOCUMENT_EVENTS_KEY = "documentEvents";
    private static final String QUEUE_CAPABLE_KEY = "queueCapable";
    private static final String CLIENT_ORIGIN_KEY = "clientOrigin";
    private static final String INVITE_TOKEN_KEY = "inviteToken";
    private static final String OWNER_DOCUMENT_LINE_KEY = "ownerDocumentLine";
    private static final String BASE_DOCUMENT_KEY = "baseDocument";
    private static final String BASE_LAST_DOCUMENT_LINE_KEY = "baseLastDocumentLine";
    private static final String BASE_LAST_ATTENDANCE_KEY = "baseLastAttendance";

    public SubmitDocumentChangesArgumentSerialCodec() {
        super(SubmitDocumentChangesArgument.class, CODEC_ID);
    }

    @Override
    public void encode(SubmitDocumentChangesArgument arg, AstObject serial) {
        encodeString( serial, HISTORY_COMMENT_KEY, arg.historyComment());
        encodeArray(  serial, DOCUMENT_EVENTS_KEY, arg.documentEvents());
        encodeBoolean(serial, QUEUE_CAPABLE_KEY,   arg.queueCapable());
        encodeString( serial, CLIENT_ORIGIN_KEY,   arg.clientOrigin());
        encodeString( serial, INVITE_TOKEN_KEY,    arg.inviteToken());
        encodeObject( serial, OWNER_DOCUMENT_LINE_KEY, arg.ownerDocumentLine());
        encodeObject( serial, BASE_DOCUMENT_KEY,   arg.baseDocument());
        encodeObject( serial, BASE_LAST_DOCUMENT_LINE_KEY, arg.baseLastDocumentLine());
        encodeObject( serial, BASE_LAST_ATTENDANCE_KEY, arg.baseLastAttendance());
    }

    @Override
    public SubmitDocumentChangesArgument decode(ReadOnlyAstObject serial) {
        return new SubmitDocumentChangesArgument(
            decodeString(     serial, HISTORY_COMMENT_KEY),
            decodeArray(      serial, DOCUMENT_EVENTS_KEY, AbstractDocumentEvent.class),
            decodeBooleanSafe(serial, QUEUE_CAPABLE_KEY),
            decodeString(     serial, CLIENT_ORIGIN_KEY),
            decodeString(     serial, INVITE_TOKEN_KEY),
            decodeObject(     serial, OWNER_DOCUMENT_LINE_KEY),
            decodeObject(     serial, BASE_DOCUMENT_KEY),
            decodeObject(     serial, BASE_LAST_DOCUMENT_LINE_KEY),
            decodeObject(     serial, BASE_LAST_ATTENDANCE_KEY)
        );
    }

    static {
        RArray.register(AbstractDocumentEvent.class, AbstractDocumentEvent[]::new);
    }

}
