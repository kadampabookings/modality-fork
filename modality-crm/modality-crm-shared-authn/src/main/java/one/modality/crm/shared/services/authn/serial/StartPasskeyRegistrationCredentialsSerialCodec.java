package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.StartPasskeyRegistrationCredentials;

/**
 * @author Claude Code
 */
public final class StartPasskeyRegistrationCredentialsSerialCodec extends SerialCodecBase<StartPasskeyRegistrationCredentials> {

    private static final String CODEC_ID = "StartPasskeyRegistrationCredentials";
    private static final String CURRENT_PASSWORD_KEY = "currentPassword";

    public StartPasskeyRegistrationCredentialsSerialCodec() {
        super(StartPasskeyRegistrationCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(StartPasskeyRegistrationCredentials arg, AstObject serial) {
        // Nothing about the ceremony — the server chooses every parameter of it; only the proof
        encodeString(serial, CURRENT_PASSWORD_KEY, arg.currentPassword());
    }

    @Override
    public StartPasskeyRegistrationCredentials decode(ReadOnlyAstObject serial) {
        return new StartPasskeyRegistrationCredentials(decodeString(serial, CURRENT_PASSWORD_KEY));
    }
}
