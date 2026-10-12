package net.resheim.timekeeper.server;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.agroal.api.AgroalDataSource;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;

@ApplicationScoped @org.eclipse.microprofile.health.Readiness
public class Readiness implements HealthCheck {
    @Inject AgroalDataSource datasource;
    @Inject TokenRegistry tokens;
    @Override public HealthCheckResponse call() {
        try(var connection=datasource.getConnection()) {tokens.load();ServerSchema.validate(connection);return HealthCheckResponse.up("Timekeeper configuration and schema");}
        catch(Exception e) {return HealthCheckResponse.down("Timekeeper configuration and schema");}
    }
}
