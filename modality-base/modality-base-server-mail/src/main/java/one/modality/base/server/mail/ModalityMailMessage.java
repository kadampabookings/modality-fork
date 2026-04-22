package one.modality.base.server.mail;

import dev.webfx.stack.mail.MailMessage;
import dev.webfx.stack.mail.MailMessageDelegate;
import one.modality.base.shared.context.ModalityContext;

/**
 * @author Bruno Salmon
 */
public class ModalityMailMessage extends MailMessageDelegate {

    private final ModalityContext modalityContext;
    /**
     * Optional display name shown to the recipient alongside the sender address
     * (e.g. {@code "Kadampa Booking System"}). When null, the provider leaves
     * {@code Mail.from_name} empty and the receiving client typically shows just
     * the address. Populated per-call by the caller since the brand name is
     * flow-specific, not a platform-wide default.
     */
    private final String fromName;

    public ModalityMailMessage(MailMessage delegate, ModalityContext modalityContext) {
        this(delegate, modalityContext, null);
    }

    public ModalityMailMessage(MailMessage delegate, ModalityContext modalityContext, String fromName) {
        super(delegate);
        this.modalityContext = modalityContext;
        this.fromName = fromName;
    }

    public ModalityContext getModalityContext() {
        return modalityContext;
    }

    /** Optional sender display name, or null when the caller didn't specify one. */
    public String getFromName() {
        return fromName;
    }
}
