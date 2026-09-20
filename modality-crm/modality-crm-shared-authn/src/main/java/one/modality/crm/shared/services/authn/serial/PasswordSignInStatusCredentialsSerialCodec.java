package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.PasswordSignInStatusCredentials;

/**
 * @author Claude Code
 */
public final class PasswordSignInStatusCredentialsSerialCodec extends SerialCodecBase<PasswordSignInStatusCredentials> {

    private static final String CODEC_ID = "PasswordSignInStatusCredentials";

    public PasswordSignInStatusCredentialsSerialCodec() {
        super(PasswordSignInStatusCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(PasswordSignInStatusCredentials arg, AstObject serial) {
        // No fields — the server reads whose account it is from the session
    }

    @Override
    public PasswordSignInStatusCredentials decode(ReadOnlyAstObject serial) {
        return new PasswordSignInStatusCredentials();
    }
}
