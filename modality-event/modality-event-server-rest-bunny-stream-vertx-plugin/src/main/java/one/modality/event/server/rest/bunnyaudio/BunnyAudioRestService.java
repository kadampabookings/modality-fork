package one.modality.event.server.rest.bunnyaudio;

import dev.webfx.platform.boot.spi.ApplicationModuleBooter;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Numbers;
import dev.webfx.platform.util.vertx.VertxInstance;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * {@code POST /rest/audio/upload}: one audio recording, from the back office to the organisation's Bunny zone.
 *
 * <p>The request is a multipart form with the file ({@code file}) and four fields — {@code durationMillis}
 * (read by the browser from the file it is sending), {@code publish}, {@code overwrite} and
 * {@code deletePrevious} — plus the {@value #TICKET_HEADER} header. The ticket is the whole of the
 * authorisation: {@code prepareAudioUploads} issued it, on the bus, to a caller who may reach {@code /medias}
 * for that event, and it names the slot and the storage path. Nothing in the request can change either.
 *
 * <p><b>Turned away before the body is read</b> when the declared length is over the limit or the ticket is
 * not live — and the ticket is spent right there, so one ticket admits one body: a caller cannot make this
 * server write hundreds of megabytes to disk without one, or several times with one, and a ticket that lapses
 * while a long transfer is under way does not turn the finished upload away. Then, once read: the bytes must be
 * MP3 or M4A ({@link AudioFileSniffer}) and of the format the name declared, within
 * {@link AudioStorageService#MAX_UPLOAD_BYTES}.
 *
 * <p>The file is written to a temporary directory by Vert.x and deleted when the response ends; it reaches
 * Bunny with a SHA-256 checksum Bunny verifies. The replies are JSON: {@code 200 {mediaId, url, …}}, or an
 * error status with {@code {"error": "<code>"}} the back office translates.
 *
 * @author Claude Code
 */
public final class BunnyAudioRestService implements ApplicationModuleBooter {

    static final String UPLOAD_PATH = "/rest/audio/upload";
    /** Mirrored in the back office's {@code AUDIO_UPLOAD_TICKET_HEADER}. */
    static final String TICKET_HEADER = "X-KBS-Upload-Ticket";
    /** Room for the multipart boundaries and the four small fields around the file. */
    private static final long FORM_OVERHEAD_BYTES = 64 * 1024;
    static final String BAD_TICKET = "audio.ticket";
    /** Where {@link #admit} leaves the spent ticket for {@link #handleUpload}. */
    private static final String TICKET_KEY = "kbs.audio.ticket";
    private static final String NO_FILE = "audio.noFile";
    private static final String FAILED = "audio.failed";

    @Override
    public String getModuleName() {
        return "kbs-server-bunnyaudio-plugin";
    }

    @Override
    public int getBootLevel() {
        return COMMUNICATION_REGISTER_BOOT_LEVEL;
    }

    @Override
    public void bootModule() {
        // Permanent try/catch, for the reason BunnyStreamRestService gives: a throw here would fail the
        // verticle silently and leave the server without its HTTP port.
        try {
            Path uploads = Path.of(System.getProperty("java.io.tmpdir"), "kbs-audio-uploads");
            Files.createDirectories(uploads);
            registerRoutes(VertxInstance.getHttpRouter(), uploads);
            Console.log("[BUNNY-AUDIO] REST route registered: " + UPLOAD_PATH + " (uploads in " + uploads + ")");
        } catch (Throwable t) {
            System.err.println("[BUNNY-AUDIO] bootModule FAILED: " + t);
            t.printStackTrace(System.err);
            Console.error("[BUNNY-AUDIO] bootModule FAILED", t);
            throw new RuntimeException(t);
        }
    }

    /**
     * The upload's two routes, in this order. The admission check is a route of its own, ahead of the one that
     * reads the body: Vert.x refuses a BodyHandler placed after an ordinary handler on ONE route ("Cannot add
     * [BODY] handler to route with [USER] handler"), and that refusal, thrown at boot, stops the server before
     * it opens its port. {@link #admit} passes a request on with {@code next()}, to the body route.
     */
    static void registerRoutes(Router router, Path uploads) {
        router.post(UPLOAD_PATH).handler(BunnyAudioRestService::admit);
        router.post(UPLOAD_PATH)
            .handler(BodyHandler.create()
                .setBodyLimit(AudioStorageService.MAX_UPLOAD_BYTES + FORM_OVERHEAD_BYTES)
                .setHandleFileUploads(true)
                .setUploadsDirectory(uploads.toString())
                .setDeleteUploadedFilesOnEnd(true))
            .handler(BunnyAudioRestService::handleUpload);
    }

    /** Before the body: a plausible length and a live ticket, spent here, or the request goes no further. */
    private static void admit(RoutingContext ctx) {
        Long declared = Numbers.toLong(ctx.request().getHeader("Content-Length"));
        if (declared != null && declared > AudioStorageService.MAX_UPLOAD_BYTES + FORM_OVERHEAD_BYTES) {
            sendError(ctx, 413, AudioStorageService.TOO_LARGE);
            return;
        }
        AudioUploadTickets.Ticket ticket = AudioUploadTickets.consume(ctx.request().getHeader(TICKET_HEADER));
        if (ticket == null) {
            sendError(ctx, 401, BAD_TICKET);
            return;
        }
        ctx.put(TICKET_KEY, ticket);
        ctx.next();
    }

    private static void handleUpload(RoutingContext ctx) {
        AudioUploadTickets.Ticket ticket = ctx.get(TICKET_KEY);
        if (ticket == null) {
            sendError(ctx, 401, BAD_TICKET);
            return;
        }
        List<FileUpload> files = ctx.fileUploads();
        if (files == null || files.size() != 1) {
            sendError(ctx, 400, NO_FILE);
            return;
        }
        FileUpload upload = files.get(0);
        if (upload.size() <= 0) {
            sendError(ctx, 400, AudioStorageService.EMPTY_FILE);
            return;
        }
        if (upload.size() > AudioStorageService.MAX_UPLOAD_BYTES) {
            sendError(ctx, 413, AudioStorageService.TOO_LARGE);
            return;
        }
        Path file = Path.of(upload.uploadedFileName());
        Long durationMillis = Numbers.toLong(ctx.request().getFormAttribute("durationMillis"));
        boolean publish = "true".equals(ctx.request().getFormAttribute("publish"));
        boolean overwrite = "true".equals(ctx.request().getFormAttribute("overwrite"));
        boolean deletePrevious = "true".equals(ctx.request().getFormAttribute("deletePrevious"));
        // Reading the head and hashing a few hundred megabytes is blocking work: off the event loop, and unordered
        // — the default would queue every upload's hash behind the others'.
        VertxInstance.getVertx().executeBlocking(() -> new String[] { AudioFileSniffer.detect(file), sha256UpperHex(file) }, false)
            .onComplete(ar -> {
                if (ar.failed()) {
                    Console.error("[BUNNY-AUDIO] could not read the uploaded file", ar.cause());
                    sendError(ctx, 500, FAILED);
                    return;
                }
                String format = ar.result()[0];
                if (format == null) {
                    sendError(ctx, 415, AudioStorageService.UNSUPPORTED_TYPE);
                    return;
                }
                AudioStorageService.storeUpload(ticket, file.toFile(), format, ar.result()[1], durationMillis, publish,
                        overwrite, deletePrevious)
                    .onSuccess(json -> sendJson(ctx, 200, json))
                    .onFailure(err -> {
                        if (err instanceof AudioStorageService.UploadRefusal refusal) {
                            sendError(ctx, refusal.status, refusal.code);
                        } else {
                            Console.error("[BUNNY-AUDIO] upload to " + ticket.storagePath() + " failed", err);
                            sendError(ctx, 502, AudioStorageService.codeOf(err, FAILED));
                        }
                    });
            });
    }

    /** The file's SHA-256 in upper-case hex, the form Bunny's {@code Checksum} header takes. */
    static String sha256UpperHex(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; )
                digest.update(buffer, 0, n);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest.digest())
                sb.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                    .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // mandated by the JDK; unreachable
        }
    }

    private static void sendJson(RoutingContext ctx, int status, String body) {
        HttpServerResponse response = ctx.response();
        if (response.ended())
            return;
        response.putHeader("Content-Type", "application/json; charset=utf-8");
        response.putHeader("Cache-Control", "no-store");
        response.setStatusCode(status).end(body);
    }

    private static void sendError(RoutingContext ctx, int status, String code) {
        sendJson(ctx, status, "{\"error\":\"" + code + "\"}"); // codes are constants: nothing to escape
    }
}
