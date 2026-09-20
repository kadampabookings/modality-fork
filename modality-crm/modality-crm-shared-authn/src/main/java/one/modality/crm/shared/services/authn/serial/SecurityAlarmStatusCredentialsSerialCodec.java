package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.SecurityAlarmStatusCredentials;

/**
 * @author Claude Code
 */
public final class SecurityAlarmStatusCredentialsSerialCodec extends SerialCodecBase<SecurityAlarmStatusCredentials> {

    private static final String CODEC_ID = "SecurityAlarmStatusCredentials";

    public SecurityAlarmStatusCredentialsSerialCodec() {
        super(SecurityAlarmStatusCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(SecurityAlarmStatusCredentials arg, AstObject serial) {
        // No fields — the question is about the system, and who is asking comes from the session
    }

    @Override
    public SecurityAlarmStatusCredentials decode(ReadOnlyAstObject serial) {
        return new SecurityAlarmStatusCredentials();
    }
}
