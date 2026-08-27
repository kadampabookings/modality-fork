package one.modality.crm.shared.services.authn.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

/**
 * @author Bruno Salmon
 */
public final class ModalityUserPrincipalSerialCodec extends SerialCodecBase<ModalityUserPrincipal> {

    private static final String CODEC_ID = "ModalityUserPrincipal";
    private static final String USER_PERSON_ID_KEY = "userPersonId";
    private static final String USER_ACCOUNT_ID_KEY = "userAccountId";
    // Absent on every ordinary login, so old clients and stored sessions decode unchanged.
    private static final String SUPPORT_AGENT_PERSON_ID_KEY = "supportAgentPersonId";

    public ModalityUserPrincipalSerialCodec() {
        super(ModalityUserPrincipal.class, CODEC_ID);
    }

    @Override
    public void encode(ModalityUserPrincipal arg, AstObject serial) {
        encodeObject(serial, USER_PERSON_ID_KEY,  arg.getUserPersonId());
        encodeObject(serial, USER_ACCOUNT_ID_KEY, arg.getUserAccountId());
        // The client reads this to show the "you are viewing X's account" banner. It carries no
        // authority: the server decides what a support view may do from its own session state,
        // never from what comes back over the wire.
        encodeObject(serial, SUPPORT_AGENT_PERSON_ID_KEY, arg.getSupportAgentPersonId());
    }

    @Override
    public ModalityUserPrincipal decode(ReadOnlyAstObject serial) {
        return new ModalityUserPrincipal(
                decodeObject(serial, USER_PERSON_ID_KEY),
                decodeObject(serial, USER_ACCOUNT_ID_KEY),
                decodeObject(serial, SUPPORT_AGENT_PERSON_ID_KEY)
        );
    }
}
