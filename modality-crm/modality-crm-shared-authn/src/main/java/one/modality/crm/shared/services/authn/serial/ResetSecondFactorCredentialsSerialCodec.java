package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.ResetSecondFactorCredentials;

/**
 * @author Claude Code
 */
public final class ResetSecondFactorCredentialsSerialCodec extends SerialCodecBase<ResetSecondFactorCredentials> {

    private static final String CODEC_ID = "ResetSecondFactorCredentials";
    private static final String ACCOUNT_ID_KEY = "accountId";
    private static final String WHAT_KEY = "what";
    private static final String NOTE_KEY = "note";

    public ResetSecondFactorCredentialsSerialCodec() {
        super(ResetSecondFactorCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(ResetSecondFactorCredentials arg, AstObject serial) {
        encodeObject(serial, ACCOUNT_ID_KEY, arg.accountId());
        encodeString(serial, WHAT_KEY, arg.what());
        encodeString(serial, NOTE_KEY, arg.note());
    }

    @Override
    public ResetSecondFactorCredentials decode(ReadOnlyAstObject serial) {
        return new ResetSecondFactorCredentials(
            decodeObject(serial, ACCOUNT_ID_KEY),
            decodeString(serial, WHAT_KEY),
            decodeString(serial, NOTE_KEY)
        );
    }
}
