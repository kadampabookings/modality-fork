package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.AuthenticateWithBackOfficeViewCredentials;

/**
 * @author Claude Code
 */
public final class AuthenticateWithBackOfficeViewCredentialsSerialCodec extends SerialCodecBase<AuthenticateWithBackOfficeViewCredentials> {

    private static final String CODEC_ID = "AuthenticateWithBackOfficeViewCredentials";
    private static final String TOKEN_KEY = "token";

    public AuthenticateWithBackOfficeViewCredentialsSerialCodec() {
        super(AuthenticateWithBackOfficeViewCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(AuthenticateWithBackOfficeViewCredentials arg, AstObject serial) {
        encodeString(serial, TOKEN_KEY, arg.token());
    }

    @Override
    public AuthenticateWithBackOfficeViewCredentials decode(ReadOnlyAstObject serial) {
        return new AuthenticateWithBackOfficeViewCredentials(
            decodeString(serial, TOKEN_KEY)
        );
    }
}
