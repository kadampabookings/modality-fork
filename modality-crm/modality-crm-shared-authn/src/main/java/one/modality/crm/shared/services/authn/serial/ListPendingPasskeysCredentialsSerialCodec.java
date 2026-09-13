package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.ListPendingPasskeysCredentials;

/**
 * @author Claude Code
 */
public final class ListPendingPasskeysCredentialsSerialCodec extends SerialCodecBase<ListPendingPasskeysCredentials> {

    private static final String CODEC_ID = "ListPendingPasskeysCredentials";

    public ListPendingPasskeysCredentialsSerialCodec() {
        super(ListPendingPasskeysCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(ListPendingPasskeysCredentials arg, AstObject serial) {
        // No fields — the server decides who may ask and what is pending
    }

    @Override
    public ListPendingPasskeysCredentials decode(ReadOnlyAstObject serial) {
        return new ListPendingPasskeysCredentials();
    }
}
