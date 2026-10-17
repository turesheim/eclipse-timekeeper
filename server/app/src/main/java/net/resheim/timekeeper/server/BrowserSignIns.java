package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.net.URI;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** One browser authority shared by password, setup/recovery, token and passkey sign-in. */
@ApplicationScoped
class BrowserSignIns {
    @Inject AccountStore accounts;
    @Inject PasskeyRealm realm;
    enum Method { PASSWORD, SETUP, RECOVERY, TOKEN }
    record Attempt(Method method,PasskeyCeremonies.Scope scope,String nonce) {
        @Override public String toString() {return "Browser sign-in attempt ("+method+")";}
    }
    private record Decision(Attempt attempt,ApiFailure failure) { }
    /** Reserve before password hashing, so a later successful sign-in fences a slow earlier request. */
    Attempt begin(Method method,String browser,String cookie,String peer) {
        Objects.requireNonNull(method);
        var decision=accounts.tx(c->{lock(c);var current=realm.current(c);
            // Existing HTTP loopback development has no WebAuthn ceremonies.
            if(current.origin()==null) return new Decision(new Attempt(method,null,null),null);
            var scope=new PasskeyCeremonies.Scope(browser,cookie,current.origin(),URI.create(current.origin()).getHost(),current.epoch());
            var now=Instant.now();
            boolean global=attempt(c,"browser-sign-in:global",120,Duration.ofMinutes(1),now);
            boolean client=attempt(c,"browser-sign-in:browser:"+scope.browser(),20,Duration.ofMinutes(1),now);
            boolean source=attempt(c,"browser-sign-in:peer:"+peer,60,Duration.ofMinutes(1),now);
            if(!global || !client || !source) return new Decision(null,new ApiFailure(429,"RATE_LIMITED"));
            try {
                var pending=new PasskeyCeremonies(c).create("LOGIN",null,scope,object().put("method",method.name()),now);
                return new Decision(new Attempt(method,scope,pending.secret()),null);
            }catch(ApiFailure failure) {if(failure.status==429) return new Decision(null,failure);throw failure;}
        });
        if(decision.failure()!=null) throw decision.failure();return decision.attempt();
    }
    AccountStore.SessionIssuer issuer(Attempt attempt) {
        Objects.requireNonNull(attempt);
        return (c,owner,permissions,remember,now,parent)->finish(c,attempt,owner,permissions,remember,now,parent);
    }
    /** Called inside the account mutation's global-locked transaction, never after issuing a session. */
    private AccountStore.Issued finish(Connection c,Attempt attempt,String owner,Set<String> permissions,boolean remember,Instant now,String parent) {
        var current=realm.current(c);
        if(attempt.scope()==null) {
            if(current.origin()!=null) throw ApiFailure.unauthorized(false);
            return accounts.issue(c,owner,permissions,remember,now,parent);
        }
        var scope=attempt.scope();
        if(!scope.origin().equals(current.origin()) || scope.epoch()!=current.epoch()) throw ApiFailure.unauthorized(false);
        var pending=new PasskeyCeremonies(c).claim(attempt.nonce(),"LOGIN",null,scope,now);
        if(!pending.data().path("method").asText().equals(attempt.method().name())) throw ApiFailure.unauthorized(false);
        return issueBound(c,scope,owner,permissions,remember,now,parent,null);
    }
    AccountStore.Issued issueBound(Connection c,PasskeyCeremonies.Scope scope,String owner,Set<String> permissions,boolean remember,Instant now,String parent,UUID passkey) {
        var current=realm.current(c);
        if(!scope.origin().equals(current.origin()) || scope.epoch()!=current.epoch()) throw ApiFailure.unauthorized(false);
        new PasskeyCeremonies(c).cancelBrowser(scope);
        execute(c,"DELETE FROM tk_auth_session WHERE browser_digest=? OR digest=?",scope.browser(),scope.session());
        var issued=accounts.issue(c,owner,permissions,remember,now,parent);
        execute(c,"UPDATE tk_auth_session SET origin_epoch=?,browser_digest=?,passkey_id=? WHERE digest=? AND owner_id=?",scope.epoch(),scope.browser(),passkey,hash(issued.secret()),owner);
        return issued;
    }
    void cancel(String browser,String cookie,String ceremony) {
        accounts.tx(c->{lock(c);var current=realm.current(c);
            if(current.origin()!=null) new PasskeyCeremonies(c).cancel(new PasskeyCeremonies.Scope(browser,cookie,current.origin(),URI.create(current.origin()).getHost(),current.epoch()),ceremony);
            return null;
        });
    }
    void logout(TokenRegistry.Principal principal,String browser,String cookie) {
        accounts.tx(c->{lock(c);accounts.guard(c,principal);var current=realm.current(c);
            if(current.origin()!=null) new PasskeyCeremonies(c).cancelBrowser(new PasskeyCeremonies.Scope(browser,cookie,current.origin(),URI.create(current.origin()).getHost(),current.epoch()));
            if(principal.kind().equals("SESSION")) execute(c,"DELETE FROM tk_auth_session WHERE digest=? AND owner_id=?",principal.digest(),principal.owner());return null;
        });
    }
}
