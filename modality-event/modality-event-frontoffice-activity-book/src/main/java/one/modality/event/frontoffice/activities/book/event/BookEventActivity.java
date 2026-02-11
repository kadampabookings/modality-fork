package one.modality.event.frontoffice.activities.book.event;

import dev.webfx.extras.controlfactory.button.ButtonFactoryMixin;
import dev.webfx.extras.panes.MonoPane;
import dev.webfx.extras.util.control.Controls;
import dev.webfx.kit.util.properties.FXProperties;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.service.MultipleServiceProviders;
import dev.webfx.platform.uischeduler.UiScheduler;
import dev.webfx.platform.util.Numbers;
import dev.webfx.platform.util.Objects;
import dev.webfx.platform.util.collection.Collections;
import dev.webfx.stack.orm.domainmodel.activity.viewdomain.impl.ViewDomainActivityBase;
import dev.webfx.stack.orm.domainmodel.activity.viewdomain.impl.ViewDomainActivityContextFinal;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.routing.uirouter.UiRouter;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import one.modality.base.client.mainframe.fx.FXMainFrameOverlayArea;
import one.modality.base.frontoffice.mainframe.fx.FXCollapseMenu;
import one.modality.base.shared.entities.AttendanceMode;
import one.modality.base.shared.entities.Document;
import one.modality.base.shared.entities.Event;
import one.modality.base.shared.entities.MoneyTransfer;
import one.modality.base.shared.entities.Person;
import one.modality.booking.client.workingbooking.FXPersonToBook;
import one.modality.booking.client.workingbooking.HasWorkingBookingProperties;
import one.modality.booking.client.workingbooking.WorkingBooking;
import one.modality.booking.client.workingbooking.WorkingBookingProperties;
import one.modality.booking.frontoffice.bookingform.BookingForm;
import one.modality.booking.frontoffice.bookingform.BookingFormEntryPoint;
import one.modality.booking.frontoffice.bookingform.BookingFormProvider;
import one.modality.crm.shared.services.authn.fx.FXUserPersonId;
import one.modality.ecommerce.document.service.DocumentAggregate;
import one.modality.ecommerce.document.service.DocumentService;
import one.modality.ecommerce.document.service.LoadDocumentArgument;
import one.modality.ecommerce.document.service.PolicyAndDocumentAggregates;
import one.modality.ecommerce.policy.service.LoadPolicyArgument;
import one.modality.ecommerce.policy.service.PolicyAggregate;
import one.modality.ecommerce.policy.service.PolicyService;
import one.modality.event.client.event.fx.FXEvent;
import one.modality.event.client.event.fx.FXEventId;
import one.modality.event.frontoffice.activities.book.account.CheckoutAccountRouting;
import one.modality.event.frontoffice.activities.book.fx.FXResumePayment;

import java.util.List;
import java.util.ServiceLoader;


/**
 * @author Bruno Salmon
 */
public final class BookEventActivity extends ViewDomainActivityBase implements ButtonFactoryMixin, HasWorkingBookingProperties {

    private static final List<BookingFormProvider> ALL_BOOKING_FORM_PROVIDERS_SORTED_BY_PRIORITY = MultipleServiceProviders
            .getProviders(BookingFormProvider.class, () -> ServiceLoader.load(BookingFormProvider.class));
    static {
        ALL_BOOKING_FORM_PROVIDERS_SORTED_BY_PRIORITY.sort((p1, p2) -> p2.getPriority() - p1.getPriority());
    }

    private final WorkingBookingProperties workingBookingProperties = new WorkingBookingProperties();
    // Container for the booking form
    private final MonoPane activityContainer = new MonoPane();
    // When routed through /modify-order/:modifyOrderDocumentId, this property will store the documentId to modify
    private final ObjectProperty<Object> modifyOrderDocumentIdProperty = new SimpleObjectProperty<>();
    // When routed through /pay-order/:payOrderDocumentId, this property will store the documentId to pay
    private final ObjectProperty<Object> payOrderDocumentIdProperty = new SimpleObjectProperty<>();
    // When routed through /resume-payment/:resumePaymentMoneyTransferId, this property will store the moneyTransferId
    private final ObjectProperty<Object> resumePaymentMoneyTransferIdProperty = new SimpleObjectProperty<>();
    // Note: when routed through /book-event/:eventId, FXEventId and FXEvent are used to store the event to book
    private long activityStartTimeMillis;
    // fields used to prevent multiple loading of the same thing (due to multiple calls to loadPolicyAndBooking)
    private Object loadingResumePaymentMoneyTransferId, loadingModifyOrPayOrderDocumentId;
    // Flag indicating DocumentAggregate loading was deliberately deferred to ExistingBookingSection
    private boolean deferredDocumentLoad;

    @Override
    public WorkingBookingProperties getWorkingBookingProperties() {
        return workingBookingProperties;
    }

    public WorkingBooking getWorkingBooking() {
        return workingBookingProperties.getWorkingBooking();
    }

    private Object getModifyOrderDocumentId() {
        return modifyOrderDocumentIdProperty.get();
    }

    private Object getPayOrderDocumentId() {
        return payOrderDocumentIdProperty.get();
    }

    public Object getResumePaymentMoneyTransferId() {
        return resumePaymentMoneyTransferIdProperty.get();
    }

    @Override
    public Node buildUi() {
        activityContainer.getStyleClass().add("book-event-activity");
        activityContainer.setAlignment(Pos.TOP_CENTER);
        return activityContainer;
    }

    @Override
    protected void updateModelFromContextParameters() {
        Object eventId = Objects.coalesce(getParameter("eventId"), getParameter("gpClassId"));
        if (eventId != null) { // eventId is null when sub-routing /booking/account (instead of /booking/event/:eventId)
            FXEventId.setEventPrimaryKey(Numbers.toShortestNumber(eventId));
            // Initially hiding the app menu, especially when coming from the website.
            setCollapseMenu();
        }
        Object modifyOrderDocumentId = getParameter("modifyOrderDocumentId");
        Object payOrderDocumentId = getParameter("payOrderDocumentId");
        Object resumePaymentMoneyTransferId = getParameter("resumePaymentMoneyTransferId");
        // Show spinner immediately for non-NEW_BOOKING routes to clear stale content
        boolean nonNewBookingRoute = modifyOrderDocumentId != null || payOrderDocumentId != null || resumePaymentMoneyTransferId != null;
        if (nonNewBookingRoute) {
            showLoadingSpinner();
        }
        // Set properties — if values changed, the listener in startLogic() fires loadPolicyAndBooking()
        modifyOrderDocumentIdProperty.set(modifyOrderDocumentId);
        payOrderDocumentIdProperty.set(payOrderDocumentId);
        resumePaymentMoneyTransferIdProperty.set(resumePaymentMoneyTransferId);
        // Force reload if properties didn't change (re-entering same route); guard prevents double-loading
        if (nonNewBookingRoute) {
            loadPolicyAndBooking();
        }
    }

    private void setCollapseMenu() {
        // We don't show the menu for the GP classes (recurring events)
        Event event = FXEvent.getEvent();
        FXCollapseMenu.setCollapseMenu(event == null || event.isRecurring());
    }

    @Override
    public void onCreate(ViewDomainActivityContextFinal context) {
        super.onCreate(context);
        // Hot declaration of the sub-routing to the checkout account activity
        UiRouter subRouter = UiRouter.createSubRouter(context);
        // Registering the redirect auth routes in the sub-router (to possibly have the login page within the mount node)
        subRouter.registerProvidedUiRoutes(false, true);
        // Registering the route to CheckoutAccountActivity
        subRouter.route(new CheckoutAccountRouting.CheckoutAccountUiRoute()); // sub-route = / and activity = CheckoutAccountActivity
        // Linking this sub-router to the current router (of BookEventActivity)
        getUiRouter().routeAndMount(
            CheckoutAccountRouting.getPath(), // /booking/account
            () -> this, // the parent activity factory (actually this activity)
            subRouter); // the sub-router that will mount the checkout activity
    }

    @Override
    public void onResume() {
        setCollapseMenu(); // Re-establishing the collapse menu policy for this activity
        super.onResume();
    }

    @Override
    public void onPause() {
        // Reset loading guards so re-entering with the same document ID triggers a fresh load
        loadingModifyOrPayOrderDocumentId = null;
        loadingResumePaymentMoneyTransferId = null;
        // Showing the footer again when leaving this activity.
        FXCollapseMenu.resetToDefault();
        super.onPause();
    }

    @Override
    protected void startLogic() {
        activityStartTimeMillis = System.currentTimeMillis();
        // Initial load of the event policy with the possible existing booking of the user (if logged-in)
        FXProperties.runNowAndOnPropertiesChange(this::loadPolicyAndBooking,
            modifyOrderDocumentIdProperty, payOrderDocumentIdProperty, resumePaymentMoneyTransferIdProperty,
                FXEvent.eventProperty(), FXResumePayment.moneyTransferProperty());

        // Later loading when changing the person to book (loading of possible booking and reapplying the newly selected dates)
        FXProperties.runOnPropertyChange(this::onPersonToBookChanged, FXPersonToBook.personToBookProperty());
    }

    private void showLoadingSpinner() {
        activityContainer.setContent(Controls.createSectionSizeSpinner());
        activityContainer.setAlignment(Pos.CENTER);
    }

    void loadPolicyAndBooking() {
        Object modifyOrderDocumentId = getModifyOrderDocumentId();
        Object payOrderDocumentId = getPayOrderDocumentId();
        Object modifyOrPayOrderDocumentId = Objects.coalesce(modifyOrderDocumentId, payOrderDocumentId);
        Object resumePaymentMoneyTransferId = getResumePaymentMoneyTransferId();
        Event event = FXEvent.getEvent(); // might be null on the first call (ex: on page reload)
        // Case when resuming after a redirected payment has been made (the payment gateway called back this url)
        if (resumePaymentMoneyTransferId != null && FXResumePayment.getMoneyTransfer() == null) {
            if (Objects.areEquals(resumePaymentMoneyTransferId, loadingResumePaymentMoneyTransferId))
                return;
            loadingResumePaymentMoneyTransferId = resumePaymentMoneyTransferId;
            showLoadingSpinner();
            // We load the required information about this payment (its state, amount, and associated document/event)
            EntityStore.create(getDataSourceModel())
                .<MoneyTransfer>executeQuery("select pending,successful,amount,document.(ref,person.(firstName,lastName,email),event.(" + FXEvent.EXPECTED_FIELDS + ")) from MoneyTransfer where id = $1 or parent = $1 order by id=$1 desc", resumePaymentMoneyTransferId)
                .onFailure(Console::error)
                .onSuccess(moneyTransfers -> {
                    loadingResumePaymentMoneyTransferId = null;
                    MoneyTransfer moneyTransfer = Collections.first(moneyTransfers);
                    // If the money transfer is still pending within the 10 first seconds, we try to load it again. This
                    // might be because the payment gateway called this activity a bit before too early, i.e., before
                    // the webhook finished updating the payment state.
                    if (moneyTransfer != null && moneyTransfer.isPending() && System.currentTimeMillis() - activityStartTimeMillis < 10_000) {
                        loadPolicyAndBooking();
                        return;
                    }
                    // Once the info is loaded, we set FXEvent and FXResumePaymentMoneyTransfer
                    Document document = moneyTransfers.stream().map(MoneyTransfer::getDocument).filter(Objects::nonNull).findFirst().orElse(null);
                    FXEvent.setEvent(document == null ? null : document.getEvent());
                    FXResumePayment.setMoneyTransfers(moneyTransfers); // will cause loadPolicyAndBooking() to be called again - see startLogic()
                });
        } else if (modifyOrderDocumentId != null && payOrderDocumentId == null) {
            // MODIFY_BOOKING: lightweight approach — load only PolicyAggregate, defer DocumentAggregate
            // to when the user clicks Continue in the ExistingBookingSection (already loads on demand).
            if (Objects.areEquals(modifyOrderDocumentId, loadingModifyOrPayOrderDocumentId))
                return;
            loadingModifyOrPayOrderDocumentId = modifyOrderDocumentId;
            showLoadingSpinner();
            // Step 1: Lightweight query to get event from document
            EntityStore.create(getDataSourceModel())
                .<Document>executeQuery("select event from Document where id=$1", modifyOrderDocumentId)
                .onFailure(error -> {
                    loadingModifyOrPayOrderDocumentId = null;
                    Console.error(error);
                })
                .onSuccess(documents -> {
                    Document doc = Collections.first(documents);
                    if (doc == null) {
                        loadingModifyOrPayOrderDocumentId = null;
                        return;
                    }
                    Object eventPk = doc.getEventId().getPrimaryKey();
                    FXEventId.setEventPrimaryKey(eventPk); // triggers async FXEvent loading
                    // Step 2: Load just PolicyAggregate (in parallel with FXEvent loading)
                    PolicyService.loadPolicy(new LoadPolicyArgument(eventPk))
                        .onFailure(error -> {
                            loadingModifyOrPayOrderDocumentId = null;
                            Console.error(error);
                        })
                        .onSuccess(policyAggregate -> {
                            if (!Objects.areEquals(modifyOrderDocumentId, getModifyOrderDocumentId()))
                                return; // No longer relevant
                            // Ensure FXEvent is available before proceeding (for rebuildEntities)
                            Event fxEvent = FXEvent.getEvent();
                            if (fxEvent != null && Entities.samePrimaryKey(fxEvent, eventPk)) {
                                policyAggregate.rebuildEntities(fxEvent);
                                onPolityAndDocumentAggregatesLoaded(policyAggregate, null);
                            } else {
                                // FXEvent not ready yet — wait for it
                                FXProperties.onPropertySet(FXEvent.eventProperty(), e -> {
                                    policyAggregate.rebuildEntities(e);
                                    onPolityAndDocumentAggregatesLoaded(policyAggregate, null);
                                });
                            }
                        });
                });
        } else if (modifyOrPayOrderDocumentId != null) {
            // PAY_BOOKING: full load — DocumentAggregate needed immediately for payment flow
            if (Objects.areEquals(modifyOrPayOrderDocumentId, loadingModifyOrPayOrderDocumentId))
                return;
            loadingModifyOrPayOrderDocumentId = modifyOrPayOrderDocumentId;
            showLoadingSpinner();
            // Note: this call doesn't automatically rebuild PolicyAggregate entities
            DocumentService.loadPolicyAndDocument(LoadDocumentArgument.ofDocument(modifyOrPayOrderDocumentId))
                .onFailure(Console::error)
                .onSuccess(policyAndDocumentAggregates -> {
                    loadingModifyOrPayOrderDocumentId = null;
                    // Double-checking it's still relevant
                    if (Objects.areEquals(modifyOrPayOrderDocumentId, Objects.coalesce(getModifyOrderDocumentId(), getPayOrderDocumentId()))) {
                        onPolityAndDocumentAggregatesLoaded(policyAndDocumentAggregates);
                    }
                });
        } else {
            // TODO: if eventId doesn't exist in the database, event stays null and nothing happens (stuck on loading page)
            if (event != null) { // happens when routed through /book-event/:eventId
                setCollapseMenu(); // Updating the collapse menu policy (because it depends on the event)

                // Check if the provider wants to auto-load existing bookings for logged-in users
                BookingFormProvider bookingFormProvider = Collections.findFirst(ALL_BOOKING_FORM_PROVIDERS_SORTED_BY_PRIORITY,
                    provider -> provider.acceptEvent(event));
                boolean autoLoadExistingBooking = bookingFormProvider != null && bookingFormProvider.autoLoadExistingBooking(event);

                Person personToBook = FXPersonToBook.getPersonToBook();
                Object userPersonPrimaryKey = Entities.getPrimaryKey(personToBook);
                FXPersonToBook.setAutomaticallyFollowUserPerson(userPersonPrimaryKey == null);
                deferredDocumentLoad = false;
                if (userPersonPrimaryKey == null && autoLoadExistingBooking) {
                    // Note: It's better to use FXUserPersonId rather than FXUserPerson in case of a page reload in the browser
                    // (or redirection to this page from a website) because the retrieval of FXUserPersonId is immediate in case
                    // the user was already logged in (memorized in session), while FXUserPerson requires a DB reading, which
                    // may not be finished yet at this time.
                    userPersonPrimaryKey = FXUserPersonId.getUserPersonPrimaryKey();
                    // Defer heavy DocumentAggregate loading to ExistingBookingSection (loaded on demand
                    // when user clicks Continue). Pass null person to skip the 4-query DocumentAggregate.
                    deferredDocumentLoad = userPersonPrimaryKey != null;
                }
                // When deferring, pass null person so only PolicyAggregate is loaded (~300ms vs ~4s)
                DocumentService.loadPolicyAndDocument(event, deferredDocumentLoad ? null : userPersonPrimaryKey)
                    .onFailure(Console::error)
                    .onSuccess(policyAndDocumentAggregates -> {
                        if (event == FXEvent.getEvent()) // Double-checking that no other changes occurred in the meantime
                            onPolityAndDocumentAggregatesLoaded(policyAndDocumentAggregates);
                    });
            }
        }
    }

    private void onPolityAndDocumentAggregatesLoaded(PolicyAndDocumentAggregates policyAndDocumentAggregates) {
        PolicyAggregate policyAggregate = policyAndDocumentAggregates.policyAggregate(); // never null
        DocumentAggregate existingBooking = policyAndDocumentAggregates.documentAggregate(); // might be null
        onPolityAndDocumentAggregatesLoaded(policyAggregate, existingBooking);
    }

    private void onPolityAndDocumentAggregatesLoaded(PolicyAggregate policyAggregate, DocumentAggregate existingBooking) {
        UiScheduler.runInUiThread(() -> {
            // Ensuring the policy aggregate has rebuilt entities (not automatically done when modifying bookings)
            Event event = policyAggregate.getEvent();
            if (event == null && existingBooking != null) {
                Object eventPrimaryKey = existingBooking.getEventPrimaryKey();
                event = FXEvent.getEvent();
                if (!Entities.samePrimaryKey(event, eventPrimaryKey)) {
                    FXEventId.setEventPrimaryKey(eventPrimaryKey);
                    event = FXEvent.getEvent();
                    if (event == null) { // The event is not yet loading from the database
                        // We postpone and try again when it's loaded
                        FXProperties.onPropertySet(FXEvent.eventProperty(), e -> onPolityAndDocumentAggregatesLoaded(policyAggregate, existingBooking));
                        return;
                    }
                }
                policyAggregate.rebuildEntities(event);
            }

            // Determine entry point first, as it affects how we create the WorkingBooking
            BookingFormEntryPoint entryPoint = getModifyOrderDocumentId() != null ? BookingFormEntryPoint.MODIFY_BOOKING :
                getPayOrderDocumentId() != null ? BookingFormEntryPoint.PAY_BOOKING :
                getResumePaymentMoneyTransferId() != null ? BookingFormEntryPoint.RESUME_PAYMENT :
                    BookingFormEntryPoint.NEW_BOOKING;

            // Find the booking form provider (may specify attendance mode)
            Event finalEvent = event;
            BookingFormProvider bookingFormProvider = Collections.findFirst(ALL_BOOKING_FORM_PROVIDERS_SORTED_BY_PRIORITY,
                provider -> provider.acceptEvent(finalEvent));

            // Create WorkingBooking based on whether we have an existing booking or not
            WorkingBooking workingBooking;
            if (existingBooking != null) {
                // Modifying or paying existing booking - attendanceMode comes from document
                // We also pass getPayOrderDocumentId() which will be used to initialize paymentRequestedByUser in WorkingBooking
                workingBooking = new WorkingBooking(policyAggregate, existingBooking, getPayOrderDocumentId());
            } else {
                // New booking - determine AttendanceMode from provider or event settings
                AttendanceMode attendanceMode = null;
                if (bookingFormProvider != null) {
                    attendanceMode = bookingFormProvider.getAttendanceMode(event);
                }
                if (attendanceMode == null) {
                    // Fallback to event settings
                    attendanceMode = Boolean.TRUE.equals(event.isInPersonAllowed())
                        ? AttendanceMode.IN_PERSON
                        : AttendanceMode.ONLINE;
                }
                workingBooking = new WorkingBooking(policyAggregate, attendanceMode);
            }
            workingBookingProperties.setWorkingBooking(workingBooking);
            // Set modifyBookingMode when DocumentAggregate was deliberately deferred (either via
            // MODIFY_BOOKING route or NEW_BOOKING with autoLoadExistingBooking for logged-in users)
            if (existingBooking == null && (entryPoint == BookingFormEntryPoint.MODIFY_BOOKING || deferredDocumentLoad)) {
                workingBooking.setModifyBookingMode(true);
                deferredDocumentLoad = false;
            }

            // Use the provider-based approach for all entry points
            if (bookingFormProvider != null) {
                BookingForm bookingForm = bookingFormProvider.createBookingForm(finalEvent, this, entryPoint);
                Node bookingFormView = bookingForm.getView();
                if (bookingFormView != null) {
                    activityContainer.setAlignment(Pos.TOP_CENTER);
                    activityContainer.setContent(bookingFormView);
                    bookingForm.onWorkingBookingLoaded();
                    // Add sticky header to the main frame overlay area (if the form has one)
                    Node stickyHeader = bookingForm.getStickyHeader();
                    if (stickyHeader != null && !FXMainFrameOverlayArea.getOverlayChildren().contains(stickyHeader)) {
                        FXMainFrameOverlayArea.getOverlayChildren().add(stickyHeader);
                    }
                }
            }
        });
    }

    private Future<Void> loadBookingWithSamePolicy(boolean onlyIfDifferentPerson) {
        Event event = FXEvent.getEvent();
        WorkingBooking previousPersonWorkingBooking = workingBookingProperties.getWorkingBooking();
        if (event == null || onlyIfDifferentPerson && previousPersonWorkingBooking == null)
            return Future.succeededFuture();

        // Double-checking that the person to book is different from the previous booking person
        Person personToBook = FXPersonToBook.getPersonToBook();
        DocumentAggregate previousPersonExistingBooking = previousPersonWorkingBooking.getInitialDocumentAggregate();
        if (onlyIfDifferentPerson && previousPersonExistingBooking != null && Entities.sameId(previousPersonExistingBooking.getDocument().getPerson(), personToBook))
            return Future.succeededFuture();
        // Loading the possible existing booking of the new person to book for that event
        return DocumentService.loadDocument(event, personToBook)
            .onFailure(Console::error)
            .onSuccess(newPersonExistingBooking -> {
                // Re-instantiating the working booking with that new existing booking (can be null if it doesn't exist)
                onPolityAndDocumentAggregatesLoaded(workingBookingProperties.getPolicyAggregate(), newPersonExistingBooking);
            })
            .mapEmpty();
    }

    private void onPersonToBookChanged() {
        WorkingBooking workingBooking = getWorkingBooking();
        if (workingBooking != null && workingBooking.isNewBooking())
            workingBooking.getDocument().setPerson(FXPersonToBook.getPersonToBook());
        else
            loadBookingWithSamePolicy(true);
    }

}
