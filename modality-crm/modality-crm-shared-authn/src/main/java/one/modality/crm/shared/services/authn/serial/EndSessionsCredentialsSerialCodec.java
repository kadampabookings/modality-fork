package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.EndSessionsCredentials;

/**
 * @author Claude Code
 */
public final class EndSessionsCredentialsSerialCodec extends SerialCodecBase<EndSessionsCredentials> {

    private static final String CODEC_ID = "EndSessionsCredentials";
    private static final String ACCOUNT_ID_KEY = "accountId";
    private static final String SESSION_ID_KEY = "sessionId";
    private static final String NOTE_KEY = "note";

    public EndSessionsCredentialsSerialCodec() {
        super(EndSessionsCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(EndSessionsCredentials arg, AstObject serial) {
        encodeObject(serial, ACCOUNT_ID_KEY, arg.accountId());
        encodeString(serial, SESSION_ID_KEY, arg.sessionId());
        encodeString(serial, NOTE_KEY, arg.note());
    }

    @Override
    public EndSessionsCredentials decode(ReadOnlyAstObject serial) {
        return new EndSessionsCredentials(
            decodeObject(serial, ACCOUNT_ID_KEY),
            decodeString(serial, SESSION_ID_KEY),
            decodeString(serial, NOTE_KEY)
        );
    }
}
