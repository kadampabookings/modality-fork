package one.modality.base.server.rest.images;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.boot.spi.ApplicationModuleBooter;
import dev.webfx.platform.conf.ConfigLoader;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.file.spi.impl.jre.JreFile;
import dev.webfx.platform.util.Booleans;
import dev.webfx.platform.util.vertx.VertxInstance;
import dev.webfx.stack.cloud.image.CloudImageService;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityStore;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static dev.webfx.platform.util.http.HttpResponseStatus.*;

/**
 * The authenticated replacement for the generic cloud-image REST endpoints.
 *
 * <p>It serves the same four paths the clients already call, plus one new
 * ({@code /rest/images/access-token}), so no client URL changes. What changes is that every
 * write is now attributed and constrained:
 *
 * <ul>
 *   <li>the caller is identified from the session id it sends (see {@link RestSessionPrincipal});</li>
 *   <li>the storage key must match a known category, and for the categories where the client has
 *       no business choosing the name — volunteer portraits, chat attachments, pass artwork —
 *       the server generates it, so an upload can never land on top of an existing object;</li>
 *   <li>the bytes are checked for size and for a real image signature, rather than trusted
 *       because the browser said so;</li>
 *   <li>profile photographs are refused outright, the feature having been withdrawn.</li>
 * </ul>
 *
 * <p>The endpoints this replaces required no credential at all while the server attached our
 * Bunny storage password on the caller's behalf, which meant anybody on the internet could
 * replace or delete any picture in the zone without ever holding that password. That is the
 * hole being closed here.
 *
 * <p>One upload path stays open to anonymous callers: the public volunteer application form has
 * no account behind it. There, the compensating controls are the server-chosen object name, the
 * byte validation, the per-IP rate limit — and the fact that reading anything under
 * {@code volunteers/} requires a signed token issued only to staff.
 */
public final class SecuredImagesRestService implements ApplicationModuleBooter {

    private static final String CONFIG_PATH = "modality.base.server.rest.images";

    private static final String EXISTS_PATH = "/rest/images/exists";
    private static final String UPLOAD_PATH = "/rest/images/upload";
    private static final String DELETE_PATH = "/rest/images/delete";
    private static final String URL_PATTERN_PATH = "/rest/images/url-pattern";
    private static final String ACCESS_TOKEN_PATH = "/rest/images/access-token";

    /** Largest picture accepted. The clients resize well below this before uploading. */
    private static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024;
    /** Body limit adds room for the multipart envelope around the file itself. */
    private static final long BODY_LIMIT_BYTES = MAX_UPLOAD_BYTES + 64 * 1024;

    /** The one CDN prefix that requires a signed token to read. */
    private static final String PROTECTED_TOKEN_PATH = "/volunteers/";
    /** Lifetime of an issued directory token. Long enough for a working session, short enough to matter. */
    private static final long ACCESS_TOKEN_TTL_SECONDS = 3600;

    private static final SlidingWindowRateLimiter VOLUNTEER_UPLOAD_LIMITER =
        new SlidingWindowRateLimiter(10, 10 * 60_000L);
    private static final SlidingWindowRateLimiter CHAT_UPLOAD_LIMITER =
        new SlidingWindowRateLimiter(30, 10 * 60_000L);

    /** PNG signature: the eight bytes every PNG starts with. */
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    /** Pull zone token key; null until config resolves, and absent in environments without one. */
    private String pullZoneTokenKey;

    @Override
    public String getModuleName() {
        return "modality-base-server-rest-images-plugin";
    }

    @Override
    public int getBootLevel() {
        return ApplicationModuleBooter.COMMUNICATION_REGISTER_BOOT_LEVEL;
    }

    @Override
    public void bootModule() {
        // Same guard as the sibling bunny-stream service: an exception escaping bootModule()
        // fails the verticle silently, leaving the port unbound and nothing in the log.
        try {
            // Routes first, and never conditional on configuration. These endpoints ARE the access
            // control on the picture store; if a missing optional setting could stop them being
            // registered, a configuration slip would silently reopen the hole they exist to close.
            // The token key below is optional by design — without it only /access-token degrades.
            Router router = VertxInstance.getHttpRouter();

            // Public: the URL template the clients use to build read URLs. Contains no secret.
            router.route(HttpMethod.GET, URL_PATTERN_PATH)
                .handler(ctx -> ctx.response().end(CloudImageService.urlPattern()));

            router.route(HttpMethod.POST, EXISTS_PATH)
                .handler(BodyHandler.create().setBodyLimit(BODY_LIMIT_BYTES))
                .handler(this::handleExists);

            router.route(HttpMethod.POST, UPLOAD_PATH)
                .handler(BodyHandler.create().setBodyLimit(BODY_LIMIT_BYTES))
                .handler(this::handleUpload);

            router.route(HttpMethod.POST, DELETE_PATH)
                .handler(BodyHandler.create().setBodyLimit(BODY_LIMIT_BYTES))
                .handler(this::handleDelete);

            router.route(HttpMethod.POST, ACCESS_TOKEN_PATH)
                .handler(BodyHandler.create().setBodyLimit(BODY_LIMIT_BYTES))
                .handler(this::handleAccessToken);

            Console.log("[IMAGES] Secured REST routes registered: " + URL_PATTERN_PATH + " (GET), "
                + EXISTS_PATH + ", " + UPLOAD_PATH + ", " + DELETE_PATH + ", " + ACCESS_TOKEN_PATH + " (POST)");

            // The pull zone token key is looked up afterwards, and tolerates being absent: the
            // config subtree itself is null when the module's declare@ file has not been folded
            // into the application's config bundle, and the variable is empty in environments
            // that have no protected prefix yet.
            ConfigLoader.onConfigLoaded(CONFIG_PATH, config -> {
                String key = config == null ? null : config.getString("pullZoneTokenKey");
                // An unresolved ${{ }} placeholder means the variable was never supplied.
                pullZoneTokenKey = key == null || key.isEmpty() || key.contains("${{") ? null : key;
                Console.log("[IMAGES] Pull zone token key: " + (pullZoneTokenKey != null
                    ? "***configured***"
                    : "NOT SET — " + ACCESS_TOKEN_PATH + " will return 503 and volunteer photos "
                      + "will be served untokened (fine until token auth is enabled on the zone)"));
            });
        } catch (Throwable t) {
            System.err.println("[IMAGES] bootModule FAILED: " + t);
            t.printStackTrace(System.err);
            Console.error("[IMAGES] bootModule FAILED", t);
            throw t;
        }
    }

    // ─── /rest/images/exists ───────────────────────────────────────────────

    /**
     * Existence probe. Requires a logged-in caller: knowing whether a given picture exists is
     * itself information (it reveals that the row behind it exists), so it is not offered to
     * anonymous callers even though it changes nothing.
     */
    private void handleExists(RoutingContext ctx) {
        String id = ctx.request().getParam("id");
        ImageCategoryPolicy category = ImageCategoryPolicy.ofKey(id);
        if (category == null) {
            sendStatus(ctx, BAD_REQUEST_400, "unrecognised image id");
            return;
        }
        if (category == ImageCategoryPolicy.PERSONS) {
            sendStatus(ctx, FORBIDDEN_403, "profile pictures have been withdrawn");
            return;
        }
        RestSessionPrincipal.resolve(ctx)
            .onFailure(err -> sendStatus(ctx, UNAUTHORIZED_401, "authentication required"))
            .onSuccess(caller -> CloudImageService.exists(id)
                .onFailure(err -> sendStatus(ctx, SERVICE_UNAVAILABLE_503, "image store unavailable"))
                // 200 = exists, 204 = does not: the contract the existing clients read.
                .onSuccess(exists -> ctx.response().setStatusCode(exists ? OK_200 : NO_CONTENT_204).end()));
    }

    // ─── /rest/images/upload ───────────────────────────────────────────────

    private void handleUpload(RoutingContext ctx) {
        List<FileUpload> fileUploads = ctx.fileUploads();
        if (fileUploads == null || fileUploads.size() != 1) {
            sendStatus(ctx, BAD_REQUEST_400, "exactly one file expected");
            return;
        }
        FileUpload fileUpload = fileUploads.get(0);
        // Every exit from here on must delete the temp file, so the work is wrapped and the
        // cleanup attached once at the end.
        validateAndUpload(ctx, fileUpload)
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    sendJson(ctx, OK_200, "{\"id\":\"" + escapeJson(ar.result()) + "\"}");
                } else {
                    RestFailure failure = RestFailure.of(ar.cause());
                    sendStatus(ctx, failure.status, failure.message);
                }
                fileUpload.delete();
            });
    }

    /**
     * Resolve the target key, authorise the caller for it, check the bytes, then store.
     *
     * @return the assigned storage key on success
     */
    private Future<String> validateAndUpload(RoutingContext ctx, FileUpload fileUpload) {
        if (fileUpload.size() > MAX_UPLOAD_BYTES)
            return RestFailure.fail(PAYLOAD_TO_LARGE_413, "image too large");

        String categoryParam = ctx.request().getParam("category");
        Long conversationId = parseLong(ctx.request().getParam("conversationId"));

        if (categoryParam != null) {
            // Server-named category: the client asks for a slot, we decide the name.
            ImageCategoryPolicy category = ImageCategoryPolicy.ofParameterName(categoryParam);
            if (category == null)
                return RestFailure.fail(BAD_REQUEST_400, "unknown category");
            if (category == ImageCategoryPolicy.CHATS && conversationId == null)
                return RestFailure.fail(BAD_REQUEST_400, "conversationId required");
            return authoriseUpload(ctx, category, conversationId)
                .compose(v -> checkImageBytes(fileUpload, category))
                .compose(v -> {
                    String assignedKey = category.generateKey(UUID.randomUUID().toString(), conversationId);
                    // What prevents an upload landing on an existing object is the freshly generated
                    // UUID, not this flag: the Bunny provider ignores `overwrite` and always PUTs.
                    // Passing false anyway states the intent, and would take effect if that changed.
                    return store(fileUpload, assignedKey, false);
                });
        }

        // Entity-keyed category: the client names the key, we verify it is well-formed, that the
        // row it refers to exists, and that the caller is staff.
        String id = ctx.request().getParam("id");
        ImageCategoryPolicy category = ImageCategoryPolicy.ofKey(id);
        if (category == null)
            return RestFailure.fail(BAD_REQUEST_400, "unrecognised image id");
        if (category.isServerNamed())
            return RestFailure.fail(BAD_REQUEST_400, "use ?category= for this image type");
        if (category.getUploadAccess() == ImageCategoryPolicy.Access.DENIED)
            return RestFailure.fail(FORBIDDEN_403, "this image type can no longer be uploaded");
        boolean overwrite = Booleans.parseBoolean(ctx.request().getParam("overwrite"));
        return authoriseUpload(ctx, category, category.extractOwnerId(id))
            .compose(v -> checkImageBytes(fileUpload, category))
            .compose(v -> store(fileUpload, id, overwrite));
    }

    /**
     * Apply the category's upload rule to the caller of this request.
     *
     * <p>A read-only session (a staff support view onto somebody else's front office) is refused
     * every write here. Those sessions are stopped at the SQL submit layer for database writes,
     * but an image lands on the CDN instead, so that guarantee does not reach this code by itself.
     */
    private Future<Void> authoriseUpload(RoutingContext ctx, ImageCategoryPolicy category, Long ownerId) {
        switch (category.getUploadAccess()) {
            case DENIED:
                return RestFailure.fail(FORBIDDEN_403, "this image type can no longer be uploaded");

            case ANONYMOUS:
                // No credential to check, so the brake is the rate limit.
                if (!VOLUNTEER_UPLOAD_LIMITER.tryAcquire(clientIp(ctx)))
                    return RestFailure.fail(TOO_MANY_REQUESTS_429, "too many uploads, try again later");
                return Future.succeededFuture();

            case CONVERSATION_MEMBER:
                return RestSessionPrincipal.resolve(ctx)
                    .recover(err -> RestFailure.fail(UNAUTHORIZED_401, "authentication required"))
                    .compose(caller -> {
                        if (!caller.isRegisteredUser() || caller.isReadOnly())
                            return RestFailure.fail(FORBIDDEN_403, "not permitted");
                        if (!CHAT_UPLOAD_LIMITER.tryAcquire(String.valueOf(caller.getPersonId())))
                            return RestFailure.fail(TOO_MANY_REQUESTS_429, "too many uploads, try again later");
                        if (caller.isStaff())
                            return requireEntityExists(category, ownerId);
                        // Not staff: the conversation must be the caller's own.
                        return requireOwnConversation(ownerId, caller.getPersonId());
                    });

            case STAFF:
            default:
                return RestSessionPrincipal.resolve(ctx)
                    .recover(err -> RestFailure.fail(UNAUTHORIZED_401, "authentication required"))
                    .compose(caller -> caller.isStaff() && !caller.isReadOnly()
                        ? requireEntityExists(category, ownerId)
                        : RestFailure.fail(FORBIDDEN_403, "not permitted"));
        }
    }

    /**
     * Confirm the row a picture claims to illustrate actually exists.
     *
     * <p>This is the ownership check at the depth the rest of the server currently enforces:
     * it stops a staff account writing pictures against ids that refer to nothing, and pins each
     * key to a real record. Narrowing it further to the caller's own organisation is the natural
     * next step and is recorded as a follow-up.
     */
    private Future<Void> requireEntityExists(ImageCategoryPolicy category, Long ownerId) {
        String entityName = category.getEntityName();
        if (entityName == null || ownerId == null)
            return Future.succeededFuture(); // Category carries no owning row.
        return EntityStore.create()
            .<Entity>executeQuery("select id from " + entityName + " where id=$1", ownerId)
            .compose(rows -> rows == null || rows.isEmpty()
                ? RestFailure.<Void>fail(BAD_REQUEST_400, entityName.toLowerCase() + " not found")
                : Future.succeededFuture())
            .recover(this::databaseFailure);
    }

    /** Confirm the conversation exists and belongs to the caller. */
    private Future<Void> requireOwnConversation(Long conversationId, Object callerPersonId) {
        if (conversationId == null || callerPersonId == null)
            return RestFailure.fail(FORBIDDEN_403, "not permitted");
        return EntityStore.create()
            .<Entity>executeQuery("select id, viewerPerson from Conversation where id=$1", conversationId)
            .compose(rows -> {
                // One answer for both "there is no such conversation" and "it is not yours".
                // Conversation ids are sequential, so telling the two apart would let any
                // logged-in user walk the range and map which conversations exist.
                if (rows == null || rows.isEmpty())
                    return RestFailure.<Void>fail(FORBIDDEN_403, "not permitted");
                Object viewerPersonId = Entities.getPrimaryKey(rows.get(0).getForeignEntityId("viewerPerson"));
                return sameId(viewerPersonId, callerPersonId)
                    ? Future.<Void>succeededFuture()
                    : RestFailure.<Void>fail(FORBIDDEN_403, "not permitted");
            })
            .recover(this::databaseFailure);
    }

    /** Store the uploaded bytes under the given key. */
    private Future<String> store(FileUpload fileUpload, String id, boolean overwrite) {
        java.io.File file = Path.of(fileUpload.uploadedFileName()).toFile();
        JreFile javaFile = new JreFile(file, fileUpload.contentType());
        return CloudImageService.upload(javaFile, id, overwrite)
            .map(v -> id)
            // A storage failure is a server-side failure and is reported as one. The endpoint
            // this replaces answered 204 here, which reads as success to anything checking res.ok.
            .recover(err -> {
                Console.log("[IMAGES] upload of '" + id + "' failed: " + err.getMessage());
                return RestFailure.fail(INTERNAL_SERVER_ERROR_500, "image store rejected the upload");
            });
    }

    /**
     * Confirm the bytes really are the picture format we accept.
     *
     * <p>The browsers convert to PNG before uploading, so the check is cheap; its purpose is that
     * the store cannot be used to host arbitrary files, and in particular not SVG, which can carry
     * script and would matter the day these images are served from a kadampa-branded hostname.
     */
    private Future<Void> checkImageBytes(FileUpload fileUpload, ImageCategoryPolicy category) {
        byte[] header = new byte[16];
        try (InputStream in = Files.newInputStream(Path.of(fileUpload.uploadedFileName()))) {
            int read = in.readNBytes(header, 0, header.length);
            if (read < header.length)
                return RestFailure.fail(UNSUPPORTED_MEDIA_TYPE_415, "not a supported image");
        } catch (IOException e) {
            return RestFailure.fail(INTERNAL_SERVER_ERROR_500, "could not read the upload");
        }
        if (isPng(header))
            return Future.succeededFuture();
        // Festival artwork is the one place a WEBP legitimately arrives.
        if (category == ImageCategoryPolicy.FESTIVALS && isWebp(header))
            return Future.succeededFuture();
        return RestFailure.fail(UNSUPPORTED_MEDIA_TYPE_415, "not a supported image");
    }

    // ─── /rest/images/delete ───────────────────────────────────────────────

    private void handleDelete(RoutingContext ctx) {
        String id = ctx.request().getParam("id");
        ImageCategoryPolicy category = ImageCategoryPolicy.ofKey(id);
        if (category == null) {
            sendStatus(ctx, BAD_REQUEST_400, "unrecognised image id");
            return;
        }
        if (category.getDeleteAccess() == ImageCategoryPolicy.Access.DENIED) {
            sendStatus(ctx, FORBIDDEN_403, "this image type can no longer be deleted");
            return;
        }
        boolean invalidate = Booleans.parseBoolean(ctx.request().getParam("invalidate"));
        // Every remaining category requires staff to delete: removing a picture is destructive and
        // has no anonymous use case, not even for the volunteer form.
        RestSessionPrincipal.resolve(ctx)
            .recover(err -> RestFailure.<RestSessionPrincipal.Caller>fail(UNAUTHORIZED_401, "authentication required"))
            .compose(caller -> caller.isStaff() && !caller.isReadOnly()
                ? CloudImageService.delete(id, invalidate)
                    .recover(err -> {
                        Console.log("[IMAGES] delete of '" + id + "' failed: " + err.getMessage());
                        return RestFailure.fail(INTERNAL_SERVER_ERROR_500, "image store rejected the delete");
                    })
                : RestFailure.<Void>fail(FORBIDDEN_403, "not permitted"))
            .onSuccess(v -> ctx.response().setStatusCode(OK_200).end())
            .onFailure(err -> {
                RestFailure failure = RestFailure.of(err);
                sendStatus(ctx, failure.status, failure.message);
            });
    }

    // ─── /rest/images/access-token ─────────────────────────────────────────

    /**
     * Issue a short-lived directory token for the protected {@code /volunteers/} prefix.
     *
     * <p>The token is what lets a back-office screen display applicant portraits once the CDN
     * requires one. It is granted to staff only, and it covers the whole prefix — a per-image
     * token would mean a round-trip per row in a list. Its scope is therefore "any volunteer
     * photograph, for the next hour", which matches how the back-office actually uses them and
     * is the same breadth as the screens themselves.
     */
    private void handleAccessToken(RoutingContext ctx) {
        if (pullZoneTokenKey == null) {
            sendStatus(ctx, SERVICE_UNAVAILABLE_503, "image access tokens are not configured");
            return;
        }
        RestSessionPrincipal.resolve(ctx)
            .onFailure(err -> sendStatus(ctx, UNAUTHORIZED_401, "authentication required"))
            .onSuccess(caller -> {
                if (!caller.isStaff()) {
                    sendStatus(ctx, FORBIDDEN_403, "not permitted");
                    return;
                }
                long expires = System.currentTimeMillis() / 1000 + ACCESS_TOKEN_TTL_SECONDS;
                String token = BunnyUrlTokenSigner.signDirectoryToken(pullZoneTokenKey, PROTECTED_TOKEN_PATH, expires);
                sendJson(ctx, OK_200, "{\"token\":\"" + escapeJson(token) + "\""
                    + ",\"expires\":" + expires
                    + ",\"tokenPath\":\"" + PROTECTED_TOKEN_PATH + "\"}");
            });
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    /** A failure carrying the HTTP status it should be reported as. */
    private static final class RestFailure extends RuntimeException {
        final int status;
        final String message;

        private RestFailure(int status, String message) {
            super(message, null, false, false); // no stack trace: these are expected outcomes
            this.status = status;
            this.message = message;
        }

        static <T> Future<T> fail(int status, String message) {
            return Future.failedFuture(new RestFailure(status, message));
        }

        /** Map any throwable to a reportable failure, defaulting to 500 for the unexpected ones. */
        static RestFailure of(Throwable t) {
            if (t instanceof RestFailure rf)
                return rf;
            Console.log("[IMAGES] unexpected failure: " + (t == null ? "null" : t.toString()));
            return new RestFailure(INTERNAL_SERVER_ERROR_500, "unexpected error");
        }
    }

    /** A database error must never read as "check passed". */
    private <T> Future<T> databaseFailure(Throwable error) {
        if (error instanceof RestFailure)
            return Future.failedFuture(error);
        Console.log("[IMAGES] database check failed: " + error.getMessage());
        return RestFailure.fail(INTERNAL_SERVER_ERROR_500, "could not verify the request");
    }

    /**
     * The caller's IP, preferring the first hop recorded by the load balancer.
     *
     * <p>{@code X-Forwarded-For} is client-settable when a request reaches the server directly,
     * so this is a rate-limiting key, not an identity — which is all it is used for.
     */
    private static String clientIp(RoutingContext ctx) {
        String forwarded = ctx.request().getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            int comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        return ctx.request().remoteAddress() == null ? "unknown" : ctx.request().remoteAddress().host();
    }

    /** Compare two primary keys that may arrive as Integer, Long or String. */
    private static boolean sameId(Object a, Object b) {
        return a != null && b != null && String.valueOf(a).equals(String.valueOf(b));
    }

    /**
     * A real PNG: the signature, followed immediately by a 13-byte {@code IHDR} chunk.
     *
     * <p>Checking the chunk header as well as the signature is what makes this more than a
     * formality — an eight-byte prefix is trivially prepended to arbitrary content, and the
     * anonymous volunteer path would otherwise accept any such file and hand back the exact
     * CDN address it was stored at. This does not make the store immune to a determined
     * polyglot (bytes can still follow a valid image), which is why the size cap and the
     * token-protected prefix matter alongside it.
     */
    private static boolean isPng(byte[] header) {
        if (!startsWith(header, PNG_MAGIC))
            return false;
        // Bytes 8-11 are the first chunk's length (IHDR is always 13), 12-15 its type.
        int ihdrLength = ((header[8] & 0xFF) << 24) | ((header[9] & 0xFF) << 16)
            | ((header[10] & 0xFF) << 8) | (header[11] & 0xFF);
        return ihdrLength == 13
            && header[12] == 'I' && header[13] == 'H' && header[14] == 'D' && header[15] == 'R';
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length)
            return false;
        for (int i = 0; i < prefix.length; i++)
            if (bytes[i] != prefix[i])
                return false;
        return true;
    }

    /** WEBP is "RIFF" + 4 size bytes + "WEBP". */
    private static boolean isWebp(byte[] header) {
        return header.length >= 12
            && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
            && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
    }

    private static Long parseLong(String s) {
        if (s == null)
            return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void sendJson(RoutingContext ctx, int statusCode, String body) {
        ctx.response()
            .putHeader("Content-Type", "application/json; charset=utf-8")
            .setStatusCode(statusCode)
            .end(body);
    }

    /** Error bodies are JSON too, so a client can show something useful without parsing prose. */
    private static void sendStatus(RoutingContext ctx, int statusCode, String message) {
        sendJson(ctx, statusCode, "{\"error\":\"" + escapeJson(message) + "\"}");
    }

    private static String escapeJson(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
