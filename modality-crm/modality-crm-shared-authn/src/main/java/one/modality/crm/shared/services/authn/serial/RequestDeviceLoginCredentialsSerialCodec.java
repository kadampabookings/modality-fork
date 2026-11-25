package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.RequestDeviceLoginCredentials;

/**
 * @author Claude Code
 */
public final class RequestDeviceLoginCredentialsSerialCodec extends SerialCodecBase<RequestDeviceLoginCredentials> {

    private static final String CODEC_ID = "RequestDeviceLoginCredentials";
    private static final String BACKOFFICE_KEY = "backoffice";

    public RequestDeviceLoginCredentialsSerialCodec() {
        super(RequestDeviceLoginCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(RequestDeviceLoginCredentials arg, AstObject serial) {
        encodeBoolean(serial, BACKOFFICE_KEY, arg.backoffice());
    }

    @Override
    public RequestDeviceLoginCredentials decode(ReadOnlyAstObject serial) {
        // Absent reads as the front office: the arm that can do less, so a malformed or truncated request
        // never asks for the wider one. It is only a request either way - the approver decides.
        return new RequestDeviceLoginCredentials(Boolean.TRUE.equals(decodeBoolean(serial, BACKOFFICE_KEY)));
    }
}
