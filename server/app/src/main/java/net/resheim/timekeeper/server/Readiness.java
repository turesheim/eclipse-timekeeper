package net.resheim.timekeeper.server;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.agroal.api.AgroalDataSource;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;

@ApplicationScoped @org.eclipse.microprofile.health.Readiness
public class Readiness implements HealthCheck {
    private static final org.jboss.logging.Logger LOG=org.jboss.logging.Logger.getLogger(Readiness.class);
    private final java.util.concurrent.atomic.AtomicLong nextFailureLog=new java.util.concurrent.atomic.AtomicLong();
    @Inject AgroalDataSource datasource;
    @Inject InstanceRestoreFence restoreFence;
    @Inject TokenRegistry tokens;
    @Inject PasskeyRealm realm;
    @Override public HealthCheckResponse call() {
        try(var connection=datasource.getConnection();var held=restoreFence.enter(connection)) {tokens.load();ServerSchema.validate(connection);realm.current(connection);if(nextFailureLog.getAndSet(0)!=0)LOG.info("Application readiness recovered");return HealthCheckResponse.up("Timekeeper configuration and schema");}
        catch(Exception e) {
            long now=System.currentTimeMillis(),next=nextFailureLog.get();
            if(now>=next&&nextFailureLog.compareAndSet(next,now+60000))LOG.warnf("Application readiness failed %s",ProblemMapper.diagnostic(e instanceof ApiFailure failure?failure.code:"READINESS_FAILED",e));
            return HealthCheckResponse.down("Timekeeper configuration and schema");
        }
    }
}
