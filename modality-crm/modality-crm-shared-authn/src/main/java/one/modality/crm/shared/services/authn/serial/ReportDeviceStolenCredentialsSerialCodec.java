package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.ReportDeviceStolenCredentials;

/**
 * @author Claude Code
 */
public final class ReportDeviceStolenCredentialsSerialCodec extends SerialCodecBase<ReportDeviceStolenCredentials> {

    private static final String CODEC_ID = "ReportDeviceStolenCredentials";

    public ReportDeviceStolenCredentialsSerialCodec() {
        super(ReportDeviceStolenCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(ReportDeviceStolenCredentials arg, AstObject serial) {
        // No fields — whose account it is comes from the session, never from the caller
    }

    @Override
    public ReportDeviceStolenCredentials decode(ReadOnlyAstObject serial) {
        return new ReportDeviceStolenCredentials();
    }
}
