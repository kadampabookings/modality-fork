package one.modality.crm.server.services.authz;

import one.modality.crm.shared.services.authn.ModalityGuestPrincipal;

/**
 * How a write rule's log line names its caller: the principal's own text, except a guest's.
 *
 * <p>A registered principal prints as {@code person=…,account=…} — account identities, which the census needs to
 * act on a refusal. A guest prints as {@code guest=<their email address>}, and the rules wrote that straight into
 * CloudWatch: personal data in a log nobody erases (found 2026-11-12). A guest is named {@code guest} here; which
 * guest is not something a census line needs.
 *
 * @author Claude Code
 */
final class CallerLabel {

    private CallerLabel() {
    }

    static String of(Object userId) {
        return userId instanceof ModalityGuestPrincipal ? "guest" : String.valueOf(userId);
    }
}
