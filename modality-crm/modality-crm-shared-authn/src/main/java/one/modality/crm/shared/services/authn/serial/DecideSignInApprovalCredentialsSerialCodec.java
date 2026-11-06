package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.DecideSignInApprovalCredentials;

/**
 * @author Claude Code
 */
public final class DecideSignInApprovalCredentialsSerialCodec extends SerialCodecBase<DecideSignInApprovalCredentials> {

    private static final String CODEC_ID = "DecideSignInApprovalCredentials";
    private static final String KIND_KEY = "kind";
    private static final String ID_KEY = "id";
    private static final String APPROVE_KEY = "approve";
    private static final String LOCK_ACCOUNT_KEY = "lockAccount";

    public DecideSignInApprovalCredentialsSerialCodec() {
        super(DecideSignInApprovalCredentials.class, CODEC_ID);
    }

    @Override
    public void encode(DecideSignInApprovalCredentials arg, AstObject serial) {
        encodeString(serial, KIND_KEY, arg.kind());
        encodeObject(serial, ID_KEY, arg.id());
        encodeBoolean(serial, APPROVE_KEY, arg.approve());
        encodeBoolean(serial, LOCK_ACCOUNT_KEY, arg.lockAccount());
    }

    @Override
    public DecideSignInApprovalCredentials decode(ReadOnlyAstObject serial) {
        return new DecideSignInApprovalCredentials(
            decodeString(serial, KIND_KEY),
            decodeObject(serial, ID_KEY),
            decodeBoolean(serial, APPROVE_KEY),
            decodeBoolean(serial, LOCK_ACCOUNT_KEY)
        );
    }
}
