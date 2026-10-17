package net.resheim.timekeeper.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** High-entropy opaque bearer tokens are configured as hashes, never plaintext. */
@ApplicationScoped
public class TokenRegistry {
    @ConfigProperty(name="timekeeper.auth.tokens-file",defaultValue="NONE") String tokensFile;
    @jakarta.inject.Inject AccountStore accounts;
    record Token(String owner,byte[] digest,Set<String> permissions,Instant expires) { }
    public record Principal(String owner,Set<String> permissions,String kind,String digest,String legacyDigest) {
        public Principal(String owner,Set<String> permissions) {this(owner,permissions,"LEGACY",null,null);}
        public Principal(String owner,Set<String> permissions,String kind,String digest) {this(owner,permissions,kind,digest,null);}
    }
    Principal recheck(Principal principal) {
        if(principal.legacyDigest()==null) return principal;
        for(var configured:load()) if(java.util.HexFormat.of().formatHex(configured.digest()).equals(principal.legacyDigest())) {
            if(!configured.owner().equals(principal.owner())) throw new ApiFailure(401,"OWNER_CHANGED");
            if(configured.expires()!=null && !configured.expires().isAfter(Instant.now())) throw ApiFailure.unauthorized(true);
            var grants=new java.util.HashSet<>(principal.permissions());grants.retainAll(configured.permissions());
            return new Principal(principal.owner(),Set.copyOf(grants),principal.kind(),principal.digest(),principal.legacyDigest());
        }
        throw ApiFailure.unauthorized(true);
    }
    Principal session(String secret) {return recheck(accounts.session(secret));}
    Principal session(String secret,String browser) {return recheck(accounts.session(secret,browser));}
    void start(@Observes StartupEvent event) { load(); }
    List<Token> load() {
        if(tokensFile==null || tokensFile.isBlank() || tokensFile.equals("NONE")) return List.of();
        try {
            Path file=Path.of(tokensFile);
            if (Files.size(file)>1048576) throw new IllegalArgumentException();
            JsonNode root=JsonValues.MAPPER.readTree(Files.readString(file,StandardCharsets.UTF_8));
            if (!root.isArray()) throw new IllegalArgumentException();
            Set<String> digests=new HashSet<>(); List<Token> tokens=new ArrayList<>();
            for (JsonNode value:root) {
                if(!value.isObject() || !value.path("ownerId").isTextual() || !value.path("sha256").isTextual()
                        || (value.hasNonNull("expiresAt") && !value.get("expiresAt").isTextual())) throw new IllegalArgumentException();
                value.fieldNames().forEachRemaining(name -> {if(!Set.of("ownerId","sha256","permissions","expiresAt").contains(name)) throw new IllegalArgumentException();});
                String owner=value.path("ownerId").asText(); String hash=value.path("sha256").asText();
                if (owner.isBlank() || owner.codePointCount(0,owner.length())>200 || !owner.equals(owner.trim()) || owner.equals("local")
                        || !hash.matches("[a-fA-F0-9]{64}") || !digests.add(hash.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException();
                Set<String> permissions=new HashSet<>();
                if (!value.path("permissions").isArray()) throw new IllegalArgumentException();
                for (JsonNode permission:value.get("permissions")) {
                    if (!permission.isTextual() || !Set.of("read","write").contains(permission.asText())) throw new IllegalArgumentException();
                    permissions.add(permission.asText());
                }
                if (!permissions.contains("read")) throw new IllegalArgumentException();
                Instant expiry=value.path("expiresAt").isMissingNode() || value.path("expiresAt").isNull() ? null : Instant.parse(value.get("expiresAt").asText());
                tokens.add(new Token(owner,HexFormat.of().parseHex(hash),Set.copyOf(permissions),expiry));
            }
            return List.copyOf(tokens);
        } catch (Exception e) { throw new IllegalStateException("Invalid token registry configuration"); }
    }
    public Principal authenticate(String authorization) {
        if (authorization==null || authorization.length()>4096 || !authorization.regionMatches(true,0,"Bearer ",0,7)) throw ApiFailure.unauthorized(true);
        String token=authorization.substring(7);
        if (!token.matches("[A-Za-z0-9._~+/-]+=*")) throw ApiFailure.unauthorized(true);
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            Principal result=null;
            for (Token configured:load()) {
                if (MessageDigest.isEqual(digest,configured.digest()) && (configured.expires()==null || configured.expires().isAfter(Instant.now())))
                    result=new Principal(configured.owner(),configured.permissions());
            }
            String hash=HexFormat.of().formatHex(digest);
            if(result!=null) return accounts.legacy(result.owner(),hash,result.permissions());
            return accounts.managed(hash);
        } catch (ApiFailure e) { throw e; }
        catch (Exception e) { throw new ApiFailure(503,"UNAVAILABLE"); }
    }
}
