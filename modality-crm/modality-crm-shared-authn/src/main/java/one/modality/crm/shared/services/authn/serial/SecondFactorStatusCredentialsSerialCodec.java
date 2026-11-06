package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.SecondFactorStatusCredentials;

/**
 * @author Claude Code
 */
public final class SecondFactorStatusCredentialsSerialCodec extends SerialCodecBase<SecondFactorStatusCredentials> {

    private static final String CODEC_ID = "SecondFactorStatusCredentials";

    public SecondFactorStatusCredentialsSerialCodec() {
        super(SecondFactorStatusCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(SecondFactorStatusCredentials arg, AstObject serial) {
        // No fields — the account is the session's
    }

    @Override
    public SecondFactorStatusCredentials decode(ReadOnlyAstObject serial) {
        return new SecondFactorStatusCredentials();
    }
}
