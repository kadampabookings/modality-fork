package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.SendBookingAccessEmailCredentials;

/**
 * @author Bruno Salmon
 */
public final class SendBookingAccessEmailCredentialsSerialCodec extends SerialCodecBase<SendBookingAccessEmailCredentials> {

    private static final String CODEC_ID     = "SendBookingAccessEmailCredentials";
    private static final String EMAIL_KEY    = "email";
    private static final String ORIGIN_KEY   = "clientOrigin";
    private static final String LANG_KEY     = "lang";

    public SendBookingAccessEmailCredentialsSerialCodec() {
        super(SendBookingAccessEmailCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(SendBookingAccessEmailCredentials arg, AstObject serial) {
        encodeString(serial, EMAIL_KEY,  arg.email());
        encodeString(serial, ORIGIN_KEY, arg.clientOrigin());
        encodeString(serial, LANG_KEY,   arg.lang());
    }

    @Override
    public SendBookingAccessEmailCredentials decode(ReadOnlyAstObject serial) {
        return new SendBookingAccessEmailCredentials(
            decodeString(serial, EMAIL_KEY),
            decodeString(serial, ORIGIN_KEY),
            decodeString(serial, LANG_KEY)
        );
    }
}
