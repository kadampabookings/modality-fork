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
    // Likewise absent unless true. IT MUST BE CARRIED: the flag is part of this principal's IDENTITY
    // (see ModalityUserPrincipal.equals), and it is what RestrictedPrincipalRegistry judges to refuse a
    // write. A codec that dropped it would decode a device principal as an ordinary one, so a session
    // that may not write would come back from any round trip looking like a session that may - the
    // restriction silently detaching from the thing it restricts. Encoded only when true so that every
    // existing session and every older client decodes exactly as before.
    private static final String DEVICE_LOGIN_KEY = "deviceLogin";

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
        if (arg.isDeviceLogin())
            encodeBoolean(serial, DEVICE_LOGIN_KEY, true);
    }

    @Override
    public ModalityUserPrincipal decode(ReadOnlyAstObject serial) {
        return new ModalityUserPrincipal(
                decodeObject(serial, USER_PERSON_ID_KEY),
                decodeObject(serial, USER_ACCOUNT_ID_KEY),
                decodeObject(serial, SUPPORT_AGENT_PERSON_ID_KEY),
                Boolean.TRUE.equals(decodeBoolean(serial, DEVICE_LOGIN_KEY))
        );
    }
}
