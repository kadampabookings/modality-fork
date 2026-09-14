package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.CancelSecondFactorCredentials;

/**
 * @author Claude Code
 */
public final class CancelSecondFactorCredentialsSerialCodec extends SerialCodecBase<CancelSecondFactorCredentials> {

    private static final String CODEC_ID = "CancelSecondFactorCredentials";

    public CancelSecondFactorCredentialsSerialCodec() {
        super(CancelSecondFactorCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(CancelSecondFactorCredentials arg, AstObject serial) {
        // No fields — the pending entry is keyed by the runId the connection already carries
    }

    @Override
    public CancelSecondFactorCredentials decode(ReadOnlyAstObject serial) {
        return new CancelSecondFactorCredentials();
    }
}
