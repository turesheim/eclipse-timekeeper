package net.resheim.timekeeper.server;

import java.time.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/** Password and passkey replies share the same session, CSRF and cookie policy. */
@ApplicationScoped
class BrowserSessions {
    @Inject AccountStore accounts;
    @Inject RequestIdentity identity;
    @Inject BrowserOrigins origins;
    Response issued(AccountStore.Issued issued,BrowserCookies.Values cookies) {
        var principal=origins.configured() ? accounts.session(issued.secret(),cookies.requireBrowser()) : accounts.session(issued.secret());
        identity.set(principal);var value=accounts.context(principal);
        String cookie=AuthenticationFilter.COOKIE+"="+issued.secret()+"; Path=/; HttpOnly; Secure; SameSite=Lax";
        if(issued.persistent()) cookie+="; Max-Age="+Math.max(0,Duration.between(Instant.now(),issued.expires()).toSeconds());
        var reply=Response.ok(value).header("Cache-Control","no-store").header("Set-Cookie",cookie);
        if(origins.configured()) reply.header("Set-Cookie",BrowserCookies.bindingCookie(cookies.requireBrowser()));return reply.build();
    }
    Response.ResponseBuilder clear(Response.ResponseBuilder reply) {
        reply.header("Set-Cookie",AuthenticationFilter.COOKIE+"=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0");
        if(origins.configured()) reply.header("Set-Cookie",BrowserCookies.clearBindingCookie());return reply;
    }
}
