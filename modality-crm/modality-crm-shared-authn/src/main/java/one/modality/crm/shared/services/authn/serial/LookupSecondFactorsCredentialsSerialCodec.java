package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.LookupSecondFactorsCredentials;

/**
 * @author Claude Code
 */
public final class LookupSecondFactorsCredentialsSerialCodec extends SerialCodecBase<LookupSecondFactorsCredentials> {

    private static final String CODEC_ID = "LookupSecondFactorsCredentials";
    private static final String EMAIL_KEY = "email";

    public LookupSecondFactorsCredentialsSerialCodec() {
        super(LookupSecondFactorsCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(LookupSecondFactorsCredentials arg, AstObject serial) {
        encodeString(serial, EMAIL_KEY, arg.email());
    }

    @Override
    public LookupSecondFactorsCredentials decode(ReadOnlyAstObject serial) {
        return new LookupSecondFactorsCredentials(
            decodeString(serial, EMAIL_KEY)
        );
    }
}
