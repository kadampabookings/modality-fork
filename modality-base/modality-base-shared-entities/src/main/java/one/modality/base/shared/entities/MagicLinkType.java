package one.modality.base.shared.entities;

/**
 * Distinguishes the purpose of a magic link, which governs its expiry and single-use behaviour.
 *
 * <ul>
 *   <li>{@link #LOGIN} — standard password-recovery / sign-in link: short-lived (10 min), single-use.</li>
 *   <li>{@link #BOOKING_ACCESS} — embedded in guest booking confirmation emails: long-lived (1 year),
 *       multi-use so the guest can open it from any device at any time.</li>
 * </ul>
 *
 * The {@code name()} of each constant is stored verbatim in the {@code magic_link.link_type} column.
 *
 * @author Bruno Salmon
 */
public enum MagicLinkType {
    LOGIN,
    BOOKING_ACCESS
}
