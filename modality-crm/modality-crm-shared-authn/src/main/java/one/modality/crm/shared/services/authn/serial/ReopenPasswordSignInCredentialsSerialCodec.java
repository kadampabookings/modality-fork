package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.ReopenPasswordSignInCredentials;

/**
 * @author Claude Code
 */
public final class ReopenPasswordSignInCredentialsSerialCodec extends SerialCodecBase<ReopenPasswordSignInCredentials> {

    private static final String CODEC_ID = "ReopenPasswordSignInCredentials";
    private static final String ACCOUNT_ID_KEY = "accountId";
    private static final String NOTE_KEY = "note";

    public ReopenPasswordSignInCredentialsSerialCodec() {
        super(ReopenPasswordSignInCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(ReopenPasswordSignInCredentials arg, AstObject serial) {
        encodeObject(serial, ACCOUNT_ID_KEY, arg.accountId());
        encodeString(serial, NOTE_KEY, arg.note());
    }

    @Override
    public ReopenPasswordSignInCredentials decode(ReadOnlyAstObject serial) {
        // Absent for the owner's own reopen; an account id only for a super administrator's rescue
        return new ReopenPasswordSignInCredentials(decodeObject(serial, ACCOUNT_ID_KEY), decodeString(serial, NOTE_KEY));
    }
}
