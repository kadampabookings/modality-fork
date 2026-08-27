package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.RequestSupportViewCredentials;

/**
 * @author Claude Code
 */
public final class RequestSupportViewCredentialsSerialCodec extends SerialCodecBase<RequestSupportViewCredentials> {

    private static final String CODEC_ID = "RequestSupportViewCredentials";
    private static final String TARGET_PERSON_ID_KEY = "targetPersonId";

    public RequestSupportViewCredentialsSerialCodec() {
        super(RequestSupportViewCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(RequestSupportViewCredentials arg, AstObject serial) {
        encodeObject(serial, TARGET_PERSON_ID_KEY, arg.targetPersonId());
    }

    @Override
    public RequestSupportViewCredentials decode(ReadOnlyAstObject serial) {
        return new RequestSupportViewCredentials(
            decodeObject(serial, TARGET_PERSON_ID_KEY)
        );
    }
}
