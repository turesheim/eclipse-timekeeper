package net.resheim.timekeeper.server;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.FileSystemAccess;
import io.vertx.ext.web.handler.StaticHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Serves the pinned draw.io assets, either in an opaque iframe or on a dedicated host. */
@ApplicationScoped
class BundledDrawio {
    @Inject Vertx vertx;
    @Inject DrawioWeb web;
    @ConfigProperty(name = "timekeeper.web.drawio-port", defaultValue = "0")
    int port;
    @ConfigProperty(name = "timekeeper.web.drawio-bind", defaultValue = "127.0.0.1")
    String bind;
    @ConfigProperty(name = "timekeeper.web.drawio-root", defaultValue = "/opt/drawio")
    String root;
    static final String PATH = "/drawio/";
    static final String ENTRY = PATH + "index.html";
    private HttpServer server;

    Path assets() {
        return Path.of(root).toAbsolutePath();
    }

    boolean available() {
        return Files.isRegularFile(assets().resolve("index.html"));
    }

    void start(@Observes StartupEvent event) throws Exception {
        if (port == 0) return;
        String parent = web.parentOrigin();
        String editor = web.editorOrigin();
        if (parent == null || editor == null || URI.create(parent).getHost().equals(URI.create(editor).getHost())) {
            throw new IllegalArgumentException("Bundled draw.io requires separate HTTPS origins for Dagsverk and draw.io");
        }
        Path assets = assets();
        if (!available()) {
            throw new IllegalStateException("Bundled draw.io assets are missing from " + assets);
        }
        server = vertx.createHttpServer().requestHandler(routes(assets, parent, false))
                .listen(port, bind).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    Router routes(Path assets, String parent, boolean embedded) {
        Router router = Router.router(vertx);
        router.route().handler(context -> {
            // WebKit treats connections from a sandboxed document as cross-origin even for 'self'.
            String app = embedded ? web.appOrigin(context) : null;
            String source = embedded ? (app == null ? "'none'" : app + PATH) : "'self'";
            context.response()
                    .putHeader("Content-Security-Policy", "default-src 'none'; script-src " + source
                            + "; style-src " + source + " 'unsafe-inline'; img-src " + source + " data: blob:"
                            + "; font-src " + source + " data:; media-src " + source + " data: blob:"
                            + "; connect-src " + source + "; worker-src " + source + " blob:; manifest-src " + source
                            + "; base-uri 'none'; form-action 'none'; "
                            + "frame-ancestors " + parent + (embedded ? "; sandbox allow-scripts" : ""))
                    .putHeader("Referrer-Policy", "no-referrer")
                    .putHeader("X-Content-Type-Options", "nosniff");
            if (embedded) {
                // Only the bundled files are readable by the opaque frame. Do not grant credentialed CORS.
                context.response().putHeader("Access-Control-Allow-Origin", "null");
            }
            String path = context.normalizedPath().toUpperCase(Locale.ROOT);
            if (embedded && path.startsWith(PATH.toUpperCase(Locale.ROOT))) {
                path = path.substring(PATH.length() - 1);
            }
            if (path.equals("/WEB-INF") || path.startsWith("/WEB-INF/")
                    || path.equals("/META-INF") || path.startsWith("/META-INF/")) {
                context.response().setStatusCode(404).end();
            } else {
                context.next();
            }
        });
        var files = StaticHandler.create(FileSystemAccess.ROOT, assets.toString())
                .setIncludeHidden(false).setDirectoryListing(false).setMaxAgeSeconds(0);
        router.get().handler(files);
        router.head().handler(files);
        router.route().handler(context -> {
            HttpMethod method = context.request().method();
            if (method == HttpMethod.GET || method == HttpMethod.HEAD) {
                context.response().setStatusCode(404).end();
            } else {
                context.response().putHeader("Allow", "GET, HEAD").setStatusCode(405).end();
            }
        });
        return router;
    }

    void stop(@Observes ShutdownEvent event) {
        if (server != null) server.close();
    }
}
