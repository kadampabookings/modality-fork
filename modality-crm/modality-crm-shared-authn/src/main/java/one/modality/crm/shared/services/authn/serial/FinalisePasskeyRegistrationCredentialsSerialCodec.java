package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.FinalisePasskeyRegistrationCredentials;

/**
 * @author Claude Code
 */
public final class FinalisePasskeyRegistrationCredentialsSerialCodec extends SerialCodecBase<FinalisePasskeyRegistrationCredentials> {

    private static final String CODEC_ID = "FinalisePasskeyRegistrationCredentials";
    private static final String ATTESTATION_OBJECT_KEY = "attestationObject";
    private static final String CLIENT_DATA_JSON_KEY = "clientDataJSON";
    private static final String TRANSPORTS_KEY = "transports";
    private static final String LABEL_KEY = "label";
    // What the browser says it is — shown to an approver as "reported by the browser", never trusted
    private static final String DEVICE_INFO_KEY = "deviceInfo";

    public FinalisePasskeyRegistrationCredentialsSerialCodec() {
        super(FinalisePasskeyRegistrationCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(FinalisePasskeyRegistrationCredentials arg, AstObject serial) {
        encodeString(serial, ATTESTATION_OBJECT_KEY, arg.attestationObject());
        encodeString(serial, CLIENT_DATA_JSON_KEY, arg.clientDataJSON());
        encodeString(serial, TRANSPORTS_KEY, arg.transports());
        encodeString(serial, LABEL_KEY, arg.label());
        encodeString(serial, DEVICE_INFO_KEY, arg.deviceInfo());
    }

    @Override
    public FinalisePasskeyRegistrationCredentials decode(ReadOnlyAstObject serial) {
        return new FinalisePasskeyRegistrationCredentials(
            decodeString(serial, ATTESTATION_OBJECT_KEY),
            decodeString(serial, CLIENT_DATA_JSON_KEY),
            decodeString(serial, TRANSPORTS_KEY),
            decodeString(serial, LABEL_KEY),
            decodeString(serial, DEVICE_INFO_KEY)
        );
    }
}
