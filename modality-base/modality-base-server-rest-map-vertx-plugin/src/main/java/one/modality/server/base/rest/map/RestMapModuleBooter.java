package one.modality.server.base.rest.map;

import dev.webfx.platform.boot.spi.ApplicationModuleBooter;
import dev.webfx.platform.conf.SourcesConfig;
import dev.webfx.platform.util.http.HttpHeaders;
import dev.webfx.platform.util.vertx.VertxInstance;
import dev.webfx.stack.orm.entity.EntityStore;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import one.modality.base.shared.entities.Country;
import one.modality.base.shared.entities.Organization;

import static dev.webfx.platform.util.http.HttpResponseStatus.*;

/**
 * @author Bruno Salmon
 */
public class RestMapModuleBooter implements ApplicationModuleBooter {

    private WebClient webClient;

    @Override
    public String getModuleName() {
        return "modality-base-server-rest-organizationmap-vertx-plugin";
    }

    @Override
    public int getBootLevel() {
        return COMMUNICATION_REGISTER_BOOT_LEVEL;
    }

    @Override
    public void bootModule() {
        webClient = WebClient.create(VertxInstance.getVertx());
        Router router = VertxInstance.getHttpRouter();
        router.route(HttpMethod.GET, "/map/organization/:organizationId")
                .handler(ctx -> {
                    Integer organizationId = parseId(ctx, "organizationId");
                    if (organizationId == null)
                        return;
                    String zoom = parseZoom(ctx, "12");
                    if (zoom == null)
                        return;
                    EntityStore.create()
                            .<Organization>executeQuery("select latitude,longitude from Organization where id=$1", organizationId)
                            .onFailure(err -> ctx.response().setStatusCode(INTERNAL_SERVER_ERROR_500).send())
                            .onSuccess(list -> { // on successfully receiving the list (should be a singleton list)
                                Float latitude = null, longitude = null;
                                if (!list.isEmpty()) {
                                    Organization organization = list.get(0);
                                    latitude = organization.getLatitude();
                                    longitude = organization.getLongitude();
                                }
                                forwardToGoogleMap(latitude, longitude, zoom, ctx);
                            });
                });

        router.route(HttpMethod.GET, "/map/country/:countryId")
                .handler(ctx -> {
                    Integer countryId = parseId(ctx, "countryId");
                    if (countryId == null)
                        return;
                    String zoom = parseZoom(ctx, "5");
                    if (zoom == null)
                        return;
                    EntityStore.create()
                            .<Country>executeQuery("select latitude,longitude from Country where id=$1", countryId)
                            .onFailure(err -> ctx.response().setStatusCode(INTERNAL_SERVER_ERROR_500).send())
                            .onSuccess(list -> { // on successfully receiving the list (should be a singleton list)
                                Float latitude = null, longitude = null;
                                if (!list.isEmpty()) {
                                    Country country = list.get(0);
                                    latitude = country.getLatitude();
                                    longitude = country.getLongitude();
                                }
                                forwardToGoogleMap(latitude, longitude, zoom, ctx);
                            });
                });
    }

    // Returns null and answers 400 when the path parameter isn't a number. Without this an
    // unparsable id raised NumberFormatException inside the handler, answering 500 with a stack trace.
    private static Integer parseId(RoutingContext ctx, String pathParamName) {
        try {
            return Integer.parseInt(ctx.pathParam(pathParamName));
        } catch (NumberFormatException e) {
            ctx.response().setStatusCode(BAD_REQUEST_400).send();
            return null;
        }
    }

    // zoom is substituted into the outbound static-map URL, so anything other than a bare number
    // would inject extra query parameters into that request (e.g. a larger size=, which this
    // unauthenticated route would then bill to our own map-service key). Google's zoom range is 0-21.
    private static String parseZoom(RoutingContext ctx, String defaultZoom) {
        if (!ctx.queryParams().contains("zoom"))
            return defaultZoom;
        try {
            int zoomLevel = Integer.parseInt(ctx.queryParams().get("zoom"));
            if (zoomLevel < 0 || zoomLevel > 21)
                throw new NumberFormatException();
            // Re-render from the parsed int rather than returning the caller's own text: parseInt
            // also accepts forms that are numeric but not ASCII digits (a leading +, or Unicode
            // digits such as '٥'), which would otherwise reach the outbound URL verbatim.
            return String.valueOf(zoomLevel);
        } catch (NumberFormatException e) {
            ctx.response().setStatusCode(BAD_REQUEST_400).send();
            return null;
        }
    }

    private void forwardToGoogleMap(Float latitude, Float longitude, String zoom, RoutingContext ctx) {
        if (latitude == null || longitude == null) {
            ctx.response().setStatusCode(BAD_REQUEST_400).send();
            return;
        }
        String organizationStaticMapUrlTemplate = SourcesConfig.getSourcesRootConfig()
                .childConfigAt("modality.base.server.rest.organizationmap")
                .getString("organizationStaticMapUrl");
        String url = organizationStaticMapUrlTemplate
                .replace("{latitude}",  Float.toString(latitude))
                .replace("{longitude}", Float.toString(longitude))
                .replace("{zoom}", zoom)
                ;
        webClient.getAbs(url)
                .send()
                .onFailure(err -> ctx.response().setStatusCode(SERVICE_UNAVAILABLE_503).send())
                .onSuccess(proxyRes -> {
                    ctx.response()
                            .setStatusCode(proxyRes.statusCode())
                            .putHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                            .headers().addAll(proxyRes.headers());
                    ctx.response().send(proxyRes.body());
                });
    }
}
