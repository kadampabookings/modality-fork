package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.ListSecondFactorsCredentials;

/**
 * @author Claude Code
 */
public final class ListSecondFactorsCredentialsSerialCodec extends SerialCodecBase<ListSecondFactorsCredentials> {

    private static final String CODEC_ID = "ListSecondFactorsCredentials";

    public ListSecondFactorsCredentialsSerialCodec() {
        super(ListSecondFactorsCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(ListSecondFactorsCredentials arg, AstObject serial) {
        // No fields — the server lists only the caller's own account's factors
    }

    @Override
    public ListSecondFactorsCredentials decode(ReadOnlyAstObject serial) {
        return new ListSecondFactorsCredentials();
    }
}
