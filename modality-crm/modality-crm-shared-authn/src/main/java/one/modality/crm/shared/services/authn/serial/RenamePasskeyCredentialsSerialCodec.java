package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.RenamePasskeyCredentials;

/**
 * @author Claude Code
 */
public final class RenamePasskeyCredentialsSerialCodec extends SerialCodecBase<RenamePasskeyCredentials> {

    private static final String CODEC_ID = "RenamePasskeyCredentials";
    private static final String PASSKEY_ID_KEY = "passkeyId";
    private static final String LABEL_KEY = "label";

    public RenamePasskeyCredentialsSerialCodec() {
        super(RenamePasskeyCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(RenamePasskeyCredentials arg, AstObject serial) {
        encodeObject(serial, PASSKEY_ID_KEY, arg.passkeyId());
        encodeString(serial, LABEL_KEY, arg.label());
    }

    @Override
    public RenamePasskeyCredentials decode(ReadOnlyAstObject serial) {
        return new RenamePasskeyCredentials(
            decodeObject(serial, PASSKEY_ID_KEY),
            decodeString(serial, LABEL_KEY)
        );
    }
}
