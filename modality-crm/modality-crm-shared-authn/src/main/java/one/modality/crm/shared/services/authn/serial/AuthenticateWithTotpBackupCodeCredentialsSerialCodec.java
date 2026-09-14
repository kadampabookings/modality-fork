package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.AuthenticateWithTotpBackupCodeCredentials;

/**
 * @author Claude Code
 */
public final class AuthenticateWithTotpBackupCodeCredentialsSerialCodec extends SerialCodecBase<AuthenticateWithTotpBackupCodeCredentials> {

    private static final String CODEC_ID = "AuthenticateWithTotpBackupCodeCredentials";
    private static final String CODE_KEY = "code";

    public AuthenticateWithTotpBackupCodeCredentialsSerialCodec() {
        super(AuthenticateWithTotpBackupCodeCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(AuthenticateWithTotpBackupCodeCredentials arg, AstObject serial) {
        encodeString(serial, CODE_KEY, arg.code());
    }

    @Override
    public AuthenticateWithTotpBackupCodeCredentials decode(ReadOnlyAstObject serial) {
        return new AuthenticateWithTotpBackupCodeCredentials(
            decodeString(serial, CODE_KEY)
        );
    }
}
