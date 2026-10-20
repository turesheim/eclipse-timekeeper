package net.resheim.timekeeper.server;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;

/** Route templates and generated IDs only: never log query strings, credentials or account data. */
@Provider @Priority(Priorities.AUTHENTICATION - 100)
@org.jboss.resteasy.reactive.server.Cancellable(false)
public class RequestTimings implements ContainerRequestFilter, ContainerResponseFilter {
    static final String HEADER="X-Timekeeper-Request-Id", PROPERTY=RequestTimings.class.getName();
    private static final Logger LOG=Logger.getLogger(RequestTimings.class);
    @Inject jakarta.inject.Provider<io.vertx.core.Vertx> vertx;
    @Context ResourceInfo resource;
    @ConfigProperty(name="timekeeper.diagnostics.slow-request-ms",defaultValue="1000") long slowMillis;
    private record Timing(String id,String method,String route,long start,long allowance,long timer,AtomicBoolean finished) {}
    @Override public void filter(ContainerRequestContext request) {
        String route=route(resource);if(!route.startsWith("/api/v1"))return;
        String collection=request.getUriInfo().getPathParameters().getFirst("collection");
        if(Set.of("clients","projects","tasks","labels","activities","work-sessions").contains(collection==null?"":collection))route=route.replace("{collection}",collection);
        final String template=route;
        String method=Set.of("GET","POST","PUT","DELETE","PATCH","HEAD","OPTIONS").contains(request.getMethod())?request.getMethod():"OTHER";
        String id=UUID.randomUUID().toString();long start=System.nanoTime(),allowance=pollAllowance(route,request.getUriInfo().getQueryParameters().getFirst("waitSeconds"));
        var finished=new AtomicBoolean();
        long timer=vertx.get().setTimer(allowance+Math.max(5000,slowMillis),ignored->{
            if(!finished.get())LOG.warnf("API request still running request_id=%s method=%s route=%s elapsed_ms=%d",id,method,template,(System.nanoTime()-start)/1_000_000);
        });
        request.setProperty(PROPERTY,new Timing(id,method,route,start,allowance,timer,finished));
    }
    @Override public void filter(ContainerRequestContext request,ContainerResponseContext response) {
        if(!(request.getProperty(PROPERTY) instanceof Timing timing))return;
        timing.finished().set(true);vertx.get().cancelTimer(timing.timer());response.getHeaders().putSingle(HEADER,timing.id());
        long elapsed=(System.nanoTime()-timing.start())/1_000_000;
        if(response.getStatus()>=500 || elapsed>=timing.allowance()+Math.max(0,slowMillis))
            LOG.warnf("API request completed request_id=%s method=%s route=%s status=%d elapsed_ms=%d",timing.id(),timing.method(),timing.route(),response.getStatus(),elapsed);
    }
    static String route(ResourceInfo resource) {
        if(resource.getResourceClass()==null || resource.getResourceMethod()==null)return "";
        Path base=resource.getResourceClass().getAnnotation(Path.class),part=resource.getResourceMethod().getAnnotation(Path.class);
        return ((base==null?"":base.value())+"/"+(part==null?"":part.value())).replaceAll("/+","/").replaceAll("/$","");
    }
    static long pollAllowance(String route,String wait) {
        if(!route.equals("/api/v1/changes") || wait==null)return 0;
        try {int seconds=Integer.parseInt(wait);return seconds>=0&&seconds<=25?seconds*1000L:0;}
        catch(NumberFormatException invalid){return 0;}
    }
}
