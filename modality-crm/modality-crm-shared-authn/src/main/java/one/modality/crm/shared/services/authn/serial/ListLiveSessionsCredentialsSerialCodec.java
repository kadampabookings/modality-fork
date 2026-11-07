package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.ListLiveSessionsCredentials;

/**
 * @author Claude Code
 */
public final class ListLiveSessionsCredentialsSerialCodec extends SerialCodecBase<ListLiveSessionsCredentials> {

    private static final String CODEC_ID = "ListLiveSessionsCredentials";

    public ListLiveSessionsCredentialsSerialCodec() {
        super(ListLiveSessionsCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(ListLiveSessionsCredentials arg, AstObject serial) {
        // No fields — everything else is the session's
    }

    @Override
    public ListLiveSessionsCredentials decode(ReadOnlyAstObject serial) {
        return new ListLiveSessionsCredentials();
    }
}
