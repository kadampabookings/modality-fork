package one.modality.booking.frontoffice.bookingpage.util;

import dev.webfx.extras.i18n.I18n;
import dev.webfx.extras.util.dialog.DialogCallback;
import dev.webfx.extras.util.dialog.DialogUtil;
import dev.webfx.extras.util.dialog.builder.DialogContent;
import dev.webfx.platform.uischeduler.UiScheduler;
import one.modality.base.client.error.ErrorReporter;
import one.modality.base.client.mainframe.fx.FXMainFrameDialogArea;
import one.modality.base.shared.entities.Event;
import one.modality.booking.frontoffice.bookingpage.BookingPageI18nKeys;

/**
 * Utility class for displaying error dialogs in booking forms.
 * Centralizes the error reporting and dialog display pattern used across
 * booking form classes.
 *
 * <p>All methods in this class:</p>
 * <ul>
 *   <li>Report the error to the database via {@link ErrorReporter}</li>
 *   <li>Display a user-friendly dialog with optional technical details</li>
 *   <li>Handle UI thread scheduling automatically</li>
 * </ul>
 *
 * @author Bruno Salmon
 */
public final class BookingFormDialogUtil {

    private BookingFormDialogUtil() {
        // Utility class - prevent instantiation
    }

    /**
     * Shows an error dialog and reports the error to the database.
     * This is the core method that other convenience methods delegate to.
     *
     * @param logContext The context identifier for logging (e.g., "StandardBookingForm")
     * @param logMessage The error message for the log (e.g., "Booking submission failed")
     * @param event The event for context (may be null)
     * @param titleKey The i18n key for the dialog title
     * @param headerKey The i18n key for the dialog header
     * @param messageKey The i18n key for the dialog message
     * @param technicalDetails Optional technical details to show in the dialog (may be null)
     */
    public static void showErrorDialog(
            String logContext,
            String logMessage,
            Event event,
            Object titleKey,
            Object headerKey,
            Object messageKey,
            String technicalDetails) {

        // Get event name for context
        String eventName = event != null ? event.getName() : null;

        // Build error report message
        StringBuilder reportMessage = new StringBuilder();
        reportMessage.append("[").append(logContext).append("] ").append(logMessage);
        if (eventName != null) {
            reportMessage.append(" | Event: ").append(eventName);
        }

        // Report error to database
        ErrorReporter.reportError(reportMessage.toString());

        // Show error dialog to user
        UiScheduler.runInUiThread(() -> {
            DialogContent errorDialog = DialogContent.createErrorDialogWithTechnicalDetails(
                I18n.getI18nText(titleKey),
                I18n.getI18nText(headerKey),
                I18n.getI18nText(messageKey),
                technicalDetails,
                null,  // No error code
                null   // No timestamp
            );

            DialogCallback callback = DialogUtil.showModalNodeInGoldLayout(
                errorDialog.build(),
                FXMainFrameDialogArea.getDialogArea()
            );
            errorDialog.setDialogCallback(callback);
            errorDialog.getPrimaryButton().setOnAction(e -> callback.closeDialog());
        });
    }

    /**
     * Shows an error dialog for booking submission failures (server/network errors).
     *
     * @param event The event for context (may be null)
     * @param error The error/exception from the server
     */
    public static void showSubmissionErrorDialog(Event event, Throwable error) {
        String errorMessage = error != null && error.getMessage() != null
            ? error.getMessage()
            : "Unknown error";

        showErrorDialog(
            "StandardBookingForm",
            "Booking submission failed: " + errorMessage,
            event,
            BookingPageI18nKeys.ServerErrorTitle,
            BookingPageI18nKeys.ServerErrorHeader,
            BookingPageI18nKeys.ServerErrorMessage,
            errorMessage
        );
    }

    /**
     * Shows an error dialog when user already has a booking for the event.
     *
     * @param event The event for context (may be null)
     */
    public static void showAlreadyBookedErrorDialog(Event event) {
        String eventName = event != null ? event.getName() : "unknown";
        showErrorDialog(
            "StandardBookingForm",
            "User already has booking for event: " + eventName,
            event,
            BookingPageI18nKeys.AlreadyBookedTitle,
            BookingPageI18nKeys.AlreadyBookedHeader,
            BookingPageI18nKeys.AlreadyBookedMessage,
            null  // No technical details needed
        );
    }

    /**
     * Shows an error dialog when queue processing fails.
     *
     * @param event The event for context (may be null)
     * @param errorMessage The error message from the server
     */
    public static void showQueueErrorDialog(Event event, String errorMessage) {
        String message = errorMessage != null ? errorMessage : "Unknown error";
        showErrorDialog(
            "BookingFormQueueHandler",
            "Queue processing failed: " + message,
            event,
            BookingPageI18nKeys.ServerErrorTitle,
            BookingPageI18nKeys.ServerErrorHeader,
            BookingPageI18nKeys.QueueErrorMessage,
            message
        );
    }
}
