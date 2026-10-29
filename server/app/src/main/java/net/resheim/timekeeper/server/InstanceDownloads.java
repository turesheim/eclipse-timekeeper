package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.hash;
import java.io.IOException;
import java.time.*;
import java.util.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Durable one-use cookie grants. Neither raw credentials nor capabilities enter URLs. */
@ApplicationScoped
class InstanceDownloads {
    static final String COOKIE="__Host-timekeeper-instance-download";
    @Inject InstanceAdministrator administrators;
    @Inject InstanceBackupJobs jobs;
    @Inject AccountStore accounts;
    @Inject BackendClock clock;
    record Issued(String secret,Instant expiresAt) {
        @Override public String toString(){return "Private instance download grant";}
    }
    /** Can only be created after atomic consumption of a stored, session-bound grant. */
    static final class Grant {
        private final TokenRegistry.Principal principal;private final Instant verifiedAt;private final long bytes;
        private Grant(TokenRegistry.Principal principal,Instant verifiedAt,long bytes){this.principal=principal;this.verifiedAt=verifiedAt;this.bytes=bytes;}
        TokenRegistry.Principal principal(){return principal;}
        Instant verifiedAt(){return verifiedAt;}
        long bytes(){return bytes;}
        @Override public String toString(){return "Consumed private instance download authority";}
    }
    Issued issue(InstanceAdministrator.Proof proof,UUID id) {
        administrators.requireFresh(proof);
        long bytes;try{bytes=jobs.read(proof,id,(input,length)->length);}catch(IOException failure){throw new ApiFailure(503,"INSTANCE_BACKUP_UNAVAILABLE");}
        String secret=AccountPasswords.secret();Instant expires=clock.now().plusSeconds(120);
        accounts.tx(c->{lock(c);administrators.check(c,proof.principal());InstanceOperations.issueDownload(c,id,proof.principal(),hash(secret),proof.verifiedAt(),expires,bytes,clock.now());return null;});
        return new Issued(secret,expires);
    }
    Grant consume(TokenRegistry.Principal principal,UUID id,String secret) {
        administrators.require(principal);
        if(secret==null||!secret.matches("[A-Za-z0-9_-]{43}"))throw denied();
        return accounts.tx(c->{lock(c);administrators.check(c,principal);var stored=InstanceOperations.consumeDownload(c,id,principal,hash(secret),clock.now());
            return new Grant(principal,Instant.parse(stored.path("verifiedAt").asText()),stored.path("bytes").asLong());});
    }
    static String cookie(String secret) {
        if(secret==null||!secret.matches("[A-Za-z0-9_-]{43}"))throw denied();
        return COOKIE+"="+secret+"; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=120";
    }
    static String clearCookie(){return COOKIE+"=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0";}
    static String readCookie(List<String> headers) {
        String secret=null;boolean seen=false;int length=0;
        for(String header:headers==null?List.<String>of():headers) {
            if(header==null||header.length()>8192||(length+=header.length())>32768||header.indexOf('\r')>=0||header.indexOf('\n')>=0)throw ApiFailure.badRequest();
            for(String field:header.split(";",-1)) {
                field=field.strip();int equals=field.indexOf('=');String name=equals<0?field:field.substring(0,equals);
                if(!name.strip().equals(COOKIE))continue;
                if(seen||equals<0||!name.equals(name.strip()))throw ApiFailure.badRequest();seen=true;secret=field.substring(equals+1);
            }
        }
        if(secret!=null&&!secret.matches("[A-Za-z0-9_-]{43}"))throw denied();return secret;
    }
    private static ApiFailure denied(){return new ApiFailure(403,"INSTANCE_DOWNLOAD_REQUIRED");}
}
