package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.ClosePasswordSignInCredentials;

/**
 * @author Claude Code
 */
public final class ClosePasswordSignInCredentialsSerialCodec extends SerialCodecBase<ClosePasswordSignInCredentials> {

    private static final String CODEC_ID = "ClosePasswordSignInCredentials";

    public ClosePasswordSignInCredentialsSerialCodec() {
        super(ClosePasswordSignInCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(ClosePasswordSignInCredentials arg, AstObject serial) {
        // No fields — the server reads whose account it is from the session
    }

    @Override
    public ClosePasswordSignInCredentials decode(ReadOnlyAstObject serial) {
        return new ClosePasswordSignInCredentials();
    }
}
