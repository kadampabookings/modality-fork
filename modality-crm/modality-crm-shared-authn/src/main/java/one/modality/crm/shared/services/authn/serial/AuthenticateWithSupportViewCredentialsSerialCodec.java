package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.AuthenticateWithSupportViewCredentials;

/**
 * @author Claude Code
 */
public final class AuthenticateWithSupportViewCredentialsSerialCodec extends SerialCodecBase<AuthenticateWithSupportViewCredentials> {

    private static final String CODEC_ID = "AuthenticateWithSupportViewCredentials";
    private static final String TOKEN_KEY = "token";

    public AuthenticateWithSupportViewCredentialsSerialCodec() {
        super(AuthenticateWithSupportViewCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(AuthenticateWithSupportViewCredentials arg, AstObject serial) {
        encodeString(serial, TOKEN_KEY, arg.token());
    }

    @Override
    public AuthenticateWithSupportViewCredentials decode(ReadOnlyAstObject serial) {
        return new AuthenticateWithSupportViewCredentials(
            decodeString(serial, TOKEN_KEY)
        );
    }
}
