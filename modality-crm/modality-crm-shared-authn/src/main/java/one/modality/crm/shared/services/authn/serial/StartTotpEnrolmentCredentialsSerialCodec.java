package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.StartTotpEnrolmentCredentials;

/**
 * @author Claude Code
 */
public final class StartTotpEnrolmentCredentialsSerialCodec extends SerialCodecBase<StartTotpEnrolmentCredentials> {

    private static final String CODEC_ID = "StartTotpEnrolmentCredentials";
    private static final String LABEL_KEY = "label";
    // Optional: omitted when this tab signed in with the password minutes ago (CredentialChangeProof)
    private static final String CURRENT_PASSWORD_KEY = "currentPassword";
    private static final String DEVICE_INFO_KEY = "deviceInfo";

    public StartTotpEnrolmentCredentialsSerialCodec() {
        super(StartTotpEnrolmentCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(StartTotpEnrolmentCredentials arg, AstObject serial) {
        encodeString(serial, LABEL_KEY, arg.label());
        encodeString(serial, CURRENT_PASSWORD_KEY, arg.currentPassword());
        encodeString(serial, DEVICE_INFO_KEY, arg.deviceInfo());
    }

    @Override
    public StartTotpEnrolmentCredentials decode(ReadOnlyAstObject serial) {
        return new StartTotpEnrolmentCredentials(
            decodeString(serial, LABEL_KEY),
            decodeString(serial, CURRENT_PASSWORD_KEY),
            decodeString(serial, DEVICE_INFO_KEY)
        );
    }
}
