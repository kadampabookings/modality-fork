package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.RaiseSecurityAlarmCredentials;

/**
 * @author Claude Code
 */
public final class RaiseSecurityAlarmCredentialsSerialCodec extends SerialCodecBase<RaiseSecurityAlarmCredentials> {

    private static final String CODEC_ID = "RaiseSecurityAlarmCredentials";

    public RaiseSecurityAlarmCredentialsSerialCodec() {
        super(RaiseSecurityAlarmCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(RaiseSecurityAlarmCredentials arg, AstObject serial) {
        // No fields — not even a duration: how long an alarm lasts is the server's to decide
    }

    @Override
    public RaiseSecurityAlarmCredentials decode(ReadOnlyAstObject serial) {
        return new RaiseSecurityAlarmCredentials();
    }
}
