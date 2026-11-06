package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.OpenEnrolmentWindowCredentials;

/**
 * @author Claude Code
 */
public final class OpenEnrolmentWindowCredentialsSerialCodec extends SerialCodecBase<OpenEnrolmentWindowCredentials> {

    private static final String CODEC_ID = "OpenEnrolmentWindowCredentials";
    private static final String ACCOUNT_ID_KEY = "accountId";
    private static final String NOTE_KEY = "note";

    public OpenEnrolmentWindowCredentialsSerialCodec() {
        super(OpenEnrolmentWindowCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(OpenEnrolmentWindowCredentials arg, AstObject serial) {
        encodeObject(serial, ACCOUNT_ID_KEY, arg.accountId());
        encodeString(serial, NOTE_KEY, arg.note());
    }

    @Override
    public OpenEnrolmentWindowCredentials decode(ReadOnlyAstObject serial) {
        return new OpenEnrolmentWindowCredentials(
            decodeObject(serial, ACCOUNT_ID_KEY),
            decodeString(serial, NOTE_KEY)
        );
    }
}
