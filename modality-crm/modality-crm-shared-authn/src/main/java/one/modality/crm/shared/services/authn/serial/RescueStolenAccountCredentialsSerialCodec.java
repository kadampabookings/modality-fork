package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.RescueStolenAccountCredentials;

/**
 * @author Claude Code
 */
public final class RescueStolenAccountCredentialsSerialCodec extends SerialCodecBase<RescueStolenAccountCredentials> {

    private static final String CODEC_ID = "RescueStolenAccountCredentials";
    private static final String ACCOUNT_ID_KEY = "accountId";
    private static final String NOTE_KEY = "note";

    public RescueStolenAccountCredentialsSerialCodec() {
        super(RescueStolenAccountCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(RescueStolenAccountCredentials arg, AstObject serial) {
        encodeObject(serial, ACCOUNT_ID_KEY, arg.accountId());
        encodeString(serial, NOTE_KEY, arg.note());
    }

    @Override
    public RescueStolenAccountCredentials decode(ReadOnlyAstObject serial) {
        return new RescueStolenAccountCredentials(decodeObject(serial, ACCOUNT_ID_KEY), decodeString(serial, NOTE_KEY));
    }
}
