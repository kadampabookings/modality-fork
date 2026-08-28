package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.RequestBackOfficeViewCredentials;

/**
 * @author Claude Code
 */
public final class RequestBackOfficeViewCredentialsSerialCodec extends SerialCodecBase<RequestBackOfficeViewCredentials> {

    private static final String CODEC_ID = "RequestBackOfficeViewCredentials";
    private static final String TARGET_PERSON_ID_KEY = "targetPersonId";

    public RequestBackOfficeViewCredentialsSerialCodec() {
        super(RequestBackOfficeViewCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(RequestBackOfficeViewCredentials arg, AstObject serial) {
        encodeObject(serial, TARGET_PERSON_ID_KEY, arg.targetPersonId());
    }

    @Override
    public RequestBackOfficeViewCredentials decode(ReadOnlyAstObject serial) {
        return new RequestBackOfficeViewCredentials(
            decodeObject(serial, TARGET_PERSON_ID_KEY)
        );
    }
}
