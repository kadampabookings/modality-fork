package one.modality.ecommerce.payment.spi.impl.server;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.service.MultipleServiceProviders;
import dev.webfx.platform.util.Arrays;
import dev.webfx.platform.util.Booleans;
import dev.webfx.platform.util.Numbers;
import dev.webfx.platform.util.Strings;
import dev.webfx.platform.util.collection.Collections;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.*;
import dev.webfx.stack.session.state.RestrictedPrincipalRegistry;
import dev.webfx.stack.session.state.SystemUserId;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.base.shared.entities.*;
import one.modality.base.shared.entities.db.DatabasePayment;
import one.modality.base.shared.entities.triggers.Triggers;
import one.modality.ecommerce.document.service.DocumentAggregate;
import one.modality.ecommerce.document.service.DocumentService;
import one.modality.ecommerce.document.service.SubmitDocumentChangesArgument;
import one.modality.ecommerce.document.service.events.AbstractDocumentEvent;
import one.modality.ecommerce.document.service.events.AbstractExistingMoneyTransferEvent;
import one.modality.ecommerce.document.service.events.book.*;
import one.modality.ecommerce.document.service.events.gateway.UpdateMoneyTransferEvent;
import one.modality.ecommerce.document.service.events.registration.documentline.RemoveDocumentLineEvent;
import one.modality.ecommerce.history.server.HistoryRecorder;
import one.modality.ecommerce.payment.*;
import one.modality.ecommerce.payment.server.gateway.*;
import one.modality.ecommerce.payment.server.gateway.PaymentGateway; // for CLI
import one.modality.ecommerce.payment.spi.PaymentServiceProvider;

import java.util.*;

/**
 * @author Bruno Salmon
 */
public final class ServerPaymentServiceProvider implements PaymentServiceProvider {

    private static List<PaymentGateway> getProvidedPaymentGateways() {
        return MultipleServiceProviders.getProviders(PaymentGateway.class, () -> ServiceLoader.load(PaymentGateway.class));
    }

    private static PaymentGateway findMatchingPaymentGatewayProvider(String gatewayCompanyName) {
        return getProvidedPaymentGateways().stream()
            .filter(pg -> pg.getName().trim().equalsIgnoreCase(gatewayCompanyName.trim()))
            .findFirst()
            .orElse(null);
    }

    private static <T> Future<T> gatewayNotFoundFailedFuture(String gatewayName) {
        return Future.failedFuture("'" + gatewayName + "' payment gateway not found! (none of the registered payment gateways matches this name)");
    }

    /**
     * Determines whether a payment should use the gateway's live environment (vs sandbox).
     *
     * <p>Three rules combine with OR:
     * <ol>
     *   <li><b>KBS3 way</b> — the event has reached {@link EventState#OPEN} or later.</li>
     *   <li><b>KBS2 fallback</b> — when the event has no state, the explicit {@code event.live} flag.</li>
     *   <li><b>Per-booking override</b> — {@code document.forceLivePayment=true} forces live mode
     *       regardless of event state, so staff can test real payments on an event that is still
     *       in DRAFT / PREPARATION before opening it to the public.</li>
     * </ol>
     */
    private static boolean isLivePayment(Document document) {
        Event event = document.getEvent();
        EventState state = event.getState();
        return (state != null && state.compareTo(EventState.OPEN) >= 0)  /* KBS3 way */
               || (state == null && event.isLive())                       /* KBS2 fallback */
               || Boolean.TRUE.equals(document.isForceLivePayment());     /* per-booking override */
    }

    @Override
    public Future<GetPaymentMethodsResult> getPaymentMethods(GetPaymentMethodsArgument argument) {
        // When an eventPrimaryKey is provided directly (e.g. public-talk pre-booking flow),
        // skip the document lookup and resolve the event's org/live status in one query.
        if (argument.eventPrimaryKey() != null) {
            return getPaymentMethodsForEvent(argument.eventPrimaryKey());
        }
        // Step 1: Load event state, live flag, and the event primary key from the document.
        // The event state determines live vs test mode (KBS3 way); event.live is the KBS2 fallback;
        // document.forceLivePayment is a per-booking override for testing live payments before opening.
        // document.inPerson is the booking channel, which decides the money account (see step 2).
        return EntityStore.create()
            .<Document>executeQuery(
                "select forceLivePayment, inPerson, event.(state, live, organization) from Document where id=$1",
                argument.documentPrimaryKey())
            .compose(documents -> {
                if (documents.isEmpty())
                    return Future.failedFuture("Document not found: " + argument.documentPrimaryKey());
                Document document = documents.get(0);
                Event event = document.getEvent();
                return loadPaymentMethods(event.getOrganizationId(), event.getPrimaryKey(),
                    !Boolean.FALSE.equals(document.isInPerson()), isLivePayment(document));
            });
    }

    private Future<GetPaymentMethodsResult> getPaymentMethodsForEvent(Object eventPk) {
        return EntityStore.create()
            .<Event>executeQuery(
                "select state, live, organization, kbs3, inPersonAllowed from Event where id=$1",
                eventPk)
            .compose(events -> {
                if (events.isEmpty())
                    return Future.failedFuture("Event not found: " + eventPk);
                Event event = events.get(0);
                EventState state = event.getState();
                boolean live = state != null && state.compareTo(EventState.OPEN) >= 0
                               || state == null && event.isLive();
                // No booking yet, so no document.inPerson: predict the one it will get. Bookings created
                // without a document (public talks) are sent in-person, and the database's document insert
                // trigger turns that to online only for a KBS2 event that does not allow in-person.
                boolean inPerson = !(Boolean.FALSE.equals(event.isKbs3()) && Boolean.FALSE.equals(event.isInPersonAllowed()));
                return loadPaymentMethods(event.getOrganizationId(), eventPk, inPerson, live);
            });
    }

    // Step 2: Find the destination MoneyAccount for an online payment from this event's organization.
    // This mirrors the autoset_money_transfer_to_account trigger logic, which is what actually picks it:
    // - type.internal=true and type.customer=false → destination account (not a customer account)
    // - gatewayCompany!=null                       → only gateway-linked accounts
    // - !closed                                    → account must be active
    // - order by preferredForOnline                → online bookings prefer accounts preferred for online,
    //                                                in-person bookings prefer the others (V0116)
    // - order by event=$eventPk desc               → prefer event-specific accounts over org-level ones
    private Future<GetPaymentMethodsResult> loadPaymentMethods(EntityId organizationId, Object eventPk, boolean inPerson, boolean live) {
        Object organizationPk = Numbers.toShortestNumber(Entities.getPrimaryKey(organizationId));
        Object normalizedEventPk = Numbers.toShortestNumber(eventPk);
        return EntityStore.create()
            .<MoneyAccount>executeQuery(
                "select gatewayCompany.name,googlePayEnabled,applePayEnabled from MoneyAccount" +
                " where organization=$1 and !closed and gatewayCompany!=null" +
                " and type.internal and !type.customer" +
                " order by " + MoneyAccount.preferredForOnline + (inPerson ? "" : " desc") + ", event=$2 desc, id",
                organizationPk, normalizedEventPk)
            .map(accounts -> {
                if (accounts.isEmpty())
                    return new GetPaymentMethodsResult("Unknown", live, new GatewayPaymentMethodInfo[0]);
                MoneyAccount moneyAccount = accounts.get(0);
                String gatewayName = moneyAccount.getGatewayCompany().getName();
                PaymentGateway gateway = findMatchingPaymentGatewayProvider(gatewayName);
                if (gateway == null)
                    return new GetPaymentMethodsResult(gatewayName, live, new GatewayPaymentMethodInfo[0]);
                List<GatewayPaymentMethodInfo> supportedPaymentMethods = Collections.filter(
                    gateway.getSupportedPaymentMethods(),
                    methodInfo ->
                        methodInfo.method() == PaymentMethod.GOOGLE_PAY ? moneyAccount.isGooglePayEnabled() :
                        methodInfo.method() == PaymentMethod.APPLE_PAY ? moneyAccount.isApplePayEnabled() :
                        true
                );
                return new GetPaymentMethodsResult(
                    gateway.getName(),
                    live,
                    supportedPaymentMethods.toArray(GatewayPaymentMethodInfo[]::new)
                );
            });
    }

    @Override
    public Future<InitiatePaymentResult> initiatePayment(InitiatePaymentArgument argument) {
        // A read-only session (a support member borrowing someone's view) must not start a payment
        // on that person's behalf. Same reasoning as ServerDocumentServiceProvider.submitDocumentChanges:
        // this flow's writes happen after async hops where the SQL layer's thread-local read-only
        // check sees no principal, so the refusal has to live here at the synchronous entry, while
        // the thread-local still holds the caller. (Gateway/system callers carry no such principal
        // and are unaffected.)
        Future<InitiatePaymentResult> refusal = refuseIfReadOnlySession("initiate a payment");
        if (refusal != null)
            return refusal;
        // Step 1: Inserting the payment in the database with its payment allocations
        return insertPayment(argument.amount(), argument.paymentAllocations(), argument.preferredFormType(), argument.paymentMethod()) // insertPayment
            .compose(databasePayment -> {
                MoneyTransfer totalTransfer = databasePayment.totalTransfer();
                // Step 2: Loading the primary document for the database payment
                return loadDatabasePaymentDocuments(databasePayment)
                    .compose(v -> {
                        // Step 3: Finding a Gateway provider registered in the software that matches the money account of the payment
                        String gatewayName = totalTransfer.getToMoneyAccount().getGatewayCompany().getName();
                        PaymentGateway paymentGateway = findMatchingPaymentGatewayProvider(gatewayName);
                        if (paymentGateway == null)
                            return gatewayNotFoundFailedFuture(gatewayName);
                        // Step 4: Loading the relevant payment gateway parameters (depending on the event state)
                        Document primaryDoc = getDatabasePaymentPrimaryDocument(databasePayment);
                        boolean live = isLivePayment(primaryDoc);
                        String moneyTransferId = totalTransfer.getPrimaryKey().toString();
                        String returnUrl = Strings.replaceAll(argument.returnUrl(), ":moneyTransferId", moneyTransferId);
                        String cancelUrl = Strings.replaceAll(argument.cancelUrl(), ":moneyTransferId", moneyTransferId);
                        return loadPaymentGatewayParameters(totalTransfer, live)
                            .compose(parameters -> {
                                // Step 5: Calling the payment gateway with all the data collected
                                String currencyCode = totalTransfer.getToMoneyAccount().getCurrency().getCode();
                                String merchantCountryCode = primaryDoc.evaluate("event.organization.country.iso_alpha2");
                                return paymentGateway.initiatePayment(new GatewayInitiatePaymentArgument(
                                    moneyTransferId,
                                    createGatewayOrder(databasePayment),
                                    currencyCode,
                                    live,
                                    argument.paymentMethod(),
                                    argument.preferredFormType(),
                                    argument.favorSeamless(),
                                    argument.isOriginOnHttps(),
                                    returnUrl,
                                    cancelUrl,
                                    parameters,
                                    createGatewayCustomer(databasePayment),
                                    merchantCountryCode
                                )).map(gatewayResult -> new InitiatePaymentResult( // Step 5: Returning an InitiatePaymentResult
                                    paymentGateway.getName(),
                                    totalTransfer.getPrimaryKey(),
                                    argument.amount(),
                                    gatewayResult.isLive(),
                                    gatewayResult.url(),
                                    gatewayResult.formType(),
                                    gatewayResult.htmlContent(),
                                    gatewayResult.isSeamless(),
                                    gatewayResult.hasHtmlPayButton(),
                                    gatewayResult.sandboxCards(),
                                    gatewayResult.fallbackRedirectUrl(),
                                    gatewayResult.paypalInAppOrderId(),
                                    gatewayResult.paypalInAppClientId(),
                                    gatewayResult.paypalInAppCurrency(),
                                    // Minted here and nowhere else: this is the first moment the payment has
                                    // an id, and the only moment the caller is known to be the one making it.
                                    ResumePaymentToken.mint(totalTransfer.getPrimaryKey())
                                ));
                            });
                    });
            });
    }

    private Future<Void> loadDatabasePaymentDocuments(DatabasePayment databasePayment) {
        return databasePayment.totalTransfer().getStore()
            .executeQuery("select toMoneyAccount.(currency.code, gatewayCompany.name), document.(ref,person_name,person_firstName,person_lastName,person_email,person_phone,person_street,person_postCode,person_cityName,person_admin1Name,person_country.(name,iso_alpha2),person_countryName,forceLivePayment,event.(name,state,live,organization.country.iso_alpha2)) from MoneyTransfer where id=$1 or parent=$1", databasePayment.totalTransfer())
            .mapEmpty();
    }

    private static GatewayCustomer createGatewayCustomer(DatabasePayment databasePayment) {
        Document document = databasePayment.totalTransfer().getDocument();
        if (document == null && databasePayment.allocatedTransfers() != null && databasePayment.allocatedTransfers().length > 0)
            document = databasePayment.allocatedTransfers()[0].getDocument();
        if (document == null)
            return null;
        return new GatewayCustomer(
            Strings.toString(Entities.getPrimaryKey(document.getPersonId())),
            document.getFirstName(),
            document.getLastName(),
            document.getEmail(),
            document.getPhone(),
            document.getStreet(),
            document.getCityName(),
            document.getPostCode(),
            document.getAdmin1Name(),
            document.evaluate("coalesce(person_country.name,person_countryName)"),
            document.evaluate("person_country.iso_alpha2")
        );
    }

    private static Document getDatabasePaymentPrimaryDocument(DatabasePayment databasePayment) {
        Document primaryDocument = databasePayment.totalTransfer().getDocument();
        if (primaryDocument == null) {
            primaryDocument = databasePayment.allocatedTransfers()[0].getDocument();
        }
        return primaryDocument;
    }

    @Override
    public Future<CompletePaymentResult> completePayment(CompletePaymentArgument argument) {
        // Same synchronous read-only refusal as initiatePayment — a borrowed view must not charge.
        Future<CompletePaymentResult> refusal = refuseIfReadOnlySession("complete a payment");
        if (refusal != null)
            return refusal;
        String gatewayName = argument.gatewayName();
        Object paymentPrimaryKey = argument.paymentPrimaryKey();
        boolean live = argument.isLive();
        PaymentGateway paymentGateway = findMatchingPaymentGatewayProvider(gatewayName);
        if (paymentGateway == null)
            return gatewayNotFoundFailedFuture(gatewayName);
        SystemUserId gatewayUserId = new SystemUserId(gatewayName);
        // Capture before the async Future.all() below — ThreadLocal is gone inside compose callbacks.
        Object userId = ThreadLocalStateHolder.getUserId();

        UpdateStore updateStore = UpdateStore.create(DataSourceModelService.getDefaultDataSourceModel());
        MoneyTransfer moneyTransfer = updateStore.updateEntity(MoneyTransfer.class, paymentPrimaryKey);

        // Extract wallet payment method from the payload if present (GOOGLE_PAY, APPLE_PAY, etc.)
        PaymentMethod paymentMethod = extractPaymentMethodFromPayload(argument.gatewayCompletePaymentPayload());

        String moneyTransferFieldsToLoad = "amount,spread,pending,successful,transactionRef,document.(ref,person,person_firstName,person_lastName,person_email,person_phone,person_street,person_postCode,person_cityName,person_admin1Name,person_country.name,person_countryName,event.name)";
        return Future.all(
            loadPaymentGatewayParameters(paymentPrimaryKey, live),
            // We also load the amount and customer info to pass it to gateways like Authorize.net that set the amount on completion
            // TODO: Should we skip this unnecessary loading for other gateways like Square?
            moneyTransfer.onExpressionLoaded(moneyTransferFieldsToLoad),
            updateStore.executeQuery("select " + moneyTransferFieldsToLoad + " from MoneyTransfer where parent=$1 order by id", moneyTransfer)
        ).compose(cf -> {
            Map<String, String> parameters = (Map<String, String>) cf.list().get(0); // result of loadPaymentGatewayParameters(paymentPrimaryKey, live)
            EntityList<MoneyTransfer> allocatedTransfers = (EntityList<MoneyTransfer>) cf.list().get(2);
            String accessToken = parameters.get("access_token");
            int amount = moneyTransfer.getAmount();
            Document billingDocument = moneyTransfer.getDocument();
            if (billingDocument == null) {
                MoneyTransfer firstChild = Collections.first(allocatedTransfers);
                if (firstChild != null) {
                    billingDocument = firstChild.getDocument();
                }
            }
            GatewayCustomer billingCustomer = billingDocument == null ? null : new GatewayCustomer(
                Strings.toString(Entities.getPrimaryKey(billingDocument.getPersonId())),
                billingDocument.getFirstName(),
                billingDocument.getLastName(),
                billingDocument.getEmail(),
                billingDocument.getPhone(),
                billingDocument.getStreet(),
                billingDocument.getCityName(),
                billingDocument.getPostCode(),
                billingDocument.getAdmin1Name(),
                billingDocument.evaluate("coalesce(person_country.name,person_countryName)"),
                billingDocument.evaluate("person_country.iso_alpha2")
            );
            DatabasePayment databasePayment = new DatabasePayment(moneyTransfer, allocatedTransfers.toArray(MoneyTransfer[]::new));

            // Idempotency guard: never charge the gateway twice for the same MoneyTransfer. A second
            // completion attempt happens when the first attempt's outcome never reached the client
            // (gateway response lost, bus reply timeout, socket drop) and the user or client retries —
            // that's how booking E1901-REF14-MT101001 got charged twice. If the transfer is already
            // successful, return the recorded outcome instead of creating a second gateway transaction.
            if (Booleans.isTrue(moneyTransfer.isSuccessful())) {
                boolean stillPending = Booleans.isTrue(moneyTransfer.isPending()); // authorised but not yet captured
                Console.log("completePayment() called for MoneyTransfer " + paymentPrimaryKey + " which is already " + (stillPending ? "authorised" : "completed") + " - skipping gateway charge");
                HistoryRecorder.preparePaymentHistoriesBeforeSubmit("Ignored duplicate completion attempt for already-successful payment [amount]", databasePayment, userId)
                    .onFailure(Console::error)
                    .onSuccess(x -> updateStore.submitChanges());
                return Future.succeededFuture(new CompletePaymentResult(stillPending ? PaymentStatus.APPROVED : PaymentStatus.COMPLETED, null));
            }
            // Same guard for a pending transfer that already has a gateway transaction recorded (e.g.
            // held for fraud review): a replayed completion (stale tab, back button) must not charge
            // again while the first transaction's outcome is still undecided. A failed attempt
            // (!pending && !successful) also stores a transactionRef, but that one must stay
            // retryable — a declined card is a definitive "no charge", so paying again is legitimate.
            if (Booleans.isTrue(moneyTransfer.isPending()) && moneyTransfer.getTransactionRef() != null) {
                Console.log("completePayment() called for MoneyTransfer " + paymentPrimaryKey + " which is still pending on gateway transaction " + moneyTransfer.getTransactionRef() + " - skipping gateway charge");
                HistoryRecorder.preparePaymentHistoriesBeforeSubmit("Ignored duplicate completion attempt for still-pending payment [amount]", databasePayment, userId)
                    .onFailure(Console::error)
                    .onSuccess(x -> updateStore.submitChanges());
                return Future.succeededFuture(new CompletePaymentResult(PaymentStatus.PENDING, null));
            }

            GatewayOrder order = createGatewayOrder(databasePayment);

            // The following code is executed just after the call to the Payment Gateway (which will take a bit of time to
            // finalize the payment and return the status). However, we add a record in the history to indicate that the
            // booker submitted valid cc details.
            HistoryRecorder.preparePaymentHistoriesBeforeSubmit("Submitted card details to " + gatewayName + " for payment [amount]" + paymentLabel(PaymentFormType.EMBEDDED, paymentMethod), databasePayment, userId)
                    .onFailure(Console::error)
                    .onSuccess(x -> updateStore.submitChanges());

            // TODO check accessToken is set, otherwise return an error
            return paymentGateway.completePayment(new GatewayCompletePaymentArgument(live, accessToken, argument.gatewayCompletePaymentPayload(), parameters, amount, billingCustomer, order))
                .onFailure(e -> {
                    Console.log("An error occurred while completing payment: " + e.getMessage());
                    // We finally update the payment status through the payment service (this will also create a history entry)
                    gatewayUserId.callAndReturn(() ->
                        updatePaymentStatus(UpdatePaymentStatusArgument.createExceptionStatusArgument(
                            paymentPrimaryKey, null, e.getMessage()))  // formType not added to exception arguments
                            .onFailure(ex -> Console.log("An error occurred while completing payment: " + ex.getMessage()))
                    );
                })
                .compose(result -> {
                    String gatewayResponse = result.gatewayResponse();
                    String gatewayTransactionRef = result.gatewayTransactionRef();
                    String gatewayStatus = result.gatewayStatus();
                    PaymentStatus paymentStatus = result.paymentStatus();
                    boolean pending = paymentStatus.isPending();
                    boolean successful = paymentStatus.isSuccessful();
                    PaymentFailureReason failureReason = result.failureReason();
                    // We finally update the payment status through the payment service (this will also create a history entry)
                    return gatewayUserId.callAndReturn(() ->
                        updatePaymentStatus(UpdatePaymentStatusArgument.createCapturedStatusArgument(
                            paymentPrimaryKey,
                            gatewayResponse,
                            gatewayTransactionRef,
                            gatewayStatus,
                            pending,
                            successful,
                            PaymentFormType.EMBEDDED,
                            paymentMethod))
                            .map(ignoredVoid -> new CompletePaymentResult(paymentStatus, failureReason))
                            .onFailure(Console::error)
                    );
                });
        });
    }

    private static GatewayOrder createGatewayOrder(DatabasePayment databasePayment) {
        MoneyTransfer moneyTransfer = databasePayment.totalTransfer();
        Document primaryDocument = getDatabasePaymentPrimaryDocument(databasePayment);
        Event primaryEvent = primaryDocument.getEvent();
        // Format used by gateway dashboards as the payment description / note:
        //   "KBS - #<eventId> <eventName> - #<bookingRef>"               (single booking)
        //   "KBS - #<eventId> <eventName> - #<ref1> #<ref2> #<ref3>"     (spread payment)
        // The "KBS -" prefix lets staff filter payments coming from this booking
        // system when the gateway account is also used for other things (e.g. a
        // till or a separate webshop). Stored in `longName`; gateways pass it
        // through to their own dashboards (Square `note`, PayPal order
        // description, Authorize.net line item) so the org can identify which
        // event/bookings each payment relates to.
        String eventLabel = "KBS - #" + primaryEvent.getPrimaryKey() + " " + primaryEvent.getName();

        // Case of a simple payment made on a single booking
        if (Booleans.isNotTrue(moneyTransfer.isSpread())) {
            String paymentDescription = eventLabel + " - #" + primaryDocument.getRef();
            return new GatewayOrder(
                // Node: Some payment gateways like Square don't allow spaces and # in identifiers
                "E" + primaryEvent.getPrimaryKey() + "-REF" + primaryDocument.getRef() + "-MT" + moneyTransfer.getPrimaryKey(),
                primaryEvent.getName() + " #" + primaryDocument.getRef(),
                paymentDescription,
                moneyTransfer.getAmount(),
                new GatewayItem[]{
                    new GatewayItem(
                        "E" + primaryEvent.getPrimaryKey() + "-REF" + primaryDocument.getRef() + "-MT" + moneyTransfer.getPrimaryKey(),
                        "Booking #" + primaryDocument.getRef(),
                        "Booking #" + primaryDocument.getRef() + " - " + primaryDocument.getFullName(),
                        1,
                        moneyTransfer.getAmount()
                    )
                }
            );
        }

        // Case of a multiple payment spread over several bookings
        StringBuilder bookingRefs = new StringBuilder();
        for (MoneyTransfer allocatedTransfer : databasePayment.allocatedTransfers()) {
            if (!bookingRefs.isEmpty()) bookingRefs.append(' ');
            bookingRefs.append('#').append(allocatedTransfer.getDocument().getRef());
        }
        String paymentDescription = eventLabel + " - " + bookingRefs;
        return new GatewayOrder(
            // Node: Some payment gateways like Square don't allow spaces and # in identifiers
            "E" + primaryEvent.getPrimaryKey() + "-MT" + moneyTransfer.getPrimaryKey(),
            primaryEvent.getName(),
            paymentDescription,
            moneyTransfer.getAmount(),
            Arrays.map(databasePayment.allocatedTransfers(), allocatedTransfer -> {
                Document document = allocatedTransfer.getDocument();
                return new GatewayItem(
                    "E" + document.getEvent().getPrimaryKey() + "-REF" + document.getRef() + "-MT" + allocatedTransfer.getPrimaryKey(),
                    "Booking #" + document.getRef(),
                    "Booking #" + document.getRef() + " - " + document.getFullName(),
                    1,
                    allocatedTransfer.getAmount()
                );
            }, GatewayItem[]::new)
        );
    }

    /**
     * A failed future when the caller on this thread is a read-only session (a support view of
     * either flavour), otherwise null. Must be called in the synchronous part of a bus call —
     * past the first async hop the thread-local no longer holds the principal and the answer
     * would silently become "not restricted", which is the failure mode this guard exists to
     * avoid. Gateway/system principals and anonymous callers are not restricted.
     */
    private static <T> Future<T> refuseIfReadOnlySession(String action) {
        if (RestrictedPrincipalRegistry.isCurrentUserRestricted())
            return Future.failedFuture("[ReadOnlySessionError] This session is not allowed to " + action);
        return null;
    }

    @Override
    public Future<CancelPaymentResult> cancelPayment(CancelPaymentArgument argument) {
        // Same synchronous read-only refusal as initiatePayment: cancellation mutates the payment
        // row and can un-book options, neither of which a borrowed view may do.
        Future<CancelPaymentResult> refusal = refuseIfReadOnlySession("cancel a payment");
        if (refusal != null)
            return refusal;
        boolean explicit = argument.isExplicitUserCancellation();
        Future<MoneyTransfer> updated = updatePaymentStatusImpl(
                UpdatePaymentStatusArgument.createCancelStatusArgument(
                        argument.paymentPrimaryKey(), explicit, argument.formType(), argument.paymentMethod()));
        // Only un-book unpaid options on EXPLICIT cancellation. An abandonment
        // (explicit=false: popup dismissed, window closed, wallet sheet swiped
        // away) leaves the document changes intact, so if the user retries and
        // succeeds the same MoneyTransfer can be flipped to successful without
        // anything to restore.
        Future<CancelPaymentResult> result = explicit
                ? updated.compose(this::unbookUnpaidOptionsIfRecurringEvent)
                : updated.map(mt -> new CancelPaymentResult(false));
        return result.onFailure(Console::error);
    }

    private Future<CancelPaymentResult> unbookUnpaidOptionsIfRecurringEvent(MoneyTransfer moneyTransfer) {
        return moneyTransfer.onExpressionLoaded("document.(event.type.recurringItem,price_deposit)")
            .compose(x -> {
                EntityId recurringItemId = moneyTransfer.evaluate("document.event.type.recurringItem");
                // We check it's a recurring event, otherwise we skip that feature
                if (recurringItemId == null)
                    return Future.succeededFuture(new CancelPaymentResult(false));
                Document document = moneyTransfer.getDocument();
                // If there was no deposit on the booking, we cancel that booking
                if (document.getPriceDeposit() == 0) {
                    return SystemUserId.SYSTEM.callAndReturn(() -> DocumentService.submitDocumentChanges(SubmitDocumentChangesArgument.of(
                        "Cancelled booking",
                        new CancelDocumentEvent(document, true))
                    ).map(ignored -> new CancelPaymentResult(true)));
                }
                // If there is a deposit, we remove all options added after the last successful payment (that is
                // meant to pay all previous options).
                // The SYSTEM reads this history, not a user: it runs after several async hops, so there is
                // no principal left on the thread, and the document service refuses a reader it cannot
                // identify. Same marker the two submitDocumentChanges calls in this method already use;
                // the wrapper covers the read because Future.all evaluates loadDocument synchronously.
                return SystemUserId.SYSTEM.callAndReturn(() ->
                    DocumentService.loadDocumentWithPolicyAndWholeHistory(document))
                    .compose(policyAndDocumentAggregates -> {
                        DocumentAggregate documentAggregate = policyAndDocumentAggregates.documentAggregate();
                        // Searching for the last successful payment (shouldn't be null as there is a price deposit)
                        MoneyTransfer lastSuccessfulPayment = documentAggregate.getSuccessfulMoneyTransfersStream().reduce((first, second) -> second).orElse(null);
                        // Searching for the event marking this payment as successful
                        List<AbstractDocumentEvent> documentEvents = documentAggregate.getNewDocumentEvents();
                        List<AbstractDocumentEvent> removeEvents = new ArrayList<>();
                        documentEvents.stream().dropWhile(e -> {
                            if (!(e instanceof AbstractExistingMoneyTransferEvent aemte))
                                return true;
                            if (aemte.getMoneyTransfer() != lastSuccessfulPayment)
                                return true;
                            return aemte.isPending() || !aemte.isSuccessful();
                        }).forEach(e -> {
                            if (e instanceof AddAttendancesEvent aae) {
                                removeEvents.add(new RemoveAttendancesEvent(aae.getAttendances()));
                            } else if (e instanceof AddDocumentLineEvent aee) {
                                removeEvents.add(new RemoveDocumentLineEvent(aee.getDocumentLine()));
                            }
                        });
                        if (removeEvents.isEmpty())
                            return Future.succeededFuture(new CancelPaymentResult(false));
                        return SystemUserId.SYSTEM.callAndReturn(() -> DocumentService.submitDocumentChanges(
                                SubmitDocumentChangesArgument.of("Unbooked unpaid options",
                                    removeEvents.toArray(new AbstractDocumentEvent[0])))
                            .map(ignoredResult -> new CancelPaymentResult(false)));
                    });
            });
    }

    private Future<DatabasePayment> insertPayment(int amount, PaymentAllocation[] paymentAllocations, PaymentFormType preferredFormType, PaymentMethod paymentMethod) {
        UpdateStore updateStore = UpdateStore.create(DataSourceModelService.getDefaultDataSourceModel());
        MoneyTransfer moneyTransfer = updateStore.insertEntity(MoneyTransfer.class);
        moneyTransfer.setAmount(amount);
        moneyTransfer.setPending(true);
        moneyTransfer.setSuccessful(false);
        moneyTransfer.setMethod(Method.ONLINE_METHOD_ID);

        DatabasePayment databasePayment;
        if (paymentAllocations.length == 1) {
            moneyTransfer.setDocument(paymentAllocations[0].documentPrimaryKey());
            databasePayment = new DatabasePayment(moneyTransfer, null);
        } else {
            moneyTransfer.setSpread(true);
            MoneyTransfer[] paymentChildren = Arrays.map(paymentAllocations, paymentAllocation -> {
                MoneyTransfer allocatedTransfer = updateStore.insertEntity(MoneyTransfer.class);
                allocatedTransfer.setParent(moneyTransfer);
                allocatedTransfer.setDocument(paymentAllocation.documentPrimaryKey());
                allocatedTransfer.setAmount(paymentAllocation.amount());
                allocatedTransfer.setPending(true);
                allocatedTransfer.setSuccessful(false);
                allocatedTransfer.setMethod(Method.ONLINE_METHOD_ID);
                return allocatedTransfer;
            }, MoneyTransfer[]::new);
            databasePayment = new DatabasePayment(moneyTransfer, paymentChildren);
        }

        return HistoryRecorder.preparePaymentHistoriesBeforeSubmit("Initiated payment [amount]" + paymentLabel(preferredFormType, paymentMethod), databasePayment)
            .compose(histories ->
                updateStore.submitChanges()
                    // On success, we load the necessary data associated with this moneyTransfer for the payment gateway
                    .compose(batch ->
                        HistoryRecorder.completeDocumentHistoriesAfterSubmit(histories, new AddMoneyTransferEvent(moneyTransfer))
                            .map(ignoredVoid -> databasePayment)
                    )
            );
    }

    // Internal server-side method only (no serialization support)

    public Future<Map<String, String>> loadPaymentGatewayParameters(Object paymentId, boolean live) {
        paymentId = Numbers.toShortestNumber(Entities.getPrimaryKey(paymentId));
        return EntityStore.create()
            .<GatewayParameter>executeQuery("select name,value from GatewayParameter where (account=(select toMoneyAccount from MoneyTransfer where id=$1) or account==null and lower(company.name)=lower((select lower(toMoneyAccount.gatewayCompany.name) from MoneyTransfer where id=$1))) and ($2 ? live : test) order by account nulls first", paymentId, live)
            .onFailure(e -> Console.error("An error occurred while loading paymentGatewayParameters", e))
            .map(gpList -> {
                Map<String, String> parameters = new HashMap<>();
                gpList.forEach(gp -> parameters.put(gp.getName(), gp.getValue()));
                return parameters;
            });
    }

    @Override
    public Future<Void> updatePaymentStatus(UpdatePaymentStatusArgument argument) {
        return updatePaymentStatusImpl(argument).mapEmpty();
    }

    private Future<MoneyTransfer> updatePaymentStatusImpl(UpdatePaymentStatusArgument argument) {
        UpdateStore updateStore = UpdateStore.create(DataSourceModelService.getDefaultDataSourceModel());
        MoneyTransfer moneyTransfer = updateStore.updateEntity(MoneyTransfer.class, argument.paymentPrimaryKey());
        String gatewayResponse = argument.gatewayResponse();
        String gatewayTransactionRef = argument.gatewayTransactionRef();
        String gatewayStatus = argument.gatewayStatus();
        boolean pending = argument.isPendingStatus();
        boolean successful = argument.isSuccessfulStatus();
        boolean isExplicitUserCancellation = argument.isExplicitUserCancellation();
        String errorMessage = argument.errorMessage();
        Object userId = ThreadLocalStateHolder.getUserId(); // Capturing userId because we may have an async call
        boolean isGatewayUser = userId instanceof SystemUserId;
        String fieldsToLoad = "amount,spread"; // As it will be required for history anyway
        if (!pending && successful) { // If the payment is successful, we check if it was pending before (to adjust the history comment)
            fieldsToLoad += ",pending";
        }
        return moneyTransfer.<MoneyTransfer>onExpressionLoaded(fieldsToLoad).compose(x -> {
            Boolean wasPending = x.isPending();
            moneyTransfer.setPending(pending);
            moneyTransfer.setSuccessful(successful);
            if (gatewayTransactionRef != null)
                moneyTransfer.setTransactionRef(gatewayTransactionRef);
            if (gatewayStatus != null)
                moneyTransfer.setStatus(gatewayStatus);
            if (gatewayResponse != null)
                moneyTransfer.setGatewayResponse(gatewayResponse);
            if (errorMessage != null)
                moneyTransfer.setComment(errorMessage);

            String ftLabel = paymentLabel(argument.formType(), argument.paymentMethod());
            String historyComment =
                errorMessage != null ? "Raised an error while processing payment [amount]" :
                    !pending && successful ? (wasPending ? "Processed payment [amount] successfully" : "Reported payment [amount] is successful") + ftLabel :
                        !pending && !successful ? (isGatewayUser ? "Reported payment [amount] is failed" : isExplicitUserCancellation ? "Cancelled payment [amount]" : "Abandoned payment [amount]" /* typically closed window */) + ftLabel :
                            pending && successful ? "Reported payment [amount] is authorised (not yet completed)" + ftLabel :
                                /*pending && !successful?*/ "Reported payment [amount] is pending" + ftLabel;

            return loadDatabasePayment(moneyTransfer)
                .compose(databasePayment ->
                    HistoryRecorder.preparePaymentHistoriesBeforeSubmit(historyComment, databasePayment, userId)
                        .compose(histories ->
                            updateStore.submitChanges(Triggers.frontOfficeTransaction(updateStore))
                                .compose(result -> { // Checking that something happened in the database
                                    int rowCount = result.getRowCount();
                                    if (rowCount == 0)
                                        return Future.failedFuture("Unknown payment");
                                    // Completing the histories recording (changes column with resolved primary keys)
                                    return HistoryRecorder.completeDocumentHistoriesAfterSubmit(histories, new UpdateMoneyTransferEvent(moneyTransfer))
                                        .map(ignoredVoid -> moneyTransfer);
                                })
                        )
                );
        });
    }

    /** Parses the wallet payment method from a gateway payload JSON string, or returns null for standard card payments. */
    private static PaymentMethod extractPaymentMethodFromPayload(String payload) {
        if (payload == null) return null;
        String key = "\"modality_paymentMethodId\"";
        int keyIdx = payload.indexOf(key);
        if (keyIdx < 0) return null;
        int colonIdx = payload.indexOf(':', keyIdx + key.length());
        if (colonIdx < 0) return null;
        int quoteStart = payload.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = payload.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;
        try {
            return PaymentMethod.valueOf(payload.substring(quoteStart + 1, quoteEnd));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** Returns " (PayPal)", " (Google Pay)", " (Apple Pay)", " (embedded form)", " (redirected page)", or "" for history comments. */
    private static String paymentLabel(PaymentFormType formType, PaymentMethod paymentMethod) {
        if (paymentMethod == PaymentMethod.PAYPAL)     return " (PayPal)";
        if (paymentMethod == PaymentMethod.GOOGLE_PAY) return " (Google Pay)";
        if (paymentMethod == PaymentMethod.APPLE_PAY)  return " (Apple Pay)";
        if (formType == null) return "";
        return formType == PaymentFormType.EMBEDDED ? " (embedded form)" : " (redirected page)";
    }

    private Future<DatabasePayment> loadDatabasePayment(MoneyTransfer moneyTransfer) {
        if (!moneyTransfer.isSpread())
            return Future.succeededFuture(new DatabasePayment(moneyTransfer, null));
        return moneyTransfer.getStore()
            .executeQuery("select amount,document.id from MoneyTransfer where parent=$1", moneyTransfer)
            .map(allocatedTransfers -> new DatabasePayment(moneyTransfer, allocatedTransfers.toArray(new MoneyTransfer[0])));
    }


    /**
     * What happened to one payment, for whoever made it.
     *
     * <p>This replaces two client DQL reads on a PUBLIC page that returned booker names, e-mail addresses and
     * amounts keyed by a sequential MoneyTransfer id. A conjunct in the client's own statement could never be
     * the boundary — the client decides whether to include it — so the query moved here, where the client
     * names a payment and presents a token and chooses nothing else.
     *
     * <p>The token is the whole of the authorization and it is checked FIRST, before anything is read: a
     * caller who fails it learns nothing, not even whether the payment exists. Same reply for a bad token, an
     * expired one and an id that was never a payment.
     *
     * <p>A payment SPREAD over several bookings is a tree: the parent carries the status and the total and no
     * document at all ({@code check_document} nulls it when {@code spread} is true), while one child per
     * booking carries the allocation. Hence {@code id=$1 or parent=$1} throughout, and hence the status and
     * amount come from the parent while the bookings come from the children.
     */
    @Override
    public Future<ResumePaymentResult> resumePayment(ResumePaymentArgument argument) {
        // Normalised as loadPaymentMethods does, so an id that arrives as the string "123" reaches the
        // query as a number. The token check accepts it either way, and without this the uniform-refusal
        // property stopped being literally true for that one case: it failed at the driver instead.
        Object paymentPk = argument == null ? null : Numbers.toShortestNumber(argument.paymentPrimaryKey());
        if (!ResumePaymentToken.isValidFor(argument == null ? null : argument.resumeToken(), paymentPk))
            return Future.failedFuture(RESUME_PAYMENT_REFUSED);
        EntityStore entityStore = EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
        return entityStore.<MoneyTransfer>executeQuery(
                RESUME_TRANSFERS_DQL, paymentPk)
            .compose(transfers -> {
                if (transfers.isEmpty()) // the token verified, so this is a payment that has since gone
                    return Future.failedFuture(RESUME_PAYMENT_REFUSED);
                // Found by MATCHING the id, not by trusting a row order. For a spread payment this is the
                // parent, the only row carrying the real status and the total the payer was charged; picking
                // a child instead would report one allocation as the amount paid, and an `order by` that
                // stopped being honoured would do it silently.
                MoneyTransfer asked = null;
                for (MoneyTransfer transfer : transfers)
                    if (Numbers.identicalObjectsOrNumberValues(transfer.getPrimaryKey(), paymentPk))
                        asked = transfer;
                if (asked == null)
                    return Future.failedFuture(RESUME_PAYMENT_REFUSED);
                Object documentPk = null, eventPk = null;
                for (MoneyTransfer transfer : transfers) {
                    Document document = transfer.getDocument();
                    if (document != null) { // a spread parent has none; its children do
                        documentPk = document.getPrimaryKey();
                        Event event = document.getEvent();
                        eventPk = event == null ? null : event.getPrimaryKey();
                        break;
                    }
                }
                boolean pending = Booleans.isTrue(asked.isPending());
                boolean successful = Booleans.isTrue(asked.isSuccessful());
                int amount = Numbers.intValue(asked.getAmount());
                final Object finalDocumentPk = documentPk, finalEventPk = eventPk;
                return loadResumePaymentBookings(entityStore, paymentPk)
                    .map(bookings -> new ResumePaymentResult(pending, successful, amount,
                        finalDocumentPk, finalEventPk, bookings));
            });
    }

    /**
     * The bookings a payment covers, as the confirmation lists them.
     *
     * <p>Two queries rather than one: the attendance count per booking is a count over another table, and
     * counting it in the same select would multiply the booking rows by their attendances. Read as the SERVER
     * — the caller proved possession of this payment's token, not membership of these bookings' account, and
     * a payer who owns neither is an ordinary case rather than a suspicious one (the pay-cart link exists to
     * be shared).
     */
    private static Future<ResumePaymentBooking[]> loadResumePaymentBookings(EntityStore entityStore, Object paymentPk) {
        return Future.all(
                entityStore.<Document>executeQuery(
                    RESUME_BOOKINGS_DQL, paymentPk),
                // BEST EFFORT, like the client code this replaced (which had .catch(() => [])): an
                // attendance count is a line of detail, and failing the whole call for it would tell a
                // payer their payment cannot be confirmed because a secondary read was shed under load.
                // PARENTHESISED, and it has to be: `documentLine.!cancelled` does not parse — a negation
                // inside a dot path is written `documentLine.(!cancelled ...)`. Removing the parentheses
                // while tidying a duplicated conjunct inside them turned this into a parse error that
                // failed the whole endpoint, on staging, for every payer (2026-10-20).
                //
                // Wrapped so that a SYNCHRONOUS throw becomes a failed future: executeQuery parses
                // eagerly, so a malformed statement never reaches the `.otherwise` below — which is why
                // the best-effort intent above did not save it either.
                Future.succeededFuture()
                    .compose(ignored -> entityStore.<Attendance>executeQuery(
                        RESUME_ATTENDANCES_DQL, paymentPk))
                    .otherwise(EntityList.create("empty-attendances", entityStore))
            ).map(composite -> {
                EntityList<Document> documents = composite.resultAt(0);
                EntityList<Attendance> attendances = composite.resultAt(1);
                // Keyed by the id NORMALISED to a Long, on both sides. The two sides come from different
                // result sets now — one is an entity's primary key, the other a raw aliased column — and
                // nothing guarantees the wire hands both back as the same Java type. An Integer key and a
                // Long lookup miss silently, and the page would show "0 classes" for a booking with seven.
                Map<Long, Integer> attendanceCounts = new HashMap<>();
                for (Attendance attendance : attendances) {
                    // From the ALIAS, not from attendance.getDocumentLine(): the aliased select emits the
                    // foreign-key column and no entity graph, which is precisely what keeps the query from
                    // loading Document's display fields. See RESUME_ATTENDANCES_DQL.
                    Long key = Numbers.toLong(attendance.getFieldValue(ATTENDANCE_DOCUMENT_ALIAS));
                    if (key != null)
                        attendanceCounts.merge(key, 1, Integer::sum);
                }
                return Arrays.map(documents.toArray(new Document[0]), document -> {
                    Person person = document.getPerson();
                    // The booking's own person_* columns are what the booker typed AT THE TIME, and the
                    // person row is what it is now. The page has always preferred the live row and fallen
                    // back, so that is preserved rather than quietly changed by moving the read here.
                    // Read through getStringFieldValue: Document has no accessor for its denormalised
                    // person_* columns, and this is the seam that reads them by name. NOT String.valueOf on
                    // a generic getValue — that infers char[] and throws, which cost five days once.
                    String firstName = person != null && person.getFirstName() != null
                            ? person.getFirstName() : document.getStringFieldValue("person_firstName");
                    String lastName = person != null && person.getLastName() != null
                            ? person.getLastName() : document.getStringFieldValue("person_lastName");
                    String name = Strings.toSafeString(firstName) + " " + Strings.toSafeString(lastName);
                    return new ResumePaymentBooking(
                        document.getPrimaryKey(),
                        person == null ? null : person.getPrimaryKey(),
                        name.trim(),
                        // NOT toSafeString: the page treats a missing ref as absent, and "" renders as an
                        // empty ref instead. The client it replaced produced null here.
                        document.getRef() == null ? null : String.valueOf(document.getRef()),
                        Numbers.intValue(document.getPriceNet()),
                        Numbers.intValue(document.getPriceDeposit()),
                        attendanceCounts.getOrDefault(Numbers.toLong(document.getPrimaryKey()), 0),
                        document.isInPerson());
                }, ResumePaymentBooking[]::new);
            });
    }

    /**
     * One reply for every way the check can fail, so the page cannot be used to learn which.
     *
     * <p>Not "payment not found" and not "invalid token": those two answers together are an oracle for which
     * sequential ids are payments.
     */
    private static final String RESUME_PAYMENT_REFUSED = "Cannot resume this payment";

    /*
     * The three statements the resume-payment endpoint runs, named so that ResumePaymentQueriesCheck can
     * PARSE them against the real domain model. They were inline string literals, and nothing parsed them
     * until a request arrived: `documentLine.!cancelled` reached staging and failed every payer with
     * "Couldn't repair and continue parse" at column 68. A statement no test parses is a statement whose
     * first parse is a user's.
     */

    /** The payment and its children. The parent carries the status; `check_document` leaves it no document. */
    static final String RESUME_TRANSFERS_DQL =
        "select pending,successful,amount,document.(id,event.id) from MoneyTransfer"
        + " where id=$1 or parent=$1";

    /** The bookings the payment covers. No e-mail column: the page does not show one. */
    static final String RESUME_BOOKINGS_DQL =
        "select ref,price_net,price_deposit,inPerson,person.(id,firstName,lastName),"
        + "person_firstName,person_lastName from Document d"
        + " where exists(select MoneyTransfer mt where mt.document=d and (mt.id=$1 or mt.parent=$1))"
        + " and !cancelled order by ref";

    /** The alias the document id arrives under, named once because the query and the reader must agree. */
    static final String ATTENDANCE_DOCUMENT_ALIAS = "documentId";

    /**
     * Attendances per booking, as a bare id per row.
     *
     * <p>Two things here are load-bearing and neither is obvious.
     *
     * <p>The dot path in the WHERE is PARENTHESISED, because `documentLine.!cancelled` does not parse — a
     * negation inside a dot path needs them, and removing them broke the endpoint for every payer.
     *
     * <p>The selected column is ALIASED, and that is what keeps this query small. Unaliased,
     * `documentLine.document.id` compiles — on the server, which has the DQL runtime and so compiles with
     * expressions off — into seventeen columns: the compiler treats the second hop as "load that entity" and
     * adds Document's display fields, among them special_needs, comment and request. This endpoint is PUBLIC
     * and needs one id per row, so it was reading members' accessibility and health notes out of the
     * database to count them. With the alias the compiler prunes to the foreign-key column: two columns, and
     * neither the document nor the multiple_booking join is emitted at all.
     *
     * <p>The cost of the alias is the entity graph: nothing populates `attendance.documentLine` any more, so
     * the id is read from the alias (see {@link #ATTENDANCE_DOCUMENT_ALIAS}).
     */
    static final String RESUME_ATTENDANCES_DQL =
        "select documentLine.document.id as " + ATTENDANCE_DOCUMENT_ALIAS + " from Attendance"
        + " where documentLine.(!cancelled"
        + " and document.id in (select mt.document from MoneyTransfer mt"
        + " where mt.id=$1 or mt.parent=$1))";


}
