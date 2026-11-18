package one.modality.ecommerce.document.service.spi.impl.server;

/**
 * What a new booking may say about its person's age (GDPR minimisation: date of birth is no longer
 * collected, and {@code document.person_age} is kept for an under-18 only).
 *
 * <p>The age is self-declared, so the server cannot check it is TRUE. What it can do is bound it, and
 * refuse to keep it where it is not needed:
 *
 * <ul>
 *   <li>Outside 0–120 is refused. A negative age would match every rate's {@code age <= ageMax} band,
 *       which is the cheapest child rate.</li>
 *   <li>18 or over is accepted and FORGOTTEN: the booking stores null. Age bands exist for children, so
 *       an adult's age is data with no purpose. It is not refused, because a stale front-office bundle
 *       still sends it.</li>
 * </ul>
 *
 * <p>Pure on purpose, so {@code BookingAgeRuleCheck} pins it without a running server;
 * {@link ServerDocumentServiceProvider} applies it.
 */
final class BookingAgeRule {

    /** Prefix on the refusal, so clients and logs can recognise it. */
    static final String ERROR_PREFIX = "[BookingAgeError] ";

    static final int MIN_AGE = 0;
    static final int MAX_AGE = 120;
    /** From this age on, the booking keeps no age at all. */
    static final int ADULT_AGE = 18;

    private BookingAgeRule() { }

    /** The refusal for this declared age, or null when it may be submitted. Null (not declared) passes. */
    static String check(Integer age) {
        if (age != null && (age < MIN_AGE || age > MAX_AGE))
            return ERROR_PREFIX + "The age must be between " + MIN_AGE + " and " + MAX_AGE;
        return null;
    }

    /** Whether an accepted age must be dropped before the booking is written. */
    static boolean mustForget(Integer age) {
        return age != null && age >= ADULT_AGE;
    }
}
