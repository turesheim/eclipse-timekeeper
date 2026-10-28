package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;

/** Offline recovery sequence. Launchers call it before starting Quarkus. */
final class InstanceRestoreRecovery {
    private InstanceRestoreRecovery() { }
    @FunctionalInterface interface ConfigurationCheck {void validate(InstanceRestoreMaterial.Installed material) throws IOException;}
    static InstanceRestoreMaterial.Installed resume(Connection c,InstanceRestoreIntent intent,Instant now) throws IOException,SQLException {return resume(c,intent,now,material->{});}
    static InstanceRestoreMaterial.Installed resume(Connection c,InstanceRestoreIntent intent,Instant now,ConfigurationCheck configuration) throws IOException,SQLException {
        if(!c.getAutoCommit())throw new SQLException("Restore recovery requires a fresh connection");
        c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        var complete=completed(c,intent);
        if(complete!=null){intent.cleanupCompleted(c,complete);return complete;}
        InstanceRestoreMaterial.Installed material;
        try(var prepared=intent.open();var exclusive=InstanceRestoreFence.exclusive(c)) {
            prepared.targetBinding(c);String phase=prepared.phase(c);material=InstanceRestoreMaterial.install(prepared);configuration.validate(material);
            if(phase.equals("PREPARED")) {
                c.setAutoCommit(false);var spec=prepared.spec();
                InstanceRestoreDatabase.apply(c,exclusive,prepared.archive(),spec.deployment(),spec.actor(),spec.operation(),spec.reviewVersion()+1,spec.artifactRoot(),spec.generation(),now);
                c.commit();c.setAutoCommit(true);phase=prepared.phase(c);
            }
            if(phase.equals("DATABASE_APPLIED")) {
                InstanceRestoreMaterial.publish(prepared,c,exclusive,material);c.setAutoCommit(false);prepared.complete(c,exclusive,now);c.commit();c.setAutoCommit(true);
            }else if(!phase.equals("COMPLETE"))throw new IOException("INSTANCE_RESTORE_NOT_AUTHORIZED");
        }
        intent.cleanupCompleted(c,material);return material;
    }
    private static InstanceRestoreMaterial.Installed completed(Connection c,InstanceRestoreIntent intent) throws IOException,SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT payload FROM tk_instance WHERE kind='CONTROL'")) {
            if(!r.next())throw new SQLException("Missing instance control");var control=parse(r.getString(1));
            if(control.path("restorePending").asBoolean()||!control.path("restore").path("phase").asText().equals("COMPLETE"))return null;
            var material=InstanceRestoreMaterial.active(intent.storage());
            if(!material.generation().toString().equals(control.path("restoreGeneration").asText())||!material.operation().toString().equals(control.path("restore").path("operationId").asText())||!material.digest().equals(control.path("restore").path("materialSha256").asText())||!material.intentDigest().equals(control.path("restoreIntent").path("sha256").asText()))throw new IOException("INVALID_INSTANCE_RESTORE_MATERIAL");return material;
        }
    }
}
