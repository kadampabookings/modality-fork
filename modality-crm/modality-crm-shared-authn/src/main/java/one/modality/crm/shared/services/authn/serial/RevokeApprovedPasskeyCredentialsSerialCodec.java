package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.RevokeApprovedPasskeyCredentials;

/**
 * @author Claude Code
 */
public final class RevokeApprovedPasskeyCredentialsSerialCodec extends SerialCodecBase<RevokeApprovedPasskeyCredentials> {

    private static final String CODEC_ID = "RevokeApprovedPasskeyCredentials";
    private static final String PASSKEY_ID_KEY = "passkeyId";
    private static final String NOTE_KEY = "note";

    public RevokeApprovedPasskeyCredentialsSerialCodec() {
        super(RevokeApprovedPasskeyCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(RevokeApprovedPasskeyCredentials arg, AstObject serial) {
        encodeObject(serial, PASSKEY_ID_KEY, arg.passkeyId());
        encodeString(serial, NOTE_KEY, arg.note());
    }

    @Override
    public RevokeApprovedPasskeyCredentials decode(ReadOnlyAstObject serial) {
        return new RevokeApprovedPasskeyCredentials(
            decodeObject(serial, PASSKEY_ID_KEY),
            decodeString(serial, NOTE_KEY)
        );
    }
}
