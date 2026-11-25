package one.modality.crm.server.person;

/**
 * Check for the send-letter endpoint's letter rule ({@link LetterApplicability}) and the order of its
 * send: fire, confirm a mail was composed, then record the history.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits non-zero
 * on failure, like the other checks here.
 *
 * <p><b>What is pinned is what an edit could quietly undo.</b> The rule is the only thing standing
 * between a caller allowed to send letters for a booking and ANY letter id they name — another
 * organization's, a suppression, an online-only letter for an in-person booker. And the history line
 * is written only after a new mail is seen: written before, it would say "Sent" for the letters the
 * trigger silently skips.
 */
public class LetterApplicabilityCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    // The booking's event: id 500, organization 1, type 7, venue 3.
    static final Long EVENT = 500L, ORG = 1L, TYPE = 7L, VENUE = 3L;

    /** An active, non-suppressing letter for both modes, with the given scope, for an in-person booking. */
    static boolean sendable(Object letterEvent, Object letterOrg, Object letterType, Object letterSite) {
        return LetterApplicability.isSendable(true, false, true, true,
            letterEvent, letterOrg, letterType, letterSite, EVENT, ORG, TYPE, VENUE, true);
    }

    public static void main(String[] args) {
        System.out.println("LetterApplicabilityCheck");

        // --- scope ---
        check("the event's own letter is sendable", sendable(EVENT, ORG, null, null));
        check("another event's letter is not", !sendable(501L, ORG, null, null));
        check("an organization letter of the event's organization is", sendable(null, ORG, null, null));
        check("another organization's letter is not", !sendable(null, 2L, null, null));
        check("an organization letter for the event's type is", sendable(null, ORG, TYPE, null));
        check("an organization letter for another type is not", !sendable(null, ORG, 8L, null));
        check("an organization letter for the event's venue is", sendable(null, ORG, null, VENUE));
        check("an organization letter for another venue is not", !sendable(null, ORG, null, 4L));
        check("a global letter of the event's type is", sendable(null, null, TYPE, null));
        check("a global letter of another type is not", !sendable(null, null, 8L, null));
        check("a letter with no scope at all is not", !sendable(null, null, null, null));
        check("an event without a type matches no type-scoped letter",
            !LetterApplicability.isSendable(true, false, true, true, null, ORG, TYPE, null, EVENT, ORG, null, VENUE, true));

        // --- state and mode ---
        check("an inactive letter is not",
            !LetterApplicability.isSendable(false, false, true, true, EVENT, ORG, null, null, EVENT, ORG, TYPE, VENUE, true));
        check("a letter whose active flag is unknown is not",
            !LetterApplicability.isSendable(null, false, true, true, EVENT, ORG, null, null, EVENT, ORG, TYPE, VENUE, true));
        check("a suppression letter is not",
            !LetterApplicability.isSendable(true, true, true, true, EVENT, ORG, null, null, EVENT, ORG, TYPE, VENUE, true));
        check("an online-only letter is not sent to an in-person booker",
            !LetterApplicability.isSendable(true, false, false, true, EVENT, ORG, null, null, EVENT, ORG, TYPE, VENUE, true));
        check("but is to an online booker",
            LetterApplicability.isSendable(true, false, false, true, EVENT, ORG, null, null, EVENT, ORG, TYPE, VENUE, false));
        check("an unset mode flag counts as applicable",
            LetterApplicability.isSendable(true, false, null, null, EVENT, ORG, null, null, EVENT, ORG, TYPE, VENUE, true));

        // --- staff alerts are never sent by hand ---
        check("the letter's type is loaded with its staff-alert flag",
            LetterApplicability.LETTER_FIELDS.contains("type.staffAlert"));
        String rule = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
            + "one/modality/crm/server/person/LetterApplicability.java");
        check("a staff alert, or a letter whose type is not loaded, is refused",
            rule.contains("letter.getType() == null || !Boolean.FALSE.equals(letter.getType().getBooleanFieldValue(\"staffAlert\"))"));

        // --- ids compare by value across number types ---
        check("an Integer key equals a Long key", LetterApplicability.key(500).equals(LetterApplicability.key(500L)));
        check("a null id stays null", LetterApplicability.key(null) == null);

        // --- the send's order, in the rules' source ---
        String source = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
            + "one/modality/crm/server/person/DocumentLetterRules.java");
        check("the rules source was found", !source.isEmpty());
        int checkAt = source.indexOf("LetterApplicability.isSendable(letter, document)");
        int fireAt = source.indexOf("setForeignField(TRIGGER_SEND_LETTER");
        int confirmAt = source.indexOf(".compose(ignored -> lastMail(store, documentId, letterId))");
        int historyAt = source.indexOf("insertEntity(History.class)");
        check("the letter is checked", checkAt >= 0);
        check("the letter is checked before the trigger is fired", checkAt >= 0 && fireAt > checkAt);
        check("a new mail is looked for after the trigger is fired", confirmAt > fireAt);
        check("the history is written only after that", historyAt > confirmAt);
        check("no new mail fails the call with its own key",
            source.contains("\"[\" + NOT_COMPOSED_KEY + \"]"));
        check("the letter is read only after the grant is checked",
            source.indexOf("whenCallerHoldsOperation") < source.indexOf("from Letter where id=$1"));

        // --- reading a mail: same fence as sending, and bound to the booking ---
        check("a mail is read only within the booking named",
            DocumentLetterRules.MAIL_CONTENT_DQL.contains("where id=$1 and document=$2"));
        check("the read stands behind the letter-sender fence",
            source.contains("forLetterSenderOf(state, documentId, \"Mail read\""));
        check("and so does the send",
            source.contains("forLetterSenderOf(state, documentId, \"Send\""));
        check("the fence checks SendDocumentLetter in the booking's own organization and event",
            source.contains("whenCallerHoldsOperation(state, SEND_DOCUMENT_LETTER_OPERATION,")
            && source.contains("Entities.getPrimaryKey(event.getOrganizationId()), event.getPrimaryKey()"));
        check("each read is logged with the person who asked", source.contains("\" read by person \" + callerPersonId"));
        check("and so is each refused read", source.contains("\"Mail read refused: mail \""));
        String endpoint = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
            + "one/modality/crm/server/person/ReadDocumentMailEndpoint.java");
        check("the endpoint takes exactly two arguments", endpoint.contains("arguments.length != 2"));
        String services = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/resources/META-INF/services/"
            + "dev.webfx.stack.com.bus.call.spi.BusCallEndpoint");
        String webfx = AccountOwnerCheck.readSource("modality-fork/modality-crm/modality-crm-server-person-plugin/webfx.xml");
        String moduleInfo = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/module-info.java");
        check("the endpoint is registered in webfx.xml, the service file and module-info",
            webfx.contains("ReadDocumentMailEndpoint") && services.contains("ReadDocumentMailEndpoint")
            && moduleInfo.contains("ReadDocumentMailEndpoint"));

        // --- previewing a letter: same fence, same letter rule, same composition as the send trigger ---
        check("the preview stands behind the letter-sender fence",
            source.contains("forLetterSenderOf(state, documentId, \"Preview\""));
        check("and previews only a letter the booking is offered",
            source.contains("withSendableLetter(store, document, letterId, \"Preview\""));
        check("the send goes through the same letter rule",
            source.contains("withSendableLetter(store, document, letterId, \"Send\""));
        check("the preview composes through the read-only letter_preview(), with the letter and booking bound",
            DocumentLetterRules.PREVIEW_SQL.equals("select subject, content from letter_preview($1, $2)"));
        check("and binds the CHECKED letter's key, not the caller's value",
            source.contains(".setParameters(Entities.getPrimaryKey(letter), Entities.getPrimaryKey(document))"));
        String migration = AccountOwnerCheck.readSource(
            "modality-fork/modality-base/modality-base-server-dbmigration-plugin/src/main/resources/one/modality/base/"
            + "server/services/dbmigration/scripts/V0182__letter_preview_read_only.sql");
        int readOnlyAt = migration.indexOf("PERFORM set_config('transaction_read_only', 'on', true);");
        int composeAt = migration.indexOf("letter_compose_email(l, letter_mail_lang(l, d.person_lang, d.organization_id), d.id, NULL)");
        check("letter_preview switches to read only BEFORE it composes", readOnlyAt > 0 && composeAt > readOnlyAt);
        String writeSecrets = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-authz-required-plugin/src/main/java/one/modality/crm/"
            + "server/services/authz/ClientWriteSecrets.java");
        check("clients may not write the bracket patterns a preview executes",
            writeSecrets.contains("denyTable(\"bracket_pattern\");"));
        check("the preview is a read, never a write",
            source.contains("QueryService.executeQuery(new QueryArgumentBuilder()") && !source.contains("SubmitService"));
        check("each preview is logged with the person who asked",
            source.contains("\" previewed for document \""));
        String previewEndpoint = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
            + "one/modality/crm/server/person/PreviewDocumentLetterEndpoint.java");
        check("the preview endpoint takes exactly two arguments", previewEndpoint.contains("arguments.length != 2"));
        check("the preview endpoint is registered in webfx.xml, the service file and module-info",
            webfx.contains("PreviewDocumentLetterEndpoint") && services.contains("PreviewDocumentLetterEndpoint")
            && moduleInfo.contains("PreviewDocumentLetterEndpoint"));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}
