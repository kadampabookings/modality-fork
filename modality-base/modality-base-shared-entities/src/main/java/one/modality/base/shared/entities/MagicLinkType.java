package one.modality.base.shared.entities;

/**
 * Distinguishes the purpose of a magic link, which governs its expiry and single-use behaviour.
 *
 * <ul>
 *   <li>{@link #LOGIN} — standard password-recovery / sign-in link: short-lived (10 min), single-use.</li>
 *   <li>{@link #BOOKING_ACCESS} — embedded in guest booking confirmation emails: long-lived (1 year),
 *       multi-use so the guest can open it from any device at any time.</li>
 *   <li>{@link #SUPPORT_VIEW} — issued to a support member to open a customer's front office read-only:
 *       very short-lived (2 min to redeem), single-use, never emailed.</li>
 *   <li>{@link #BACKOFFICE_VIEW} — issued to a super admin to open the back office as another
 *       back-office user, read-only: same mechanics as SUPPORT_VIEW (2 min to redeem, single-use,
 *       never emailed), but redeemable only in a back-office context and only mintable by a
 *       super admin for a target account that has back-office access.</li>
 * </ul>
 *
 * The {@code name()} of each constant is stored verbatim in the {@code magic_link.link_type} column,
 * which is {@code varchar(20)} — a new constant's name must fit, which is why it is
 * {@code BACKOFFICE_VIEW} and not the more symmetric {@code SUPPORT_VIEW_BACKOFFICE}.
 *
 * <p><b>The type is a security boundary, not a label.</b> Each redemption path must accept only the
 * type it was written for: a SUPPORT_VIEW grant redeemed through the ordinary magic-link path would
 * produce a full read-write session as the customer, which is the whole thing the support-view design
 * exists to prevent. {@code MagicLinkService} enforces this on both sides.
 *
 * @author Bruno Salmon
 */
public enum MagicLinkType {
    LOGIN,
    BOOKING_ACCESS,
    SUPPORT_VIEW,
    BACKOFFICE_VIEW
}
