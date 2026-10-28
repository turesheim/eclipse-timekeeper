package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import static net.resheim.timekeeper.server.InstanceRestoreTarget.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import net.resheim.eclipse.timekeeper.domain.DefaultLabels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceRestoreTargetTest {
    @TempDir Path directory;
    Connection target() throws Exception {
        var connection=DriverManager.getConnection("jdbc:h2:"+directory.resolve("target"));ServerSchema.initializeOrValidate(connection);
        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);connection.setAutoCommit(false);
        execute(connection,"INSERT INTO tk_owner VALUES('bootstrap','UTC',0,0,?)","a".repeat(64));
        execute(connection,"INSERT INTO tk_account(owner_id,username,password_hash,display_name,state,administrator,initialized,version) VALUES('bootstrap','administrator','synthetic-password-hash','Administrator','ENABLED',TRUE,TRUE,1)");connection.commit();return connection;
    }
    void defaults(Connection c) {
        int sequence=0;
        for(var label:DefaultLabels.definitions()){
            var id=UUID.randomUUID();var value=object().put("id",id.toString()).put("ownerId","bootstrap").put("version",0).put("name",label.name()).put("color",label.hexColor());
            execute(c,"INSERT INTO tk_resource VALUES('bootstrap','LABEL',?,0,?)",id,value.toString());
            execute(c,"INSERT INTO tk_change VALUES('bootstrap',?,'LABEL',?,'CREATED','2026-10-28T00:00:00Z')",++sequence,id.toString());
        }
    }
    @Test void unusedBootstrapAndUnmodifiedBuiltInLabelsAreEligibleWithoutChangingRows() throws Exception {
        try(var c=target()){
            assertTrue(inspect(c,"bootstrap").databaseEligible());defaults(c);c.commit();
            assertTrue(inspect(c,"bootstrap").databaseEligible());c.commit();
            try(var rows=c.createStatement().executeQuery("SELECT COUNT(*) FROM tk_resource")){assertTrue(rows.next());assertEquals(8,rows.getLong(1));}
            execute(c,"UPDATE tk_resource SET version=1 WHERE entity_type='LABEL'");assertTrue(inspect(c,"bootstrap").blockers().contains(Blocker.TARGET_BUSINESS_DATA));
        }
    }
    @Test void journalAttachmentsUnknownResourceTypesAndDeletedBusinessHistoryBlockReplacement() throws Exception {
        try(var c=target()){
            for(String type:List.of("DAY_LABEL","DAY_NOTE","WEEK_NOTE","JOURNAL_ASSET","CLIENT","TASK","FUTURE_STATE")){
                var save=c.setSavepoint();execute(c,"INSERT INTO tk_resource VALUES('bootstrap',?,?,1,?)",type,UUID.randomUUID(),"{\"private\":\""+"x".repeat(type.equals("JOURNAL_ASSET")?3_000_000:1)+"\"}");
                assertTrue(inspect(c,"bootstrap").blockers().contains(Blocker.TARGET_BUSINESS_DATA));c.rollback(save);
            }
            execute(c,"INSERT INTO tk_change VALUES('bootstrap',1,'CLIENT',?,'DELETED','2026-10-28T00:00:00Z')",UUID.randomUUID().toString());
            assertEquals(Set.of(Blocker.TARGET_BUSINESS_HISTORY),inspect(c,"bootstrap").blockers());
        }
    }
    @Test void anotherAccountAndEvenRevokedTokensAreNotDisposableBootstrapData() throws Exception {
        try(var c=target()){
            execute(c,"INSERT INTO tk_owner VALUES('other','UTC',0,0,?)","b".repeat(64));assertTrue(inspect(c,"bootstrap").blockers().contains(Blocker.TARGET_OTHER_ACCOUNTS));
            execute(c,"INSERT INTO tk_api_token(digest,id,owner_id,name,permissions,revoked,source,created_at,version) VALUES(?,?,'bootstrap','Private token','READ_WRITE',TRUE,'MANAGED','2026-10-28T00:00:00Z',1)","c".repeat(64),UUID.randomUUID());
            assertTrue(inspect(c,"bootstrap").blockers().contains(Blocker.TARGET_API_TOKENS));
        }
    }
    @Test void incompleteDefaultsExtraFieldsAndUnknownSchemaFailClosed() throws Exception {
        try(var c=target()){
            defaults(c);execute(c,"DELETE FROM tk_resource WHERE id=(SELECT MIN(id) FROM tk_resource)");assertFalse(inspect(c,"bootstrap").databaseEligible());
            execute(c,"CREATE TABLE tk_future_secret(id INTEGER)");assertThrows(SQLException.class,()->inspect(c,"bootstrap"));
        }
    }
    @Test void inspectionRefusesUnsafeTransactionIsolation() throws Exception {
        try(var c=target()){
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);assertThrows(SQLException.class,()->inspect(c,"bootstrap"));
        }
    }
}
