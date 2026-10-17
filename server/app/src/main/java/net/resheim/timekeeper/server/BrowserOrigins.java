package net.resheim.timekeeper.server;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.List;

/** Explicit public origin for a TLS reverse proxy; forwarded headers are not trusted implicitly. */
@ApplicationScoped
public class BrowserOrigins {
    @ConfigProperty(name="timekeeper.auth.public-origin",defaultValue="NONE") String publicOrigin;
    boolean configured() {return !publicOrigin.equals("NONE");}
    /** Host cookies cross ports: require the configured page origin even on reads. */
    void cookie(UriInfo uri,List<String> origins,List<String> referers,boolean write) {
        if(!configured()) {check(uri,origins);return;}
        if(origins!=null && !origins.isEmpty()) {check(uri,origins);return;}
        if(write || referers==null || referers.size()!=1) throw new ApiFailure(403,"CSRF_FAILED");
        String origin;
        try {
            URI referer=URI.create(referers.getFirst());
            if(referer.getHost()==null || referer.getRawUserInfo()!=null || referer.getRawFragment()!=null)
                throw new IllegalArgumentException();
            origin=referer.getScheme()+"://"+referer.getRawAuthority();
        } catch(RuntimeException invalid) {throw new ApiFailure(403,"CSRF_FAILED");}
        check(uri,List.of(origin));
    }
    void check(UriInfo uri,List<String> origins) {
        if(origins==null || origins.isEmpty()) return;
        String expected=publicOrigin.equals("NONE") ? uri.getBaseUri().getScheme()+"://"+uri.getBaseUri().getRawAuthority() : publicOrigin;
        try {
            URI configured=URI.create(expected);
            boolean local=List.of("localhost","127.0.0.1","[::1]").contains(configured.getHost());
            if(configured.getRawAuthority()==null || configured.getUserInfo()!=null || configured.getRawQuery()!=null || configured.getRawFragment()!=null
                    || (configured.getRawPath()!=null && !configured.getRawPath().isEmpty())
                    || (!configured.getScheme().equals("https") && !(configured.getScheme().equals("http") && local))) throw new IllegalArgumentException();
        } catch(RuntimeException invalid) {throw new ApiFailure(503,"UNAVAILABLE");}
        if(origins.size()!=1 || !expected.equals(origins.getFirst())) throw new ApiFailure(403,"CSRF_FAILED");
    }
}
