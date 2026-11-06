package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.SecondFactorCoverageCredentials;

/**
 * @author Claude Code
 */
public final class SecondFactorCoverageCredentialsSerialCodec extends SerialCodecBase<SecondFactorCoverageCredentials> {

    private static final String CODEC_ID = "SecondFactorCoverageCredentials";

    public SecondFactorCoverageCredentialsSerialCodec() {
        super(SecondFactorCoverageCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(SecondFactorCoverageCredentials arg, AstObject serial) {
        // No fields — the account is the session's
    }

    @Override
    public SecondFactorCoverageCredentials decode(ReadOnlyAstObject serial) {
        return new SecondFactorCoverageCredentials();
    }
}
