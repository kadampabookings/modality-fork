package one.modality.event.server.rest.bunnyaudio;

import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.ReadOnlyAstArray;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.async.Promise;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.fetch.Fetch;
import dev.webfx.platform.fetch.FetchOptions;
import dev.webfx.platform.fetch.Headers;
import dev.webfx.platform.util.Numbers;
import dev.webfx.platform.util.vertx.VertxInstance;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Bunny Edge Storage, for audio recordings: find an organisation's zone, list a folder, store, delete and purge
 * a file.
 *
 * <p><b>One credential is stored, the zone's is derived.</b> An organisation records its account-level API key
 * ({@code Organization.bunnyApiKey}, write-only) and the NAME of its audio storage zone
 * ({@code audioBunnyStorageZoneId}, labelled "Audio storage zone name" in My organization → Media; a numeric
 * id is accepted too). The account API lists the zone with its own password and storage hostname, and the
 * pull zones in front of it with their hostnames — the same bridge {@code BunnyStreamApiClient.resolveLibrary}
 * builds for video. So the zone password never needs storing, and never leaves this server: the browser only
 * ever receives a ticket ({@link AudioUploadTickets}).
 *
 * <p>Endpoint shapes are Bunny's documented ones (checked 2026-10-20): {@code GET api.bunny.net/storagezone}
 * (a bare array when unpaged), {@code GET|PUT|DELETE https://{StorageHostname}/{zone}/{path}} with the zone
 * password as {@code AccessKey}, an optional upper-case hex SHA-256 {@code Checksum} on PUT (201 on success),
 * and {@code POST api.bunny.net/purge?url=}.
 */
public final class BunnyStorageClient {

    private static final String ACCOUNT_API = "https://api.bunny.net";

    /**
     * A storage zone as this server uses it. {@code password} is secret: never logged, never returned.
     *
     * @param cdnHostname the hostname new addresses are built on (the pull zone's system hostname)
     * @param hostnames   every hostname of that pull zone, lower case, {@code cdnHostname} first — an address
     *                    typed by hand on any of them names the same file
     */
    public record Zone(String name, String password, String storageHostname, String cdnHostname, List<String> hostnames) {

        /** The public address a file at {@code path} is served from. */
        public String cdnUrl(String path) {
            return "https://" + cdnHostname + "/" + AudioStorageNames.encodePath(path);
        }

        /** Whether {@code host} serves this zone's files. */
        public boolean servesHost(String host) {
            return host != null && hostnames.contains(host.toLowerCase(Locale.ROOT));
        }

        /** The Storage API address of {@code path} (a file, or a folder when it ends with a slash). */
        String storageUrl(String path) {
            return "https://" + storageHostname + "/" + AudioStorageNames.encodePath(name) + "/"
                + AudioStorageNames.encodePath(path);
        }

        /** The generated record string would print the password. */
        @Override
        public String toString() {
            return "Zone[" + name + " → " + cdnHostname + "]";
        }
    }

    /** One entry of a folder listing. */
    public record Entry(String name, boolean directory, long length) {
    }

    /** Raised with a code the back office translates; the message is for the server log. */
    public static final class StorageException extends RuntimeException {
        public final String code;

        StorageException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Codes for {@link StorageException#code} — mirrored in the back office's audio-storage error map. */
    public static final String ZONE_NOT_FOUND = "bunny.zoneNotFound";
    public static final String NO_PULL_ZONE = "bunny.noPullZone";
    public static final String BAD_API_KEY = "bunny.badApiKey";
    public static final String UNAVAILABLE = "bunny.unavailable";

    /**
     * Zones found so far, by account key and zone. Kept {@link #ZONE_TTL} at most, and dropped as soon as the
     * storage refuses the password: a zone's password can be reset in Bunny, and its pull zone can gain a
     * hostname — which the "is this file still played?" checks must know about.
     */
    private static final ConcurrentHashMap<String, CachedZone> ZONES = new ConcurrentHashMap<>();
    private static final Duration ZONE_TTL = Duration.ofMinutes(10);

    private record CachedZone(Zone zone, long fetchedAtMillis) {
        boolean fresh() {
            return System.currentTimeMillis() - fetchedAtMillis < ZONE_TTL.toMillis();
        }
    }

    /**
     * A PUT connection on which nothing moves, either way, for this long is given up on, so a stalled transfer
     * cannot hold its slot. On the CONNECTION, not the request: a request's idle timeout only counts what comes
     * back, and would cut every upload that takes longer than this to send.
     */
    private static final int UPLOAD_IDLE_TIMEOUT_SECONDS = (int) Duration.ofMinutes(2).toSeconds();
    private static volatile HttpClient uploadClient;

    private BunnyStorageClient() {
    }

    /**
     * The organisation's audio zone.
     *
     * @param accountApiKey   {@code Organization.bunnyApiKey}
     * @param zoneNameOrId    {@code Organization.audioBunnyStorageZoneId} — the zone's name, or its numeric id
     * @param pullZoneIdOrNull {@code Organization.audioBunnyPullZoneId}: which of the zone's pull zones serves
     *                        the files, or null for the first one connected to it
     */
    public static Future<Zone> resolveZone(String accountApiKey, String zoneNameOrId, String pullZoneIdOrNull) {
        // The key's digest, not its hashCode: two keys must never share an entry, which holds the zone password.
        String cacheKey = sha256Hex(accountApiKey) + "|" + zoneNameOrId + "|" + pullZoneIdOrNull;
        CachedZone cached = ZONES.get(cacheKey);
        if (cached != null && cached.fresh())
            return Future.succeededFuture(cached.zone());
        String wanted = zoneNameOrId.trim();
        String url = ACCOUNT_API + "/storagezone?search=" + URLEncoder.encode(wanted, StandardCharsets.UTF_8);
        return Fetch.fetch(url, new FetchOptions().setMethod("GET").setHeaders(headers(accountApiKey, null)))
            .compose(response -> {
                if (response.status() == 401 || response.status() == 403)
                    return Future.failedFuture(new StorageException(BAD_API_KEY, "account API refused the key (" + response.status() + ")"));
                if (!response.ok())
                    return Future.failedFuture(new StorageException(UNAVAILABLE, "storagezone list answered " + response.status()));
                return response.text();
            })
            .compose(text -> {
                ReadOnlyAstArray zones = AST.parseArraySilently(text, "json");
                if (zones == null)
                    return Future.failedFuture(new StorageException(UNAVAILABLE, "storagezone list was not an array"));
                for (int i = 0; i < zones.size(); i++) {
                    ReadOnlyAstObject z = zones.getObject(i);
                    if (z == null || Boolean.TRUE.equals(z.getBoolean("Deleted")))
                        continue;
                    String name = z.getString("Name");
                    String id = String.valueOf(Numbers.toLong(z.get("Id")));
                    if (!wanted.equalsIgnoreCase(name) && !wanted.equals(id))
                        continue;
                    List<String> hostnames = pickHostnames(z.getArray("PullZones"), pullZoneIdOrNull);
                    if (hostnames.isEmpty())
                        return Future.failedFuture(new StorageException(NO_PULL_ZONE, "no pull zone serves storage zone " + name));
                    String storageHostname = z.getString("StorageHostname");
                    Zone zone = new Zone(name, z.getString("Password"),
                        storageHostname == null || storageHostname.isBlank() ? "storage.bunnycdn.com" : storageHostname,
                        hostnames.get(0), List.copyOf(hostnames));
                    ZONES.put(cacheKey, new CachedZone(zone, System.currentTimeMillis()));
                    return Future.succeededFuture(zone);
                }
                return Future.failedFuture(new StorageException(ZONE_NOT_FOUND, "no storage zone named " + wanted));
            });
    }

    /**
     * The hostnames files are served from: those of the chosen pull zone (or the first enabled one), lower
     * case, its system hostname ({@code *.b-cdn.net}) first — the form every audio address already stored
     * uses, so new files sit beside them — then those of the zone's other pull zones, which serve the same
     * files: an address typed on any of them still names a file of this zone, and the "is it still played?"
     * checks must see it. Empty when no pull zone fits.
     */
    static List<String> pickHostnames(ReadOnlyAstArray pullZones, String pullZoneIdOrNull) {
        List<String> result = new ArrayList<>();
        if (pullZones == null)
            return result;
        String wanted = pullZoneIdOrNull == null || pullZoneIdOrNull.isBlank() ? null : pullZoneIdOrNull.trim();
        int chosen = -1;
        for (int i = 0; i < pullZones.size() && chosen < 0; i++) {
            ReadOnlyAstObject pz = pullZones.getObject(i);
            if (pz == null)
                continue;
            String id = String.valueOf(Numbers.toLong(pz.get("Id")));
            boolean fits = wanted == null ? !Boolean.FALSE.equals(pz.getBoolean("Enabled"))
                : wanted.equals(id) || wanted.equalsIgnoreCase(pz.getString("Name"));
            if (fits && addHostnames(pz, result, true))
                chosen = i;
        }
        if (chosen < 0)
            return result;
        for (int i = 0; i < pullZones.size(); i++)
            if (i != chosen && pullZones.getObject(i) != null)
                addHostnames(pullZones.getObject(i), result, false);
        return result;
    }

    /** Adds a pull zone's hostnames, lower case and once each — its system hostname first when {@code lead}. */
    private static boolean addHostnames(ReadOnlyAstObject pullZone, List<String> result, boolean lead) {
        ReadOnlyAstArray hostnames = pullZone.getArray("Hostnames");
        boolean added = false;
        for (int h = 0; hostnames != null && h < hostnames.size(); h++) {
            ReadOnlyAstObject host = hostnames.getObject(h);
            String value = host == null ? null : host.getString("Value");
            if (value == null || value.isBlank())
                continue;
            String lower = value.trim().toLowerCase(Locale.ROOT);
            if (result.contains(lower))
                continue;
            if (lead && Boolean.TRUE.equals(host.getBoolean("IsSystemHostname")))
                result.add(0, lower);
            else
                result.add(lower);
            added = true;
        }
        return added;
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest)
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // mandated by the JDK; unreachable
        }
    }

    /** The entries of {@code folder} (no trailing slash needed); empty when the folder does not exist yet. */
    public static Future<List<Entry>> list(Zone zone, String folder) {
        String path = folder == null || folder.isEmpty() ? "" : folder + "/";
        return Fetch.fetch(zone.storageUrl(path), new FetchOptions().setMethod("GET").setHeaders(headers(zone.password(), null)))
            .compose(response -> {
                if (response.status() == 404)
                    return Future.succeededFuture("[]");
                if (refusedPassword(zone, response.status()))
                    return Future.failedFuture(new StorageException(BAD_API_KEY, "storage zone refused its own password"));
                if (!response.ok())
                    return Future.failedFuture(new StorageException(UNAVAILABLE, "folder listing answered " + response.status()));
                return response.text();
            })
            .map(text -> {
                ReadOnlyAstArray items = AST.parseArraySilently(text, "json");
                List<Entry> entries = new ArrayList<>();
                for (int i = 0; items != null && i < items.size(); i++) {
                    ReadOnlyAstObject item = items.getObject(i);
                    if (item == null || item.getString("ObjectName") == null)
                        continue;
                    entries.add(new Entry(item.getString("ObjectName"), Boolean.TRUE.equals(item.getBoolean("IsDirectory")),
                        Numbers.toLong(item.get("Length")) == null ? 0 : Numbers.toLong(item.get("Length"))));
                }
                return entries;
            });
    }

    /**
     * Stores {@code file} at {@code path}, overwriting what is there; Bunny checks {@code sha256} on receipt.
     *
     * <p>Sent with the Vert.x client rather than {@code Fetch}: the Vert.x Fetch provider opens a file body and
     * never closes it, so every upload would keep its temporary file's descriptor — and, once the request ends
     * and the file is deleted, its disk space — until the server restarts. Here the file is closed when the
     * exchange ends, whatever the outcome. (The provider's leak is reported for the WebFX maintainers.)
     */
    public static Future<Void> upload(Zone zone, String path, File file, String sha256UpperHex) {
        Promise<Void> promise = Promise.promise();
        RequestOptions request = new RequestOptions()
            .setMethod(HttpMethod.PUT)
            .setAbsoluteURI(zone.storageUrl(path))
            .putHeader("AccessKey", zone.password())
            .putHeader("Accept", "application/json")
            .putHeader("Content-Type", "application/octet-stream")
            .putHeader("Content-Length", String.valueOf(file.length()))
            .putHeader("Checksum", sha256UpperHex);
        VertxInstance.getVertx().fileSystem().open(file.getAbsolutePath(), new OpenOptions().setRead(true).setWrite(false).setCreate(false))
            .onFailure(err -> promise.fail(new StorageException(UNAVAILABLE, "upload could not open the file: " + err.getMessage())))
            .onSuccess(body -> uploadClient().request(request)
                .compose(put -> put.send(body))
                .compose(response -> response.body().map(answer -> new Object[] { response.statusCode(), answer.toString() }))
                .onComplete(ar -> {
                    closeQuietly(body);
                    if (ar.failed()) {
                        promise.fail(new StorageException(UNAVAILABLE, "upload failed: " + ar.cause().getMessage()));
                        return;
                    }
                    int status = (Integer) ar.result()[0];
                    if (status >= 200 && status < 300)
                        promise.complete();
                    else if (refusedPassword(zone, status))
                        promise.fail(new StorageException(BAD_API_KEY, "storage zone refused its own password"));
                    else
                        promise.fail(new StorageException(UNAVAILABLE, "upload answered " + status + ": " + abbreviate((String) ar.result()[1])));
                }));
        return promise.future();
    }

    private static HttpClient uploadClient() {
        HttpClient client = uploadClient;
        if (client == null) {
            synchronized (BunnyStorageClient.class) {
                if (uploadClient == null)
                    uploadClient = VertxInstance.getVertx().createHttpClient(new HttpClientOptions()
                        .setIdleTimeout(UPLOAD_IDLE_TIMEOUT_SECONDS).setIdleTimeoutUnit(TimeUnit.SECONDS));
                client = uploadClient;
            }
        }
        return client;
    }

    private static void closeQuietly(AsyncFile file) {
        file.close().onFailure(err -> Console.log("[BUNNY-AUDIO] closing an uploaded file failed: " + err.getMessage()));
    }

    /** Removes the file at {@code path}; one already gone counts as removed. */
    public static Future<Void> delete(Zone zone, String path) {
        return Fetch.fetch(zone.storageUrl(path), new FetchOptions().setMethod("DELETE").setHeaders(headers(zone.password(), null)))
            .compose(response -> response.ok() || response.status() == 404 ? Future.<Void>succeededFuture()
                : refusedPassword(zone, response.status())
                    ? Future.failedFuture(new StorageException(BAD_API_KEY, "storage zone refused its own password"))
                    : Future.failedFuture(new StorageException(UNAVAILABLE, "delete answered " + response.status())));
    }

    /**
     * Whether the storage refused the zone's password ({@code 401}/{@code 403}); when it did, the zone is
     * forgotten so the next call asks the account API again — the password may have been reset in Bunny.
     */
    private static boolean refusedPassword(Zone zone, int status) {
        if (status != 401 && status != 403)
            return false;
        ZONES.values().removeIf(cached -> cached.zone() == zone);
        return true;
    }

    /**
     * Drops {@code cdnUrl} from Bunny's edge caches, so an overwritten or deleted file stops being served. A
     * failure is reported, not hidden: a caller that promised a purge must say when it did not happen.
     */
    public static Future<Void> purge(String accountApiKey, String cdnUrl) {
        String url = ACCOUNT_API + "/purge?url=" + URLEncoder.encode(cdnUrl, StandardCharsets.UTF_8);
        return Fetch.fetch(url, new FetchOptions().setMethod("POST").setHeaders(headers(accountApiKey, null)))
            .compose(response -> response.ok() ? Future.<Void>succeededFuture()
                : Future.failedFuture(new StorageException(UNAVAILABLE, "purge answered " + response.status())));
    }

    private static Headers headers(String accessKey, String contentType) {
        Headers headers = Fetch.createHeaders().set("AccessKey", accessKey).set("Accept", "application/json");
        if (contentType != null)
            headers.set("Content-Type", contentType);
        return headers;
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
