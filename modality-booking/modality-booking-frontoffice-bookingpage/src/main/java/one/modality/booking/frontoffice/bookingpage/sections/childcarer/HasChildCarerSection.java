package one.modality.booking.frontoffice.bookingpage.sections.childcarer;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ObservableList;
import one.modality.booking.frontoffice.bookingpage.BookingFormSection;
import one.modality.booking.frontoffice.bookingpage.ResettableSection;
import one.modality.booking.frontoffice.bookingpage.standard.BookingSelectionState;

/**
 * Interface for the "Child Carer Selection" section of a booking form.
 * This section allows users to designate carers for children attending
 * international festivals.
 *
 * <p>Carer requirements by age:</p>
 * <ul>
 *   <li>Children under 12: Require 2 designated carers</li>
 *   <li>Children 12+: Require 1 designated carer</li>
 * </ul>
 *
 * <p>For each carer slot, users can:</p>
 * <ul>
 *   <li>Select from account members (self or other adults on the account)</li>
 *   <li>Enter "Someone else" with name and optional booking reference</li>
 * </ul>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Dynamic carer slots based on child's age</li>
 *   <li>Account member selection</li>
 *   <li>External carer entry with booking reference</li>
 *   <li>Child safety policy acceptance</li>
 *   <li>Validation requiring all carers + policy acceptance</li>
 * </ul>
 *
 * <p>CSS classes used:</p>
 * <ul>
 *   <li>{@code .bookingpage-childcarer-section} - section container</li>
 *   <li>{@code .bookingpage-childcarer-card} - carer selection card</li>
 *   <li>{@code .bookingpage-carer-slot} - individual carer slot</li>
 * </ul>
 *
 * @author Bruno Salmon
 * @see BookingFormSection
 */
public interface HasChildCarerSection extends BookingFormSection, ResettableSection {

    /**
     * Data class representing an account member who can be selected as a carer.
     */
    class AccountMember {
        private final Object personId;
        private final String name;
        private final boolean isSelf;
        private final boolean isAdult;
        private final Object documentId; // Document ID if this person has an existing booking for the event

        public AccountMember(Object personId, String name, boolean isSelf, boolean isAdult) {
            this(personId, name, isSelf, isAdult, null);
        }

        public AccountMember(Object personId, String name, boolean isSelf, boolean isAdult, Object documentId) {
            this.personId = personId;
            this.name = name;
            this.isSelf = isSelf;
            this.isAdult = isAdult;
            this.documentId = documentId;
        }

        public Object getPersonId() { return personId; }
        public String getName() { return name; }
        public boolean isSelf() { return isSelf; }
        public boolean isAdult() { return isAdult; }
        public Object getDocumentId() { return documentId; }

        @Override
        public String toString() {
            return name + (isSelf ? " (You)" : "");
        }
    }

    // === Visibility ===

    /**
     * Property controlling whether this section is visible.
     * Should only be visible when booking for a child.
     */
    BooleanProperty visibleProperty();

    /**
     * Sets whether this section is visible.
     */
    void setVisible(boolean visible);

    /**
     * Returns whether this section is currently visible.
     */
    boolean isVisible();

    // === Child Information ===

    /**
     * Sets the age of the child being booked.
     * This determines how many carers are required (under 12 = 2, 12+ = 1).
     */
    void setChildAge(int age);

    /**
     * Returns the child's age.
     */
    int getChildAge();

    /**
     * Returns the number of required carers based on child's age.
     */
    default int getRequiredCarerCount() {
        return getChildAge() < 12 ? 2 : 1;
    }

    /**
     * Sets the name of the child being booked (for display purposes).
     */
    void setChildName(String name);

    /**
     * Returns the child's name.
     */
    String getChildName();

    // === Account Members ===

    /**
     * Returns the list of account members available for selection as carers.
     */
    ObservableList<AccountMember> getAccountMembers();

    /**
     * Sets the available account members.
     */
    void setAccountMembers(java.util.List<AccountMember> members);

    /**
     * Clears the account members list.
     */
    void clearAccountMembers();

    // === Carer 1 Selection ===

    /**
     * Property for carer 1 type: "household" or "external".
     */
    StringProperty carer1TypeProperty();

    /**
     * Property for carer 1 person ID (when account member selected).
     */
    ObjectProperty<Object> carer1PersonIdProperty();

    /**
     * Property for carer 1 name (when external carer entered).
     */
    StringProperty carer1NameProperty();

    /**
     * Property for carer 1 booking reference (optional, for external carer).
     */
    StringProperty carer1BookingRefProperty();

    /**
     * Returns true if carer 1 is defined (either household or external).
     */
    default boolean hasCarer1() {
        String type = carer1TypeProperty().get();
        if ("household".equals(type)) {
            return carer1PersonIdProperty().get() != null;
        } else if ("external".equals(type)) {
            String name = carer1NameProperty().get();
            return name != null && !name.trim().isEmpty();
        }
        return false;
    }

    // === Carer 2 Selection ===

    /**
     * Property for carer 2 type: "household" or "external".
     */
    StringProperty carer2TypeProperty();

    /**
     * Property for carer 2 person ID (when account member selected).
     */
    ObjectProperty<Object> carer2PersonIdProperty();

    /**
     * Property for carer 2 name (when external carer entered).
     */
    StringProperty carer2NameProperty();

    /**
     * Property for carer 2 booking reference (optional, for external carer).
     */
    StringProperty carer2BookingRefProperty();

    /**
     * Returns true if carer 2 is defined (either household or external).
     */
    default boolean hasCarer2() {
        String type = carer2TypeProperty().get();
        if ("household".equals(type)) {
            return carer2PersonIdProperty().get() != null;
        } else if ("external".equals(type)) {
            String name = carer2NameProperty().get();
            return name != null && !name.trim().isEmpty();
        }
        return false;
    }

    // === Policy Acceptance ===

    /**
     * Property for child safety policy acceptance.
     */
    BooleanProperty policyAcceptedProperty();

    /**
     * Returns true if the child safety policy has been accepted.
     */
    default boolean isPolicyAccepted() {
        return policyAcceptedProperty().get();
    }

    /**
     * Sets whether the child safety policy has been accepted.
     */
    default void setPolicyAccepted(boolean accepted) {
        policyAcceptedProperty().set(accepted);
    }

    // === Validation ===

    /**
     * Returns a validation message if the section is invalid, or null if valid.
     * Section is invalid if:
     * - Not all required carers are selected
     * - Child safety policy not accepted
     */
    String getValidationMessage();

    // === Reset ===

    /**
     * Resets the section to its initial state.
     */
    @Override
    void reset();

    // === Callbacks ===

    /**
     * Sets a callback to be invoked when any selection changes.
     * @param callback the callback to invoke on selection change
     */
    void setOnSelectionChanged(Runnable callback);

    // === Selection State Binding ===

    /**
     * Binds this section to the centralized BookingSelectionState.
     * Changes in the section will be pushed to the state.
     *
     * @param selectionState the centralized state to bind to
     */
    default void bindToSelectionState(BookingSelectionState selectionState) {
        if (selectionState == null) return;

        // Carer 1
        carer1TypeProperty().addListener((obs, oldVal, newVal) -> {
            selectionState.setChildCarer1Type(newVal);
        });
        carer1PersonIdProperty().addListener((obs, oldVal, newVal) -> {
            selectionState.setChildCarer1PersonId(newVal);
        });
        carer1NameProperty().addListener((obs, oldVal, newVal) -> {
            selectionState.setChildCarer1Name(newVal);
        });
        carer1BookingRefProperty().addListener((obs, oldVal, newVal) -> {
            selectionState.setChildCarer1BookingRef(newVal);
        });

        // Carer 2
        carer2TypeProperty().addListener((obs, oldVal, newVal) -> {
            selectionState.setChildCarer2Type(newVal);
        });
        carer2PersonIdProperty().addListener((obs, oldVal, newVal) -> {
            selectionState.setChildCarer2PersonId(newVal);
        });
        carer2NameProperty().addListener((obs, oldVal, newVal) -> {
            selectionState.setChildCarer2Name(newVal);
        });
        carer2BookingRefProperty().addListener((obs, oldVal, newVal) -> {
            selectionState.setChildCarer2BookingRef(newVal);
        });

        // Policy
        policyAcceptedProperty().addListener((obs, oldVal, newVal) -> {
            selectionState.setChildPolicyAccepted(newVal);
        });

        // Initialize state from current values
        selectionState.setChildCarer1Type(carer1TypeProperty().get());
        selectionState.setChildCarer1PersonId(carer1PersonIdProperty().get());
        selectionState.setChildCarer1Name(carer1NameProperty().get());
        selectionState.setChildCarer1BookingRef(carer1BookingRefProperty().get());
        selectionState.setChildCarer2Type(carer2TypeProperty().get());
        selectionState.setChildCarer2PersonId(carer2PersonIdProperty().get());
        selectionState.setChildCarer2Name(carer2NameProperty().get());
        selectionState.setChildCarer2BookingRef(carer2BookingRefProperty().get());
        selectionState.setChildPolicyAccepted(isPolicyAccepted());
    }
}
