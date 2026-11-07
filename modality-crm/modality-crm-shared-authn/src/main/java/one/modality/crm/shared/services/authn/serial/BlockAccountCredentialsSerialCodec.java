package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.BlockAccountCredentials;

/**
 * @author Claude Code
 */
public final class BlockAccountCredentialsSerialCodec extends SerialCodecBase<BlockAccountCredentials> {

    private static final String CODEC_ID = "BlockAccountCredentials";
    private static final String ACCOUNT_ID_KEY = "accountId";
    private static final String NOTE_KEY = "note";

    public BlockAccountCredentialsSerialCodec() {
        super(BlockAccountCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(BlockAccountCredentials arg, AstObject serial) {
        encodeObject(serial, ACCOUNT_ID_KEY, arg.accountId());
        encodeString(serial, NOTE_KEY, arg.note());
    }

    @Override
    public BlockAccountCredentials decode(ReadOnlyAstObject serial) {
        return new BlockAccountCredentials(
            decodeObject(serial, ACCOUNT_ID_KEY),
            decodeString(serial, NOTE_KEY)
        );
    }
}
