package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.DescribeDeviceLoginCredentials;

/**
 * @author Claude Code
 */
public final class DescribeDeviceLoginCredentialsSerialCodec extends SerialCodecBase<DescribeDeviceLoginCredentials> {

    private static final String CODEC_ID = "DescribeDeviceLoginCredentials";
    private static final String CODE_KEY = "code";

    public DescribeDeviceLoginCredentialsSerialCodec() {
        super(DescribeDeviceLoginCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(DescribeDeviceLoginCredentials arg, AstObject serial) {
        encodeString(serial, CODE_KEY, arg.code());
    }

    @Override
    public DescribeDeviceLoginCredentials decode(ReadOnlyAstObject serial) {
        return new DescribeDeviceLoginCredentials(decodeString(serial, CODE_KEY));
    }
}
