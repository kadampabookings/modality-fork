package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.AuthenticateWithPasskeyCredentials;

/**
 * @author Claude Code
 */
public final class AuthenticateWithPasskeyCredentialsSerialCodec extends SerialCodecBase<AuthenticateWithPasskeyCredentials> {

    private static final String CODEC_ID = "AuthenticateWithPasskeyCredentials";
    private static final String CREDENTIAL_ID_KEY = "credentialId";
    private static final String AUTHENTICATOR_DATA_KEY = "authenticatorData";
    private static final String CLIENT_DATA_JSON_KEY = "clientDataJSON";
    private static final String SIGNATURE_KEY = "signature";
    private static final String USER_HANDLE_KEY = "userHandle";

    public AuthenticateWithPasskeyCredentialsSerialCodec() {
        super(AuthenticateWithPasskeyCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(AuthenticateWithPasskeyCredentials arg, AstObject serial) {
        encodeString(serial, CREDENTIAL_ID_KEY, arg.credentialId());
        encodeString(serial, AUTHENTICATOR_DATA_KEY, arg.authenticatorData());
        encodeString(serial, CLIENT_DATA_JSON_KEY, arg.clientDataJSON());
        encodeString(serial, SIGNATURE_KEY, arg.signature());
        encodeString(serial, USER_HANDLE_KEY, arg.userHandle());
    }

    @Override
    public AuthenticateWithPasskeyCredentials decode(ReadOnlyAstObject serial) {
        return new AuthenticateWithPasskeyCredentials(
            decodeString(serial, CREDENTIAL_ID_KEY),
            decodeString(serial, AUTHENTICATOR_DATA_KEY),
            decodeString(serial, CLIENT_DATA_JSON_KEY),
            decodeString(serial, SIGNATURE_KEY),
            decodeString(serial, USER_HANDLE_KEY)
        );
    }
}
