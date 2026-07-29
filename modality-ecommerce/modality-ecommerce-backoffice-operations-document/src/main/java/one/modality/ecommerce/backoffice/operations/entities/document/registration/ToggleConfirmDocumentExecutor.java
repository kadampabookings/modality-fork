package one.modality.ecommerce.backoffice.operations.entities.document.registration;

import dev.webfx.extras.async.AsyncDialog;
import dev.webfx.extras.i18n.I18n;
import dev.webfx.extras.util.dialog.builder.DialogContent;
import dev.webfx.platform.async.Future;
import dev.webfx.stack.orm.entity.UpdateStore;
import javafx.geometry.HPos;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.GridPane;
import one.modality.base.client.i18n.BaseI18nKeys;
import one.modality.base.shared.entities.Document;
import one.modality.base.shared.entities.Event;
import one.modality.base.shared.entities.Letter;
import one.modality.ecommerce.document.service.DocumentService;
import one.modality.ecommerce.document.service.SubmitDocumentChangesArgument;
import one.modality.ecommerce.document.service.events.registration.ConfirmDocumentEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Bruno Salmon
 */
final class ToggleConfirmDocumentExecutor {

    static Future<Void> executeRequest(ToggleConfirmDocumentRequest rq) {
        return Future.all(
            rq.getDocument().<Document>onExpressionLoaded("confirmed,passReady"),
            rq.getDocument().getEvent().<Event>onExpressionLoaded("type,venue,organization")
        ).compose(compositeFuture -> {
            Document document = rq.getDocument();
            Event event = document.getEvent();
            // Scope-applicable confirmation letters (letter scope resolution, V0037/V0052): the
            // event's own letters, wider-scoped ones of the event's organization matching its
            // type / venue, plus GLOBAL event-type letters (organization null, V0052).
            StringBuilder condition = new StringBuilder("active and type.confirmation and (event=$1 or event=null and organization=$2");
            List<Object> params = new ArrayList<>();
            params.add(event);
            params.add(event.getOrganization());
            condition.append(" and (eventType=null");
            if (event.getType() != null) {
                params.add(event.getType());
                condition.append(" or eventType=$").append(params.size());
            }
            condition.append(") and (site=null");
            if (event.getVenue() != null) {
                params.add(event.getVenue());
                condition.append(" or site=$").append(params.size());
            }
            condition.append(')');
            if (event.getType() != null) {
                params.add(event.getType());
                condition.append(" or event=null and organization=null and eventType=$").append(params.size());
            }
            condition.append(')');
            return document.getStore().<Letter>executeQuery("select subject_en,event,eventType,site,organization,suppressesSending from Letter where " + condition, params.toArray());
        }).compose(letters -> {
            Document document = rq.getDocument();
            boolean confirmed = !document.isConfirmed(); // toggling confirmed
            boolean read = !document.isPassReady(); // read if pass is not ready, otherwise
            String confirmationText = "Are you sure you want to " + (confirmed ? "confirm" : "unconfirm") + " this booking?";
            DialogContent dialogContent = new DialogContent()
                .setHeaderText(I18n.getI18nText(BaseI18nKeys.AreYouSure))
                .setContentText(confirmationText)
                .setYesNo();
            Letter confirmationLetter = pickNarrowestUnique(letters);
            CheckBox sendConfirmationLetterCheckBox;
            if (confirmed && confirmationLetter != null) {
                sendConfirmationLetterCheckBox = new CheckBox("Send the confirmation letter");
                sendConfirmationLetterCheckBox.setSelected(true);
                dialogContent.setContent(sendConfirmationLetterCheckBox);
                GridPane.setHalignment(sendConfirmationLetterCheckBox, HPos.CENTER);
            } else
                sendConfirmationLetterCheckBox = null;
            return AsyncDialog.showDialogWithAsyncOperationOnPrimaryButton(dialogContent, rq.getParentContainer(), () -> {
                boolean sendConfirmationLetter = sendConfirmationLetterCheckBox != null && sendConfirmationLetterCheckBox.isSelected();
                Future<?> preDocumentSubmit;
                if (sendConfirmationLetter) {
                    UpdateStore updateStore = UpdateStore.createAbove(document.getStore());
                    document.setForeignField("triggerSendLetter", confirmationLetter);
                    preDocumentSubmit = updateStore.submitChanges();
                } else
                    preDocumentSubmit = Future.succeededFuture();
                return preDocumentSubmit.compose(ignored ->
                    DocumentService.submitDocumentChanges(
                        SubmitDocumentChangesArgument.of(
                            sendConfirmationLetter ? "Sent '" + confirmationLetter.getFieldValue("subject_en") + "'" : confirmed ? "Confirmed booking" : "Unconfirmed booking",
                            new ConfirmDocumentEvent(document, confirmed, read)
                        )
                    ));
            });
        });
    }

    /**
     * Scope rank of a letter, mirroring the SQL letter_scope_rank() (V0052 ladder): 0 = event,
     * 1 = (site, eventType), 2 = eventType, 3 = eventType GLOBAL (no organization), 4 = site,
     * 5 = organization. The query already guarantees each letter's scope matches the document's
     * event context, so only the shape of the scope columns matters here.
     */
    private static int scopeRank(Letter letter) {
        if (letter.getEventId() != null)
            return 0;
        boolean siteScoped = letter.getSiteId() != null;
        if (letter.getEventTypeId() != null)
            return siteScoped ? 1 : letter.getOrganizationId() != null ? 2 : 3;
        return siteScoped ? 4 : 5;
    }

    /**
     * The resolved confirmation letter: the unique letter at the narrowest applicable scope.
     * A tie at the best rank means the choice is ambiguous — return null so the dialog offers
     * no auto-send (the same guard the previous letters.size() == 1 check provided). A
     * suppression letter (V0043) winning the resolution also yields null: it says "no
     * confirmation letter here", overriding wider-scoped ones.
     */
    private static Letter pickNarrowestUnique(List<Letter> letters) {
        Letter best = null;
        boolean unique = false;
        for (Letter letter : letters) {
            if (best == null || scopeRank(letter) < scopeRank(best)) {
                best = letter;
                unique = true;
            } else if (scopeRank(letter) == scopeRank(best))
                unique = false;
        }
        if (best != null && Boolean.TRUE.equals(best.isSuppressesSending()))
            return null;
        return unique ? best : null;
    }
}
