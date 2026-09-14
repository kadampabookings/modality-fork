package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.RemoveTotpCredentials;

/**
 * @author Claude Code
 */
public final class RemoveTotpCredentialsSerialCodec extends SerialCodecBase<RemoveTotpCredentials> {

    private static final String CODEC_ID = "RemoveTotpCredentials";
    private static final String TOTP_ID_KEY = "totpId";
    private static final String CODE_KEY = "code";

    public RemoveTotpCredentialsSerialCodec() {
        super(RemoveTotpCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(RemoveTotpCredentials arg, AstObject serial) {
        encodeObject(serial, TOTP_ID_KEY, arg.totpId());
        encodeString(serial, CODE_KEY, arg.code());
    }

    @Override
    public RemoveTotpCredentials decode(ReadOnlyAstObject serial) {
        return new RemoveTotpCredentials(
            decodeObject(serial, TOTP_ID_KEY),
            decodeString(serial, CODE_KEY)
        );
    }
}
