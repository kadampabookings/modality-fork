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

    public StartPasskeyRegistrationCredentialsSerialCodec() {
        super(StartPasskeyRegistrationCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(StartPasskeyRegistrationCredentials arg, AstObject serial) {
        // No fields — the server chooses every ceremony parameter
    }

    @Override
    public StartPasskeyRegistrationCredentials decode(ReadOnlyAstObject serial) {
        return new StartPasskeyRegistrationCredentials();
    }
}
