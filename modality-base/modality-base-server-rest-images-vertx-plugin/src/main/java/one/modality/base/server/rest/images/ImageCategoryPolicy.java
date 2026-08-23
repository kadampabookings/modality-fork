package one.modality.base.server.rest.images;

import java.util.regex.Pattern;

/**
 * The catalogue of image "categories" this server accepts, and the rule attached to each.
 *
 * <p>Every storage key that reaches Bunny must match exactly one entry here. That is the
 * whole point: before this class existed, the caller chose the key and the server forwarded
 * it verbatim with our storage password attached, so any string — including one belonging to
 * somebody else's picture — was a legal write target. Matching is done with anchored regexes
 * over the full key, so no traversal ({@code ../}), no null byte and no unexpected prefix can
 * survive; anything unmatched is a 400 rather than a best-effort guess.
 *
 * <p>Two shapes of category exist:
 * <ul>
 *   <li><b>Entity-keyed</b> (events, series, buildings, sites, site-items, festivals): the key
 *       embeds the primary key of the row the picture illustrates, so the client legitimately
 *       names it, and the server checks the row exists and the caller is staff.</li>
 *   <li><b>Server-named</b> (volunteers, chats, passes): the key embeds a random UUID. The
 *       client no longer proposes it — {@link #generateKey} does — which removes overwriting
 *       of an existing object as a possibility rather than as a rule to enforce.</li>
 * </ul>
 *
 * <p>{@link #PERSONS} is listed but permanently denied. Profile photographs were deleted on
 * 2026-08-20 and the feature is being withdrawn; keeping the category visible and refusing it
 * means an attempt is answered with 403 and shows up in the log, instead of silently matching
 * nothing.
 */
public enum ImageCategoryPolicy {

    /**
     * Event cover pictures, optionally suffixed with a language ({@code -fr}) for translated
     * covers. Extensions are constrained to the three the clients actually probe.
     */
    EVENTS("events/", Access.STAFF, Access.STAFF, "Event",
        Pattern.compile("events/event-(\\d{1,10})(-[a-z]{2})?\\.(png|jpg|webp)")),

    /**
     * Audio/video cover pictures. The Java program module reads only {@code .png}, so no other
     * extension is accepted here even though the sibling event covers allow three.
     */
    EVENT_AV_COVERS("events/audio-video-covers/", Access.STAFF, Access.STAFF, "Event",
        Pattern.compile("events/audio-video-covers/event-(\\d{1,10})-cover(-[a-z]{2})?\\.png")),

    /** Shared cover for a recurring-event series; the id is the series' representative event. */
    SERIES("series/", Access.STAFF, Access.STAFF, "Event",
        Pattern.compile("series/series-(\\d{1,10})(-[a-z]{2})?\\.(png|jpg|webp)")),

    /** Building photographs shown in the rooms back-office. */
    BUILDINGS("buildings/", Access.STAFF, Access.STAFF, "Building",
        Pattern.compile("buildings/building-(\\d{1,10})\\.(png|jpg|webp)")),

    /** Site photographs. */
    SITES("sites/", Access.STAFF, Access.STAFF, "Site",
        Pattern.compile("sites/site-(\\d{1,10})\\.(png|jpg|webp)")),

    /** Up to eight album pictures per (site, item) pair; the trailing digit is the slot. */
    SITE_ITEMS("site-items/", Access.STAFF, Access.STAFF, "Site",
        Pattern.compile("site-items/site-(\\d{1,10})-item-(\\d{1,10})-[1-8]\\.(png|jpg|webp)")),

    /** Festival artwork, keyed by the representative event's id. */
    FESTIVALS("festivals/", Access.STAFF, Access.STAFF, "Event",
        Pattern.compile("festivals/festival-(\\d{1,10})\\.(webp|png)")),

    /** Pass-designer artwork. Server-named: the key lives inside the pass design JSON. */
    PASSES("passes/", Access.STAFF, Access.STAFF, null,
        Pattern.compile("passes/" + Uuid.REGEX + "\\.png")),

    /**
     * Volunteer applicant portraits. Upload is deliberately open: the application form at
     * {@code /book-volunteer/:orgId} is public and the applicant has no account yet. What
     * replaces authentication here is that the server names the object, never overwrites,
     * validates the bytes, and rate-limits by IP — and that reading these back requires a
     * signed token (they are the one protected prefix on the CDN).
     */
    VOLUNTEERS("volunteers/", Access.ANONYMOUS, Access.STAFF, null,
        Pattern.compile("volunteers/vol-" + Uuid.REGEX + "\\.png")),

    /**
     * Support-chat attachments, nested under the conversation they belong to. Upload requires
     * a logged-in caller who either is staff or owns that conversation.
     */
    CHATS("chats/", Access.CONVERSATION_MEMBER, Access.STAFF, "Conversation",
        Pattern.compile("chats/(\\d{1,10})/img-" + Uuid.REGEX + "\\.png")),

    /** Withdrawn feature — see the class javadoc. Both directions are refused. */
    PERSONS("persons/", Access.DENIED, Access.DENIED, null,
        Pattern.compile("persons/person-(\\d{1,10})\\.(png|jpg|webp)"));

    /** Who may perform an operation on a category. */
    public enum Access {
        /** No credential required (volunteer applicants). */
        ANONYMOUS,
        /** Any logged-in registered user who owns the target conversation, or staff. */
        CONVERSATION_MEMBER,
        /** A logged-in user with a back-office role or super-admin rights, verified in the database. */
        STAFF,
        /** Never allowed, whoever asks. */
        DENIED
    }

    /** UUID shape shared by the server-named categories. */
    private static final class Uuid {
        private static final String REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    }

    private final String prefix;
    private final Access uploadAccess;
    private final Access deleteAccess;
    private final String entityName;
    private final Pattern keyPattern;

    ImageCategoryPolicy(String prefix, Access uploadAccess, Access deleteAccess, String entityName, Pattern keyPattern) {
        this.prefix = prefix;
        this.uploadAccess = uploadAccess;
        this.deleteAccess = deleteAccess;
        this.entityName = entityName;
        this.keyPattern = keyPattern;
    }

    public String getPrefix() { return prefix; }

    public Access getUploadAccess() { return uploadAccess; }

    public Access getDeleteAccess() { return deleteAccess; }

    /**
     * The entity whose existence is checked before an entity-keyed write, or null when the key
     * carries no primary key (server-named categories).
     */
    public String getEntityName() { return entityName; }

    /** True when the server, not the client, decides the object name for this category. */
    public boolean isServerNamed() {
        return this == VOLUNTEERS || this == CHATS || this == PASSES;
    }

    /**
     * Resolve a full storage key to its category, or null when it matches none.
     *
     * <p>Prefix and pattern are both required. The prefix look-up alone would let
     * {@code events/../../x} through; the anchored pattern is what actually decides.
     */
    public static ImageCategoryPolicy ofKey(String key) {
        if (key == null || key.isEmpty())
            return null;
        for (ImageCategoryPolicy category : values()) {
            // EVENT_AV_COVERS nests under the EVENTS prefix, so test the pattern (anchored,
            // full-match) rather than trusting prefix order.
            if (key.startsWith(category.prefix) && category.keyPattern.matcher(key).matches())
                return category;
        }
        return null;
    }

    /** Resolve the {@code ?category=} query parameter used by the server-named categories. */
    public static ImageCategoryPolicy ofParameterName(String name) {
        if (name == null)
            return null;
        for (ImageCategoryPolicy category : values()) {
            if (category.name().equalsIgnoreCase(name) && category.isServerNamed())
                return category;
        }
        return null;
    }

    /**
     * The numeric id embedded in an entity-keyed storage key — the event, building, site or
     * conversation the picture belongs to — or null when the category carries none.
     */
    public Long extractOwnerId(String key) {
        java.util.regex.Matcher matcher = keyPattern.matcher(key);
        if (!matcher.matches() || entityName == null)
            return null;
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    /**
     * Build the storage key for a server-named category.
     *
     * @param uuid            a random UUID string supplied by the caller of this method (the
     *                        server, never the HTTP client)
     * @param conversationId  required for {@link #CHATS}, ignored otherwise
     */
    public String generateKey(String uuid, Long conversationId) {
        switch (this) {
            case VOLUNTEERS: return "volunteers/vol-" + uuid + ".png";
            case PASSES:     return "passes/" + uuid + ".png";
            case CHATS:      return "chats/" + conversationId + "/img-" + uuid + ".png";
            default: throw new IllegalStateException("Category " + name() + " is not server-named");
        }
    }
}
