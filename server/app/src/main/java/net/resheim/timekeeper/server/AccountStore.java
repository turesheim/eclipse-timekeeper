package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.nio.file.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Shared account/session/token authority. Authentication never selects a business-data owner from input. */
@ApplicationScoped
public class AccountStore {
    @Inject AgroalDataSource datasource;
    @Inject TokenRegistry registry;
    @Inject PasskeyRealm realm;
    @ConfigProperty(name="timekeeper.auth.setup-file",defaultValue="NONE") String setupFile;
    @ConfigProperty(name="quarkus.datasource.jdbc.url") String jdbcUrl;
    private static final String DUMMY=AccountPasswords.hash("This is a dummy password for constant work");
    record Issued(String secret,JsonNode account,String csrf,Instant expires,boolean persistent) {
        @Override public String toString() {return "Browser session";}
    }
    @FunctionalInterface interface SessionIssuer {
        Issued issue(Connection c,String owner,Set<String> permissions,boolean remember,Instant now,String parent);
    }
    static Set<String> permissions(String value) {return value.equals("READ_WRITE") ? Set.of("read","write") : Set.of("read");}
    static String permissions(Set<String> values) {return values.contains("write") ? "READ_WRITE" : "READ";}
    <T> T tx(Function<Connection,T> work) {
        try(var connection=datasource.getConnection()) {
            connection.setAutoCommit(false);
            try {var result=work.apply(connection);connection.commit();return result;}
            catch(Throwable failure) {try {connection.rollback();}catch(SQLException rollback) {failure.addSuppressed(rollback);}throw failure;}
        } catch(SQLException failure) {throw databaseFailure(failure);}
    }
    static RuntimeException databaseFailure(SQLException failure) {
        if("23505".equals(failure.getSQLState())) return ApiFailure.conflict();
        return new ApiFailure(503,"UNAVAILABLE");
    }
    static PreparedStatement query(Connection c,String sql,Object... values) throws SQLException {
        var statement=c.prepareStatement(sql);for(int i=0;i<values.length;i++) statement.setObject(i+1,values[i]);return statement;
    }
    static int execute(Connection c,String sql,Object... values) {
        try(var statement=query(c,sql,values)) {return statement.executeUpdate();}catch(SQLException failure) {throw databaseFailure(failure);}
    }
    static void lock(Connection c) {
        try(var statement=query(c,"SELECT id FROM tk_auth_control WHERE id=1 FOR UPDATE");var rows=statement.executeQuery()) {if(!rows.next()) throw new SQLException("Missing auth control");}
        catch(SQLException failure) {throw databaseFailure(failure);}
    }
    static String username(String value) {
        if(value==null) throw ApiFailure.validation();String name=value.strip().toLowerCase(Locale.ROOT);
        if(!name.matches("[a-z0-9][a-z0-9._-]{2,63}")) throw ApiFailure.validation();return name;
    }
    static String displayName(String value) {
        if(value==null || value.isBlank() || value.codePointCount(0,value.length())>200) throw ApiFailure.validation();return value.strip();
    }
    private void owner(Connection c,String id) {
        // Serialize account creation with the auth-control lock; existing business identities are never renamed.
        try(var statement=query(c,"SELECT id FROM tk_owner WHERE id=?",id);var rows=statement.executeQuery()) {
            if(!rows.next()) execute(c,"INSERT INTO tk_owner(id,zone_id,version,revision,cursor_secret) VALUES(?,'UTC',0,0,?)",id,hash(AccountPasswords.secret()));
        }catch(SQLException failure) {throw databaseFailure(failure);}
    }
    static void ensureAccount(Connection c,String owner) {
        try(var statement=query(c,"SELECT owner_id FROM tk_account WHERE owner_id=?",owner);var rows=statement.executeQuery()) {
            if(!rows.next()) execute(c,"INSERT INTO tk_account(owner_id,username,password_hash,display_name,state,administrator,version) VALUES(?,NULL,NULL,?,'UNCLAIMED',FALSE,0)",owner,owner);
        }catch(SQLException failure) {throw databaseFailure(failure);}
    }
    static ObjectNode account(Connection c,String owner,boolean locked) {
        try(var statement=query(c,"SELECT username,display_name,state,administrator,version FROM tk_account WHERE owner_id=?"+(locked ? " FOR UPDATE" : ""),owner);var rows=statement.executeQuery()) {
            if(!rows.next()) throw ApiFailure.notFound();
            var result=object();result.put("ownerId",owner);result.put("username",rows.getString(1));result.put("displayName",rows.getString(2));result.put("state",rows.getString(3));result.put("administrator",rows.getBoolean(4));result.put("version",rows.getLong(5));return result;
        }catch(SQLException failure) {throw databaseFailure(failure);}
    }
    void initialize() {
        tx(c->{lock(c);
            try(var statement=query(c,"SELECT id FROM tk_owner");var rows=statement.executeQuery()) {while(rows.next()) ensureAccount(c,rows.getString(1));}
            catch(SQLException failure) {throw databaseFailure(failure);}
            if(setupFile==null || setupFile.isBlank() || setupFile.equals("NONE")) return null;
            JsonNode input;
            try {var path=Path.of(setupFile);if(Files.size(path)>4096) throw new IllegalArgumentException();input=MAPPER.readTree(Files.readString(path));}
            catch(Exception failure) {throw new IllegalStateException("Invalid account setup file");}
            String digest=input.path("sha256").asText(),purpose=input.path("purpose").asText();
            if(!digest.matches("[a-f0-9]{64}") || !Set.of("SETUP","RECOVERY").contains(purpose)) throw new IllegalStateException("Invalid account setup file");
            var expires=Instant.parse(input.path("expiresAt").asText());String target=optionalText(input.get("ownerId")).orElse(null);
            if(target!=null) {
                if(target.isBlank() || !target.equals(target.strip()) || target.equals("local") || target.codePointCount(0,target.length())>200) throw new IllegalStateException("Invalid setup owner");
                if(purpose.equals("SETUP")) owner(c,target);
                ensureAccount(c,target); // RECOVERY must refer to an existing owned identity (FK).
            } else if(purpose.equals("RECOVERY")) throw new IllegalStateException("Recovery requires an explicit existing owner");
            try(var statement=query(c,"SELECT digest FROM tk_auth_challenge WHERE digest=?",digest);var rows=statement.executeQuery()) {
                if(!rows.next()) execute(c,"INSERT INTO tk_auth_challenge(digest,purpose,owner_id,expires_at,consumed) VALUES(?,?,?,?,FALSE)",digest,purpose,target,expires.toString());
            }catch(SQLException failure) {throw databaseFailure(failure);}
            return null;
        });
    }
    private static boolean bootstrapped(Connection c) {
        try(var statement=query(c,"SELECT bootstrap_completed FROM tk_auth_control WHERE id=1");var rows=statement.executeQuery()) {if(!rows.next()) throw new SQLException();return rows.getBoolean(1);}
        catch(SQLException failure) {throw databaseFailure(failure);}
    }
    JsonNode status() {return tx(c->{var node=object();node.put("setupRequired",!bootstrapped(c));return node;});}
    static boolean attempt(Connection c,String key,int limit,Duration period,Instant now) {
        String id=hash(key);int count=0;Instant window=now;boolean exists=false;
        try(var statement=query(c,"SELECT attempts,window_start FROM tk_auth_throttle WHERE id=?",id);var rows=statement.executeQuery()) {
            if(rows.next()) {exists=true;count=rows.getInt(1);window=Instant.parse(rows.getString(2));}
        }catch(SQLException failure) {throw databaseFailure(failure);}
        if(!now.isBefore(window.plus(period))) {window=now;count=0;}
        if(count>=limit) return false;
        if(exists) execute(c,"UPDATE tk_auth_throttle SET attempts=?,window_start=? WHERE id=?",count+1,window.toString(),id);
        else execute(c,"INSERT INTO tk_auth_throttle(id,attempts,window_start) VALUES(?,?,?)",id,1,window.toString());return true;
    }
    private boolean allowed(String name,boolean setup) {
        return tx(c->{lock(c);Instant now=Instant.now();
            boolean global=attempt(c,setup ? "setup" : "login-global",setup ? 10 : 100,Duration.ofMinutes(setup ? 1 : 10),now);
            boolean user=setup || attempt(c,"login:"+name,10,Duration.ofMinutes(10),now);return global && user;
        });
    }
    Issued complete(String code,String login,String password,String display,boolean recovery) {
        return complete(code,login,password,display,recovery,null);
    }
    Issued complete(String code,String login,String password,String display,boolean recovery,String email) {
        return complete(code,login,password,display,recovery,email,this::issue);
    }
    Issued complete(String code,String login,String password,String display,boolean recovery,String email,SessionIssuer issuer) {
        if(!allowed("",true)) throw new ApiFailure(429,"RATE_LIMITED");
        String name=username(login),label=displayName(display),encoded=AccountPasswords.hash(password);
        String pending=email==null ? null : RecoveryEmail.normalize(email);
        if(code==null || !code.matches("[A-Za-z0-9_-]{43}")) throw ApiFailure.unauthorized(false);
        return tx(c->{lock(c);String target=null,expected=recovery ? "RECOVERY" : "SETUP",digest=hash(code);Instant now=Instant.now();
            if(!recovery && bootstrapped(c)) throw ApiFailure.unauthorized(false);
            try(var statement=query(c,"SELECT owner_id,expires_at,consumed,purpose FROM tk_auth_challenge WHERE digest=? FOR UPDATE",digest);var rows=statement.executeQuery()) {
                if(!rows.next() || rows.getBoolean(3) || !expected.equals(rows.getString(4)) || !Instant.parse(rows.getString(2)).isAfter(now)) throw ApiFailure.unauthorized(false);
                target=rows.getString(1);
            }catch(SQLException failure) {throw databaseFailure(failure);}
            if(target==null) target=UUID.randomUUID().toString();owner(c,target);ensureAccount(c,target);
            var current=account(c,target,true);
            if(!recovery && !current.get("state").asText().equals("UNCLAIMED")) throw ApiFailure.conflict();
            boolean admin=!recovery || current.get("administrator").asBoolean();
            execute(c,"UPDATE tk_account SET username=?,password_hash=?,display_name=?,state='ENABLED',administrator=?,version=version+1 WHERE owner_id=?",name,encoded,label,admin,target);
            if(!recovery && pending!=null) execute(c,"INSERT INTO tk_recovery_address VALUES(?,'PRIMARY',NULL,?,1)",target,pending);
            execute(c,"UPDATE tk_auth_challenge SET consumed=TRUE WHERE digest=?",digest);
            if(!recovery) execute(c,"UPDATE tk_auth_control SET bootstrap_completed=TRUE WHERE id=1");
            if(recovery) {PasskeyCredentials.recover(c,target);revokeAll(c,target);}else execute(c,"DELETE FROM tk_auth_session WHERE owner_id=?",target);return issuer.issue(c,target,Set.of("read","write"),false,now,null);
        });
    }
    Issued login(String login,String password,boolean remember) {
        return login(login,password,remember,this::issue);
    }
    Issued login(String login,String password,boolean remember,SessionIssuer issuer) {
        String name=login==null ? "" : login.strip().toLowerCase(Locale.ROOT);
        if(name.length()>200 || !allowed(name,false)) throw new ApiFailure(429,"RATE_LIMITED");
        record Candidate(String owner,String hash,long version) { }
        Candidate candidate=tx(c->{
            try(var statement=query(c,"SELECT owner_id,password_hash,version FROM tk_account WHERE username=? AND state='ENABLED'",name);var rows=statement.executeQuery()) {
                return rows.next() ? new Candidate(rows.getString(1),rows.getString(2),rows.getLong(3)) : null;
            }catch(SQLException failure) {throw databaseFailure(failure);}
        });
        boolean valid=AccountPasswords.matches(password,candidate==null ? DUMMY : candidate.hash());
        if(!valid || candidate==null) throw ApiFailure.unauthorized(false);
        return tx(c->{lock(c);var current=account(c,candidate.owner(),true);
            if(!current.get("state").asText().equals("ENABLED") || current.get("version").asLong()!=candidate.version()) throw ApiFailure.unauthorized(false);
            execute(c,"DELETE FROM tk_auth_throttle WHERE id=?",hash("login:"+name));
            return issuer.issue(c,candidate.owner(),Set.of("read","write"),remember,Instant.now(),null);
        });
    }
    Issued issue(Connection c,String owner,Set<String> permissions,boolean remember,Instant now,String parent) {
        String secret=AccountPasswords.secret(),csrf=AccountPasswords.secret();Instant expires=now.plus(remember ? Duration.ofDays(30) : Duration.ofHours(12));

        execute(c,"INSERT INTO tk_auth_session(digest,owner_id,csrf,permissions,created_at,expires_at,last_seen_at,persistent,parent_digest) VALUES(?,?,?,?,?,?,?,?,?)",hash(secret),owner,csrf,permissions(permissions),now.toString(),expires.toString(),now.toString(),remember,parent);
        return new Issued(secret,account(c,owner,false),csrf,expires,remember);
    }
    static boolean currentPassword(Connection c,String owner,String password) {
        try(var statement=query(c,"SELECT password_hash FROM tk_account WHERE owner_id=? AND state='ENABLED'",owner);var rows=statement.executeQuery()) {
            String encoded=rows.next() ? rows.getString(1) : null;boolean valid=AccountPasswords.matches(password,encoded==null ? DUMMY : encoded);return encoded!=null && valid;
        }catch(SQLException failure) {throw databaseFailure(failure);}
    }
    static boolean accountSession(Connection c,TokenRegistry.Principal principal) {
        if(!principal.kind().equals("SESSION") || !principal.permissions().contains("write")) return false;
        try(var statement=query(c,"SELECT s.parent_digest,a.state FROM tk_auth_session s JOIN tk_account a ON a.owner_id=s.owner_id WHERE s.digest=? AND s.owner_id=?",principal.digest(),principal.owner());var rows=statement.executeQuery()) {
            return rows.next() && rows.getString(1)==null && rows.getString(2).equals("ENABLED");
        }catch(SQLException failure) {throw databaseFailure(failure);}
    }
    Issued exchange(TokenRegistry.Principal principal) {
        return exchange(principal,this::issue);
    }
    Issued exchange(TokenRegistry.Principal principal,SessionIssuer issuer) {
        return tx(c->{lock(c);guard(c,principal);return issuer.issue(c,principal.owner(),principal.permissions(),false,Instant.now(),principal.digest());});
    }
    TokenRegistry.Principal session(String secret) {
        return session(secret,null,false);
    }
    TokenRegistry.Principal session(String secret,String browser) {
        return session(secret,browser,true);
    }
    private TokenRegistry.Principal session(String secret,String browser,boolean checkBrowser) {
        if(secret==null || !secret.matches("[A-Za-z0-9_-]{43}")) throw ApiFailure.unauthorized(false);
        String digest=hash(secret);
        return tx(c->{
            try(var statement=query(c,"SELECT s.owner_id,s.permissions,t.permissions,t.legacy_digest,s.browser_digest FROM tk_auth_session s LEFT JOIN tk_api_token t ON t.owner_id=s.owner_id AND t.digest=s.parent_digest WHERE s.digest=?",digest);var rows=statement.executeQuery()) {
                if(!rows.next()) throw ApiFailure.unauthorized(false);
                if(checkBrowser && rows.getString(5)!=null && (browser==null || !browser.matches("[A-Za-z0-9_-]{43}") || !rows.getString(5).equals(hash(browser)))) throw ApiFailure.unauthorized(false);
                var grants=new HashSet<>(permissions(rows.getString(2)));if(rows.getString(3)!=null) grants.retainAll(permissions(rows.getString(3)));
                var principal=new TokenRegistry.Principal(rows.getString(1),Set.copyOf(grants),"SESSION",digest,rows.getString(4));guard(c,principal);return principal;
            }catch(SQLException failure) {throw databaseFailure(failure);}
        });
    }
    TokenRegistry.Principal legacy(String owner,String rawDigest,Set<String> permissions) {
        String digest=hash("legacy\n"+owner+"\n"+rawDigest);
        return tx(c->{lock(c);owner(c,owner);ensureAccount(c,owner);
            try(var statement=query(c,"SELECT digest FROM tk_api_token WHERE digest=?",digest);var rows=statement.executeQuery()) {
                if(!rows.next()) execute(c,"INSERT INTO tk_api_token(digest,id,owner_id,name,permissions,expires_at,revoked,source,created_at,last_used_at,version,legacy_digest) VALUES(?,?,?,?,?,NULL,FALSE,'LEGACY',?,NULL,0,?)",digest,UUID.nameUUIDFromBytes(digest.getBytes(java.nio.charset.StandardCharsets.UTF_8)),owner,"Imported API token",permissions(permissions),Instant.now().toString(),rawDigest);
            }catch(SQLException failure) {throw databaseFailure(failure);}
            var principal=new TokenRegistry.Principal(owner,permissions,"LEGACY",digest,rawDigest);guard(c,principal);
            execute(c,"UPDATE tk_api_token SET permissions=? WHERE digest=?",permissions(permissions),digest);return principal;
        });
    }
    TokenRegistry.Principal managed(String digest) {
        return tx(c->{
            try(var statement=query(c,"SELECT owner_id,permissions FROM tk_api_token WHERE digest=? AND source='MANAGED'",digest);var rows=statement.executeQuery()) {
                if(!rows.next()) throw ApiFailure.unauthorized(true);
                var principal=new TokenRegistry.Principal(rows.getString(1),permissions(rows.getString(2)),"TOKEN",digest);guard(c,principal);return principal;
            }catch(SQLException failure) {throw databaseFailure(failure);}
        });
    }
    /** Held until the business transaction commits: disable/revoke waits for already-authorized work. */
    void guard(Connection c,TokenRegistry.Principal principal) {
        if(!registry.recheck(principal).permissions().containsAll(principal.permissions())) throw new ApiFailure(403,"FORBIDDEN");
        var account=account(c,principal.owner(),true);
        if(account.get("state").asText().equals("DISABLED")) throw ApiFailure.unauthorized(true);
        if(principal.kind().equals("SESSION")) {
            if(!account.get("state").asText().equals("ENABLED") && !account.get("state").asText().equals("UNCLAIMED")) throw ApiFailure.unauthorized(false);
            try(var statement=query(c,"SELECT expires_at,last_seen_at,persistent,parent_digest,origin_epoch,browser_digest,passkey_id FROM tk_auth_session WHERE digest=? AND owner_id=? FOR UPDATE",principal.digest(),principal.owner());var rows=statement.executeQuery()) {
                Instant now=Instant.now();
                if(!rows.next() || !Instant.parse(rows.getString(1)).isAfter(now) || !Instant.parse(rows.getString(2)).plus(rows.getBoolean(3) ? Duration.ofDays(7) : Duration.ofMinutes(30)).isAfter(now)) throw ApiFailure.unauthorized(false);
                // Plain reads preserve the auth-control -> account/session mutation lock order.
                var current=realm.current(c);
                if(current.origin()==null) {
                    if(rows.getLong(5)!=0 || rows.getString(6)!=null || rows.getObject(7)!=null) throw ApiFailure.unauthorized(false);
                } else if(rows.getLong(5)!=current.epoch() || rows.getString(6)==null) throw ApiFailure.unauthorized(false);
                if(rows.getObject(7)!=null) {
                    if(!account.path("state").asText().equals("ENABLED")) throw ApiFailure.unauthorized(false);
                    try(var key=query(c,"SELECT rp_id FROM tk_passkey_credential WHERE id=? AND owner_id=? AND revoked=FALSE",rows.getObject(7),principal.owner());var credentials=key.executeQuery()) {
                        if(!credentials.next() || !realm.rpId().filter(credentials.getString(1)::equals).isPresent()) throw ApiFailure.unauthorized(false);
                    }
                }
                if(rows.getString(4)!=null) guard(c,new TokenRegistry.Principal(principal.owner(),principal.permissions(),"TOKEN",rows.getString(4)));
                execute(c,"UPDATE tk_auth_session SET last_seen_at=? WHERE digest=?",now.toString(),principal.digest());
            }catch(SQLException failure) {throw databaseFailure(failure);}
        } else {
            try(var statement=query(c,"SELECT revoked,expires_at FROM tk_api_token WHERE digest=? AND owner_id=? FOR UPDATE",principal.digest(),principal.owner());var rows=statement.executeQuery()) {
                if(!rows.next() || rows.getBoolean(1) || (rows.getString(2)!=null && !Instant.parse(rows.getString(2)).isAfter(Instant.now()))) throw ApiFailure.unauthorized(true);
                execute(c,"UPDATE tk_api_token SET last_used_at=? WHERE digest=?",Instant.now().toString(),principal.digest());
            }catch(SQLException failure) {throw databaseFailure(failure);}
        }
    }
    JsonNode account(TokenRegistry.Principal principal) {return tx(c->{guard(c,principal);return account(c,principal.owner(),false);});}
    JsonNode context(TokenRegistry.Principal principal) {
        return tx(c->{guard(c,principal);var value=account(c,principal.owner(),false);var grants=value.putArray("permissions");principal.permissions().stream().sorted().forEach(grants::add);
            if(principal.kind().equals("SESSION")) try(var statement=query(c,"SELECT csrf,expires_at,persistent FROM tk_auth_session WHERE digest=? AND owner_id=?",principal.digest(),principal.owner());var rows=statement.executeQuery()) {
                if(!rows.next()) throw ApiFailure.unauthorized(false);value.put("csrf",rows.getString(1));value.put("expiresAt",rows.getString(2));value.put("remember",rows.getBoolean(3));
            }catch(SQLException failure) {throw databaseFailure(failure);}
            return value;});
    }
    void csrf(TokenRegistry.Principal principal,String value) {
        if(!principal.kind().equals("SESSION")) return;
        var context=context(principal);String expected=context.path("csrf").asText();
        if(value==null || !java.security.MessageDigest.isEqual(value.getBytes(java.nio.charset.StandardCharsets.UTF_8),expected.getBytes(java.nio.charset.StandardCharsets.UTF_8))) throw new ApiFailure(403,"CSRF_FAILED");
    }
    void logout(TokenRegistry.Principal principal) {
        if(principal.kind().equals("SESSION")) tx(c->{execute(c,"DELETE FROM tk_auth_session WHERE digest=? AND owner_id=?",principal.digest(),principal.owner());return null;});
    }
    static void revokeAll(Connection c,String owner) {
        execute(c,"DELETE FROM tk_auth_session WHERE owner_id=?",owner);execute(c,"UPDATE tk_api_token SET revoked=TRUE,version=version+1 WHERE owner_id=? AND revoked=FALSE",owner);
        execute(c,"UPDATE tk_recovery_link SET consumed=TRUE WHERE owner_id=? AND consumed=FALSE",owner);
        PasskeyCeremonies.cancelOwner(c,owner);
    }
    private static void requireWrite(TokenRegistry.Principal principal) {if(!principal.permissions().contains("write")) throw new ApiFailure(403,"FORBIDDEN");}
    private static void requireAdmin(Connection c,TokenRegistry.Principal principal) {
        requireWrite(principal);if(!account(c,principal.owner(),false).get("administrator").asBoolean()) throw new ApiFailure(403,"FORBIDDEN");
    }
    JsonNode tokens(TokenRegistry.Principal principal) {
        return tx(c->{guard(c,principal);var items=array();
            try(var statement=query(c,"SELECT id,name,permissions,expires_at,revoked,source,created_at,last_used_at,version FROM tk_api_token WHERE owner_id=? ORDER BY created_at,id",principal.owner());var rows=statement.executeQuery()) {
                while(rows.next()) {var value=object();value.put("id",rows.getObject(1).toString());value.put("name",rows.getString(2));var grants=value.putArray("permissions");permissions(rows.getString(3)).stream().sorted().forEach(grants::add);value.put("expiresAt",rows.getString(4));value.put("revoked",rows.getBoolean(5));value.put("source",rows.getString(6));value.put("createdAt",rows.getString(7));value.put("lastUsedAt",rows.getString(8));value.put("version",rows.getLong(9));items.add(value);}
            }catch(SQLException failure) {throw databaseFailure(failure);}
            var value=object();value.set("items",items);return value;});
    }
    JsonNode createToken(TokenRegistry.Principal principal,String name,Set<String> permissions,Instant expires) {
        requireWrite(principal);name=displayName(name);if(!permissions.contains("read") || !Set.of("read","write").containsAll(permissions) || (expires!=null && !expires.isAfter(Instant.now()))) throw ApiFailure.validation();
        final String label=name;return tx(c->{lock(c);guard(c,principal);String secret="tkp_"+AccountPasswords.secret();UUID id=UUID.randomUUID();
            execute(c,"INSERT INTO tk_api_token(digest,id,owner_id,name,permissions,expires_at,revoked,source,created_at,last_used_at,version) VALUES(?,?,?,?,?,?,FALSE,'MANAGED',?,NULL,0)",hash(secret),id,principal.owner(),label,permissions(permissions),expires==null ? null : expires.toString(),Instant.now().toString());
            var result=object();result.put("id",id.toString());result.put("token",secret);return result;});
    }
    void revokeToken(TokenRegistry.Principal principal,UUID id) {
        requireWrite(principal);tx(c->{lock(c);guard(c,principal);
            if(execute(c,"UPDATE tk_api_token SET revoked=TRUE,version=version+1 WHERE owner_id=? AND id=?",principal.owner(),id)!=1) throw ApiFailure.notFound();return null;});
    }
    JsonNode users(TokenRegistry.Principal principal) {
        return tx(c->{guard(c,principal);requireAdmin(c,principal);var items=array();
            try(var statement=query(c,"SELECT owner_id FROM tk_account ORDER BY owner_id");var rows=statement.executeQuery()) {while(rows.next()) items.add(account(c,rows.getString(1),false));}
            catch(SQLException failure) {throw databaseFailure(failure);}
            var result=object();result.set("users",items);return result;});
    }
    JsonNode user(TokenRegistry.Principal principal,String owner) {return tx(c->{guard(c,principal);requireAdmin(c,principal);return account(c,owner,false);});}
    JsonNode createUser(TokenRegistry.Principal principal,String username,String password,String display,boolean admin) {
        return createUser(principal,username,password,display,admin,null);
    }
    JsonNode createUser(TokenRegistry.Principal principal,String username,String password,String display,boolean admin,String email) {
        String name=username(username),label=displayName(display),encoded=AccountPasswords.hash(password);
        String pending=email==null ? null : RecoveryEmail.normalize(email);
        return tx(c->{lock(c);guard(c,principal);requireAdmin(c,principal);String id=UUID.randomUUID().toString();owner(c,id);ensureAccount(c,id);
            execute(c,"UPDATE tk_account SET username=?,password_hash=?,display_name=?,state='ENABLED',administrator=?,version=1 WHERE owner_id=?",name,encoded,label,admin,id);
            if(pending!=null) execute(c,"INSERT INTO tk_recovery_address VALUES(?,'PRIMARY',NULL,?,1)",id,pending);
            return account(c,id,false);});
    }
    JsonNode updateUser(TokenRegistry.Principal principal,String target,String match,String login,String display,boolean enabled,boolean admin) {
        String name=username(login),label=displayName(display);
        return tx(c->{lock(c);guard(c,principal);requireAdmin(c,principal);var previous=account(c,target,true);ApiReplies.match(previous,match);
            if(previous.get("state").asText().equals("UNCLAIMED")) throw ApiFailure.conflict();
            if(previous.get("administrator").asBoolean() && previous.get("state").asText().equals("ENABLED") && (!enabled || !admin)) {
                try(var statement=query(c,"SELECT COUNT(*) FROM tk_account WHERE administrator=TRUE AND state='ENABLED'");var rows=statement.executeQuery()) {rows.next();if(rows.getInt(1)<=1) throw ApiFailure.conflict();}
                catch(SQLException failure) {throw databaseFailure(failure);}
            }
            execute(c,"UPDATE tk_account SET username=?,display_name=?,state=?,administrator=?,version=version+1 WHERE owner_id=?",name,label,enabled ? "ENABLED" : "DISABLED",admin,target);
            if(!enabled || previous.get("administrator").asBoolean()!=admin) revokeAll(c,target);return account(c,target,false);});
    }
    JsonNode updateSelf(TokenRegistry.Principal principal,String match,String login,String display) {
        requireWrite(principal);String name=username(login),label=displayName(display);
        return tx(c->{lock(c);guard(c,principal);var previous=account(c,principal.owner(),true);ApiReplies.match(previous,match);
            if(previous.get("state").asText().equals("UNCLAIMED")) throw ApiFailure.conflict();
            execute(c,"UPDATE tk_account SET username=?,display_name=?,version=version+1 WHERE owner_id=?",name,label,principal.owner());return account(c,principal.owner(),false);});
    }
    void changePassword(TokenRegistry.Principal principal,String oldPassword,String newPassword) {
        requireWrite(principal);String encoded=AccountPasswords.hash(newPassword);
        tx(c->{lock(c);guard(c,principal);
            try(var statement=query(c,"SELECT password_hash FROM tk_account WHERE owner_id=?",principal.owner());var rows=statement.executeQuery()) {if(!rows.next() || !AccountPasswords.matches(oldPassword,rows.getString(1)==null ? DUMMY : rows.getString(1))) throw ApiFailure.unauthorized(false);}
            catch(SQLException failure) {throw databaseFailure(failure);}
            execute(c,"UPDATE tk_account SET password_hash=?,version=version+1 WHERE owner_id=?",encoded,principal.owner());revokeAll(c,principal.owner());return null;});
    }
}
