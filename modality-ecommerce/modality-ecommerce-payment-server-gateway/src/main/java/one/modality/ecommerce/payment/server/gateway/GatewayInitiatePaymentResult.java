package one.modality.ecommerce.payment.server.gateway;

import one.modality.ecommerce.payment.PaymentFormType;
import one.modality.ecommerce.payment.SandboxCard;

/**
 * @param isLive             indicates if it's a live payment (false indicates a test / sandbox payment)
 * @param url                URL of the page that can handle the payment (isEmbedded will tell what to do with it)
 * @param formType           Indicates the payment form type (embedded or redirected)
 * @param htmlContent        Direct HTML content that can handle the payment (CC details, etc...) in an embedded WebView (ex: Stripe)
 * @param isSeamless         indicates if the HTML content can be integrated seamlessly in the browser page
 * @param hasHtmlPayButton   indicates if a "Pay" button is already integrated in the gateway HTML code
 * @param fallbackRedirectUrl optional redirect URL if the embedded form fails to load
 * @param paypalInAppOrderId  PayPal order id for the same-origin in-app Buttons widget (null unless this is a PayPal in-app result)
 * @param paypalInAppClientId PayPal SDK client id (null unless this is a PayPal in-app result)
 * @param paypalInAppCurrency Currency code for the PayPal SDK URL (null unless this is a PayPal in-app result)
 *
 * @author Bruno Salmon
 */
public record GatewayInitiatePaymentResult(
    boolean isLive,
    String url,
    PaymentFormType formType,
    // The following fields are only used when isEmbedded is true
    String htmlContent,
    boolean isSeamless,
    boolean hasHtmlPayButton,
    SandboxCard[] sandboxCards,
    // Optional redirect URL to fall back to if the embedded form cannot load
    // (e.g., blocked by a browser extension). Null means no fallback available.
    String fallbackRedirectUrl,
    // PayPal in-app SDK parameters — set when the gateway wants the client to render
    // the PayPal Buttons widget directly in the host page (no iframe), so a single
    // user click opens the PayPal popup. Null for any other flow.
    String paypalInAppOrderId,
    String paypalInAppClientId,
    String paypalInAppCurrency
) {

    /*=========================================== Static factory methods =============================================*/

    /*================================================ Redirect API ==================================================*/
    // => payment page hosted by the gateway company

    public static GatewayInitiatePaymentResult createLiveRedirectInitiatePaymentResult(String url) {
        return createRedirectInitiatePaymentResult(true, url);
    }

    public static GatewayInitiatePaymentResult createSandboxRedirectInitiatePaymentResult(boolean seamless, String url) {
        return createRedirectInitiatePaymentResult(false, url);
    }

    public static GatewayInitiatePaymentResult createRedirectInitiatePaymentResult(boolean live, String url) {
        return new GatewayInitiatePaymentResult(live, url, PaymentFormType.REDIRECTED, null, false, false, null, null, null, null, null);
    }

    /*========================================= Embedded API (HTML content)  =========================================*/
    // => payment hosted by the app with provided HTML content, eventually seamlessly if possible

    public static GatewayInitiatePaymentResult createLiveEmbeddedContentInitiatePaymentResult(boolean seamless, String htmlContent, boolean hasHtmlPayButton) {
        return createEmbeddedContentInitiatePaymentResult(true, seamless, htmlContent, hasHtmlPayButton, null);
    }

    public static GatewayInitiatePaymentResult createSandboxEmbeddedContentInitiatePaymentResult(boolean seamless, String htmlContent, boolean hasHtmlPayButton, SandboxCard[] sandboxCards) {
        return createEmbeddedContentInitiatePaymentResult(false, seamless, htmlContent, hasHtmlPayButton, sandboxCards);
    }

    public static GatewayInitiatePaymentResult createEmbeddedContentInitiatePaymentResult(boolean live, boolean seamless, String htmlContent, boolean hasHtmlPayButton, SandboxCard[] sandboxCards) {
        return new GatewayInitiatePaymentResult(live, null, PaymentFormType.EMBEDDED, htmlContent, seamless, hasHtmlPayButton, sandboxCards, null, null, null, null);
    }

    /*========================================= Embedded API (URL)  =========================================*/
    // => payment hosted by the app with provided URL, eventually seamlessly if possible

    public static GatewayInitiatePaymentResult createLiveEmbeddedUrlInitiatePaymentResult(boolean seamless, String url, boolean hasHtmlPayButton) {
        return createEmbeddedUrlInitiatePaymentResult(true, seamless, url, hasHtmlPayButton, null);
    }

    public static GatewayInitiatePaymentResult createSandboxEmbeddedUrlInitiatePaymentResult(boolean seamless, String url, boolean hasHtmlPayButton, SandboxCard[] sandboxCards) {
        return createEmbeddedUrlInitiatePaymentResult(false, seamless, url, hasHtmlPayButton, sandboxCards);
    }


    public static GatewayInitiatePaymentResult createEmbeddedUrlInitiatePaymentResult(boolean live, boolean seamless, String url, boolean hasHtmlPayButton, SandboxCard[] sandboxCards) {
        return new GatewayInitiatePaymentResult(live, url, PaymentFormType.EMBEDDED, null, seamless, hasHtmlPayButton, sandboxCards, null, null, null, null);
    }

    /*========================================== PayPal in-app SDK API ===============================================*/
    // => client renders the PayPal Buttons widget directly in the host page (same origin), so a
    //    single user click opens the PayPal popup. No iframe, no embedded HTML content.

    public static GatewayInitiatePaymentResult createPayPalInAppInitiatePaymentResult(boolean live, String orderId, String clientId, String currency, String fallbackRedirectUrl, SandboxCard[] sandboxCards) {
        return new GatewayInitiatePaymentResult(live, null, PaymentFormType.EMBEDDED, null, true, true, sandboxCards, fallbackRedirectUrl, orderId, clientId, currency);
    }

    /** Returns a copy of this result with the given fallback redirect URL set. */
    public GatewayInitiatePaymentResult withFallbackRedirectUrl(String fallbackRedirectUrl) {
        return new GatewayInitiatePaymentResult(isLive, url, formType, htmlContent, isSeamless, hasHtmlPayButton, sandboxCards, fallbackRedirectUrl, paypalInAppOrderId, paypalInAppClientId, paypalInAppCurrency);
    }
}
