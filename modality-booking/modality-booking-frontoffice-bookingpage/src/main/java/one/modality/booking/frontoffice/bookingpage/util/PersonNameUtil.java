package one.modality.booking.frontoffice.bookingpage.util;

import one.modality.base.shared.entities.Document;
import one.modality.base.shared.entities.Person;
import one.modality.base.shared.entities.markers.HasPersonalDetails;

/**
 * Utility class for extracting person names and emails from entities.
 * Provides null-safe methods that handle the common pattern of getting personal
 * details from Document (with fallback to Person entity).
 *
 * <p>Note: This exists because {@link HasPersonalDetails#getFullName()} doesn't
 * handle null first/last names gracefully (concatenates "null" strings).</p>
 *
 * @author Bruno Salmon
 */
public final class PersonNameUtil {

    private PersonNameUtil() {
        // Utility class - prevent instantiation
    }

    /**
     * Gets full name from any entity implementing HasPersonalDetails, with null-safe handling.
     * Returns empty string for null entity or if both firstName and lastName are null.
     *
     * @param entity The entity to get the name from (Person, Document, etc.)
     * @return Full name as "firstName lastName", or empty string if not available
     */
    public static String getFullName(HasPersonalDetails entity) {
        if (entity == null) return "";
        String firstName = entity.getFirstName();
        String lastName = entity.getLastName();
        if (firstName == null && lastName == null) return "";
        return ((firstName != null ? firstName : "") + " " +
                (lastName != null ? lastName : "")).trim();
    }

    /**
     * Gets person name from a Document entity with fallback to its Person entity.
     * Document has personal details copied directly via EntityHasPersonalDetailsCopy interface.
     *
     * @param doc The document to get the person name from
     * @return Person name, or empty string if not available
     */
    public static String getDocumentPersonName(Document doc) {
        if (doc == null) return "";

        // Try to get name from Document directly (personal details are copied to Document)
        String firstName = doc.getFirstName();
        String lastName = doc.getLastName();

        if (firstName != null || lastName != null) {
            return ((firstName != null ? firstName : "") + " " +
                    (lastName != null ? lastName : "")).trim();
        }

        // Fall back to Person entity
        return getFullName(doc.getPerson());
    }

    /**
     * Gets person email from a Document entity with fallback to its Person entity.
     * Document has personal details copied directly via EntityHasPersonalDetailsCopy interface.
     *
     * @param doc The document to get the email from
     * @return Email address, or empty string if not available
     */
    public static String getDocumentPersonEmail(Document doc) {
        if (doc == null) return "";

        // Try to get email from Document directly (personal details are copied to Document)
        String email = doc.getEmail();
        if (email != null) {
            return email;
        }

        // Fall back to Person entity
        Person person = doc.getPerson();
        return person != null && person.getEmail() != null ? person.getEmail() : "";
    }
}
