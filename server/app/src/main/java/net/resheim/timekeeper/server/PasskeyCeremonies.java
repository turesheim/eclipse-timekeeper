package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** One-use authority shared by replicas. Caller holds the authentication-control lock. */
final class PasskeyCeremonies {
    record Scope(String browserSecret,String sessionSecret,String origin,String rpId,long epoch) {
        Scope {
            if(!secret(browserSecret) || sessionSecret!=null && !secret(sessionSecret) || epoch<1
                    || !java.net.URI.create(RecoveryOrigin.validate(origin)).getHost().equals(rpId)) throw ApiFailure.validation();
        }
        String browser() {return hash(browserSecret);}
        String session() {return sessionSecret==null ? "" : hash(sessionSecret);}
        @Override public String toString() {return "Browser ceremony scope";}
    }
    record Pending(String secret,String purpose,String owner,String session,ObjectNode data,Instant expires) { }
    private final Connection connection;
    PasskeyCeremonies(Connection connection) {this.connection=connection;}
    private static boolean secret(String value) {return value!=null && value.matches("[A-Za-z0-9_-]{43}");}
    private static void purpose(String value) {if(!Set.of("REGISTER","LOGIN","MANAGE","PROOF").contains(value)) throw ApiFailure.validation();}
    private record Saved(String digest,String browser,String purpose,String owner,String session,ObjectNode payload,Instant expires,boolean consumed) { }
    private Saved saved(ResultSet rows) throws SQLException {
        return new Saved(rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5),
                (ObjectNode)parse(rows.getString(6)),Instant.parse(rows.getString(7)),rows.getBoolean(8));
    }
    Pending create(String purpose,String owner,Scope scope,ObjectNode data,Instant now) {
        purpose(purpose);
        if((owner==null)!=purpose.equals("LOGIN") || owner!=null && scope.session().isEmpty()) throw ApiFailure.validation();
        var existing=new ArrayList<Saved>();
        try(var statement=query(connection,"SELECT digest,browser_digest,purpose,owner_id,session_digest,payload,expires_at,consumed FROM tk_passkey_ceremony FETCH FIRST 501 ROWS ONLY");var rows=statement.executeQuery()) {
            while(rows.next()) existing.add(saved(rows));
        }catch(SQLException failure) {throw databaseFailure(failure);}
        int pending=0,browserPending=0;
        for(var previous:existing) {
            if(previous.consumed() || !previous.expires().isAfter(now)) execute(connection,"DELETE FROM tk_passkey_ceremony WHERE digest=?",previous.digest());
            else {pending++;if(previous.browser().equals(scope.browser())) browserPending++;}
        }
        if(pending>=500 || browserPending>=10) throw new ApiFailure(429,"RATE_LIMITED");
        String secret=AccountPasswords.secret();Instant expires=now.plusSeconds(120);
        var payload=object().put("origin",scope.origin()).put("rpId",scope.rpId()).put("epoch",scope.epoch()).put("expectedSession",scope.session());payload.set("data",data.deepCopy());
        if(payload.toString().length()>32768) throw ApiFailure.validation();
        execute(connection,"INSERT INTO tk_passkey_ceremony(digest,browser_digest,purpose,owner_id,session_digest,payload,expires_at,consumed) VALUES(?,?,?,?,?,?,?,FALSE)",
                hash(secret),scope.browser(),purpose,owner,owner==null ? null : scope.session(),payload.toString(),expires.toString());
        return new Pending(secret,purpose,owner,owner==null ? null : scope.session(),data.deepCopy(),expires);
    }
    /**
     * Consume before crypto verification. Expected rejection must be returned from
     * the enclosing transaction, then converted to HTTP failure after commit.
     */
    Pending claim(String secret,String purpose,String owner,Scope scope,Instant now) {
        purpose(purpose);if(!secret(secret)) throw ApiFailure.unauthorized(false);
        Saved value;
        try(var statement=query(connection,"SELECT digest,browser_digest,purpose,owner_id,session_digest,payload,expires_at,consumed FROM tk_passkey_ceremony WHERE digest=? FOR UPDATE",hash(secret));var rows=statement.executeQuery()) {
            if(!rows.next()) throw ApiFailure.unauthorized(false);value=saved(rows);
        }catch(SQLException failure) {throw databaseFailure(failure);}
        if(value.consumed() || !value.expires().isAfter(now) || !value.browser().equals(scope.browser()) || !value.purpose().equals(purpose)
                || !Objects.equals(value.owner(),owner) || owner!=null && !Objects.equals(value.session(),scope.session())
                || !value.payload().path("expectedSession").asText().equals(scope.session())
                || !value.payload().path("origin").asText().equals(scope.origin()) || !value.payload().path("rpId").asText().equals(scope.rpId())
                || value.payload().path("epoch").asLong()!=scope.epoch()) throw ApiFailure.unauthorized(false);
        if(execute(connection,"UPDATE tk_passkey_ceremony SET consumed=TRUE WHERE digest=? AND consumed=FALSE",value.digest())!=1) throw ApiFailure.unauthorized(false);
        return new Pending(secret,purpose,owner,value.session(),((ObjectNode)value.payload().get("data")).deepCopy(),value.expires());
    }
    void cancelBrowser(Scope scope) {execute(connection,"UPDATE tk_passkey_ceremony SET consumed=TRUE WHERE browser_digest=? AND consumed=FALSE",scope.browser());}
    /** A delayed tab may cancel its own ceremony, never a newer browser sign-in. */
    void cancel(Scope scope,String secret) {
        if(!secret(secret)) return;
        try(var statement=query(connection,"SELECT digest,browser_digest,purpose,owner_id,session_digest,payload,expires_at,consumed FROM tk_passkey_ceremony WHERE digest=? FOR UPDATE",hash(secret));var rows=statement.executeQuery()) {
            if(!rows.next()) return;var value=saved(rows);
            if(!value.browser().equals(scope.browser()) || !value.payload().path("expectedSession").asText().equals(scope.session())
                    || !value.payload().path("origin").asText().equals(scope.origin()) || !value.payload().path("rpId").asText().equals(scope.rpId())
                    || value.payload().path("epoch").asLong()!=scope.epoch()) return;
            execute(connection,"UPDATE tk_passkey_ceremony SET consumed=TRUE WHERE digest=?",value.digest());
        }catch(SQLException failure) {throw databaseFailure(failure);}
    }
    static void cancelOwner(Connection connection,String owner) {execute(connection,"UPDATE tk_passkey_ceremony SET consumed=TRUE WHERE owner_id=? AND consumed=FALSE",owner);}
}
