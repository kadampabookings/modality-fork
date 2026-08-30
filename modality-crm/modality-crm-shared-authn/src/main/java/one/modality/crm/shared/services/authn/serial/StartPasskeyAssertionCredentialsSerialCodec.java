package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.StartPasskeyAssertionCredentials;

/**
 * @author Claude Code
 */
public final class StartPasskeyAssertionCredentialsSerialCodec extends SerialCodecBase<StartPasskeyAssertionCredentials> {

    private static final String CODEC_ID = "StartPasskeyAssertionCredentials";

    public StartPasskeyAssertionCredentialsSerialCodec() {
        super(StartPasskeyAssertionCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(StartPasskeyAssertionCredentials arg, AstObject serial) {
        // No fields — an account is never named before the authenticator has proven possession
    }

    @Override
    public StartPasskeyAssertionCredentials decode(ReadOnlyAstObject serial) {
        return new StartPasskeyAssertionCredentials();
    }
}
