package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.*;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Trusted configuration only; neither RP nor origin is inferred from requests. */
@ApplicationScoped
class PasskeyRealm {
    @Inject RecoveryOrigin origin;
    @ConfigProperty(name="timekeeper.auth.passkeys-enabled",defaultValue="true") boolean configuredEnabled;
    @ConfigProperty(name="timekeeper.auth.passkey-rp-id",defaultValue="AUTO") String configuredRp;

    static Optional<String> rp(String publicOrigin,String configuredRp,boolean enabled) {
        if(!enabled || publicOrigin.equals("NONE")) return Optional.empty();
        var uri=URI.create(RecoveryOrigin.validate(publicOrigin));String host=uri.getHost();
        boolean dns=host.equals("localhost") || host.matches("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
        boolean numeric=host.matches("[0-9.]+") || host.contains(":");
        if(numeric) {
            if(!configuredRp.equals("AUTO")) throw new IllegalArgumentException("Passkey RP ID requires the configured HTTPS DNS hostname");
            return Optional.empty();
        }
        if(!dns || uri.getPort()==443 || !publicOrigin.equals("https://"+host+(uri.getPort()<0 ? "" : ":"+uri.getPort())))
            throw new IllegalArgumentException("Passkeys require a canonical lowercase HTTPS DNS origin without a trailing dot");
        if(!configuredRp.equals("AUTO") && !configuredRp.equals(host))
            throw new IllegalArgumentException("Passkey RP ID must equal the configured HTTPS hostname; changing it requires a documented origin cutover");
        return Optional.of(host);
    }
    Optional<String> rpId() {return rp(origin.configured(),configuredRp,configuredEnabled);}
    boolean enabled() {return rpId().isPresent();}
    void initialize() {
        rpId(); // Reject inconsistent configuration before changing shared authority.
        origin.initialize();
    }
    /** Plain read avoids reversing the global-control -> account/session lock order. */
    RecoveryOrigin.Current current(Connection connection) {
        try(var statement=query(connection,"SELECT origin,epoch FROM tk_recovery_control WHERE id=1");var rows=statement.executeQuery()) {
            if(!rows.next()) throw new SQLException("Missing authentication origin control");
            var value=new RecoveryOrigin.Current(rows.getString(1),rows.getLong(2));
            boolean matches=origin.configured().equals("NONE") ? value.origin()==null && value.epoch()==0
                    : origin.configured().equals(value.origin()) && origin.epoch()==value.epoch();
            if(!matches) throw new ApiFailure(503,"UNAVAILABLE");return value;
        }catch(SQLException failure) {throw databaseFailure(failure);}
    }
    PasskeyCeremonies.Scope scope(Connection connection,String browserSecret,String sessionSecret) {
        var current=current(connection);var rp=rpId().orElseThrow(()->new ApiFailure(503,"UNAVAILABLE"));
        return new PasskeyCeremonies.Scope(browserSecret,sessionSecret,current.origin(),rp,current.epoch());
    }
}
