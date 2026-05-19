package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.AuthenticateWithCartCredentials;

/**
 * @author Bruno Salmon
 */
public final class AuthenticateWithCartCredentialsSerialCodec extends SerialCodecBase<AuthenticateWithCartCredentials> {

    private static final String CODEC_ID = "AuthenticateWithCartCredentials";
    private static final String CART_UUID_KEY = "cartUuid";

    public AuthenticateWithCartCredentialsSerialCodec() {
        super(AuthenticateWithCartCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(AuthenticateWithCartCredentials arg, AstObject serial) {
        encodeString(serial, CART_UUID_KEY, arg.cartUuid());
    }

    @Override
    public AuthenticateWithCartCredentials decode(ReadOnlyAstObject serial) {
        return new AuthenticateWithCartCredentials(
            decodeString(serial, CART_UUID_KEY)
        );
    }
}
