package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.ListSignInApprovalsCredentials;

/**
 * @author Claude Code
 */
public final class ListSignInApprovalsCredentialsSerialCodec extends SerialCodecBase<ListSignInApprovalsCredentials> {

    private static final String CODEC_ID = "ListSignInApprovalsCredentials";

    public ListSignInApprovalsCredentialsSerialCodec() {
        super(ListSignInApprovalsCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(ListSignInApprovalsCredentials arg, AstObject serial) {
        // No fields — the account is the session's
    }

    @Override
    public ListSignInApprovalsCredentials decode(ReadOnlyAstObject serial) {
        return new ListSignInApprovalsCredentials();
    }
}
