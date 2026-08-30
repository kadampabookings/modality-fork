package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.RemovePasskeyCredentials;

/**
 * @author Claude Code
 */
public final class RemovePasskeyCredentialsSerialCodec extends SerialCodecBase<RemovePasskeyCredentials> {

    private static final String CODEC_ID = "RemovePasskeyCredentials";
    private static final String PASSKEY_ID_KEY = "passkeyId";

    public RemovePasskeyCredentialsSerialCodec() {
        super(RemovePasskeyCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(RemovePasskeyCredentials arg, AstObject serial) {
        encodeObject(serial, PASSKEY_ID_KEY, arg.passkeyId());
    }

    @Override
    public RemovePasskeyCredentials decode(ReadOnlyAstObject serial) {
        return new RemovePasskeyCredentials(
            decodeObject(serial, PASSKEY_ID_KEY)
        );
    }
}
