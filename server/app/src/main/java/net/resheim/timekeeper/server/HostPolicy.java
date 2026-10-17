package net.resheim.timekeeper.server;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.vertx.http.runtime.filters.Filters;

/** One exact effective public hostname plus loopback, including static and health routes. */
@ApplicationScoped
class HostPolicy {
    @Inject BrowserOrigins origins;
    static boolean allowed(String authority,String publicOrigin) {
        try {
            if(authority==null || authority.isBlank() || !authority.equals(authority.strip())) return false;
            var uri=URI.create("http://"+authority);String host=uri.getHost();
            if(host==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || !uri.getRawPath().isEmpty() || uri.getPort()==0 || uri.getPort()>65535) return false;
            host=host.toLowerCase(Locale.ROOT);
            if(List.of("localhost","127.0.0.1","[::1]").contains(host)) return true;
            return publicOrigin!=null && !publicOrigin.equals("NONE") && host.equals(URI.create(publicOrigin).getHost().toLowerCase(Locale.ROOT));
        }catch(IllegalArgumentException invalid) {return false;}
    }
    void register(@Observes Filters filters) {
        filters.register(context->{
            String authority=context.request().getHeader("Host");
            if(authority==null && context.request().authority()!=null) authority=context.request().authority().toString();
            if(context.request().headers().getAll("Host").size()>1 || !allowed(authority,origins.currentOrigin())) {
                context.response().setStatusCode(400).putHeader("Content-Type","application/problem+json").putHeader("Cache-Control","no-store")
                        .end("{\"type\":\"urn:timekeeper:problem:bad-request\",\"title\":\"BAD REQUEST\",\"status\":400,\"code\":\"BAD_REQUEST\"}");return;
            }
            context.next();
        },1000);
    }
}
