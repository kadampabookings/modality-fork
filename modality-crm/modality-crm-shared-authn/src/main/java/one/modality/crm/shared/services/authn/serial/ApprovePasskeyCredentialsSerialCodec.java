package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.ApprovePasskeyCredentials;

/**
 * @author Claude Code
 */
public final class ApprovePasskeyCredentialsSerialCodec extends SerialCodecBase<ApprovePasskeyCredentials> {

    private static final String CODEC_ID = "ApprovePasskeyCredentials";
    private static final String PASSKEY_ID_KEY = "passkeyId";

    public ApprovePasskeyCredentialsSerialCodec() {
        super(ApprovePasskeyCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(ApprovePasskeyCredentials arg, AstObject serial) {
        encodeObject(serial, PASSKEY_ID_KEY, arg.passkeyId());
    }

    @Override
    public ApprovePasskeyCredentials decode(ReadOnlyAstObject serial) {
        return new ApprovePasskeyCredentials(
            decodeObject(serial, PASSKEY_ID_KEY)
        );
    }
}
