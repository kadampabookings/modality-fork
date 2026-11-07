package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.UnblockAccountCredentials;

/**
 * @author Claude Code
 */
public final class UnblockAccountCredentialsSerialCodec extends SerialCodecBase<UnblockAccountCredentials> {

    private static final String CODEC_ID = "UnblockAccountCredentials";
    private static final String ACCOUNT_ID_KEY = "accountId";
    private static final String NOTE_KEY = "note";

    public UnblockAccountCredentialsSerialCodec() {
        super(UnblockAccountCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(UnblockAccountCredentials arg, AstObject serial) {
        encodeObject(serial, ACCOUNT_ID_KEY, arg.accountId());
        encodeString(serial, NOTE_KEY, arg.note());
    }

    @Override
    public UnblockAccountCredentials decode(ReadOnlyAstObject serial) {
        return new UnblockAccountCredentials(
            decodeObject(serial, ACCOUNT_ID_KEY),
            decodeString(serial, NOTE_KEY)
        );
    }
}
