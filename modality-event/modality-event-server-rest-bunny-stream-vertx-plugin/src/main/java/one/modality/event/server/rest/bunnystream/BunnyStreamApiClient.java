package one.modality.event.server.rest.bunnystream;

import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.fetch.FetchOptions;
import dev.webfx.platform.fetch.Headers;
import dev.webfx.platform.fetch.json.JsonFetch;
import dev.webfx.platform.util.Numbers;

import static dev.webfx.platform.util.http.HttpResponseStatus.NOT_FOUND_404;
import static dev.webfx.platform.util.http.HttpResponseStatus.UNAUTHORIZED_401;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin wrapper around the Bunny.net Stream HTTP management API.
 *
 * <p>Only the three calls the publish-video flow needs are exposed:
 * <ul>
 *   <li>{@link #createVideo} — register a new video in the library and obtain its {@code guid}</li>
 *   <li>{@link #getVideo}    — read transcoding state (used by the status-poll endpoint)</li>
 *   <li>{@link #deleteVideo} — clean up a video record after a failed/aborted upload</li>
 * </ul>
 *
 * <p>Bunny TUS direct uploads (browser → bunny.net) require a SHA-256 signature
 * derived from {@code libraryId + apiKey + expires + videoGuid}. {@link #buildTusSignature}
 * computes that so the API key never reaches the browser.
 *
 * <p>The {@code apiKey} parameter is the per-organization Bunny Stream AccessKey
 * (read at request time from {@code Organization.bunnyStreamApiKey}). It is never
 * stored in module state and never logged.
 */
public final class BunnyStreamApiClient {

    /** Bunny Stream management API base. */
    private static final String BASE_URL = "https://video.bunnycdn.com";

    /** TUS upload duration window. Long enough that even a slow 5 GB upload finishes
     *  before the signature expires; if it doesn't, the client refreshes the signature. */
    public static final Duration TUS_AUTH_LIFETIME = Duration.ofHours(6);

    private BunnyStreamApiClient() {
    }

    /** Create a new video record on Bunny and return its {@code guid}. */
    public static Future<String> createVideo(String libraryId, String apiKey, String title) {
        FetchOptions options = new FetchOptions()
                .setMethod("POST")
                .setHeaders(authHeaders(apiKey, "application/json"))
                .setBody("{\"title\":\"" + escapeJson(title) + "\"}");
        return fetchJsonChecked("createVideo", BASE_URL + "/library/" + libraryId + "/videos", options)
                .map(json -> json.getString("guid"));
    }

    /** Get the current state of a video (status, encodeProgress, length, …). */
    public static Future<ReadOnlyAstObject> getVideo(String libraryId, String apiKey, String guid) {
        FetchOptions options = new FetchOptions()
                .setMethod("GET")
                .setHeaders(authHeaders(apiKey, null));
        return fetchJsonChecked("getVideo",
                BASE_URL + "/library/" + libraryId + "/videos/" + guid,
                options);
    }

    /** Fetch the library metadata (name, etc.) from Bunny.
     *
     *  <p>Used by {@code GET /rest/videos/library} to (a) verify the apiKey/libraryId
     *  pair is valid, and (b) return the human-readable library name to the React
     *  Publish Video tab so it can show a "Library: {name}" chip near the dropzone.
     *  The endpoint Bunny exposes — {@code GET /library/{libraryId}} — is the cheapest
     *  authenticated round-trip available; a 401 here means the credentials are wrong.
     */
    public static Future<ReadOnlyAstObject> getLibrary(String libraryId, String apiKey) {
        FetchOptions options = new FetchOptions()
                .setMethod("GET")
                .setHeaders(authHeaders(apiKey, null));
        return fetchJsonChecked("getLibrary",
                BASE_URL + "/library/" + libraryId,
                options);
    }

    /** List videos in a library, paginated. Used by the Library Management tab to
     *  enumerate every video the organization has stored on Bunny.
     *
     *  <p>{@code orderBy=date} (newest first) matches the layout users expect from the
     *  Bunny dashboard, and means a fresh upload appears at the top of the grid as
     *  soon as the React side polls again. Pagination uses Bunny's native
     *  {@code page}/{@code itemsPerPage} parameters; the caller decides the page size.
     *
     *  <p>The raw Bunny JSON object is returned (shape: {@code {items:[…], totalItems,
     *  currentPage, itemsPerPage}}); the caller picks the fields it cares about so we
     *  don't drag in a JSON parser dependency for one call.
     */
    public static Future<ReadOnlyAstObject> listVideos(String libraryId, String apiKey, int page, int perPage) {
        FetchOptions options = new FetchOptions()
                .setMethod("GET")
                .setHeaders(authHeaders(apiKey, null));
        return fetchJsonChecked("listVideos",
                BASE_URL + "/library/" + libraryId
                        + "/videos?page=" + page
                        + "&itemsPerPage=" + perPage
                        + "&orderBy=date",
                options);
    }

    /** Fetch aggregate library statistics (total bandwidth, cumulative views, …).
     *
     *  <p>The cheaper {@link #getLibrary} response already exposes {@code StorageUsage}
     *  and {@code TrafficUsage}, which is enough to drive the stats banner. This call
     *  is provided for the follow-up where a per-period bandwidth chart is needed —
     *  Bunny's {@code /statistics} endpoint exposes a time series the cheap library
     *  endpoint does not.
     */
    public static Future<ReadOnlyAstObject> getLibraryStatistics(String libraryId, String apiKey) {
        FetchOptions options = new FetchOptions()
                .setMethod("GET")
                .setHeaders(authHeaders(apiKey, null));
        return fetchJsonChecked("getLibraryStatistics",
                BASE_URL + "/library/" + libraryId + "/statistics",
                options);
    }

    /** Account-level (management) API base — used for endpoints that the Stream
     *  domain {@link #BASE_URL} does not expose, in particular library-level
     *  storage / bandwidth / video-count totals. Same {@code AccessKey} header
     *  pattern; the org's account-level API key is reused. */
    private static final String ACCOUNT_BASE_URL = "https://api.bunny.net";

    /** Cache mapping library id → resolved library properties (Stream API key
     *  and numeric pull-zone id). Both come from the same account-API call so
     *  caching them together avoids a second round-trip for the billing
     *  endpoint, which needs the numeric pull-zone id to query CDN statistics.
     *  The cache lives forever — the per-library values effectively never
     *  change, and rotating them from the Bunny dashboard would also require
     *  an operator restart of the server to take effect anyway. */
    private static final ConcurrentHashMap<String, ResolvedLibrary> LIBRARY_CACHE = new ConcurrentHashMap<>();

    /** Resolved properties of a Bunny Stream library — the per-library Stream
     *  API key (used for {@code video.bunnycdn.com} calls) and the numeric pull
     *  zone id (used for the account-level CDN statistics endpoint). */
    public static final class ResolvedLibrary {
        /** Per-library Stream API key — for {@code video.bunnycdn.com}. */
        public final String streamApiKey;
        /** Numeric pull-zone id — for {@code api.bunny.net/statistics?pullZone=…}. */
        public final long pullZoneId;
        public ResolvedLibrary(String streamApiKey, long pullZoneId) {
            this.streamApiKey = streamApiKey;
            this.pullZoneId = pullZoneId;
        }
    }

    /** Fetch the full library record from the account-level API.
     *
     *  <p>The Stream API's {@code GET https://video.bunnycdn.com/library/{id}} returns
     *  only library *settings* (transcoding, watermark, name) — the {@code StorageUsage}
     *  / {@code TrafficUsage} / {@code VideoCount} numbers our stats banner needs come
     *  back as zero. Those fields live on the account-level endpoint
     *  {@code GET https://api.bunny.net/videolibrary/{id}}, which returns the same
     *  {@code Name} field plus the usage counters.
     *
     *  <p>This method requires the **account-level** API key (the one that lives at
     *  the bottom of the Bunny dashboard, NOT a per-library Stream key). Where this
     *  is the same value we already store as {@code Organization.bunnyApiKey}, both
     *  endpoints share a key and the call works transparently. If the org has only a
     *  per-library key, this call returns 401 and the caller falls back to
     *  {@link #getLibrary}.
     */
    public static Future<ReadOnlyAstObject> getVideoLibrary(String libraryId, String apiKey) {
        FetchOptions options = new FetchOptions()
                .setMethod("GET")
                .setHeaders(authHeaders(apiKey, null));
        return fetchJsonChecked("getVideoLibrary",
                ACCOUNT_BASE_URL + "/videolibrary/" + libraryId,
                options);
    }

    /** Resolve the per-library Stream API key for {@code libraryId} given an
     *  account-level API key.
     *
     *  <p>The Stream API at {@code video.bunnycdn.com} (used for video CRUD,
     *  list, status, delete, TUS signature) only accepts the per-library
     *  Stream key — the account-level key is rejected with
     *  {@code authentication.failed}. Conversely, the account API at
     *  {@code api.bunny.net} only accepts the account-level key. We bridge the
     *  two by reading the {@code ApiKey} field of each library record from the
     *  account API and caching it.
     *
     *  <p>The result is cached in {@link #STREAM_KEY_CACHE} for the lifetime of
     *  the process, so subsequent calls for the same library skip the round
     *  trip. The cache is keyed only on {@code libraryId}; if the same library
     *  is reached via different account keys the first one to win populates the
     *  cache (acceptable — the Stream key is a property of the library, not the
     *  caller).
     *
     *  @param libraryId       Bunny library id (numeric, but treated as opaque string).
     *  @param accountApiKey   Account-level API key (works against api.bunny.net).
     *  @return Future resolving to the per-library Stream API key, or failing
     *          if the account API rejected the key or the response did not
     *          include an {@code ApiKey} field.
     */
    public static Future<ResolvedLibrary> resolveLibrary(String libraryId, String accountApiKey) {
        ResolvedLibrary cached = LIBRARY_CACHE.get(libraryId);
        if (cached != null) return Future.succeededFuture(cached);
        // getVideoLibrary() now goes through fetchJsonChecked(), which converts both
        // non-2xx responses AND HTTP-200-with-{ErrorKey} envelopes into tagged
        // failures (e.g. "[getVideoLibrary|401] authentication.failed: …") — so no
        // ErrorKey check is needed here.
        return getVideoLibrary(libraryId, accountApiKey)
                .compose(json -> {
                    String streamKey = json.getString("ApiKey");
                    if (streamKey == null || streamKey.isEmpty()) {
                        return Future.failedFuture("[resolveLibrary] Library response did not include an ApiKey field");
                    }
                    long pullZoneId = Numbers.longValue(json.get("PullZoneId"));
                    ResolvedLibrary resolved = new ResolvedLibrary(streamKey, pullZoneId);
                    LIBRARY_CACHE.put(libraryId, resolved);
                    return Future.succeededFuture(resolved);
                });
    }

    /** Backwards-compatible shim — callers that only want the Stream API key
     *  go through {@link #resolveLibrary} but only see the key. */
    public static Future<String> resolveLibraryStreamKey(String libraryId, String accountApiKey) {
        return resolveLibrary(libraryId, accountApiKey).map(r -> r.streamApiKey);
    }

    /** Account-level CDN statistics for a single pull zone, summed over a date
     *  range. Used by the Library Management billing endpoint to compute the
     *  total bandwidth served between an event's start date and today (or its
     *  VOD expiration, whichever comes first) — Bunny's Stream library record
     *  only carries the rolling current-month {@code TrafficUsage}, which is
     *  not enough for events that span multiple months.
     *
     *  <p>Bunny's response includes {@code TotalBandwidthUsed} (a long, bytes)
     *  pre-summed across the period, plus a {@code BandwidthUsedChart} for
     *  per-day values; the caller normally just reads {@code TotalBandwidthUsed}.
     *
     *  @param pullZoneId    Numeric pull-zone id (from {@link ResolvedLibrary#pullZoneId}).
     *  @param accountApiKey Account-level API key (account API only).
     *  @param dateFrom      Period start (inclusive, ISO {@code YYYY-MM-DD}).
     *  @param dateTo        Period end (inclusive, ISO {@code YYYY-MM-DD}).
     */
    public static Future<ReadOnlyAstObject> getCdnStatistics(
            long pullZoneId, String accountApiKey, String dateFrom, String dateTo) {
        FetchOptions options = new FetchOptions()
                .setMethod("GET")
                .setHeaders(authHeaders(accountApiKey, null));
        String url = ACCOUNT_BASE_URL + "/statistics"
                + "?dateFrom=" + dateFrom
                + "&dateTo=" + dateTo
                + "&pullZone=" + pullZoneId
                + "&hourly=false";
        return fetchJsonChecked("getCdnStatistics", url, options);
    }

    /** Test-only hook to flush the library cache (e.g. between integration
     *  tests so each test starts with no resolved keys). Not used in production. */
    public static void clearStreamKeyCache() {
        LIBRARY_CACHE.clear();
    }

    /** Delete a video — used to clean up after aborted/failed uploads so we don't
     *  leave orphan records in the library. Treats 404 as success.
     *
     *  <p>Note: the {@code .recover()} here intentionally swallows 404s — for an
     *  abort/cleanup path, "video already gone" is success, not failure. Callers
     *  that need to discriminate (e.g. the Library Management Delete button) should
     *  use {@link #deleteVideoStrict} instead, which surfaces 404 as a typed error
     *  so the React side can render a "video not found" banner. */
    public static Future<Void> deleteVideo(String libraryId, String apiKey, String guid) {
        return deleteVideoStrict(libraryId, apiKey, guid)
                .recover(err -> Future.succeededFuture()); // 404 / already-deleted is fine
    }

    /** Same as {@link #deleteVideo} but propagates failures (incl. 404) verbatim
     *  so the caller can show the right error to the operator. */
    public static Future<Void> deleteVideoStrict(String libraryId, String apiKey, String guid) {
        FetchOptions options = new FetchOptions()
                .setMethod("DELETE")
                .setHeaders(authHeaders(apiKey, null));
        return fetchJsonChecked("deleteVideo",
                        BASE_URL + "/library/" + libraryId + "/videos/" + guid,
                        options)
                .mapEmpty();
    }

    /** Compute the TUS authorisation signature Bunny expects on direct browser uploads.
     *
     *  <p>Bunny's spec: {@code SHA256(libraryId + apiKey + expirationTimestamp + videoId)},
     *  hex-encoded lowercase. The server returns {@code (signature, expire)} to the
     *  browser; the browser sends them as {@code AuthorizationSignature} /
     *  {@code AuthorizationExpire} headers on the TUS PATCH request, so the apiKey
     *  itself is never exposed.
     */
    public static TusAuth buildTusSignature(String libraryId, String apiKey, String guid) {
        long expires = Instant.now().plus(TUS_AUTH_LIFETIME).getEpochSecond();
        String message = libraryId + apiKey + expires + guid;
        return new TusAuth(sha256Hex(message), expires);
    }

    /**
     * Status-aware JSON fetcher. Every failure is wrapped in a
     * {@link TaggedBunnyException} that carries the originating Bunny endpoint
     * name and (when known) an HTTP-equivalent status code, so the React
     * categoriser can discriminate bad API key (401/403) from bad library id /
     * video guid / pull-zone id (404 on the corresponding endpoint).
     *
     * <p>{@link TaggedBunnyException#getMessage()} formats as
     * {@code "[endpoint|status] detail"} when the status is known, or
     * {@code "[endpoint] detail"} for transport-level / parse failures.
     * Bunny's own error envelopes are detected by reading {@code StatusCode}
     * and {@code ErrorKey} from the JSON body — {@link JsonFetch#fetchJsonObject}
     * silently returns parsed JSON for non-2xx responses, so the body is the
     * only place we can recover the status without bypassing JsonFetch.
     */
    private static Future<ReadOnlyAstObject> fetchJsonChecked(
            String endpoint, String url, FetchOptions options) {
        return JsonFetch.fetchJsonObject(url, options)
                .compose(BunnyStreamApiClient::interpretBunnyResponse)
                .recover(err -> Future.failedFuture(new TaggedBunnyException(endpoint, err)));
    }

    private static Future<ReadOnlyAstObject> interpretBunnyResponse(ReadOnlyAstObject json) {
        if (json == null) return Future.failedFuture("empty body");
        int statusCode = Numbers.intValue(json.get("StatusCode"));
        String errorKey = json.getString("ErrorKey");
        if (statusCode >= 400) {
            return Future.failedFuture(new BunnyStatusException(statusCode, formatDetail(errorKey, json.getString("Message"))));
        }
        if (errorKey != null && !errorKey.isEmpty()) {
            int inferred = inferStatusFromErrorKey(errorKey);
            String detail = formatDetail(errorKey, json.getString("Message"));
            return inferred > 0
                    ? Future.failedFuture(new BunnyStatusException(inferred, detail))
                    : Future.failedFuture(detail);
        }
        return Future.succeededFuture(json);
    }

    private static String formatDetail(String errorKey, String message) {
        String prefix = errorKey == null || errorKey.isEmpty() ? "" : "ErrorKey=" + errorKey + ": ";
        String body = message == null || message.isEmpty() ? "(no message)" : message;
        return prefix + body;
    }

    /** Map a Bunny {@code ErrorKey} string to the closest HTTP-status equivalent.
     *  Returns 0 when the key matches no known pattern — the React side then
     *  surfaces the error in the {@code unknown} bucket rather than misleading
     *  the operator with a {@code badApiKey} banner. */
    private static int inferStatusFromErrorKey(String errorKey) {
        String lower = errorKey.toLowerCase();
        if (lower.contains("not_found") || lower.contains("notfound")) return NOT_FOUND_404;
        if (lower.contains("unauthorized") || lower.contains("authentication")) return UNAUTHORIZED_401;
        return 0;
    }

    /** Raised by {@link #interpretBunnyResponse} when a Bunny response carries
     *  a 4xx-equivalent error envelope. Captured by {@link TaggedBunnyException}
     *  in {@link #fetchJsonChecked}'s recover branch so the endpoint tag can be
     *  attached without re-parsing the message string downstream. */
    private static final class BunnyStatusException extends RuntimeException {
        final int status;
        BunnyStatusException(int status, String detail) {
            super(detail);
            this.status = status;
        }
    }

    /** Wraps any Bunny call failure with its originating endpoint so callers
     *  (e.g. {@code BunnyStreamRestService.resolveStreamApiKey}) can branch on
     *  the structured {@link #status} field instead of re-parsing the message. */
    public static final class TaggedBunnyException extends RuntimeException {
        public final String endpoint;
        /** HTTP-equivalent status, or 0 when the cause was a transport / parse
         *  error or an unknown {@code ErrorKey}. */
        public final int status;

        TaggedBunnyException(String endpoint, Throwable cause) {
            super(format(endpoint, cause), cause);
            this.endpoint = endpoint;
            this.status = cause instanceof BunnyStatusException
                    ? ((BunnyStatusException) cause).status
                    : 0;
        }

        private static String format(String endpoint, Throwable cause) {
            String detail = cause == null || cause.getMessage() == null
                    ? "transport error" : cause.getMessage();
            if (cause instanceof BunnyStatusException) {
                return "[" + endpoint + "|" + ((BunnyStatusException) cause).status + "] " + detail;
            }
            return "[" + endpoint + "] " + detail;
        }
    }

    private static Headers authHeaders(String apiKey, String contentType) {
        Headers headers = Headers.create()
                .set("AccessKey", apiKey)
                .set("Accept", "application/json");
        if (contentType != null) headers.set("Content-Type", contentType);
        return headers;
    }

    /** Minimal JSON string escaper for the only string field we ship in a request body
     *  (the video title). Avoids dragging in a JSON formatter dependency for one call. */
    static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20)
                        sb.append(String.format("\\u%04x", (int) c));
                    else
                        sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK spec; this branch is unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Pair of TUS authorisation values returned to the browser. */
    public static final class TusAuth {
        public final String signature;
        public final long expire;

        public TusAuth(String signature, long expire) {
            this.signature = signature;
            this.expire = expire;
        }
    }
}
