package one.modality.crm.server.authsession;

import dev.webfx.stack.com.serial.SerialCodecManager;
import dev.webfx.stack.session.token.IdentityToken;
import dev.webfx.stack.session.token.PrincipalToken;
import dev.webfx.stack.session.token.SessionTier;
import dev.webfx.stack.session.token.SignedToken;
import one.modality.crm.shared.services.authn.ModalityGuestPrincipal;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;
import one.modality.crm.shared.services.authn.serial.ModalityGuestPrincipalSerialCodec;
import one.modality.crm.shared.services.authn.serial.ModalityUserPrincipalSerialCodec;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Modality's own principals, through the identity token that carries them.
 *
 * <p>The token layer checks the mechanism with a principal of its own — it has to, since the stack must
 * build without the application above it. What that cannot check is the half that belongs here: that
 * THESE types, with these codecs, survive being minted and verified. They are what actually travels.
 *
 * <p>Three of them matter for different reasons:
 *
 * <ul>
 *   <li><b>A registered user</b> must come back equal to itself. Two of these once failed: ids minted as
 *       Integer came back as Byte, and the principal did not equals() itself. A principal whose equality
 *       is by object identity passes a round trip and still breaks the authorization cache and the
 *       login-transition check, silently, which is why the assertion is on equality and not on the
 *       fields.</li>
 *   <li><b>A support view</b> must keep its restriction. Losing {@code supportAgentPersonId} in transit
 *       would turn an agent looking at a customer's account into the customer, with the customer's
 *       rights, and nothing downstream would know to refuse.</li>
 *   <li><b>A guest</b> must keep the email it was issued for: it is the whole of a guest's identity, and
 *       their booking is reached through it.</li>
 * </ul>
 *
 * <p>The last case is the blue/green one. A token minted before the session fields existed carries the
 * encoded principal and nothing else, and must still name its user — otherwise a deploy signs out
 * everyone holding one, while both halves still verify the signature perfectly.
 *
 * <p>No test framework, for the reason recorded in webfx-stack-authz-core. Run from main(); it exits
 * non-zero while the issue stands.
 */
public class ModalityPrincipalTokenCheck {

    static int pass = 0, fail = 0;
    static final long NOW = 1_000_000L;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); } else { fail++; System.out.println("  FAIL " + what); }
    }

    /** A token whose session ends at NOW + 60s, minted the way a login mints one. */
    static Object roundTrip(Object principal) {
        String token = PrincipalToken.mint(principal, null, 0, SessionTier.FRONT_OFFICE, NOW + 60_000, NOW);
        IdentityToken identity = PrincipalToken.verify(token, NOW);
        return identity == null ? null : identity.principal();
    }

    public static void main(String[] args) {
        SerialCodecManager.registerSerialCodec(new ModalityUserPrincipalSerialCodec());
        SerialCodecManager.registerSerialCodec(new ModalityGuestPrincipalSerialCodec());
        SignedToken.setKeys(List.of("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));

        System.out.println("a registered user survives the round trip:");
        ModalityUserPrincipal user = new ModalityUserPrincipal(42, 7);
        Object back = roundTrip(user);
        check("comes back as ModalityUserPrincipal", back instanceof ModalityUserPrincipal);
        check("same person and account", user.equals(back));

        System.out.println("a support view keeps its restriction, which is the point:");
        ModalityUserPrincipal support = new ModalityUserPrincipal(42, 7, 99);
        Object supportBack = roundTrip(support);
        check("still a support view after the round trip", ((ModalityUserPrincipal) supportBack).isSupportView());
        check("support agent preserved", support.equals(supportBack));
        check("a support view is NOT equal to the plain user", !supportBack.equals(back));

        System.out.println("a guest — the type whose verifyAuthenticated accepted anything:");
        ModalityGuestPrincipal guest = new ModalityGuestPrincipal("someone@example.com");
        Object guestBack = roundTrip(guest);
        check("comes back as a guest", guestBack instanceof ModalityGuestPrincipal);
        check("email preserved", "someone@example.com".equals(((ModalityGuestPrincipal) guestBack).getEmail()));

        System.out.println("a token minted before the session fields existed still names its user:");
        String legacy = SignedToken.mint(
            "{\"$codec\":\"ModalityUserPrincipal\",\"userPersonId\":42,\"userAccountId\":7}", NOW + 60_000);
        IdentityToken legacyIdentity = PrincipalToken.verify(legacy, NOW);
        check("the principal is the same one", user.equals(legacyIdentity.principal()));
        check("and it is recognised as legacy, so renewal upgrades it", legacyIdentity.isLegacy());

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
