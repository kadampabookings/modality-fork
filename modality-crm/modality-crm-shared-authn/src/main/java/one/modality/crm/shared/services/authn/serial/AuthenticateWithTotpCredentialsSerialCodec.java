package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.AuthenticateWithTotpCredentials;

/**
 * @author Claude Code
 */
public final class AuthenticateWithTotpCredentialsSerialCodec extends SerialCodecBase<AuthenticateWithTotpCredentials> {

    private static final String CODEC_ID = "AuthenticateWithTotpCredentials";
    private static final String CODE_KEY = "code";

    public AuthenticateWithTotpCredentialsSerialCodec() {
        super(AuthenticateWithTotpCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(AuthenticateWithTotpCredentials arg, AstObject serial) {
        encodeString(serial, CODE_KEY, arg.code());
    }

    @Override
    public AuthenticateWithTotpCredentials decode(ReadOnlyAstObject serial) {
        return new AuthenticateWithTotpCredentials(
            decodeString(serial, CODE_KEY)
        );
    }
}
