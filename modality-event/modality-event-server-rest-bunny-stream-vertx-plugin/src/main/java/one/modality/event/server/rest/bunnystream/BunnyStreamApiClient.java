package one.modality.event.server.rest.bunnystream;

import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.fetch.FetchOptions;
import dev.webfx.platform.fetch.Headers;
import dev.webfx.platform.fetch.json.JsonFetch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;

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
        return JsonFetch.fetchJsonObject(BASE_URL + "/library/" + libraryId + "/videos", options)
                .map(json -> json.getString("guid"));
    }

    /** Get the current state of a video (status, encodeProgress, length, …). */
    public static Future<ReadOnlyAstObject> getVideo(String libraryId, String apiKey, String guid) {
        FetchOptions options = new FetchOptions()
                .setMethod("GET")
                .setHeaders(authHeaders(apiKey, null));
        return JsonFetch.fetchJsonObject(
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
        return JsonFetch.fetchJsonObject(
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
        return JsonFetch.fetchJsonObject(
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
        return JsonFetch.fetchJsonObject(
                BASE_URL + "/library/" + libraryId + "/statistics",
                options);
    }

    /** Delete a video — used to clean up after aborted/failed uploads so we don't
     *  leave orphan records in the library. Treats 404 as success. */
    public static Future<Void> deleteVideo(String libraryId, String apiKey, String guid) {
        FetchOptions options = new FetchOptions()
                .setMethod("DELETE")
                .setHeaders(authHeaders(apiKey, null));
        return JsonFetch.fetchJsonObject(
                        BASE_URL + "/library/" + libraryId + "/videos/" + guid,
                        options)
                .<Void>mapEmpty()
                .recover(err -> Future.succeededFuture()); // 404 / already-deleted is fine
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
