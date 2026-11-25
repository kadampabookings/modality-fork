package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.ApproveDeviceLoginCredentials;

/**
 * @author Claude Code
 */
public final class ApproveDeviceLoginCredentialsSerialCodec extends SerialCodecBase<ApproveDeviceLoginCredentials> {

    private static final String CODEC_ID = "ApproveDeviceLoginCredentials";
    private static final String CODE_KEY = "code";

    public ApproveDeviceLoginCredentialsSerialCodec() {
        super(ApproveDeviceLoginCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(ApproveDeviceLoginCredentials arg, AstObject serial) {
        encodeString(serial, CODE_KEY, arg.code());
    }

    @Override
    public ApproveDeviceLoginCredentials decode(ReadOnlyAstObject serial) {
        return new ApproveDeviceLoginCredentials(decodeString(serial, CODE_KEY));
    }
}
