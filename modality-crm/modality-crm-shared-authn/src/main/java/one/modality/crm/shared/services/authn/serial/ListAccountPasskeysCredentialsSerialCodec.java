package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.ListAccountPasskeysCredentials;

/**
 * @author Claude Code
 */
public final class ListAccountPasskeysCredentialsSerialCodec extends SerialCodecBase<ListAccountPasskeysCredentials> {

    private static final String CODEC_ID = "ListAccountPasskeysCredentials";
    private static final String ACCOUNT_ID_KEY = "accountId";

    public ListAccountPasskeysCredentialsSerialCodec() {
        super(ListAccountPasskeysCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(ListAccountPasskeysCredentials arg, AstObject serial) {
        encodeObject(serial, ACCOUNT_ID_KEY, arg.accountId());
    }

    @Override
    public ListAccountPasskeysCredentials decode(ReadOnlyAstObject serial) {
        return new ListAccountPasskeysCredentials(
            decodeObject(serial, ACCOUNT_ID_KEY)
        );
    }
}
