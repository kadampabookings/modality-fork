package one.modality.booking.frontoffice.bookingpage.standard;

import dev.webfx.stack.orm.entity.EntityStore;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import one.modality.base.shared.entities.Document;
import one.modality.base.shared.entities.Item;
import one.modality.booking.client.workingbooking.WorkingBooking;
import one.modality.booking.frontoffice.bookingpage.sections.accommodation.HasAccommodationSelectionSection.AccommodationOption;
import one.modality.booking.frontoffice.bookingpage.sections.dates.HasFestivalDaySelectionSection.ArrivalDepartureTime;
import one.modality.booking.frontoffice.bookingpage.sections.options.HasAdditionalOptionsSection.AdditionalOption;
import one.modality.booking.frontoffice.bookingpage.sections.options.HasAdditionalOptionsSection.CeremonyOption;
import one.modality.booking.frontoffice.bookingpage.sections.transport.HasTransportSection.ParkingOption;
import one.modality.booking.frontoffice.bookingpage.sections.transport.HasTransportSection.ShuttleOption;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Centralized state holder for user booking selections.
 * This class implements the "Selection Model Pattern" to decouple UI sections
 * from the data they represent.
 *
 * <p>Instead of sections storing data internally (Section-as-Data-Store anti-pattern),
 * sections push their changes to this state object. The booking form reads from
 * this state when building the WorkingBooking.</p>
 *
 * <p>Benefits:</p>
 * <ul>
 *   <li>Single source of truth for user selections</li>
 *   <li>Sections become pure UI components</li>
 *   <li>Form doesn't need to know section internals</li>
 *   <li>Testable without UI</li>
 *   <li>Easier state serialization/persistence</li>
 * </ul>
 *
 * @author Bruno Salmon
 */
public class BookingSelectionState {

    // === Accommodation Selection ===

    private final ObjectProperty<AccommodationOption> selectedAccommodation = new SimpleObjectProperty<>();

    public ObjectProperty<AccommodationOption> selectedAccommodationProperty() {
        return selectedAccommodation;
    }

    public AccommodationOption getSelectedAccommodation() {
        return selectedAccommodation.get();
    }

    public void setSelectedAccommodation(AccommodationOption option) {
        selectedAccommodation.set(option);
    }

    public boolean hasAccommodation() {
        AccommodationOption option = getSelectedAccommodation();
        return option != null && !option.isDayVisitor();
    }

    public boolean isDayVisitor() {
        AccommodationOption option = getSelectedAccommodation();
        return option != null && option.isDayVisitor();
    }

    /**
     * Returns true if the selected accommodation is a "Share Accommodation" type.
     * Share Accommodation means the user is sharing a room with someone else who is booking the room.
     * Detected by checking if the Item entity has share_mate=true.
     */
    public boolean isShareAccommodation() {
        AccommodationOption option = getSelectedAccommodation();
        if (option == null || option.getItemEntity() == null) {
            return false;
        }
        return Boolean.TRUE.equals(option.getItemEntity().isShare_mate());
    }

    /**
     * Returns the Item entity for the selected accommodation, or null if none selected.
     */
    public Item getSelectedAccommodationItem() {
        AccommodationOption option = getSelectedAccommodation();
        return option != null ? option.getItemEntity() : null;
    }

    // === Date Selection ===

    private final ObjectProperty<LocalDate> arrivalDate = new SimpleObjectProperty<>();
    private final ObjectProperty<LocalDate> departureDate = new SimpleObjectProperty<>();
    private final ObjectProperty<ArrivalDepartureTime> arrivalTime = new SimpleObjectProperty<>();
    private final ObjectProperty<ArrivalDepartureTime> departureTime = new SimpleObjectProperty<>();

    public ObjectProperty<LocalDate> arrivalDateProperty() {
        return arrivalDate;
    }

    public LocalDate getArrivalDate() {
        return arrivalDate.get();
    }

    public void setArrivalDate(LocalDate date) {
        arrivalDate.set(date);
    }

    public ObjectProperty<LocalDate> departureDateProperty() {
        return departureDate;
    }

    public LocalDate getDepartureDate() {
        return departureDate.get();
    }

    public void setDepartureDate(LocalDate date) {
        departureDate.set(date);
    }

    public ObjectProperty<ArrivalDepartureTime> arrivalTimeProperty() {
        return arrivalTime;
    }

    public ArrivalDepartureTime getArrivalTime() {
        return arrivalTime.get();
    }

    public void setArrivalTime(ArrivalDepartureTime time) {
        arrivalTime.set(time);
    }

    public ObjectProperty<ArrivalDepartureTime> departureTimeProperty() {
        return departureTime;
    }

    public ArrivalDepartureTime getDepartureTime() {
        return departureTime.get();
    }

    public void setDepartureTime(ArrivalDepartureTime time) {
        departureTime.set(time);
    }

    // === Roommate Information ===

    private final BooleanProperty isRoomBooker = new SimpleBooleanProperty(false);
    private final ObservableList<String> roommateNames = FXCollections.observableArrayList();
    private final StringProperty shareRoommateName = new SimpleStringProperty();  // For "Share Accommodation" scenario

    public BooleanProperty isRoomBookerProperty() {
        return isRoomBooker;
    }

    public boolean isRoomBooker() {
        return isRoomBooker.get();
    }

    public void setIsRoomBooker(boolean roomBooker) {
        isRoomBooker.set(roomBooker);
    }

    public ObservableList<String> getRoommateNames() {
        return roommateNames;
    }

    public void setRoommateNames(List<String> names) {
        roommateNames.setAll(names);
    }

    public void addRoommateName(String name) {
        roommateNames.add(name);
    }

    public void clearRoommateNames() {
        roommateNames.clear();
    }

    public StringProperty shareRoommateNameProperty() {
        return shareRoommateName;
    }

    public String getShareRoommateName() {
        return shareRoommateName.get();
    }

    public void setShareRoommateName(String name) {
        shareRoommateName.set(name);
    }

    /**
     * Returns all roommate names for booking purposes.
     * For room bookers: returns the list of roommate names
     * For share accommodation: returns a single-element list with the share roommate name
     */
    public List<String> getAllRoommateNamesForBooking() {
        if (isRoomBooker()) {
            return new ArrayList<>(roommateNames);
        } else {
            String shareName = getShareRoommateName();
            return shareName != null && !shareName.isBlank()
                ? List.of(shareName)
                : List.of();
        }
    }

    // === Meal Selection ===

    private final BooleanProperty wantsBreakfast = new SimpleBooleanProperty(false);
    private final BooleanProperty wantsLunch = new SimpleBooleanProperty(false);
    private final BooleanProperty wantsDinner = new SimpleBooleanProperty(false);
    private final ObjectProperty<Item> selectedDietaryItem = new SimpleObjectProperty<>();

    public BooleanProperty wantsBreakfastProperty() {
        return wantsBreakfast;
    }

    public boolean wantsBreakfast() {
        return wantsBreakfast.get();
    }

    public void setWantsBreakfast(boolean wants) {
        wantsBreakfast.set(wants);
    }

    public BooleanProperty wantsLunchProperty() {
        return wantsLunch;
    }

    public boolean wantsLunch() {
        return wantsLunch.get();
    }

    public void setWantsLunch(boolean wants) {
        wantsLunch.set(wants);
    }

    public BooleanProperty wantsDinnerProperty() {
        return wantsDinner;
    }

    public boolean wantsDinner() {
        return wantsDinner.get();
    }

    public void setWantsDinner(boolean wants) {
        wantsDinner.set(wants);
    }

    public ObjectProperty<Item> selectedDietaryItemProperty() {
        return selectedDietaryItem;
    }

    public Item getSelectedDietaryItem() {
        return selectedDietaryItem.get();
    }

    public void setSelectedDietaryItem(Item item) {
        selectedDietaryItem.set(item);
    }

    public boolean hasAnyMeals() {
        return wantsBreakfast() || wantsLunch() || wantsDinner();
    }

    // === Transport Selection ===

    private final ObservableList<ParkingOption> selectedParkingOptions = FXCollections.observableArrayList();
    private final ObservableList<ShuttleOption> selectedShuttleOptions = FXCollections.observableArrayList();

    public ObservableList<ParkingOption> getSelectedParkingOptions() {
        return selectedParkingOptions;
    }

    public void setSelectedParkingOptions(List<ParkingOption> options) {
        selectedParkingOptions.setAll(options);
    }

    public void addSelectedParkingOption(ParkingOption option) {
        selectedParkingOptions.add(option);
    }

    public void clearSelectedParkingOptions() {
        selectedParkingOptions.clear();
    }

    public ObservableList<ShuttleOption> getSelectedShuttleOptions() {
        return selectedShuttleOptions;
    }

    public void setSelectedShuttleOptions(List<ShuttleOption> options) {
        selectedShuttleOptions.setAll(options);
    }

    public void addSelectedShuttleOption(ShuttleOption option) {
        selectedShuttleOptions.add(option);
    }

    public void clearSelectedShuttleOptions() {
        selectedShuttleOptions.clear();
    }

    public boolean hasTransport() {
        return !selectedParkingOptions.isEmpty() || !selectedShuttleOptions.isEmpty();
    }

    // === Audio Recording Selection ===

    private final ObjectProperty<Object> selectedAudioPhase = new SimpleObjectProperty<>();

    public ObjectProperty<Object> selectedAudioPhaseProperty() {
        return selectedAudioPhase;
    }

    public Object getSelectedAudioPhase() {
        return selectedAudioPhase.get();
    }

    public void setSelectedAudioPhase(Object phase) {
        selectedAudioPhase.set(phase);
    }

    public boolean hasAudioSelection() {
        return selectedAudioPhase.get() != null;
    }

    // === Additional Options Selection ===

    private final ObservableList<AdditionalOption> selectedAdditionalOptions = FXCollections.observableArrayList();
    private final ObservableList<CeremonyOption> selectedCeremonyOptions = FXCollections.observableArrayList();

    public ObservableList<AdditionalOption> getSelectedAdditionalOptions() {
        return selectedAdditionalOptions;
    }

    public void setSelectedAdditionalOptions(List<AdditionalOption> options) {
        selectedAdditionalOptions.setAll(options);
    }

    public void addSelectedAdditionalOption(AdditionalOption option) {
        selectedAdditionalOptions.add(option);
    }

    public void clearSelectedAdditionalOptions() {
        selectedAdditionalOptions.clear();
    }

    public ObservableList<CeremonyOption> getSelectedCeremonyOptions() {
        return selectedCeremonyOptions;
    }

    public void setSelectedCeremonyOptions(List<CeremonyOption> options) {
        selectedCeremonyOptions.setAll(options);
    }

    public void addSelectedCeremonyOption(CeremonyOption option) {
        selectedCeremonyOptions.add(option);
    }

    public void clearSelectedCeremonyOptions() {
        selectedCeremonyOptions.clear();
    }

    public boolean hasAdditionalOptions() {
        return !selectedAdditionalOptions.isEmpty() || !selectedCeremonyOptions.isEmpty();
    }

    // === Comments / Special Requests ===

    private final StringProperty commentText = new SimpleStringProperty();

    public StringProperty commentTextProperty() {
        return commentText;
    }

    public String getCommentText() {
        return commentText.get();
    }

    public void setCommentText(String text) {
        commentText.set(text);
    }

    public boolean hasComment() {
        String text = commentText.get();
        return text != null && !text.trim().isEmpty();
    }

    // === Translation or Hard of Hearing Selection ===

    private final BooleanProperty needsTranslation = new SimpleBooleanProperty(false);
    private final StringProperty translationLanguage = new SimpleStringProperty();

    public BooleanProperty needsTranslationProperty() {
        return needsTranslation;
    }

    public boolean needsTranslation() {
        return needsTranslation.get();
    }

    public void setNeedsTranslation(boolean needs) {
        needsTranslation.set(needs);
    }

    public StringProperty translationLanguageProperty() {
        return translationLanguage;
    }

    public String getTranslationLanguage() {
        return translationLanguage.get();
    }

    public void setTranslationLanguage(String language) {
        translationLanguage.set(language);
    }

    public boolean hasTranslationSelection() {
        return needsTranslation() && translationLanguage.get() != null && !translationLanguage.get().isEmpty();
    }

    // === Ordination Ceremony ===

    private final BooleanProperty wantsOrdinationCeremony = new SimpleBooleanProperty(false);

    public BooleanProperty wantsOrdinationCeremonyProperty() {
        return wantsOrdinationCeremony;
    }

    public boolean wantsOrdinationCeremony() {
        return wantsOrdinationCeremony.get();
    }

    public void setWantsOrdinationCeremony(boolean wants) {
        wantsOrdinationCeremony.set(wants);
    }

    // === Assistance Needs ===

    private final BooleanProperty assistanceMobility = new SimpleBooleanProperty(false);
    private final BooleanProperty assistanceHearing = new SimpleBooleanProperty(false);
    private final BooleanProperty assistanceVisual = new SimpleBooleanProperty(false);
    private final StringProperty assistanceDietary = new SimpleStringProperty();

    public BooleanProperty assistanceMobilityProperty() {
        return assistanceMobility;
    }

    public boolean needsMobilityAssistance() {
        return assistanceMobility.get();
    }

    public void setAssistanceMobility(boolean needs) {
        assistanceMobility.set(needs);
    }

    public BooleanProperty assistanceHearingProperty() {
        return assistanceHearing;
    }

    public boolean needsHearingAssistance() {
        return assistanceHearing.get();
    }

    public void setAssistanceHearing(boolean needs) {
        assistanceHearing.set(needs);
    }

    public BooleanProperty assistanceVisualProperty() {
        return assistanceVisual;
    }

    public boolean needsVisualAssistance() {
        return assistanceVisual.get();
    }

    public void setAssistanceVisual(boolean needs) {
        assistanceVisual.set(needs);
    }

    public StringProperty assistanceDietaryProperty() {
        return assistanceDietary;
    }

    public String getAssistanceDietary() {
        return assistanceDietary.get();
    }

    public void setAssistanceDietary(String dietary) {
        assistanceDietary.set(dietary);
    }

    public boolean hasAnyAssistanceNeeds() {
        return needsMobilityAssistance() || needsHearingAssistance() || needsVisualAssistance()
            || (assistanceDietary.get() != null && !assistanceDietary.get().isEmpty());
    }

    // === Child Carer Information ===

    private final StringProperty childCarer1Type = new SimpleStringProperty(); // "household" or "external"
    private final ObjectProperty<Object> childCarer1PersonId = new SimpleObjectProperty<>();
    private final StringProperty childCarer1Name = new SimpleStringProperty();
    private final StringProperty childCarer1BookingRef = new SimpleStringProperty();
    private final StringProperty childCarer2Type = new SimpleStringProperty();
    private final ObjectProperty<Object> childCarer2PersonId = new SimpleObjectProperty<>();
    private final StringProperty childCarer2Name = new SimpleStringProperty();
    private final StringProperty childCarer2BookingRef = new SimpleStringProperty();
    private final BooleanProperty childPolicyAccepted = new SimpleBooleanProperty(false);
    private final ObjectProperty<Object> childCarer1DocumentId = new SimpleObjectProperty<>(); // Document ID if carer has existing booking
    private final ObjectProperty<Object> childCarer2DocumentId = new SimpleObjectProperty<>(); // Document ID if carer has existing booking

    public StringProperty childCarer1TypeProperty() {
        return childCarer1Type;
    }

    public String getChildCarer1Type() {
        return childCarer1Type.get();
    }

    public void setChildCarer1Type(String type) {
        childCarer1Type.set(type);
    }

    public ObjectProperty<Object> childCarer1PersonIdProperty() {
        return childCarer1PersonId;
    }

    public Object getChildCarer1PersonId() {
        return childCarer1PersonId.get();
    }

    public void setChildCarer1PersonId(Object personId) {
        childCarer1PersonId.set(personId);
    }

    public StringProperty childCarer1NameProperty() {
        return childCarer1Name;
    }

    public String getChildCarer1Name() {
        return childCarer1Name.get();
    }

    public void setChildCarer1Name(String name) {
        childCarer1Name.set(name);
    }

    public StringProperty childCarer1BookingRefProperty() {
        return childCarer1BookingRef;
    }

    public String getChildCarer1BookingRef() {
        return childCarer1BookingRef.get();
    }

    public void setChildCarer1BookingRef(String ref) {
        childCarer1BookingRef.set(ref);
    }

    public StringProperty childCarer2TypeProperty() {
        return childCarer2Type;
    }

    public String getChildCarer2Type() {
        return childCarer2Type.get();
    }

    public void setChildCarer2Type(String type) {
        childCarer2Type.set(type);
    }

    public ObjectProperty<Object> childCarer2PersonIdProperty() {
        return childCarer2PersonId;
    }

    public Object getChildCarer2PersonId() {
        return childCarer2PersonId.get();
    }

    public void setChildCarer2PersonId(Object personId) {
        childCarer2PersonId.set(personId);
    }

    public StringProperty childCarer2NameProperty() {
        return childCarer2Name;
    }

    public String getChildCarer2Name() {
        return childCarer2Name.get();
    }

    public void setChildCarer2Name(String name) {
        childCarer2Name.set(name);
    }

    public StringProperty childCarer2BookingRefProperty() {
        return childCarer2BookingRef;
    }

    public String getChildCarer2BookingRef() {
        return childCarer2BookingRef.get();
    }

    public void setChildCarer2BookingRef(String ref) {
        childCarer2BookingRef.set(ref);
    }

    public BooleanProperty childPolicyAcceptedProperty() {
        return childPolicyAccepted;
    }

    public boolean isChildPolicyAccepted() {
        return childPolicyAccepted.get();
    }

    public void setChildPolicyAccepted(boolean accepted) {
        childPolicyAccepted.set(accepted);
    }

    public ObjectProperty<Object> childCarer1DocumentIdProperty() {
        return childCarer1DocumentId;
    }

    public Object getChildCarer1DocumentId() {
        return childCarer1DocumentId.get();
    }

    public void setChildCarer1DocumentId(Object docId) {
        childCarer1DocumentId.set(docId);
    }

    public ObjectProperty<Object> childCarer2DocumentIdProperty() {
        return childCarer2DocumentId;
    }

    public Object getChildCarer2DocumentId() {
        return childCarer2DocumentId.get();
    }

    public void setChildCarer2DocumentId(Object docId) {
        childCarer2DocumentId.set(docId);
    }

    /**
     * Returns true if at least the first carer is defined (either from household or external).
     */
    public boolean hasChildCarer1() {
        return (childCarer1PersonId.get() != null) ||
            (childCarer1Name.get() != null && !childCarer1Name.get().isEmpty());
    }

    /**
     * Returns true if the second carer is defined (either from household or external).
     */
    public boolean hasChildCarer2() {
        return (childCarer2PersonId.get() != null) ||
            (childCarer2Name.get() != null && !childCarer2Name.get().isEmpty());
    }

    /**
     * Resets child carer information.
     */
    public void resetChildCarerInfo() {
        childCarer1Type.set(null);
        childCarer1PersonId.set(null);
        childCarer1Name.set(null);
        childCarer1BookingRef.set(null);
        childCarer1DocumentId.set(null);
        childCarer2Type.set(null);
        childCarer2PersonId.set(null);
        childCarer2Name.set(null);
        childCarer2BookingRef.set(null);
        childCarer2DocumentId.set(null);
        childPolicyAccepted.set(false);
    }

    /**
     * Commits child carer information to the working booking.
     * For household carers: uses Document reference if they have existing booking, otherwise name.
     * For external carers: uses the entered name.
     *
     * @param workingBooking The working booking to update
     * @param store The entity store for retrieving Document entities
     */
    public void commitCarersInfoToBooking(WorkingBooking workingBooking, EntityStore store) {
        String carer1Name = null;
        Document carer1Doc = null;
        String carer2Name = null;
        Document carer2Doc = null;

        // Carer 1
        String type1 = getChildCarer1Type();
        if ("external".equals(type1)) {
            carer1Name = getChildCarer1Name();
        } else if ("household".equals(type1)) {
            Object docId = getChildCarer1DocumentId();
            if (docId != null && store != null) {
                carer1Doc = store.getEntity(Document.class, docId);
            }
            // Use name as fallback (for carers without existing bookings)
            if (carer1Doc == null) {
                carer1Name = getChildCarer1Name();
            }
        }

        // Carer 2
        String type2 = getChildCarer2Type();
        if ("external".equals(type2)) {
            carer2Name = getChildCarer2Name();
        } else if ("household".equals(type2)) {
            Object docId = getChildCarer2DocumentId();
            if (docId != null && store != null) {
                carer2Doc = store.getEntity(Document.class, docId);
            }
            // Use name as fallback (for carers without existing bookings)
            if (carer2Doc == null) {
                carer2Name = getChildCarer2Name();
            }
        }

        workingBooking.setCarersInfo(carer1Name, carer1Doc, carer2Name, carer2Doc);
    }

    // === Shuttle Time Slots ===

    private final StringProperty shuttleOutboundTimeSlot = new SimpleStringProperty();
    private final StringProperty shuttleReturnTimeSlot = new SimpleStringProperty();

    public StringProperty shuttleOutboundTimeSlotProperty() {
        return shuttleOutboundTimeSlot;
    }

    public String getShuttleOutboundTimeSlot() {
        return shuttleOutboundTimeSlot.get();
    }

    public void setShuttleOutboundTimeSlot(String timeSlot) {
        shuttleOutboundTimeSlot.set(timeSlot);
    }

    public StringProperty shuttleReturnTimeSlotProperty() {
        return shuttleReturnTimeSlot;
    }

    public String getShuttleReturnTimeSlot() {
        return shuttleReturnTimeSlot.get();
    }

    public void setShuttleReturnTimeSlot(String timeSlot) {
        shuttleReturnTimeSlot.set(timeSlot);
    }

    // === Reset ===

    /**
     * Resets all selections to their default (empty) state.
     * Called when starting a new booking or when the form is reset.
     */
    public void reset() {
        // Accommodation
        selectedAccommodation.set(null);

        // Dates
        arrivalDate.set(null);
        departureDate.set(null);
        arrivalTime.set(null);
        departureTime.set(null);

        // Roommates
        isRoomBooker.set(false);
        roommateNames.clear();
        shareRoommateName.set(null);

        // Meals
        wantsBreakfast.set(false);
        wantsLunch.set(false);
        wantsDinner.set(false);
        selectedDietaryItem.set(null);

        // Transport
        selectedParkingOptions.clear();
        selectedShuttleOptions.clear();

        // Audio
        selectedAudioPhase.set(null);

        // Additional Options
        selectedAdditionalOptions.clear();
        selectedCeremonyOptions.clear();

        // Comments
        commentText.set(null);

        // Translation
        needsTranslation.set(false);
        translationLanguage.set(null);

        // Ordination Ceremony
        wantsOrdinationCeremony.set(false);

        // Assistance Needs
        assistanceMobility.set(false);
        assistanceHearing.set(false);
        assistanceVisual.set(false);
        assistanceDietary.set(null);

        // Child Carer
        resetChildCarerInfo();

        // Shuttle Time Slots
        shuttleOutboundTimeSlot.set(null);
        shuttleReturnTimeSlot.set(null);
    }

    /**
     * Resets roommate-related selections.
     * Called when accommodation selection changes.
     */
    public void resetRoommateInfo() {
        isRoomBooker.set(false);
        roommateNames.clear();
        shareRoommateName.set(null);
    }

    /**
     * Resets date-related selections.
     * Called when accommodation changes and dates need to be recalculated.
     */
    public void resetDates() {
        arrivalDate.set(null);
        departureDate.set(null);
        arrivalTime.set(null);
        departureTime.set(null);
    }
}
