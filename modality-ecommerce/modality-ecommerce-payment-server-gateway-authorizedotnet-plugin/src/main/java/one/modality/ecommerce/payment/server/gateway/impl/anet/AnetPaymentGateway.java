package one.modality.ecommerce.payment.server.gateway.impl.anet;

import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.resource.Resource;
import dev.webfx.platform.util.Strings;
import dev.webfx.platform.util.uuid.Uuid;
import net.authorize.Environment;
import net.authorize.api.contract.v1.*;
import net.authorize.api.controller.CreateTransactionController;
import one.modality.ecommerce.payment.*;
import one.modality.ecommerce.payment.server.gateway.*;
import one.modality.ecommerce.payment.server.gateway.impl.util.RestApiOneTimeHtmlResponsesCache;

import java.math.BigDecimal;
import java.util.List;

import static one.modality.ecommerce.payment.server.gateway.impl.anet.AnetRestApiJob.AUTHORIZE_PAYMENT_FORM_LOAD_ENDPOINT;

/**
 * @author Bruno Salmon
 */
public final class AnetPaymentGateway implements PaymentGateway {

    static final String GATEWAY_NAME = "Authorize.net";
    private static final String ACCEPT_JS_TEST_URL = "https://jstest.authorize.net/v3/AcceptUI.js";
    private static final String ACCEPT_JS_LIVE_URL = "https://js.authorize.net/v3/AcceptUI.js";

    private static final String HTML_TEMPLATE = Resource.getText(Resource.toUrl("modality-anet-payment-form-iframe.html", AnetPaymentGateway.class));

    private static final SandboxCard[] SANDBOX_CARDS = {
        new SandboxCard("Visa - Success", "4111 1111 1111 1111", null, "123", "46282"),
        new SandboxCard("Mastercard - Success", "5424 0000 0000 0015", null, "123", "46282"),
        new SandboxCard("Discover - Success", "6011 0000 0000 0012", null, "123", "46282"),
        new SandboxCard("American Express - Success", "3700 0000 0000 0002", null, "1234", "46282"),
        new SandboxCard("JCB - Success", "3088 0000 0000 0017", null, "123", "46282"),
        new SandboxCard("Dinners Club - Success", "3800 0000 0000 06", null, "123", "46282"),
        new SandboxCard("Wrong CVV", "4111 1111 1111 1111", null, "901", "46282"),
    };

    private static final List<GatewayPaymentMethodInfo> SUPPORTED_METHODS = List.of(
        new GatewayPaymentMethodInfo(PaymentMethod.CARD, PaymentFormType.EMBEDDED)
    );

    @Override
    public String getName() {
        return GATEWAY_NAME;
    }

    @Override
    public List<GatewayPaymentMethodInfo> getSupportedPaymentMethods() {
        return SUPPORTED_METHODS;
    }

    @Override
    public Future<GatewayInitiatePaymentResult> initiatePayment(GatewayInitiatePaymentArgument argument) {
        // Integrating the Authorize.net Embedded Payment Form inside the Modality page
        try {
            boolean live = argument.isLive();
            String apiLoginID = argument.getRequiredAccountParameter("apiLoginID");
            String clientKey = argument.getRequiredAccountParameter("clientKey");
            String acceptUIFormHeaderTxt = argument.getAccountParameter("acceptUIFormHeaderTxt", "");
            String acceptUIJsURL = live ? ACCEPT_JS_LIVE_URL : ACCEPT_JS_TEST_URL;
            boolean seamless = false; //argument.favorSeamless();

            String paymentFormContent = HTML_TEMPLATE
                .replace("${apiLoginID}", apiLoginID)
                .replace("${clientKey}", clientKey)
                .replace("${acceptUIFormHeaderTxt}", acceptUIFormHeaderTxt)
                .replace("${acceptUIFormHeaderTxt}", acceptUIFormHeaderTxt)
                .replace("${acceptUIJsURL}", acceptUIJsURL)
                ;

            SandboxCard[] sandboxCards = live ? null : SANDBOX_CARDS;
            if (seamless) {
                return Future.succeededFuture(GatewayInitiatePaymentResult.createEmbeddedContentInitiatePaymentResult(live, true, paymentFormContent, true, sandboxCards));
            } else { // In other cases, we embed the page in a WebView/iFrame that can be loaded through https (assuming this server is on https)
                String htmlCacheKey = Uuid.randomUuid();
                RestApiOneTimeHtmlResponsesCache.registerOneTimeHtmlResponse(htmlCacheKey, paymentFormContent);
                String url = AUTHORIZE_PAYMENT_FORM_LOAD_ENDPOINT.replace(":htmlCacheKey", htmlCacheKey);
                return Future.succeededFuture(GatewayInitiatePaymentResult.createEmbeddedUrlInitiatePaymentResult(live, false, url, true, sandboxCards));
            }
        } catch (Exception e) {
            return Future.failedFuture(GATEWAY_NAME + " initiatePayment() failed: " + e.getMessage());
        }
    }

    @Override
    public Future<GatewayCompletePaymentResult> completePayment(GatewayCompletePaymentArgument argument) {
        // Capturing argument parameters
        String apiLoginID = argument.getAccountParameter("apiLoginID");
        String apiTransactionKey = argument.getAccountParameter("apiTransactionKey");
        boolean live = argument.isLive();
        long amount = argument.amount();
        GatewayCustomer serverSideCustomer = argument.customer();
        GatewayOrder serverOrder = argument.order();
        // Reading the payload generated by the script in modality-anet-payment-form-iframe.html
        ReadOnlyAstObject payload = AST.parseObject(argument.payload(), "json");
        String dataDescriptor      = payload.getString("anet_dataDescriptor");
        String dataValue           = payload.getString("anet_dataValue");
        String clientSideFirstName = payload.getString("modality_firstName");
        String clientSideLastName  = payload.getString("modality_lastName");
        String clientSideEmail     = payload.getString("modality_email");
        String clientSidePhone     = payload.getString("modality_phone");
        String clientSideAddress   = payload.getString("modality_address");
        String clientSideCity      = payload.getString("modality_city");
        String clientSideState     = payload.getString("modality_state");
        String clientSideZipCode   = payload.getString("modality_zipCode");
        String clientCountry       = payload.getString("modality_country");

        // Passing all the above data using the Authorize.net API to request a payment completion

        Environment environment = live ? Environment.PRODUCTION : Environment.SANDBOX;

        MerchantAuthenticationType merchantAuthentication = new MerchantAuthenticationType();
        merchantAuthentication.setName(apiLoginID);
        merchantAuthentication.setTransactionKey(apiTransactionKey);

        // Use the client-supplied value when non-blank; fall back to the server-side snapshot
        // (denormalized person_* fields on the Document). The React client always sends strings
        // (never null), so a plain != null check would silently accept empty strings and
        // bypass the fallback — !Strings.isBlank() avoids that pitfall.
        CustomerAddressType billingAddress = new CustomerAddressType();
        billingAddress.setFirstName(  !Strings.isBlank(clientSideFirstName) ? clientSideFirstName : serverSideCustomer.firstName());
        billingAddress.setLastName(   !Strings.isBlank(clientSideLastName)  ? clientSideLastName  : serverSideCustomer.lastName());
        billingAddress.setEmail(      !Strings.isBlank(clientSideEmail)     ? clientSideEmail     : serverSideCustomer.email());
        billingAddress.setPhoneNumber(!Strings.isBlank(clientSidePhone)     ? clientSidePhone     : serverSideCustomer.phone());
        billingAddress.setAddress(    !Strings.isBlank(clientSideAddress)   ? clientSideAddress   : serverSideCustomer.address());
        billingAddress.setCity(       !Strings.isBlank(clientSideCity)      ? clientSideCity      : serverSideCustomer.city());
        billingAddress.setState(      !Strings.isBlank(clientSideState)     ? clientSideState     : serverSideCustomer.state());
        billingAddress.setZip(        !Strings.isBlank(clientSideZipCode)   ? clientSideZipCode   : serverSideCustomer.zipCode());
        billingAddress.setCountry(    !Strings.isBlank(clientCountry)       ? clientCountry       : serverSideCustomer.country());

        // Some fields are limited in length, so we truncate them if necessary
        // (Source: https://api.authorize.net/xml/v1/schema/anetapischema.xsd - nameAndAddressType)
        billingAddress.setFirstName(Strings.truncate(billingAddress.getFirstName(), 50));
        billingAddress.setLastName( Strings.truncate(billingAddress.getLastName(),  50));
        billingAddress.setAddress(  Strings.truncate(billingAddress.getAddress(),   60));
        billingAddress.setCity(     Strings.truncate(billingAddress.getCity(),      40));
        billingAddress.setState(    Strings.truncate(billingAddress.getState(),     40));
        billingAddress.setZip(      Strings.truncate(billingAddress.getZip(),       20));
        billingAddress.setCountry(  Strings.truncate(billingAddress.getCountry(),   60));

        // TODO: create one line per serverOrder.items()
        LineItemType anetItem = new LineItemType();
        anetItem.setItemId(         Strings.truncate(serverOrder.id(),                31));
        anetItem.setName(           Strings.truncate(serverOrder.shortName(),         31));
        anetItem.setDescription(    Strings.truncate(serverOrder.longName(),          255));
        anetItem.setQuantity(BigDecimal.valueOf(1)); //serverOrder.quantity()));
        anetItem.setUnitPrice(BigDecimal.valueOf(0.01 * serverOrder.amount())); // the modality amount is in cents

        ArrayOfLineItem lineItems = new ArrayOfLineItem();
        lineItems.getLineItem().add(anetItem);

        CustomerDataType customerData = new CustomerDataType();
        customerData.setId(serverSideCustomer.id());
        customerData.setEmail(serverSideCustomer.email());
        customerData.setType(CustomerTypeEnum.INDIVIDUAL);

        OpaqueDataType opaqueData = new OpaqueDataType();
        opaqueData.setDataDescriptor(dataDescriptor);
        opaqueData.setDataValue(dataValue);

        PaymentType paymentType = new PaymentType();
        paymentType.setOpaqueData(opaqueData);

        TransactionRequestType txnRequest = new TransactionRequestType();
        txnRequest.setTransactionType(TransactionTypeEnum.AUTH_CAPTURE_TRANSACTION.value());
        txnRequest.setAmount(BigDecimal.valueOf(0.01 * amount)); // the modality amount is in cents
        txnRequest.setPayment(paymentType);
        txnRequest.setBillTo(billingAddress);
        txnRequest.setCustomer(customerData);
        txnRequest.setLineItems(lineItems);

        // Provide a unique order/invoice number per payment to avoid duplicate detection when paying the same booking multiple times
        String uniqueSuffix = Uuid.randomUuid().substring(0, 8);
        OrderType order = new OrderType();
        String invoiceNumber = Strings.truncate(serverOrder.id(), 20); // Authorize.Net allows up to 20 chars
        order.setInvoiceNumber(invoiceNumber);
        // Optionally include a concise longName
        order.setDescription(Strings.truncate(serverOrder.shortName(), 255));
        txnRequest.setOrder(order);

        // Explicitly disable duplicate transaction check to allow multiple payments with same amount/card
        SettingType dupSetting = new SettingType();
        dupSetting.setSettingName("duplicateWindow");
        dupSetting.setSettingValue("0");
        ArrayOfSetting settings = new ArrayOfSetting();
        settings.getSetting().add(dupSetting);
        txnRequest.setTransactionSettings(settings);

        CreateTransactionRequest request = new CreateTransactionRequest();
        request.setMerchantAuthentication(merchantAuthentication);
        // Set a unique refId for traceability and to further distinguish requests
        request.setRefId(Strings.truncate("mod-" + uniqueSuffix, 20));
        request.setTransactionRequest(txnRequest);

        CreateTransactionController controller = new CreateTransactionController(request);
        controller.execute(environment);
        CreateTransactionResponse response = controller.getApiResponse();

        MessageTypeEnum resultCode = response == null ? null : response.getMessages().getResultCode();
        if (resultCode == MessageTypeEnum.OK) { // API request succeeded (doesn't mean the payment is successful)
            TransactionResponse transactionResponse = response.getTransactionResponse();
            String transId = transactionResponse.getTransId();
            String responseCode = transactionResponse.getResponseCode();

            // Response code "4" = held for review (fraud screening) → PENDING, not a failure
            PaymentStatus paymentStatus;
            PaymentFailureReason failureReason = null;
            if ("1".equals(responseCode)) {
                paymentStatus = PaymentStatus.COMPLETED;
            } else if ("4".equals(responseCode)) {
                paymentStatus = PaymentStatus.PENDING;
            } else {
                paymentStatus = PaymentStatus.FAILED;
                // Check transactionResponse errors first — they carry the specific decline reason
                // (e.g., error code 6 = invalid card, 8 = expired, 78 = invalid CVV, 27 = AVS mismatch)
                failureReason = mapTransactionErrorsToFailureReason(transactionResponse);
                if (failureReason == null) {
                    // Fall back to the top-level response code
                    failureReason = switch (responseCode) {
                        case "2" -> PaymentFailureReason.DECLINED_BY_BANK;
                        case "3" -> PaymentFailureReason.GATEWAY_ERROR;
                        default  -> PaymentFailureReason.UNKNOWN_REASON;
                    };
                }
            }
            return Future.succeededFuture(new GatewayCompletePaymentResult(
                null,
                transId,
                responseCode,
                paymentStatus,
                failureReason
            ));
        } else { // API request technically failed (validation error before the transaction was even attempted)
            // Map error codes to structured failure reasons rather than throwing a raw error string.
            // The raw details are collected for server-side logging only.
            TransactionResponse transactionResponse = response == null ? null : response.getTransactionResponse();
            PaymentFailureReason failureReason = mapTransactionErrorsToFailureReason(transactionResponse);
            if (failureReason == null)
                failureReason = PaymentFailureReason.UNKNOWN_REASON;

            // Build a detail string for server-side logging (never shown to users)
            StringBuilder sb = new StringBuilder("Authorize payment completion failed:");
            TransactionResponse.Errors errors = transactionResponse == null ? null : transactionResponse.getErrors();
            if (errors != null) {
                for (TransactionResponse.Errors.Error error : errors.getError()) {
                    sb.append(" errorCode = ").append(error.getErrorCode()).append(": ").append(error.getErrorText());
                }
            }
            ANetApiResponse errorResponse = controller.getErrorResponse();
            MessagesType messages = errorResponse != null ? errorResponse.getMessages()
                                  : response  != null ? response.getMessages()
                                  : null;
            if (messages != null) {
                for (MessagesType.Message message : messages.getMessage()) {
                    sb.append(" code = ").append(message.getCode()).append(": ").append(message.getText());
                }
            } else if (sb.toString().endsWith(":")) {
                sb.append(" no response or no message");
            }
            // Log the technical detail server-side; return a clean structured result to the client
            Console.log(sb.toString());
            return Future.succeededFuture(new GatewayCompletePaymentResult(
                null, null, null, PaymentStatus.FAILED, failureReason
            ));
        }
    }

    /**
     * Maps Authorize.net transaction error codes to a {@link PaymentFailureReason}.
     * Returns {@code null} if the error list is absent or contains no recognisable code,
     * allowing callers to fall back to a coarser mapping.
     *
     * <p>Error code reference:
     * <a href="https://developer.authorize.net/api/reference/responseCodes.html">Authorize.net Response Codes</a>
     */
    private static PaymentFailureReason mapTransactionErrorsToFailureReason(TransactionResponse transactionResponse) {
        TransactionResponse.Errors errors = transactionResponse == null ? null : transactionResponse.getErrors();
        if (errors == null)
            return null;
        for (TransactionResponse.Errors.Error error : errors.getError()) {
            PaymentFailureReason reason = mapAnetErrorCode(error.getErrorCode());
            if (reason != null)
                return reason; // return on first recognisable code
        }
        return null;
    }

    /**
     * Maps a single Authorize.net error code string to a {@link PaymentFailureReason}.
     * Returns {@code null} for unrecognised codes so the caller can apply a fallback.
     */
    private static PaymentFailureReason mapAnetErrorCode(String errorCode) {
        if (errorCode == null)
            return null;
        return switch (errorCode) {
            case "6"       -> PaymentFailureReason.INVALID_CARD_NUMBER;  // The credit card number is invalid
            case "7"       -> PaymentFailureReason.INVALID_EXPIRY_DATE;  // The expiration date is invalid
            case "8"       -> PaymentFailureReason.EXPIRED_CARD;         // The credit card has expired
            case "17", "28" -> PaymentFailureReason.CARD_TYPE_NOT_ACCEPTED; // Merchant does not accept this card type
            case "27", "45" -> PaymentFailureReason.DECLINED_BY_BANK;   // AVS / CVV+AVS mismatch — bank declined
            case "65"      -> PaymentFailureReason.INSUFFICIENT_FUNDS;  // Exceeds per-transaction limit
            case "78"      -> PaymentFailureReason.INVALID_CVV;              // The CVV is invalid
            case "33"      -> PaymentFailureReason.BILLING_ADDRESS_REQUIRED; // Bill To Address/Zip is required
            default        -> null;
        };
    }

}
