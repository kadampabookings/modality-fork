package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.ListSecurityEventsCredentials;

/**
 * @author Claude Code
 */
public final class ListSecurityEventsCredentialsSerialCodec extends SerialCodecBase<ListSecurityEventsCredentials> {

    private static final String CODEC_ID = "ListSecurityEventsCredentials";

    public ListSecurityEventsCredentialsSerialCodec() {
        super(ListSecurityEventsCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(ListSecurityEventsCredentials arg, AstObject serial) {
        // No fields — everything else is the session's
    }

    @Override
    public ListSecurityEventsCredentials decode(ReadOnlyAstObject serial) {
        return new ListSecurityEventsCredentials();
    }
}
