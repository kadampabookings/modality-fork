package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.ConfirmTotpEnrolmentCredentials;

/**
 * @author Claude Code
 */
public final class ConfirmTotpEnrolmentCredentialsSerialCodec extends SerialCodecBase<ConfirmTotpEnrolmentCredentials> {

    private static final String CODEC_ID = "ConfirmTotpEnrolmentCredentials";
    private static final String CODE_KEY = "code";

    public ConfirmTotpEnrolmentCredentialsSerialCodec() {
        super(ConfirmTotpEnrolmentCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(ConfirmTotpEnrolmentCredentials arg, AstObject serial) {
        encodeString(serial, CODE_KEY, arg.code());
    }

    @Override
    public ConfirmTotpEnrolmentCredentials decode(ReadOnlyAstObject serial) {
        return new ConfirmTotpEnrolmentCredentials(
            decodeString(serial, CODE_KEY)
        );
    }
}
