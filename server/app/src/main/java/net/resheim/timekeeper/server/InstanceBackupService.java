package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.io.IOException;
import java.nio.file.*;
import java.sql.Connection;
import java.util.Arrays;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Privileged archive creation. No HTTP route exposes these primitives directly. */
@ApplicationScoped
class InstanceBackupService {
    @Inject InstanceAdministrator administrators;
    @Inject AgroalDataSource datasource;
    @Inject RecoveryOrigin origin;
    @Inject ServerInfo info;
    @ConfigProperty(name="timekeeper.package-id",defaultValue="timekeeper") String packageId;

    /** Consumes and clears the supplied passphrase, including on failure. */
    Path create(InstanceAdministrator.Proof proof,char[] passphrase,Path privateRoot) {
        Path encrypted=null;boolean complete=false;
        try {
            administrators.requireFresh(proof);
            if(passphrase==null||passphrase.length<12||passphrase.length>1024)throw ApiFailure.validation();
            try(var configuration=InstanceConfiguration.captureRuntime()) {
                try(var connection=datasource.getConnection()) {
                    String backend=connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")?"postgresql":"h2";
                    connection.setTransactionIsolation(backend.equals("postgresql")?Connection.TRANSACTION_REPEATABLE_READ:Connection.TRANSACTION_SERIALIZABLE);connection.setAutoCommit(false);
                    try {
                        var build=info.journalIdentity();
                        var source=object().put("serverVersion",build.path("version").asText()).put("commit",build.path("commit").isTextual()?build.path("commit").asText():"unknown").put("databaseType",backend).put("packageId",packageId);
                        source.put("publicOrigin",origin.configured().equals("NONE")?null:origin.configured());
                        encrypted=InstanceBackupArchive.create(connection,configuration,source,passphrase,privateRoot);connection.commit();
                    }catch(Exception failure){connection.rollback();throw failure;}
                }
                try(var current=InstanceConfiguration.captureRuntime()) {
                    if(!configuration.matches(current))throw new ApiFailure(503,"INSTANCE_CONFIGURATION_CHANGED");
                }
            }
            // A long-running operation may outlive its initial five-minute
            // proof window. It still cannot publish after logout, revocation,
            // demotion or a password change invalidates the original session.
            administrators.require(proof.principal());complete=true;return encrypted;
        }catch(ApiFailure failure){throw failure;}
        catch(Exception failure){throw new ApiFailure(503,"INSTANCE_BACKUP_FAILED");}
        finally {
            if(passphrase!=null)Arrays.fill(passphrase,'\0');
            if(!complete&&encrypted!=null)try{Files.deleteIfExists(encrypted);}catch(IOException failure){throw new ApiFailure(503,"INSTANCE_BACKUP_CLEANUP_FAILED");}
        }
    }
}
