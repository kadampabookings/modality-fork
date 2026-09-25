package one.modality.crm.server.authn.gateway.shared;

import java.util.List;

/**
 * Check for the guest access behind a booking cart's /cart/:uuid page.
 *
 * <p>What is pinned is who decides. The cart's link is what lets somebody without an account open their booking from
 * the letter, so each input to it is a way in: an address chosen by the caller hands someone else's booking to
 * whoever asks, a host chosen by the caller has a genuine KBS mail send the booking's key to that host, and a cart
 * shared by two people opens one person's booking to the other. Every one of these was either live or one edit away,
 * and each looks harmless on its own — "the client already knows its origin", "the booker typed the address".
 */
public class GuestBookingAccessCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    private static final String SHARED = "modality-fork/modality-crm/modality-crm-server-authn-gateway-shared/"
                                         + "src/main/java/one/modality/crm/server/authn/gateway/shared/";
    private static final String GUEST = "modality-fork/modality-crm/modality-crm-server-authn-gateway-guest-plugin/"
                                        + "src/main/java/one/modality/crm/server/authn/gateway/guest/";
    private static final String DOCUMENT_SERVER = "modality-fork/modality-ecommerce/modality-ecommerce-document-service-server/"
                                                  + "src/main/java/one/modality/ecommerce/document/service/spi/impl/server/";

    static String readSource(String relative) {
        java.nio.file.Path here = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (java.nio.file.Path at = here; at != null; at = at.getParent()) {
            java.nio.file.Path candidate = at.resolve(relative);
            if (java.nio.file.Files.exists(candidate)) {
                try {
                    return java.nio.file.Files.readString(candidate);
                } catch (Exception e) {
                    return "";
                }
            }
        }
        return "";
    }

    /** The text between two markers, or empty when either is missing — how a method body is scoped. */
    static String between(String source, String from, String to) {
        int start = source.indexOf(from);
        int end = start < 0 ? -1 : source.indexOf(to, start);
        return start < 0 || end < 0 ? "" : source.substring(start, end);
    }

    private static GuestBookingAccess.CartBooking guest(String email) {
        return new GuestBookingAccess.CartBooking(email, false, false);
    }

    public static void main(String[] args) {
        System.out.println("GuestBookingAccessCheck");

        // --- which carts get a guest link, and for whom ---
        check("a cart with one account-less booking gets a link for its address",
            "ann@example.org".equals(GuestBookingAccess.guestEmailOf(List.of(guest(" ann@example.org ")))));
        check("several bookings for the same address, however it is cased, share the link",
            "Ann@Example.org".equals(GuestBookingAccess.guestEmailOf(List.of(guest("Ann@Example.org"), guest("ann@example.ORG")))));
        // THE one that matters most: a cart carries one guest identity, so a second address means none at all.
        check("two people's bookings in one cart: no link, or each letter would open the other's booking",
            GuestBookingAccess.guestEmailOf(List.of(guest("ann@example.org"), guest("bob@example.org"))) == null);
        check("a booking whose person has an account: no link, they sign in",
            GuestBookingAccess.guestEmailOf(List.of(guest("ann@example.org"),
                new GuestBookingAccess.CartBooking("ann@example.org", true, false))) == null);
        check("a booking billed to a payer: no link, the payer's cart holds other people's bookings",
            GuestBookingAccess.guestEmailOf(List.of(new GuestBookingAccess.CartBooking("ann@example.org", false, true))) == null);
        check("a booking with no address: no link",
            GuestBookingAccess.guestEmailOf(List.of(guest("ann@example.org"), guest("  "))) == null
            && GuestBookingAccess.guestEmailOf(List.of(guest(null))) == null);
        check("an empty cart: no link",
            GuestBookingAccess.guestEmailOf(List.of()) == null && GuestBookingAccess.guestEmailOf(null) == null);
        String longest = "a".repeat(GuestBookingAccess.MAX_EMAIL_LENGTH - "@example.org".length()) + "@example.org";
        check("an address that fits magic_link.email gets a link",
            longest.equals(GuestBookingAccess.guestEmailOf(List.of(guest(longest)))));
        check("one that does not fit gets none, rather than a failed insert",
            GuestBookingAccess.guestEmailOf(List.of(guest("a" + longest))) == null);

        // --- the recovery mail's buttons ---
        String buttons = GuestBookingAccess.cartButtonsHtml("https://kadampabookings.org", List.of("3f2c", "9a1b"));
        check("each button is a whole url on the given origin",
            buttons.contains("href=\"https://kadampabookings.org/cart/3f2c\"") && buttons.contains("href=\"https://kadampabookings.org/cart/9a1b\""));
        String hostile = GuestBookingAccess.cartButtonsHtml("https://x.org", List.of("u\" onclick=\"steal()\"><script>x</script>"));
        check("a quote cannot close the attribute and add markup to a genuine KBS mail",
            !hostile.contains("\" onclick=") && !hostile.contains("<script>") && hostile.contains("&quot;") && hostile.contains("&lt;script&gt;"));

        // --- one recovery mail per address per window ---
        GuestBookingAccess.forgetRecoveryMails();
        long now = 1_000_000_000L;
        check("the first request for an address is mailed", GuestBookingAccess.mayMailRecoveryTo("ann@example.org", now));
        check("a second one inside the window is not", !GuestBookingAccess.mayMailRecoveryTo("ann@example.org", now + 60_000));
        check("nor is the same address cased or padded differently", !GuestBookingAccess.mayMailRecoveryTo(" ANN@example.org ", now + 60_000));
        check("another address has its own window", GuestBookingAccess.mayMailRecoveryTo("bob@example.org", now + 60_000));
        check("once the window has passed, the address is mailed again", GuestBookingAccess.mayMailRecoveryTo("ann@example.org", now + 5 * 60_000));
        check("a blank address is never mailed", !GuestBookingAccess.mayMailRecoveryTo(" ", now) && !GuestBookingAccess.mayMailRecoveryTo(null, now));
        GuestBookingAccess.forgetRecoveryMails();

        // --- no caller input reaches a link: asserted on the method bodies, never on whole files ---
        String gateway = readSource(GUEST + "ModalityGuestAuthenticationGateway.java");
        String recovery = between(gateway, "private Future<Void> sendBookingAccessEmail(", "private Future<Void> mailBookingAccessLinks(");
        check("the recovery mail's method was found", !recovery.isEmpty());
        check("the recovery mail does not read the caller's origin", !recovery.contains("clientOrigin()"));
        check("it is throttled per address before anything else", recovery.indexOf("mayMailRecoveryTo(") >= 0
            && recovery.indexOf("mayMailRecoveryTo(") < recovery.indexOf("isPasswordClosedForEmail("));
        String cartSignIn = between(gateway, "private Future<String> authenticateWithCart(", "public boolean acceptsUserId(");
        check("the cart page's sign-in was found", !cartSignIn.isEmpty());
        check("every click is judged against the cart's bookings now, not just the link it happens to carry",
            cartSignIn.contains("GuestBookingAccess.linkToRedeem(") && !cartSignIn.contains("getMagicLink()"));

        String documentServer = readSource(DOCUMENT_SERVER + "ServerDocumentServiceProvider.java");
        String submit = between(documentServer, "private static Future<SubmitDocumentChangesResult> submitDocumentChangesAfterChecks(",
            "private static Future<SubmitDocumentChangesResult> submitChangesAndPrepareResult(");
        check("the submit method was found", !submit.isEmpty());
        check("the submit hands the link service nothing from the request but the cart",
            !submit.contains("clientOrigin()") && submit.contains("syncCartAccessLink(result.cartPrimaryKey())"));

        String magicLinks = readSource(SHARED + "MagicLinkService.java");
        String personLookup = between(magicLinks, "public static Future<Person> loadUserPersonFromMagicLink(", "// ===");
        check("the person lookup was found", !personLookup.isEmpty());
        check("a booking-access link never resolves to an account, whatever its address",
            personLookup.contains("if (magicLink.isBookingAccess())")
            && personLookup.indexOf("if (magicLink.isBookingAccess())") < personLookup.indexOf("executeQuery("));
        String mint = between(magicLinks, "private static Future<MagicLink> createBookingAccessLink(String token", "public static boolean isBookingAccessLinkStillValid(");
        check("the booking-access mint was found", !mint.isEmpty());
        check("and it mints no six-digit code", !mint.contains("setVerificationCode("));

        System.out.println("  " + pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}
