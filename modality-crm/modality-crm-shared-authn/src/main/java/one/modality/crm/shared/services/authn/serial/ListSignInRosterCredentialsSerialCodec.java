package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.ListSignInRosterCredentials;

/**
 * @author Claude Code
 */
public final class ListSignInRosterCredentialsSerialCodec extends SerialCodecBase<ListSignInRosterCredentials> {

    private static final String CODEC_ID = "ListSignInRosterCredentials";

    public ListSignInRosterCredentialsSerialCodec() {
        super(ListSignInRosterCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(ListSignInRosterCredentials arg, AstObject serial) {
        // No fields — everything else is the session's
    }

    @Override
    public ListSignInRosterCredentials decode(ReadOnlyAstObject serial) {
        return new ListSignInRosterCredentials();
    }
}
