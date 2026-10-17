package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Shared desired/effective configuration. Only coordinated startup activates a saved version. */
@ApplicationScoped
class ServiceSettings {
    @Inject AccountStore accounts;
    @Inject RecoveryOrigin origin;
    @Inject PasskeyRealm realm;
    @Inject BrowserOrigins browserOrigins;
    @Inject RecoveryMail mail;
    @ConfigProperty(name="timekeeper.service-settings.recover",defaultValue="false") boolean recover;
    private volatile long localVersion=-1;
    private static final Set<String> FIELDS=Set.of("publicOrigin","originEpoch","passkeysEnabled","passkeyRpId","emailRecoveryEnabled",
            "smtpHost","smtpPort","smtpFrom","smtpUsername","smtpTls","smtpStartTls");
    record Stored(long version,long active,ObjectNode saved,ObjectNode effective,String savedPassword,String effectivePassword) {
        @Override public String toString() {return "Private service configuration";}
    }
    record Reply(ObjectNode data,String etag) { }
    static String tag(long version) {return "\"service-settings-"+version+"\"";}
    private static String config(String name,String fallback) {return ConfigProvider.getConfig().getOptionalValue(name,String.class).orElse(fallback);}
    private static ObjectNode deployment() {
        var value=object();String publicOrigin=config("timekeeper.auth.public-origin","NONE");
        value.put("publicOrigin",publicOrigin.equals("NONE") ? null : publicOrigin);
        value.put("originEpoch",publicOrigin.equals("NONE") ? 0 : Long.parseLong(config("timekeeper.auth.origin-epoch","1")));
        value.put("passkeysEnabled",Boolean.parseBoolean(config("timekeeper.auth.passkeys-enabled","true")));
        value.put("passkeyRpId",config("timekeeper.auth.passkey-rp-id","AUTO"));
        value.put("emailRecoveryEnabled",Boolean.parseBoolean(config("timekeeper.mail.enabled","false")));
        value.put("smtpHost",config("quarkus.mailer.host","localhost"));value.put("smtpPort",Integer.parseInt(config("quarkus.mailer.port","587")));
        value.put("smtpFrom",config("quarkus.mailer.from","timekeeper@localhost"));value.put("smtpUsername",config("quarkus.mailer.username",""));
        value.put("smtpTls",Boolean.parseBoolean(config("quarkus.mailer.tls","false")));value.put("smtpStartTls",config("quarkus.mailer.start-tls","REQUIRED"));
        return value;
    }
    private static String publicOrigin(JsonNode value) {return value.hasNonNull("publicOrigin") ? value.get("publicOrigin").asText() : "NONE";}
    private static String text(JsonNode value,String field,int length) {
        var item=value.get(field);
        if(item==null || !item.isTextual() || item.asText().length()>length || item.asText().chars().anyMatch(c->c<32 || c==127)) throw ApiFailure.validation();
        return item.asText();
    }
    private static void validate(ObjectNode value,String password) {
        var fields=new HashSet<String>();value.fieldNames().forEachRemaining(fields::add);if(!FIELDS.equals(fields)) throw ApiFailure.validation();
        for(String field:List.of("passkeysEnabled","emailRecoveryEnabled","smtpTls")) if(!value.path(field).isBoolean()) throw ApiFailure.validation();
        if(!value.path("originEpoch").canConvertToLong() || value.path("originEpoch").asLong()<0) throw ApiFailure.validation();
        if(!value.path("publicOrigin").isNull()) {
            String url=text(value,"publicOrigin",2048);try {RecoveryOrigin.validate(url);}catch(IllegalArgumentException invalid) {throw ApiFailure.validation();}
            if(value.path("originEpoch").asLong()<1) throw ApiFailure.validation();
        }
        try {PasskeyRealm.rp(publicOrigin(value),text(value,"passkeyRpId",255),value.path("passkeysEnabled").asBoolean());}catch(IllegalArgumentException invalid) {throw ApiFailure.validation();}
        if(value.path("emailRecoveryEnabled").asBoolean() && publicOrigin(value).equals("NONE")) throw ApiFailure.validation();
        if(!text(value,"smtpHost",255).matches("[A-Za-z0-9._:\\[\\]-]+") || !value.path("smtpPort").isIntegralNumber() || value.path("smtpPort").asInt()<1 || value.path("smtpPort").asInt()>65535) throw ApiFailure.validation();
        if(!text(value,"smtpFrom",320).matches("[^@\\s<>]+@[^@\\s<>]+")) throw ApiFailure.validation();
        text(value,"smtpUsername",255);if(!Set.of("DISABLED","OPTIONAL","REQUIRED").contains(text(value,"smtpStartTls",16))) throw ApiFailure.validation();
        if(password!=null && (password.length()>1024 || password.indexOf(0)>=0)) throw ApiFailure.validation();
    }
    private static Stored read(Connection c,boolean locked) {
        try(var statement=query(c,"SELECT version,active_version,saved,effective,saved_smtp_password,effective_smtp_password FROM tk_service_settings WHERE id=1"+(locked ? " FOR UPDATE" : ""));var rows=statement.executeQuery()) {
            if(!rows.next()) return null;
            var saved=(ObjectNode)parse(rows.getString(3));var effective=(ObjectNode)parse(rows.getString(4));
            String savedPassword=rows.getString(5),effectivePassword=rows.getString(6);
            try {validate(saved,savedPassword);validate(effective,effectivePassword);}catch(RuntimeException invalid) {throw new ApiFailure(503,"UNAVAILABLE");}
            return new Stored(rows.getLong(1),rows.getLong(2),saved,effective,savedPassword,effectivePassword);
        }catch(SQLException failure) {throw databaseFailure(failure);}
    }
    private static long nextEpoch(Connection c,long previous) {
        try(var statement=query(c,"SELECT epoch FROM tk_recovery_control WHERE id=1 FOR UPDATE");var rows=statement.executeQuery()) {
            if(!rows.next()) throw new SQLException("Missing authentication origin control");
            long last=Math.max(previous,rows.getLong(1));if(last>=9007199254740991L) throw ApiFailure.conflict();return last+1;
        }catch(SQLException failure) {throw databaseFailure(failure);}
    }
    void initialize() {
        var initial=deployment();String initialPassword=config("quarkus.mailer.password","");
        var stored=accounts.tx(c->{lock(c);var current=read(c,true);
            if(current==null) {
                validate(initial,initialPassword);
                execute(c,"INSERT INTO tk_service_settings(id,version,active_version,saved,effective,saved_smtp_password,effective_smtp_password) VALUES(1,0,0,?,?,?,?)",initial.toString(),initial.toString(),initialPassword,initialPassword);
            } else if(recover) {
                long configured=Long.parseLong(config("timekeeper.auth.origin-epoch","1"));
                long required=nextEpoch(c,current.saved().path("originEpoch").asLong());
                if(configured<required) throw new IllegalStateException("Service settings recovery requires an explicitly newer authentication origin epoch");
                initial.put("originEpoch",configured);validate(initial,initialPassword);long version=current.version()+1;
                if(version>9007199254740991L) throw ApiFailure.conflict();
                execute(c,"UPDATE tk_service_settings SET version=?,active_version=?,saved=?,effective=?,saved_smtp_password=?,effective_smtp_password=? WHERE id=1",version,version,initial.toString(),initial.toString(),initialPassword,initialPassword);
            } else if(current.version()!=current.active()) {
                execute(c,"UPDATE tk_service_settings SET active_version=version,effective=saved,effective_smtp_password=saved_smtp_password WHERE id=1");
            }
            return read(c,false);
        });
        var effective=stored.effective();
        origin.configure(publicOrigin(effective),effective.path("originEpoch").asLong());browserOrigins.configure(publicOrigin(effective));
        realm.configure(effective.path("passkeysEnabled").asBoolean(),effective.path("passkeyRpId").asText());
        mail.configure(effective,stored.effectivePassword());localVersion=stored.active();
    }
    void requireCurrent(Connection c) {
        if(localVersion<0) throw new ApiFailure(503,"UNAVAILABLE");
        try(var statement=query(c,"SELECT active_version FROM tk_service_settings WHERE id=1");var rows=statement.executeQuery()) {
            if(!rows.next() || rows.getLong(1)!=localVersion) throw new ApiFailure(503,"UNAVAILABLE");
        }catch(SQLException failure) {throw databaseFailure(failure);}
    }
    boolean available() {
        if(localVersion<0) return false;
        try {return accounts.tx(c->{requireCurrent(c);return true;});}catch(RuntimeException unavailable) {return false;}
    }
    private void manager(Connection c,TokenRegistry.Principal principal,boolean proof) {
        requireCurrent(c);accounts.guard(c,principal);requireAdmin(c,principal);
        if(proof && !accountSession(c,principal)) throw new ApiFailure(403,"FORBIDDEN");
    }
    Reply get(TokenRegistry.Principal principal) {return accounts.tx(c->{
        manager(c,principal,false);var value=read(c,false);requireCurrent(c);
        if(value.active()!=localVersion) throw new ApiFailure(503,"UNAVAILABLE");
        var reply=reply(value);reply.data().put("canEdit",accountSession(c,principal));return reply;
    });}
    Reply save(TokenRegistry.Principal principal,String match,JsonNode input) {
        boolean allowed=accounts.tx(c->{lock(c);manager(c,principal,true);return attempt(c,"service-settings:proof:"+principal.owner(),5,Duration.ofMinutes(5),Instant.now());});
        if(!allowed) throw new ApiFailure(429,"RATE_LIMITED");
        var outcome=accounts.tx(c->{lock(c);manager(c,principal,true);var current=read(c,true);
            if(match==null) throw new ApiFailure(428,"PRECONDITION_REQUIRED");if(!tag(current.version()).equals(match)) throw new ApiFailure(412,"STALE_VERSION");
            if(!currentPassword(c,principal.owner(),input.path("currentPassword").asText())) return null;
            var desired=current.saved().deepCopy();for(String key:FIELDS) if(!key.equals("originEpoch")) desired.set(key,input.get(key).deepCopy());
            String password=switch(input.path("smtpPasswordAction").asText()) {
                case "PRESERVE" -> current.savedPassword();case "CLEAR" -> "";case "REPLACE" -> input.path("smtpPassword").asText();default -> throw ApiFailure.validation();
            };
            if(!input.path("smtpPasswordAction").asText().equals("REPLACE") && input.hasNonNull("smtpPassword")) throw ApiFailure.validation();
            if(input.path("smtpPasswordAction").asText().equals("REPLACE") && (!input.path("smtpPassword").isTextual() || input.path("smtpPassword").asText().isEmpty())) throw ApiFailure.validation();
            if(desired.equals(current.saved()) && Objects.equals(password,current.savedPassword())) return reply(current);
            desired.put("originEpoch",nextEpoch(c,current.saved().path("originEpoch").asLong()));
            validate(desired,password);long version=current.version()+1;if(version>9007199254740991L) throw ApiFailure.conflict();
            if(execute(c,"UPDATE tk_service_settings SET version=?,saved=?,saved_smtp_password=? WHERE id=1 AND version=?",version,desired.toString(),password,current.version())!=1) throw new ApiFailure(412,"STALE_VERSION");
            return reply(read(c,false));
        });
        if(outcome==null) throw new ApiFailure(422,"SERVICE_PROOF_REQUIRED");return outcome;
    }
    private Reply reply(Stored value) {
        var result=object().put("canEdit",true).put("configurationManagedBy","administration").put("schemaVersion",ServerSchema.CURRENT)
                .put("publicOrigin",origin.configured().equals("NONE") ? null : origin.configured()).put("originEpoch",origin.epoch())
                .put("passkeysEnabled",realm.enabled()).put("passkeyRpId",realm.rpId().orElse(null)).put("emailRecoveryEnabled",mail.configuredEnabled())
                .put("version",value.version()).put("effectiveVersion",value.active()).put("restartRequired",value.version()!=value.active())
                .put("smtpPasswordConfigured",value.savedPassword()!=null && !value.savedPassword().isEmpty())
                .put("effectiveSmtpPasswordConfigured",value.effectivePassword()!=null && !value.effectivePassword().isEmpty());
        result.set("saved",value.saved().deepCopy());result.set("effective",value.effective().deepCopy());return new Reply(result,tag(value.version()));
    }
}
