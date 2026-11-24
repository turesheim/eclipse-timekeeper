package net.resheim.timekeeper.server;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.Locale;

/** Web-only configuration for the editor frame. */
@ApplicationScoped
class DrawioWeb {
    @ConfigProperty(name = "timekeeper.web.content-security-policy")
    String policy;
    @ConfigProperty(name = "timekeeper.web.drawio-origin")
    String configured;
    @ConfigProperty(name = "timekeeper.auth.public-origin")
    String publicOrigin;

    static String validateOrigin(String value) {
        if ("NONE".equals(value)) return null;
        var uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || !uri.getRawPath().isEmpty() || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("The draw.io editor requires an exact HTTPS origin");
        }
        var authority = uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        if (!authority.equals(uri.getRawAuthority())) {
            throw new IllegalArgumentException("The draw.io editor requires an exact HTTPS origin");
        }
        return "https://" + uri.getHost().toLowerCase(Locale.ROOT)
                + (uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort());
    }

    String editorOrigin() { return validateOrigin(configured); }

    String parentOrigin() { return validateOrigin(publicOrigin); }

    void routes(@Observes Router router) {
        String origin = editorOrigin();
        if (origin != null && !"NONE".equals(publicOrigin)
                && URI.create(origin).getHost().equalsIgnoreCase(URI.create(publicOrigin).getHost())) {
            throw new IllegalArgumentException("The draw.io editor requires a dedicated host");
        }
        String editorPolicy = origin == null ? policy : policy + "; frame-src " + origin;
        router.routeWithRegex("/(?:web|admin)(?:/.*)?").order(-1000).handler(context -> {
            String path = context.normalizedPath();
            boolean web = path.equals("/web") || path.equals("/web/") || path.equals("/web/index.html");
            context.response().putHeader("Content-Security-Policy", web ? editorPolicy : policy);
            context.next();
        });
        router.get("/web/drawio-origin").order(-100).handler(context -> {
            context.response().putHeader("Cache-Control", "no-store")
                    .putHeader("Content-Type", "text/plain; charset=utf-8")
                    .end(origin == null ? "" : origin);
        });
    }
}
