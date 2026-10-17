package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.io.IOException;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.data.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Shared transaction boundary for enrollment, fresh proof, sign-in and revocation. */
@ApplicationScoped
class PasskeyStore {
    @Inject AccountStore accounts;
    @Inject PasskeyRealm realm;
    @Inject BrowserSignIns browserSignIns;
    private record Decision<T>(T value,ApiFailure failure) { }
    private static <T> Decision<T> accepted(T value) {return new Decision<>(value,null);}
    private static <T> Decision<T> rejected(int status,String code) {return new Decision<>(null,new ApiFailure(status,code));}
    private <T> T tx(Function<Connection,Decision<T>> work) {
        var result=accounts.tx(c->{lock(c);realm.current(c);return work.apply(c);});
        // Expected crypto/password failures must commit consumption and throttles.
        if(result.failure()!=null) throw result.failure();return result.value();
    }
    private static boolean rate(Connection c,String kind,String browser,String peer,Instant now) {
        boolean global=attempt(c,"passkey:"+kind+":global",120,Duration.ofMinutes(1),now);
        boolean client=attempt(c,"passkey:"+kind+":browser:"+browser,20,Duration.ofMinutes(1),now);
        boolean source=attempt(c,"passkey:"+kind+":peer:"+peer,60,Duration.ofMinutes(1),now);return global && client && source;
    }
    private ObjectNode manager(Connection c,TokenRegistry.Principal principal) {
        accounts.guard(c,principal);
        if(!accountSession(c,principal)) throw new ApiFailure(403,"PASSKEY_ACCOUNT_SIGN_IN_REQUIRED");
        return account(c,principal.owner(),false);
    }
    private PasskeyCeremonies.Scope scope(Connection c,TokenRegistry.Principal principal,String browser,String cookie) {
        var scope=realm.scope(c,browser,cookie);
        if(!principal.digest().equals(scope.session())) throw new ApiFailure(403,"PASSKEY_PROOF_REQUIRED");return scope;
    }
    private static String text(JsonNode input,String field,int limit,boolean optional) {
        var value=input.path(field);if(optional && value.isMissingNode()) return null;
        if(!value.isTextual() || value.asText().length()>limit || value.asText().indexOf(0)>=0) throw ApiFailure.validation();return value.asText();
    }
    private static String response(JsonNode input) {var value=input.path("credential");if(!value.isObject() || value.toString().length()>98304) throw ApiFailure.validation();return value.toString();}
    private static ObjectNode managementClaim(Connection c,String token,String purpose,String owner,PasskeyCeremonies.Scope scope,Instant now) {
        try {return new PasskeyCeremonies(c).claim(token,purpose,owner,scope,now).data();}
        catch(ApiFailure invalid) {if(invalid.status==401) return null;throw invalid;}
    }
    private static WebAuthnProtocol protocol(PasskeyCeremonies.Scope scope,PasskeyCredentials credentials) {return new WebAuthnProtocol(scope.rpId(),scope.origin(),credentials);}
    private static ObjectNode action(JsonNode input,PasskeyCredentials credentials,String owner) {
        String action=text(input,"action",16,false);var value=object().put("action",action);
        if(action.equals("REMOVE")) {
            var id=ContractInputs.pathId(text(input,"id",36,false));var target=credentials.owned(owner,id);String tag=text(input,"etag",100,false);ApiReplies.match(target.management(),tag);
            value.put("id",id.toString()).put("etag",tag);
        } else if(!action.equals("ADD") || input.has("id") || input.has("etag")) throw ApiFailure.validation();
        return value;
    }
    private boolean proof(Connection c,TokenRegistry.Principal principal,ObjectNode account,PasskeyCeremonies.Scope scope,
            ObjectNode action,JsonNode input,ObjectNode evidence,Instant now) {
        String password=text(input,"currentPassword",1024,true),proof=text(input,"proof",100,true);
        if((password==null)==(proof==null)) return false;
        if(!attempt(c,"passkey:proof:"+principal.owner(),5,Duration.ofMinutes(5),now)) return false;
        if(password!=null) return currentPassword(c,principal.owner(),password);
        try {
            var pending=new PasskeyCeremonies(c).claim(proof,"PROOF",principal.owner(),scope,now);var data=pending.data();
            if(!data.path("action").equals(action) || data.path("accountVersion").asLong()!=account.path("version").asLong()) return false;
            var source=new PasskeyCredentials(c,scope.rpId()).owned(principal.owner(),ContractInputs.pathId(data.path("sourceCredential").asText()));
            if(!source.rpId().equals(scope.rpId())) return false;evidence.put("sourceCredential",source.id().toString());return true;
        }catch(ApiFailure invalid) {if(invalid.status==401 || invalid.status==404) return false;throw invalid;}
    }
    ObjectNode registerOptions(TokenRegistry.Principal principal,String browser,String cookie,String peer,JsonNode input) {
        String name=displayName(text(input,"name",400,false));
        return tx(c->{var account=manager(c,principal);var scope=scope(c,principal,browser,cookie);var now=Instant.now();
            if(!rate(c,"management",scope.browser(),peer,now)) return rejected(429,"RATE_LIMITED");
            var data=object().put("name",name).put("accountVersion",account.path("version").asLong());
            if(!proof(c,principal,account,scope,object().put("action","ADD"),input,data,now)) return rejected(403,"PASSKEY_PROOF_REQUIRED");
            var credentials=new PasskeyCredentials(c,scope.rpId());
            try {
                var options=protocol(scope,credentials).registration(UserIdentity.builder().name(account.path("username").asText()).displayName(account.path("displayName").asText()).id(credentials.handle(principal.owner())).build());
                data.put("request",options.toJson());var pending=new PasskeyCeremonies(c).create("REGISTER",principal.owner(),scope,data,now);
                var reply=object().put("ceremony",pending.secret());reply.set("publicKey",parse(options.toCredentialsCreateJson()).path("publicKey"));return accepted(reply);
            }catch(IOException failure) {throw new ApiFailure(503,"UNAVAILABLE");}
        });
    }
    ObjectNode register(TokenRegistry.Principal principal,String browser,String cookie,String peer,JsonNode input) {
        String ceremony=text(input,"ceremony",100,false),response=response(input);
        return tx(c->{var account=manager(c,principal);var scope=scope(c,principal,browser,cookie);var now=Instant.now();
            if(!rate(c,"finish",scope.browser(),peer,now)) return rejected(429,"RATE_LIMITED");
            var data=managementClaim(c,ceremony,"REGISTER",principal.owner(),scope,now);if(data==null) return rejected(403,"PASSKEY_REJECTED");
            if(data.path("accountVersion").asLong()!=account.path("version").asLong()) return rejected(403,"PASSKEY_PROOF_REQUIRED");
            var credentials=new PasskeyCredentials(c,scope.rpId());
            if(data.has("sourceCredential")) {
                try {if(!credentials.owned(principal.owner(),ContractInputs.pathId(data.path("sourceCredential").asText())).rpId().equals(scope.rpId())) return rejected(403,"PASSKEY_PROOF_REQUIRED");}
                catch(ApiFailure missing) {if(missing.status==404) return rejected(403,"PASSKEY_PROOF_REQUIRED");throw missing;}
            }
            try {
                var registration=protocol(scope,credentials).register(PublicKeyCredentialCreationOptions.fromJson(data.path("request").asText()),response);
                return accepted(credentials.add(principal.owner(),data.path("name").asText(),registration,now).management());
            }catch(WebAuthnProtocol.Rejected rejected) {return rejected(403,"PASSKEY_REJECTED");}
            catch(ApiFailure failure) {if(failure.status==409 || failure.status==422) return rejected(failure.status,"PASSKEY_REJECTED");throw failure;}
            catch(IOException failure) {throw new ApiFailure(503,"UNAVAILABLE");}
        });
    }
    ObjectNode loginOptions(String browser,String cookie,String peer,boolean remember) {
        return tx(c->{var scope=realm.scope(c,browser,cookie);var now=Instant.now();
            if(!rate(c,"options",scope.browser(),peer,now)) return rejected(429,"RATE_LIMITED");
            try {
                var options=protocol(scope,new PasskeyCredentials(c,scope.rpId())).assertion(Optional.empty());
                var data=object().put("request",options.toJson()).put("remember",remember).put("method","PASSKEY");var pending=new PasskeyCeremonies(c).create("LOGIN",null,scope,data,now);
                var reply=object().put("ceremony",pending.secret());reply.set("publicKey",parse(options.toCredentialsGetJson()).path("publicKey"));return accepted(reply);
            }catch(IOException failure) {throw new ApiFailure(503,"UNAVAILABLE");}
        });
    }
    AccountStore.Issued login(String browser,String cookie,String peer,JsonNode input) {
        String ceremony=text(input,"ceremony",100,false),response=response(input);
        return tx(c->{var scope=realm.scope(c,browser,cookie);var now=Instant.now();
            if(!rate(c,"finish",scope.browser(),peer,now)) return rejected(429,"RATE_LIMITED");
            ObjectNode data;
            try {data=new PasskeyCeremonies(c).claim(ceremony,"LOGIN",null,scope,now).data();}
            catch(ApiFailure invalid) {if(invalid.status==401) return rejected(401,"PASSKEY_REJECTED");throw invalid;}
            if(!data.path("method").asText().equals("PASSKEY")) return rejected(401,"PASSKEY_REJECTED");
            var credentials=new PasskeyCredentials(c,scope.rpId());
            try {
                var assertion=protocol(scope,credentials).authenticate(AssertionRequest.fromJson(data.path("request").asText()),response);
                var key=credentials.used(assertion,now);
                return accepted(browserSignIns.issueBound(c,scope,key.owner(),Set.of("read","write"),data.path("remember").asBoolean(),now,null,key.id()));
            }catch(WebAuthnProtocol.Rejected failure) {return rejected(401,"PASSKEY_REJECTED");}
            catch(IOException failure) {throw new ApiFailure(503,"UNAVAILABLE");}
        });
    }
    ObjectNode managementOptions(TokenRegistry.Principal principal,String browser,String cookie,String peer,JsonNode input) {
        return tx(c->{var account=manager(c,principal);var scope=scope(c,principal,browser,cookie);var now=Instant.now();
            if(!rate(c,"management",scope.browser(),peer,now)) return rejected(429,"RATE_LIMITED");
            var credentials=new PasskeyCredentials(c,scope.rpId());var data=object().put("accountVersion",account.path("version").asLong());data.set("action",action(input,credentials,principal.owner()));
            try {
                var options=protocol(scope,credentials).assertion(Optional.of(account.path("username").asText()));data.put("request",options.toJson());
                var pending=new PasskeyCeremonies(c).create("MANAGE",principal.owner(),scope,data,now);var reply=object().put("ceremony",pending.secret());reply.set("publicKey",parse(options.toCredentialsGetJson()).path("publicKey"));return accepted(reply);
            }catch(IOException failure) {throw new ApiFailure(503,"UNAVAILABLE");}
        });
    }
    ObjectNode managementProof(TokenRegistry.Principal principal,String browser,String cookie,String peer,JsonNode input) {
        String ceremony=text(input,"ceremony",100,false),response=response(input);
        return tx(c->{var account=manager(c,principal);var scope=scope(c,principal,browser,cookie);var now=Instant.now();
            if(!rate(c,"finish",scope.browser(),peer,now)) return rejected(429,"RATE_LIMITED");
            var flows=new PasskeyCeremonies(c);var data=managementClaim(c,ceremony,"MANAGE",principal.owner(),scope,now);if(data==null) return rejected(403,"PASSKEY_REJECTED");
            if(data.path("accountVersion").asLong()!=account.path("version").asLong()) return rejected(403,"PASSKEY_PROOF_REQUIRED");
            var credentials=new PasskeyCredentials(c,scope.rpId());
            try {
                var assertion=protocol(scope,credentials).authenticate(AssertionRequest.fromJson(data.path("request").asText()),response);
                var key=credentials.credential(assertion.result().getCredentialId(),true).orElseThrow(()->ApiFailure.unauthorized(false));
                if(!key.owner().equals(principal.owner())) return rejected(403,"PASSKEY_REJECTED");
                credentials.used(assertion,now);var proof=object().put("accountVersion",account.path("version").asLong()).put("sourceCredential",key.id().toString());proof.set("action",data.path("action"));
                var pending=flows.create("PROOF",principal.owner(),scope,proof,now);return accepted(object().put("proof",pending.secret()).put("expiresAt",pending.expires().toString()));
            }catch(WebAuthnProtocol.Rejected failure) {return rejected(403,"PASSKEY_REJECTED");}
            catch(IOException failure) {throw new ApiFailure(503,"UNAVAILABLE");}
        });
    }
    ObjectNode list(TokenRegistry.Principal principal) {return tx(c->{manager(c,principal);String rp=realm.rpId().orElse("");var value=object().put("enabled",!rp.isEmpty()).put("rpId",rp);var items=value.putArray("items");new PasskeyCredentials(c,rp).owned(principal.owner()).forEach(key->items.add(key.listed(rp)));return accepted(value);});}
    ObjectNode rename(TokenRegistry.Principal principal,UUID id,String match,String name) {return tx(c->{manager(c,principal);return accepted(new PasskeyCredentials(c,realm.rpId().orElse("")).rename(principal.owner(),id,match,name).management());});}
    boolean remove(TokenRegistry.Principal principal,UUID id,String match,String browser,String cookie,String peer,JsonNode input) {
        return tx(c->{var account=manager(c,principal);var scope=scope(c,principal,browser,cookie);var now=Instant.now();
            if(!rate(c,"management",scope.browser(),peer,now)) return rejected(429,"RATE_LIMITED");
            var credentials=new PasskeyCredentials(c,scope.rpId());ApiReplies.match(credentials.owned(principal.owner(),id).management(),match);
            var action=object().put("action","REMOVE").put("id",id.toString()).put("etag",match);
            if(!proof(c,principal,account,scope,action,input,object(),now)) return rejected(403,"PASSKEY_PROOF_REQUIRED");
            boolean signedOut;
            try(var statement=query(c,"SELECT passkey_id FROM tk_auth_session WHERE digest=? AND owner_id=?",principal.digest(),principal.owner());var rows=statement.executeQuery()) {
                signedOut=rows.next() && id.equals(rows.getObject(1,UUID.class));
            }catch(java.sql.SQLException failure) {throw databaseFailure(failure);}
            credentials.revoke(principal.owner(),id,match);
            if(signedOut) new PasskeyCeremonies(c).cancelBrowser(scope);return accepted(signedOut);
        });
    }
    void cancel(String browser,String cookie) {tx(c->{new PasskeyCeremonies(c).cancelBrowser(realm.scope(c,browser,cookie));return accepted(null);});}
}
