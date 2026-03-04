package one.modality.booking.frontoffice.bookingpage.sections.member;

import javafx.beans.value.ObservableBooleanValue;
import one.modality.base.shared.entities.Person;
import one.modality.booking.frontoffice.bookingpage.BookingFormSection;
import one.modality.booking.frontoffice.bookingpage.sections.childcarer.DefaultChildCarerSection;
import one.modality.booking.frontoffice.bookingpage.standard.BookingSelectionState;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Interface for the "Member Selection" section of a booking form.
 * This section displays account members and allows selection of who to book for.
 *
 * @author Bruno Salmon
 * @see BookingFormSection
 */
public interface HasMemberSelectionSection extends BookingFormSection {

    /**
     * Status of a member for booking purposes.
     */
    enum MemberStatus {
        /** Member is fully validated and can be booked for */
        ACTIVE,
        /** Member invitation is pending (waiting for response) */
        PENDING_INVITATION,
        /** Member needs validation (they created their own account) */
        NEEDS_VALIDATION,
        /** Member is the account owner (always bookable) */
        OWNER
    }

    /**
     * Data class representing an account member.
     */
    class MemberInfo {
        private final Object personId;
        private final String name;
        private final String email;
        private final Person personEntity;
        private final MemberStatus status;

        public MemberInfo(Object personId, String name, String email, Person personEntity, MemberStatus status) {
            this.personId = personId;
            this.name = name;
            this.email = email;
            this.personEntity = personEntity;
            this.status = status;

            // Split name into first/last
        }

        public Object getPersonId() { return personId; }
        public String getName() { return name; }
        public String getEmail() { return email; }

        public Person getPersonEntity() { return personEntity; }
        public MemberStatus getStatus() { return status; }

        public boolean isBookable() {
            return status == MemberStatus.ACTIVE || status == MemberStatus.OWNER;
        }

    }

    /**
     * Sets the callback for when a member is selected.
     */
    void setOnMemberSelected(Consumer<MemberInfo> callback);

    /**
     * Sets the callback for when the continue button is pressed.
     */
    void setOnContinuePressed(Runnable callback);

    /**
     * Sets the callback for when the back button is pressed.
     */
    void setOnBackPressed(Runnable callback);

    /**
     * Adds a single member to the list.
     */
    void addMember(MemberInfo member);

    /**
     * Clears all members from the list.
     */
    void clearMembers();

    /**
     * Clears the current selection without removing members.
     */
    void clearSelection();

    /**
     * Sets the set of person IDs already booked for this event.
     */
    void setAlreadyBookedPersonIds(Set<Object> personIds);

    /**
     * Clears all already-booked markers.
     */
    void clearAlreadyBooked();

    /**
     * Returns an observable property indicating if there are members available to book.
     * Returns true if accountMembers contains at least one member not in alreadyBookedPersonIds.
     */
    ObservableBooleanValue hasAvailableMembersProperty();

    // === Inline Child Carer Support ===

    /**
     * Enables inline child carer selection for this member selection section.
     * When enabled, selecting a child (under 18) will show the child carer
     * selection form directly below the member cards.
     *
     * <p>This is optional - forms that don't need child carer selection
     * simply don't call this method (defaults to disabled).</p>
     *
     * @param enabled true to enable inline child carer, false to disable
     */
    default void setInlineChildCarerEnabled(boolean enabled) {
        // Default: no-op - implementations that support this feature override
    }

    /**
     * Returns whether inline child carer selection is enabled.
     */
    default boolean isInlineChildCarerEnabled() {
        return false;
    }

    /**
     * Binds this section to the centralized BookingSelectionState.
     * Also binds the inline child carer section if enabled.
     *
     * @param selectionState the centralized state to bind to
     */
    default void bindToSelectionState(BookingSelectionState selectionState) {
        // Default: no-op - implementations that support this feature override
    }

    /**
     * Returns the inline child carer section if enabled, null otherwise.
     */
    default DefaultChildCarerSection getChildCarerSection() {
        return null;
    }

}
