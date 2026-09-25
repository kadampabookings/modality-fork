package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.conf.Config;
import dev.webfx.platform.conf.ConfigLoader;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.substitution.Substitutor;
import dev.webfx.platform.util.collection.Collections;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.entity.EntityList;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.EntityStoreQuery;
import dev.webfx.stack.orm.entity.UpdateStore;
import one.modality.base.shared.entities.Cart;
import one.modality.base.shared.entities.Document;
import one.modality.base.shared.entities.MagicLink;
import one.modality.base.shared.entities.Person;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which booking carts somebody WITHOUT an account may open from their confirmation letter, and the BOOKING_ACCESS
 * link each cart carries for it.
 *
 * <p>The letter's button is {@code [cartUrl]}, i.e. {@code /cart/<uuid>}. The cart page opens a guest session only
 * through the cart's link ({@code cart.magic_link_id}), so a cart without one sends its visitor to a sign-in they
 * cannot do. Links used to be minted only when the person SUBMITTING had no account — a front-office guest
 * checkout — so every booking staff entered in the back office for somebody without an account had a dead button.
 *
 * <p>Whether a cart gets a link is decided here, from the database, and nothing about it comes from the request:
 * not the address, not the host, not the token. A cart qualifies when every booking in it is for the same address,
 * none belongs to a person with a front-office account (they sign in), and none is billed to a payer. The last two
 * rules follow from a cart carrying ONE guest identity: were two people's bookings to share a cart — a payer's cart
 * holds all the registrants it pays for — each one's letter would open the cart as whoever the link was minted for,
 * showing them somebody else's booking and letting them cancel it.
 *
 * <p>Links are brought in line with that rule at three moments: after a booking in the cart is submitted
 * ({@link #syncCartLink}), at every click on the cart's page ({@link #linkToRedeem}, which also gives a cart that
 * never had a link its first — what makes letters already sent for back-office bookings work), and when the guest
 * asks for their links again ({@link #cartUuidsToMail}).
 */
public final class GuestBookingAccess {

    private GuestBookingAccess() {
    }

    /** The magic-link route, as ModalityMagicLinkAuthenticationGateway serves it. */
    public static final String MAGIC_LINK_ACTIVITY_PATH_FULL = "/magic-link/:token";

    /** magic_link.email is varchar(64): a longer address could not be stored, so its cart gets no link. */
    static final int MAX_EMAIL_LENGTH = 64;

    /**
     * The front office's origin as THIS SERVER is configured with it, with no trailing slash — or null when it is not
     * configured.
     *
     * <p>Never a caller's. The recovery mail used to build its buttons from the origin the browser sent, on an
     * endpoint anyone can call without signing in: name somebody's address and your own host, and KBS mailed them a
     * genuine message whose button handed that host their cart id — the key to their booking. Mail scanners that
     * open links in advance could hand it over without a click.
     *
     * <p>Read from {@code modality.crm.server.person.frontofficeBaseUrl}, the member emails' key (MemberMail), which
     * every deploy already sets to FRONTOFFICE_ORIGIN. A second key for the same value would need a new declare@ file
     * merged into the server's src-root.json by {@code webfx update} — the step whose omission once shipped a gateway
     * inert. Read through ConfigLoader and on demand, for the reasons MemberMail.config() gives.
     */
    public static String configuredFrontOfficeOrigin() {
        try {
            Config config = ConfigLoader.getRootConfig().childConfigAt("modality.crm.server.person");
            String value = config == null ? null : config.getString("frontofficeBaseUrl");
            if (value == null || !Substitutor.areValuesNonNullAndResolved(value) || value.isBlank())
                return null;
            String trimmed = value.trim();
            return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The origin for a recovery mail's buttons, or null — said in the log — when none is configured: no mail then. */
    public static String recoveryMailOrigin() {
        String origin = configuredFrontOfficeOrigin();
        if (origin == null)
            Console.log("⚠️ Booking recovery mail not sent: modality.crm.server.person.frontofficeBaseUrl is unset or"
                        + " unresolved. The AWS deploys pass FRONTOFFICE_ORIGIN here.");
        return origin;
    }

    // ── The rule ───────────────────────────────────────────────────────────────────────────────────────────────

    /** One booking of a cart, reduced to what the rule reads. */
    public record CartBooking(String email, boolean personHasAccount, boolean hasPayer) {
    }

    /**
     * The address a cart's guest link is for, or null when the cart must not carry one. Cancelled bookings count:
     * the page still has to show a cancelled booking to the right person, and never to anyone else.
     */
    public static String guestEmailOf(List<CartBooking> bookings) {
        if (bookings == null || bookings.isEmpty())
            return null;
        String email = null;
        for (CartBooking booking : bookings) {
            if (booking.personHasAccount() || booking.hasPayer())
                return null;
            String bookingEmail = booking.email() == null ? null : booking.email().trim();
            if (bookingEmail == null || bookingEmail.isEmpty())
                return null;
            if (email == null)
                email = bookingEmail;
            else if (!email.equalsIgnoreCase(bookingEmail))
                return null;
        }
        return email.length() <= MAX_EMAIL_LENGTH ? email : null;
    }

    // ── Keeping a cart's link in line with its bookings ─────────────────────────────────────────────────────────

    /** A cart's current link (null when it has none) and the address its bookings call for (null for none). */
    private record CartState(MagicLink link, String guestEmail, String cartUuid, String lang) {
    }

    private static Future<CartState> loadCartState(Object cartPk, DataSourceModel dataSourceModel) {
        return EntityStore.create(dataSourceModel).executeQueryBatch(
                new EntityStoreQuery("select uuid, magicLink.(token,email,creationDate) from Cart where id=$1", cartPk),
                new EntityStoreQuery("select person_email,person_lang,person.frontendAccount,payer from Document where cart=$1 order by id", cartPk))
            .map(lists -> {
                Cart cart = (Cart) Collections.first(lists[0]);
                @SuppressWarnings("unchecked")
                EntityList<Document> documents = (EntityList<Document>) (EntityList<?>) lists[1];
                List<CartBooking> bookings = new ArrayList<>();
                for (Document document : documents) {
                    Person person = document.getPerson();
                    bookings.add(new CartBooking(document.getEmail(),
                        person != null && person.getFrontendAccountId() != null,
                        document.getPayerId() != null));
                }
                Document first = Collections.first(documents);
                return new CartState(cart == null ? null : cart.getMagicLink(), guestEmailOf(bookings),
                    cart == null ? null : cart.getUuid(), first == null ? null : first.getPersonLang());
            });
    }

    private static boolean isLiveLinkFor(MagicLink link, String email) {
        return link != null && email != null && email.equalsIgnoreCase(link.getEmail())
               && MagicLinkService.isBookingAccessLinkStillValid(link.getCreationDate());
    }

    // A submit keeps a link this recent rather than minting another. Minting on every submit, as the front office
    // always did, added a row per amendment; never re-minting would let a link expire a year after the FIRST booking
    // however recently the booking was worked on. A month bounds both.
    private static final Duration LINK_REUSE_WINDOW = Duration.ofDays(30);

    private static boolean isRecent(MagicLink link) {
        return link.getCreationDate() != null && link.getCreationDate().plus(LINK_REUSE_WINDOW).isAfter(Instant.now());
    }

    /**
     * After a booking in the cart was submitted: the link its bookings call for — the recent one it already has, else a
     * new one — or none. A cart that no longer qualifies loses its link: it now holds a second person's booking, or
     * belongs to someone who has an account and signs in.
     */
    public static Future<Void> syncCartLink(Object cartPk, DataSourceModel dataSourceModel) {
        if (cartPk == null)
            return Future.succeededFuture();
        return loadCartState(cartPk, dataSourceModel).compose(state -> {
            if (state.guestEmail() == null) {
                if (state.link() == null)
                    return Future.succeededFuture();
                Console.log("🔒 Guest link withdrawn from cart " + cartPk + ": its bookings no longer call for one");
                return setCartLink(cartPk, null, dataSourceModel);
            }
            if (isLiveLinkFor(state.link(), state.guestEmail()) && isRecent(state.link()))
                return Future.succeededFuture();
            return mintAndAttach(cartPk, state, dataSourceModel).mapEmpty();
        });
    }

    /**
     * The link a click on the cart's page may redeem, judged against the cart's bookings as they are NOW: its own link
     * when that is for the address they carry; a new one when it has none — every back-office booking made before
     * these links existed, which is what makes the letters already sent work — or one for an address the bookings no
     * longer carry (corrected since); and null when they call for none: a second person's booking in the cart, an
     * account holder's (they sign in), a payer's cart. Judging at every click is what keeps a link minted before the
     * cart changed from opening it as the wrong person.
     *
     * <p>An EXPIRED link for the right address is returned as it is, for the caller's validation to refuse. Re-minting
     * on a click would make the cart id alone a key for good; an expired link comes back only through the recovery
     * mail, which goes to the booking's own mailbox.
     */
    public static Future<MagicLink> linkToRedeem(Object cartPk, DataSourceModel dataSourceModel) {
        return loadCartState(cartPk, dataSourceModel).compose(state -> {
            String email = state.guestEmail();
            if (email == null)
                return Future.succeededFuture(null);
            if (state.link() != null && email.equalsIgnoreCase(state.link().getEmail()))
                return Future.succeededFuture(state.link());
            return mintAndAttach(cartPk, state, dataSourceModel);
        });
    }

    private static Future<MagicLink> mintAndAttach(Object cartPk, CartState state, DataSourceModel dataSourceModel) {
        // requestedPath is where a redemption at /magic-link lands. The cart page, not /order/<id>: a booking-access
        // link opens a guest session only, and /order/ is an account page a guest would dead-end on.
        return MagicLinkService.createBookingAccessLink(state.guestEmail(), "/cart/" + state.cartUuid(),
                configuredFrontOfficeOrigin(), MAGIC_LINK_ACTIVITY_PATH_FULL, state.lang(), dataSourceModel)
            .compose(link -> setCartLink(cartPk, link.getPrimaryKey(), dataSourceModel).map(ignored -> link));
    }

    private static Future<Void> setCartLink(Object cartPk, Object magicLinkPk, DataSourceModel dataSourceModel) {
        UpdateStore updateStore = UpdateStore.create(dataSourceModel);
        Cart cart = updateStore.updateEntity(Cart.class, cartPk);
        cart.setMagicLink(magicLinkPk);
        return updateStore.submitChanges().mapEmpty();
    }

    // ── The recovery mail ("email me my booking link") ──────────────────────────────────────────────────────────

    /**
     * The carts to put in a recovery mail to {@code email}: those whose link is for that address and whose bookings
     * still call for it. A link that has aged out is re-issued first, so the mail never carries a URL the server
     * would refuse — the one self-service route back for a guest with no account has to work.
     *
     * <p>Matching the LINK's address, not merely "a booking in the cart has this address", is what keeps a shared
     * cart out: the mail would otherwise hand one person a cart that opens as somebody else.
     *
     * <p>Re-issuing is conditional on expiry on purpose: this entry point is unauthenticated, so re-issuing on every
     * request would let anyone grow the magic_link table at will. Gated this way a cart gains at most one row per
     * expiry window, however often it is asked for. The cart's uuid never changes, so the URL is the same as always.
     */
    public static Future<List<String>> cartUuidsToMail(String email, DataSourceModel dataSourceModel) {
        return EntityStore.create(dataSourceModel)
            .<Cart>executeQuery(
                "select uuid from Cart c where magicLink!=null and lower(magicLink.email)=lower($1)" +
                " and exists(select Document d where d.cart=c and lower(d.person_email)=lower($1))",
                email)
            .compose(carts -> {
                List<Future<String>> uuids = new ArrayList<>();
                for (Cart cart : carts)
                    uuids.add(cartUuidToMail(cart, email, dataSourceModel));
                return Future.all(uuids).map(all -> {
                    List<String> toMail = new ArrayList<>();
                    for (Object uuid : all.list())
                        if (uuid != null)
                            toMail.add((String) uuid);
                    return toMail;
                });
            });
    }

    private static Future<String> cartUuidToMail(Cart cart, String email, DataSourceModel dataSourceModel) {
        Object cartPk = cart.getPrimaryKey();
        return loadCartState(cartPk, dataSourceModel).compose(state -> {
            if (state.guestEmail() == null || !state.guestEmail().equalsIgnoreCase(email))
                return Future.succeededFuture(null);
            if (isLiveLinkFor(state.link(), email))
                return Future.succeededFuture(cart.getUuid());
            return mintAndAttach(cartPk, state, dataSourceModel).map(ignored -> cart.getUuid());
        });
    }

    /**
     * The recovery mail's buttons, one per cart. The origin is this server's and the uuids are the database's, and
     * both are escaped anyway: this goes into the HTML of a genuine KBS email, and one quote character in an
     * attribute is all it takes to write your own content into it.
     */
    public static String cartButtonsHtml(String origin, List<String> cartUuids) {
        StringBuilder buttons = new StringBuilder();
        for (String cartUuid : cartUuids) {
            String url = escapeHtml(origin + "/cart/" + cartUuid);
            buttons.append("<div class=\"cta-wrap\">")
                .append("<a href=\"").append(url).append("\" class=\"cta-btn\">View my booking</a>")
                .append("</div>")
                .append("<p class=\"fallback\">Button not working? Copy and paste:<br>")
                .append("<a href=\"").append(url).append("\">").append(url).append("</a></p>");
        }
        return buttons.toString();
    }

    // Its own copy, like BookingMail's and EmailChangeNotice's: reaching for EmailChangeNotice's would load that
    // class, whose static initialiser loads its mail template — dragging the resource stack into a string function.
    private static String escapeHtml(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    // One recovery mail per address per window. The request is unauthenticated and the bus has no throttling, so
    // without this anyone could flood somebody's inbox with genuine KBS mail. A request inside the window is
    // answered exactly like any other, so the throttle reveals nothing about which addresses have bookings. Kept in
    // memory, like MagicLinkService's code-attempt cap: the server runs as a single task, and a restart only
    // resets it. Capped so a spray of made-up addresses cannot grow it without bound; evicting a real one merely
    // lets its owner be mailed once more.
    private static final long RECOVERY_MAIL_INTERVAL_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final int RECOVERY_MAIL_TRACKED_ADDRESSES = 10_000;
    private static final Map<String, Long> LAST_RECOVERY_MAIL = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > RECOVERY_MAIL_TRACKED_ADDRESSES;
        }
    };

    /** Whether a recovery mail may go to this address now; if so, the address's window starts. */
    public static boolean mayMailRecoveryTo(String email) {
        return mayMailRecoveryTo(email, System.currentTimeMillis());
    }

    static synchronized boolean mayMailRecoveryTo(String email, long nowMillis) {
        if (email == null || email.isBlank())
            return false;
        String key = email.trim().toLowerCase(Locale.ROOT);
        Long last = LAST_RECOVERY_MAIL.remove(key); // removed and re-put, so the map's order is last use
        if (last != null && nowMillis - last < RECOVERY_MAIL_INTERVAL_MILLIS) {
            LAST_RECOVERY_MAIL.put(key, last);
            return false;
        }
        LAST_RECOVERY_MAIL.put(key, nowMillis);
        return true;
    }

    /** Test seam for the checks. */
    static synchronized void forgetRecoveryMails() {
        LAST_RECOVERY_MAIL.clear();
    }
}
