package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.*;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Shared, monotonic cutover fence. A stale replica cannot restore an old recovery origin. */
@ApplicationScoped
class RecoveryOrigin {
    @Inject AccountStore accounts;
    @ConfigProperty(name="timekeeper.auth.public-origin",defaultValue="NONE") String configured;
    @ConfigProperty(name="timekeeper.auth.origin-epoch",defaultValue="1") long epoch;

    record Current(String origin,long epoch) { }

    static String validate(String value) {
        try {
            var uri=URI.create(value);
            if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null
                    || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                    || (uri.getRawPath()!=null && !uri.getRawPath().isEmpty())
                    || uri.getPort()==0 || uri.getPort()>65535 || value.length()>2048)
                throw new IllegalArgumentException();
            return value;
        } catch(RuntimeException invalid) {
            throw new IllegalArgumentException("Recovery requires a configured HTTPS origin without a path");
        }
    }

    void initialize() {
        // Email remains optional. No origin is discovered from an incoming Host header.
        if("NONE".equals(configured)) return;
        String origin=validate(configured);
        if(epoch<1) throw new IllegalArgumentException("Authentication origin epoch must be positive");
        accounts.tx(c->{lock(c);
            var current=read(c);
            if(epoch<current.epoch() || (epoch==current.epoch() && !origin.equals(current.origin())))
                throw new IllegalStateException("Authentication origin changed without a newer epoch; update every replica before cutover");
            if(epoch>current.epoch()) {
                execute(c,"UPDATE tk_recovery_control SET origin=?,epoch=? WHERE id=1",origin,epoch);
                execute(c,"UPDATE tk_recovery_link SET consumed=TRUE WHERE consumed=FALSE");
                execute(c,"UPDATE tk_passkey_ceremony SET consumed=TRUE WHERE consumed=FALSE");
                execute(c,"DELETE FROM tk_auth_session");
            }
            return null;
        });
    }

    Current requireCurrent(Connection c) {
        var current=read(c);
        if(current.origin()==null || current.epoch()!=epoch || !current.origin().equals(configured))
            throw new ApiFailure(503,"UNAVAILABLE");
        return current;
    }

    private static Current read(Connection c) {
        try(var statement=query(c,"SELECT origin,epoch FROM tk_recovery_control WHERE id=1 FOR UPDATE");var rows=statement.executeQuery()) {
            if(!rows.next()) throw new SQLException("Missing recovery origin control");
            return new Current(rows.getString(1),rows.getLong(2));
        }catch(SQLException failure) {throw databaseFailure(failure);}
    }
}
