package one.modality.booking.frontoffice.bookingpage.standard;

import dev.webfx.extras.async.AsyncSpinner;
import dev.webfx.extras.i18n.controls.I18nControls;
import dev.webfx.extras.panes.FlexPane;
import dev.webfx.extras.panes.MonoPane;
import dev.webfx.extras.panes.ScalePane;
import dev.webfx.extras.styles.bootstrap.Bootstrap;
import dev.webfx.extras.util.control.Controls;
import dev.webfx.extras.util.layout.Layouts;
import dev.webfx.kit.util.properties.FXProperties;
import dev.webfx.platform.async.AsyncResult;
import dev.webfx.platform.console.Console;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import one.modality.base.client.i18n.BaseI18nKeys;
import one.modality.base.shared.entities.Event;
import one.modality.base.shared.entities.formatters.PriceUtil;
import one.modality.booking.frontoffice.bookingform.GatewayPaymentForm;
import one.modality.ecommerce.payment.CancelPaymentResult;
import one.modality.ecommerce.payment.CompletePaymentResult;
import one.modality.ecommerce.payment.client.WebPaymentForm;

import java.util.function.Consumer;

/**
 * @author Bruno Salmon
 */
public final class ProvidedGatewayPaymentForm implements GatewayPaymentForm {

    private final String gatewayName;
    private final Button payButton = Bootstrap.largeSuccessButton(new Button());
    private final Button cancelButton = Bootstrap.largeSecondaryButton(I18nControls.newButton(BaseI18nKeys.Cancel));
    private final VBox mainVbox;
    private Button pressedButton;
    private Consumer<AsyncResult<CancelPaymentResult>> cancelPaymentResultHandler;

    public ProvidedGatewayPaymentForm(WebPaymentForm webPaymentForm, Event event, Consumer<Object> errorConsumer, Consumer<CancelPaymentResult> cancelConsumer, Consumer<CompletePaymentResult> successConsumer) {
        gatewayName = webPaymentForm.getGatewayName();

        Label gatewayLogo = new Label();
        I18nControls.bindI18nProperties(gatewayLogo, webPaymentForm.getGatewayName());

        I18nControls.bindI18nProperties(payButton, "Pay1", PriceUtil.formatWithCurrency(webPaymentForm.getAmount(), event));
        Layouts.setManagedAndVisibleProperties(payButton, !webPaymentForm.hasHtmlPayButton());
        webPaymentForm.setHtmlPayButtonText(payButton.getText());
        webPaymentForm.setHtmlHeaderText("Please enter your payment information");
        Region paymentRegion = webPaymentForm.buildEmbeddedPaymentForm();
        if (paymentRegion == null) { // This indicates a redirected payment form
            // Temporary UI (just spinner)
            paymentRegion = Controls.createPageSizeSpinner();
        }

        ScalePane scaledGatewayLogo = new ScalePane(new MonoPane(gatewayLogo));
        scaledGatewayLogo.setStretchWidth(true);

        mainVbox = new VBox(10,
            scaledGatewayLogo,
            paymentRegion
        );

        if (webPaymentForm.isSandbox()) {
            mainVbox.getChildren().add(webPaymentForm.createSandboxBar());
        }

        payButton.setDefaultButton(true);
        FXProperties.runNowAndOnPropertyChange(userInteractionAllowed -> {
            if (userInteractionAllowed) {
                turnOffWaitMode();
            } else {
                turnOnWaitMode();
            }
        }, webPaymentForm.userInteractionAllowedProperty());
        payButton.setOnAction(e -> {
            pressedButton = payButton;
            webPaymentForm.pay();
        });
        cancelButton.setOnAction(e -> {
            pressedButton = cancelButton;
            webPaymentForm.cancelPayment()
                .inUiThread()
                .onComplete(ar -> {
                    if (cancelPaymentResultHandler != null) {
                        cancelPaymentResultHandler.accept(ar);
                    } else if (ar.failed())
                        errorConsumer.accept(ar.cause().getMessage());
                    else {
                        cancelConsumer.accept(ar.result());
                    }
                });
        });
        payButton.setMaxWidth(Double.MAX_VALUE);
        cancelButton.setMaxWidth(Double.MAX_VALUE);
        FlexPane buttonBar = new FlexPane(cancelButton, payButton);
        buttonBar.setHorizontalSpace(10);
        VBox.setMargin(buttonBar, new Insets(10, 0, 10, 0));
        mainVbox.getChildren().add(buttonBar);

        webPaymentForm
            .setOnLoadFailure(errorMsg -> {
                errorConsumer.accept("ErrorWhileLoadingPaymentForm");
                Console.log(errorMsg);
            })
            .setOnInitFailure(errorMsg -> {
                errorConsumer.accept("ErrorWhileInitializingHTMLPaymentForm");
                Console.log(errorMsg);
            })
            .setOnVerificationFailure(errorMsg -> {
                errorConsumer.accept("ErrorPaymentGatewayFailure");
                Console.log(errorMsg);
            })
            .setOnPaymentFailure(errorMsg -> {
                errorConsumer.accept("ErrorPaymentModalityFailure");
                Console.log(errorMsg);
            })
            .setOnPaymentCompletion(successConsumer);
    }

    @Override
    public String getGatewayName() {
        return gatewayName;
    }

    @Override
    public void setCancelPaymentResultHandler(Consumer<AsyncResult<CancelPaymentResult>> cancelPaymentResultHandler) {
        this.cancelPaymentResultHandler = cancelPaymentResultHandler;
    }

    @Override
    public VBox getView() {
        return mainVbox;
    }

    private void turnOnWaitMode() {
        if (pressedButton == payButton)
            AsyncSpinner.displayButtonSpinner(payButton, cancelButton);
        else
            AsyncSpinner.displayButtonSpinner(cancelButton, payButton);
    }

    private void turnOffWaitMode() {
        AsyncSpinner.hideButtonSpinner(payButton);
        I18nControls.bindI18nGraphicProperty(payButton, "Pay1");
        AsyncSpinner.hideButtonSpinner(cancelButton);
        I18nControls.bindI18nGraphicProperty(cancelButton, BaseI18nKeys.Cancel);
    }

}
