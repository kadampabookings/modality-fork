package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.RejectPasskeyCredentials;

/**
 * @author Claude Code
 */
public final class RejectPasskeyCredentialsSerialCodec extends SerialCodecBase<RejectPasskeyCredentials> {

    private static final String CODEC_ID = "RejectPasskeyCredentials";
    private static final String PASSKEY_ID_KEY = "passkeyId";

    public RejectPasskeyCredentialsSerialCodec() {
        super(RejectPasskeyCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(RejectPasskeyCredentials arg, AstObject serial) {
        encodeObject(serial, PASSKEY_ID_KEY, arg.passkeyId());
    }

    @Override
    public RejectPasskeyCredentials decode(ReadOnlyAstObject serial) {
        return new RejectPasskeyCredentials(
            decodeObject(serial, PASSKEY_ID_KEY)
        );
    }
}
