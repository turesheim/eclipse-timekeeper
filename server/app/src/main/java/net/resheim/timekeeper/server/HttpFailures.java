package net.resheim.timekeeper.server;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/** Body-limit errors occur before JAX-RS; keep the API's problem representation. */
@ApplicationScoped
public class HttpFailures {
    static final long LIMIT=20L*1024*1024;
    void routes(@Observes Router router) {
        // Quarkus' Content-Length check ends the response directly, without failure handlers.
        router.route("/api/v1/*").order(Integer.MIN_VALUE).handler(context -> {
            String length=context.request().getHeader("Content-Length");
            if(length!=null) {
                try {if(Long.parseLong(length)>LIMIT) {tooLarge(context);return;}}
                catch(NumberFormatException ignored) { /* The HTTP decoder handles malformed lengths. */ }
            }
            context.next();
        });
        // Chunked uploads hit BodyHandler's failure path after the received-byte limit.
        router.route("/api/v1/*").order(1000).failureHandler(context -> {
            if(context.statusCode()==413) tooLarge(context);else context.next();
        });
    }
    private static void tooLarge(RoutingContext context) {
        var value=JsonValues.object();value.put("type","urn:timekeeper:problem:payload-too-large");value.put("title","PAYLOAD TOO LARGE");value.put("status",413);value.put("code","PAYLOAD_TOO_LARGE");
        context.response().setStatusCode(413).putHeader("Content-Type","application/problem+json").putHeader("Cache-Control","no-store").end(value.toString());
    }
}
