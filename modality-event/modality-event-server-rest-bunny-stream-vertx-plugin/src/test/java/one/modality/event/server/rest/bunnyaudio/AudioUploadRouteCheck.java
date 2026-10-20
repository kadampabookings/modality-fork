package one.modality.event.server.rest.bunnyaudio;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.Router;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Check for the upload route as the server builds it: that Vert.x accepts it at all — a BodyHandler after an
 * ordinary handler on one route is refused at boot, which once stopped staging from starting — and that the
 * admission check runs first and hands a request on to the body.
 *
 * <p>No test framework, as {@link AudioStorageNamesCheck} explains. Run by
 * {@code scripts/checks/bunny-audio/run.sh}.
 */
public class AudioUploadRouteCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("AudioUploadRouteCheck");
        Vertx vertx = Vertx.vertx();
        try {
            Router router = Router.router(vertx);
            Path uploads = Files.createTempDirectory("kbs-audio-route-check");
            boolean built;
            try {
                BunnyAudioRestService.registerRoutes(router, uploads);
                built = true;
            } catch (Throwable t) {
                System.out.println("    " + t);
                built = false;
            }
            check("Vert.x accepts the upload routes", built);
            if (!built)
                return;

            HttpServer server = vertx.createHttpServer().requestHandler(router).listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            HttpClient client = vertx.createHttpClient();
            int port = server.actualPort();

            String[] noTicket = post(client, port, null, "x", null);
            check("no ticket: 401 audio.ticket, before the body is read", "401".equals(noTicket[0]) && noTicket[1].contains("audio.ticket"));
            check("and nothing was written to the uploads folder", isEmpty(uploads));

            // Its own client: a request that declares 2 GiB and sends none of it leaves its connection unusable.
            HttpClient oneOff = vertx.createHttpClient();
            String[] tooLarge = post(oneOff, port, null, "x", String.valueOf(AudioStorageService.MAX_UPLOAD_BYTES * 2));
            check("a declared length over the limit: 413", "413".equals(tooLarge[0]));
            oneOff.close();

            BunnyStorageClient.Zone zone = new BunnyStorageClient.Zone("test-zone", "ZONE-PASSWORD-SECRET", "storage.bunnycdn.com",
                "test-zone.b-cdn.net", List.of("test-zone.b-cdn.net"));
            String ticket = AudioUploadTickets.issue(42, 1857, 1, "ittp/2026/eng/01.mp3", "mp3", zone, "ACCOUNT-KEY-SECRET");
            String[] admitted = post(client, port, ticket,
                "--B\r\nContent-Disposition: form-data; name=\"publish\"\r\n\r\ntrue\r\n--B--\r\n", null);
            check("a live ticket gets past admission to the body and the upload handler (400 audio.noFile without a file)",
                "400".equals(admitted[0]) && admitted[1].contains("audio.noFile"));
            String[] again = post(client, port, ticket, "x", null);
            check("the same ticket a second time: 401, it was spent at admission", "401".equals(again[0]));
            server.close();
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            System.out.println(pass + " passed, " + fail + " failed");
            if (fail > 0)
                System.exit(1);
        }
    }

    /** POSTs {@code body} to the upload path; {@code [status, body]}. */
    private static String[] post(HttpClient client, int port, String ticket, String body, String declaredLength) throws Exception {
        RequestOptions options = new RequestOptions().setMethod(HttpMethod.POST).setHost("localhost").setPort(port)
            .setURI(BunnyAudioRestService.UPLOAD_PATH).putHeader("Content-Type", "multipart/form-data; boundary=B");
        if (ticket != null)
            options.putHeader(BunnyAudioRestService.TICKET_HEADER, ticket);
        if (declaredLength != null)
            options.putHeader("Content-Length", declaredLength);
        return client.request(options)
            .compose(request -> declaredLength != null ? request.send() : request.send(Buffer.buffer(body)))
            .compose(response -> response.body().map(b -> new String[] { String.valueOf(response.statusCode()), b.toString() }))
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static boolean isEmpty(Path folder) throws Exception {
        try (var files = Files.list(folder)) {
            return files.findAny().isEmpty();
        }
    }
}
