package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.ListPasskeysCredentials;

/**
 * @author Claude Code
 */
public final class ListPasskeysCredentialsSerialCodec extends SerialCodecBase<ListPasskeysCredentials> {

    private static final String CODEC_ID = "ListPasskeysCredentials";

    public ListPasskeysCredentialsSerialCodec() {
        super(ListPasskeysCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(ListPasskeysCredentials arg, AstObject serial) {
        // No fields — the server lists only the caller's own account's passkeys
    }

    @Override
    public ListPasskeysCredentials decode(ReadOnlyAstObject serial) {
        return new ListPasskeysCredentials();
    }
}
