package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Authoritative recovery state. Raw links leave the transaction only for delivery, never storage/logging. */
@ApplicationScoped
class EmailRecoveryStore {
    @Inject AccountStore accounts;
    @Inject RecoveryOrigin origins;

    // These values must never be serialized into an API response or routine log.
    record Delivery(String owner,String address,String purpose,String secret,String origin,long epoch) {
        @Override public String toString() {return "Recovery email delivery";}
    }
    record Change(JsonNode addresses,List<Delivery> deliveries,List<String> notifyAddresses) { }
    record Reset(String owner,List<String> notifyAddresses) { }
    private record Link(String owner,String slot,String address,String purpose,long epoch,Instant expires,boolean consumed) { }

    private static String slot(String value) {
        if(!Set.of("PRIMARY","BACKUP").contains(value)) throw ApiFailure.validation();return value;
    }
    private boolean allow(String key,int limit) {
        return accounts.tx(c->{lock(c);return attempt(c,"recovery:"+key,limit,Duration.ofMinutes(10),Instant.now());});
    }
    private void proof(Connection c,TokenRegistry.Principal principal,String password) {
        if(!principal.permissions().contains("write")) throw new ApiFailure(403,"FORBIDDEN");
        accounts.guard(c,principal);
        try(var statement=query(c,"SELECT password_hash,state FROM tk_account WHERE owner_id=?",principal.owner());var rows=statement.executeQuery()) {
            if(!rows.next() || !"ENABLED".equals(rows.getString(2)) || !AccountPasswords.matches(password,rows.getString(1))) throw ApiFailure.unauthorized(false);
        }catch(SQLException failure) {throw databaseFailure(failure);}
    }
    private static ObjectNode address(Connection c,String owner,String slot) {
        var value=object();value.put("slot",slot);value.putNull("verifiedAddress");value.putNull("pendingAddress");value.put("version",0);
        try(var statement=query(c,"SELECT verified_address,pending_address,version FROM tk_recovery_address WHERE owner_id=? AND slot=?",owner,slot);var rows=statement.executeQuery()) {
            if(rows.next()) {value.put("verifiedAddress",rows.getString(1));value.put("pendingAddress",rows.getString(2));value.put("version",rows.getLong(3));}
        }catch(SQLException failure) {throw databaseFailure(failure);}
        return value;
    }
    private static JsonNode addresses(Connection c,String owner) {
        var result=object();var items=result.putArray("items");for(String slot:List.of("PRIMARY","BACKUP")) items.add(address(c,owner,slot));return result;
    }
    JsonNode addresses(TokenRegistry.Principal principal) {
        return accounts.tx(c->{accounts.guard(c,principal);return addresses(c,principal.owner());});
    }
    JsonNode address(TokenRegistry.Principal principal,String slot) {
        String selected=slot(slot);
        return accounts.tx(c->{accounts.guard(c,principal);return address(c,principal.owner(),selected);});
    }
    private static List<String> verified(Connection c,String owner) {
        var result=new ArrayList<String>();
        try(var statement=query(c,"SELECT verified_address FROM tk_recovery_address WHERE owner_id=? AND verified_address IS NOT NULL ORDER BY slot",owner);var rows=statement.executeQuery()) {
            while(rows.next()) result.add(rows.getString(1));return List.copyOf(result);
        }catch(SQLException failure) {throw databaseFailure(failure);}
    }
    private Delivery issue(Connection c,String owner,String slot,String address,String purpose,RecoveryOrigin.Current origin) {
        String secret=AccountPasswords.secret();Instant now=Instant.now();
        execute(c,"INSERT INTO tk_recovery_link VALUES(?,?,?,?,?,?,?,?,FALSE)",hash(secret),owner,slot,address,purpose,origin.epoch(),now.toString(),now.plusSeconds(900).toString());
        return new Delivery(owner,address,purpose,secret,origin.origin(),origin.epoch());
    }
    Change change(TokenRegistry.Principal principal,String slot,String email,String password,String match) {
        String selected=slot(slot),normalized=email==null ? null : RecoveryEmail.normalize(email);
        if(normalized==null && selected.equals("PRIMARY")) throw ApiFailure.validation();
        if(!allow("manage:"+principal.owner(),10)) throw new ApiFailure(429,"RATE_LIMITED");
        return accounts.tx(c->{lock(c);proof(c,principal,password);var origin=origins.requireCurrent(c);
            var previous=address(c,principal.owner(),selected);long version=ApiReplies.match(previous,match);
            var other=address(c,principal.owner(),selected.equals("PRIMARY") ? "BACKUP" : "PRIMARY");
            if(normalized!=null && (RecoveryEmail.same(normalized,optionalText(other.get("verifiedAddress")).orElse(null)) || RecoveryEmail.same(normalized,optionalText(other.get("pendingAddress")).orElse(null)))) throw ApiFailure.validation();
            var notifications=verified(c,principal.owner());
            execute(c,"UPDATE tk_recovery_link SET consumed=TRUE WHERE owner_id=? AND slot=? AND purpose='VERIFY' AND consumed=FALSE",principal.owner(),selected);
            boolean alreadyVerified=normalized!=null && RecoveryEmail.same(normalized,optionalText(previous.get("verifiedAddress")).orElse(null));
            String retained=normalized==null ? null : optionalText(previous.get("verifiedAddress")).orElse(null);
            String pending=alreadyVerified ? null : normalized;
            if(version==0) execute(c,"INSERT INTO tk_recovery_address VALUES(?,?,?,?,1)",principal.owner(),selected,retained,pending);
            else execute(c,"UPDATE tk_recovery_address SET verified_address=?,pending_address=?,version=version+1 WHERE owner_id=? AND slot=?",retained,pending,principal.owner(),selected);
            if(normalized==null) execute(c,"UPDATE tk_recovery_link SET consumed=TRUE WHERE owner_id=? AND consumed=FALSE",principal.owner());
            var deliveries=pending==null ? List.<Delivery>of() : List.of(issue(c,principal.owner(),selected,pending,"VERIFY",origin));
            return new Change(addresses(c,principal.owner()),deliveries,notifications);
        });
    }
    List<Delivery> resend(TokenRegistry.Principal principal,String slot) {
        String selected=slot(slot);
        if(!allow("resend:"+principal.owner()+":"+selected,3)) throw new ApiFailure(429,"RATE_LIMITED");
        return accounts.tx(c->{lock(c);accounts.guard(c,principal);
            if(!principal.permissions().contains("write")) throw new ApiFailure(403,"FORBIDDEN");
            var origin=origins.requireCurrent(c);String pending=optionalText(address(c,principal.owner(),selected).get("pendingAddress")).orElse(null);
            if(pending==null) return List.of();
            execute(c,"UPDATE tk_recovery_link SET consumed=TRUE WHERE owner_id=? AND slot=? AND purpose='VERIFY' AND consumed=FALSE",principal.owner(),selected);
            return List.of(issue(c,principal.owner(),selected,pending,"VERIFY",origin));
        });
    }
    // Called only with the authoritative owner returned by successful account creation.
    List<Delivery> initialVerification(String owner) {
        return accounts.tx(c->{lock(c);var origin=origins.requireCurrent(c);
            var current=address(c,owner,"PRIMARY");
            if(current.path("version").asLong()!=1 || !current.path("verifiedAddress").isNull()) return List.of();
            String pending=optionalText(current.get("pendingAddress")).orElse(null);
            if(pending==null) return List.of();
            execute(c,"UPDATE tk_recovery_link SET consumed=TRUE WHERE owner_id=? AND slot='PRIMARY' AND purpose='VERIFY' AND consumed=FALSE",owner);
            return List.of(issue(c,owner,"PRIMARY",pending,"VERIFY",origin));
        });
    }
    List<Delivery> requestReset(String username) {
        String name=username==null ? "" : username.strip().toLowerCase(java.util.Locale.ROOT);
        if(name.length()>200) name="invalid";
        if(!allow("reset-global",100) || !allow("reset-user:"+name,3)) return List.of();
        final String requested=name;
        return accounts.tx(c->{lock(c);var origin=origins.requireCurrent(c);String owner=null;
            try(var statement=query(c,"SELECT owner_id FROM tk_account WHERE username=? AND state='ENABLED'",requested);var rows=statement.executeQuery()) {if(rows.next()) owner=rows.getString(1);}
            catch(SQLException failure) {throw databaseFailure(failure);}
            if(owner==null) return List.of();
            execute(c,"UPDATE tk_recovery_link SET consumed=TRUE WHERE owner_id=? AND purpose='RESET' AND consumed=FALSE",owner);
            var deliveries=new ArrayList<Delivery>();
            for(String selected:List.of("PRIMARY","BACKUP")) {
                String email=optionalText(address(c,owner,selected).get("verifiedAddress")).orElse(null);
                if(email!=null) deliveries.add(issue(c,owner,selected,email,"RESET",origin));
            }
            return List.copyOf(deliveries);
        });
    }
    private Link valid(Connection c,String secret,String purpose) {
        if(secret==null || !secret.matches("[A-Za-z0-9_-]{43}")) throw ApiFailure.unauthorized(false);
        var origin=origins.requireCurrent(c);Link link;
        try(var statement=query(c,"SELECT owner_id,slot,address,purpose,origin_epoch,expires_at,consumed FROM tk_recovery_link WHERE digest=? FOR UPDATE",hash(secret));var rows=statement.executeQuery()) {
            if(!rows.next()) throw ApiFailure.unauthorized(false);
            link=new Link(rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4),rows.getLong(5),Instant.parse(rows.getString(6)),rows.getBoolean(7));
        }catch(SQLException failure) {throw databaseFailure(failure);}
        if(link.consumed() || !purpose.equals(link.purpose()) || link.epoch()!=origin.epoch() || !link.expires().isAfter(Instant.now())) throw ApiFailure.unauthorized(false);
        try(var statement=query(c,"SELECT state FROM tk_account WHERE owner_id=? FOR UPDATE",link.owner());var rows=statement.executeQuery()) {
            if(!rows.next() || !"ENABLED".equals(rows.getString(1))) throw ApiFailure.unauthorized(false);
        }catch(SQLException failure) {throw databaseFailure(failure);}
        var current=address(c,link.owner(),link.slot());
        String expected=optionalText(current.get(purpose.equals("VERIFY") ? "pendingAddress" : "verifiedAddress")).orElse(null);
        if(!RecoveryEmail.same(expected,link.address())) throw ApiFailure.unauthorized(false);
        return link;
    }
    void preview(String secret,String purpose) {
        if(!Set.of("VERIFY","RESET").contains(purpose)) throw ApiFailure.validation();
        if(!allow("preview-global",100)) throw new ApiFailure(429,"RATE_LIMITED");
        accounts.tx(c->{lock(c);valid(c,secret,purpose);return null;});
    }
    Change verify(String secret) {
        if(!allow("verify-global",100)) throw new ApiFailure(429,"RATE_LIMITED");
        return accounts.tx(c->{lock(c);var link=valid(c,secret,"VERIFY");var notifications=verified(c,link.owner());
            execute(c,"UPDATE tk_recovery_address SET verified_address=pending_address,pending_address=NULL,version=version+1 WHERE owner_id=? AND slot=?",link.owner(),link.slot());
            execute(c,"UPDATE tk_recovery_link SET consumed=TRUE WHERE owner_id=? AND (purpose='RESET' OR slot=?)",link.owner(),link.slot());
            return new Change(addresses(c,link.owner()),List.of(),notifications);
        });
    }
    Reset reset(String secret,String password) {
        if(!allow("complete-global",100)) throw new ApiFailure(429,"RATE_LIMITED");
        String encoded=AccountPasswords.hash(password);
        return accounts.tx(c->{lock(c);var link=valid(c,secret,"RESET");
            execute(c,"UPDATE tk_account SET password_hash=?,version=version+1 WHERE owner_id=?",encoded,link.owner());
            revokeAll(c,link.owner());return new Reset(link.owner(),verified(c,link.owner()));
        });
    }
    boolean deliverable(Delivery delivery) {
        try {
            return accounts.tx(c->{lock(c);var link=valid(c,delivery.secret(),delivery.purpose());return link.owner().equals(delivery.owner());});
        }catch(ApiFailure invalid) {return false;}
    }
}
