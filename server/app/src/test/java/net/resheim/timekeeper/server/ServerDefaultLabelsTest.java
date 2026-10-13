package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import net.resheim.eclipse.timekeeper.service.Commands.CreateLabel;
import net.resheim.eclipse.timekeeper.service.Commands.DeleteLabel;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateLabel;

@QuarkusTest
class ServerDefaultLabelsTest {
    @Inject ServerStore store;
    @Inject TokenRegistry registry;
    @Inject AgroalDataSource datasource;
    @TempDir Path temporary;

    private static final Map<String,String> EXPECTED=Map.of(
            "Production issue","#F46758","Testing","#F5A651",
            "Prototyping","#F6D05A","Planning","#FF00FF",
            "Programming","#57CE69","Debugging","#B16FD1",
            "Communication","#4288F3","Meeting","#9C9CA0");

    @Test void configuredOwnersHaveDefaultsBeforeAnyClientRequest() throws Exception {
        // Query JDBC directly: calling an owner transaction could provision it itself.
        for (String owner:List.of("a","b")) {
            try (var connection=datasource.getConnection();var query=connection.prepareStatement(
                    "SELECT payload FROM tk_resource WHERE owner_id=? AND entity_type='LABEL'")) {
                query.setString(1,owner);
                var labels=new java.util.HashMap<String,String>();
                try(var rows=query.executeQuery()) {
                    while(rows.next()) {
                        var label=JsonValues.parse(rows.getString(1));
                        labels.put(label.get("name").asText(),label.get("color").asText());
                    }
                }
                assertEquals(EXPECTED,labels);
            }
        }
    }

    @Test void concurrentFirstUseSeedsOnceWithOwnerScopedIdentities() throws Exception {
        String owner="concurrent-defaults-"+UUID.randomUUID();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->labels(owner));var second=pool.submit(()->labels(owner));
            var a=first.get();var b=second.get();assertEquals(a,b);
            assertEquals(EXPECTED,a.stream().collect(Collectors.toMap(v->v.get("name").asText(),v->v.get("color").asText())));
            var other=labels("other-defaults-"+UUID.randomUUID());
            assertTrue(java.util.Collections.disjoint(
                    a.stream().map(v->v.get("id").asText()).toList(),
                    other.stream().map(v->v.get("id").asText()).toList()));
            assertTrue(a.stream().allMatch(v->v.get("ownerId").asText().equals(owner)));
        }
    }

    @Test void startupPreservesCustomizedSetsAndSeedsExistingEmptyOwners() throws Exception {
        String owner="existing-defaults-"+UUID.randomUUID();
        var original=labels(owner);
        store.transaction(owner,()->{
            var service=store.service();var first=service.labels().get(0);
            service.updateLabel(new UpdateLabel(first.id(),first.version(),"My customized label",Optional.of("#123456")));
            for(var label:service.labels()) if(!label.name().equals("My customized label"))
                service.deleteLabel(new DeleteLabel(label.id(),label.version()));
            service.createLabel(new CreateLabel("My custom category",Optional.empty()));return null;
        });
        var customized=labels(owner);assertEquals(2,customized.size());
        configured(owner,()->{store.initializeConfiguredOwners();store.initializeConfiguredOwners();});
        assertEquals(customized,labels(owner));
        store.transaction(owner,()->{var service=store.service();for(var label:service.labels())
            service.deleteLabel(new DeleteLabel(label.id(),label.version()));return null;});
        assertTrue(labels(owner).isEmpty()); // Reads do not recreate deliberately deleted labels.
        configured(owner,store::initializeConfiguredOwners);
        var seeded=labels(owner);assertEquals(EXPECTED,seeded.stream().collect(Collectors.toMap(
                v->v.get("name").asText(),v->v.get("color").asText())));
        assertTrue(java.util.Collections.disjoint(original,seeded));
    }

    @Test void failedFirstTransactionDoesNotLeavePartialDefaults() throws Exception {
        String owner="rollback-defaults-"+UUID.randomUUID();
        assertThrows(IllegalStateException.class,()->store.transaction(owner,()->{throw new IllegalStateException("Abort fixture");}));
        try(var connection=datasource.getConnection();var query=connection.prepareStatement("SELECT COUNT(*) FROM tk_resource WHERE owner_id=?")) {
            query.setString(1,owner);try(var rows=query.executeQuery()) {assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
        }
        assertEquals(EXPECTED.size(),labels(owner).size());
    }

    private List<JsonNode> labels(String owner) {return store.transaction(owner,()->store.list("LABEL"));}
    private void configured(String owner,Runnable operation) throws Exception {
        var actual=io.quarkus.arc.ClientProxy.unwrap(registry);String previous=actual.tokensFile;
        var file=temporary.resolve("tokens.json");
        Files.writeString(file,"[{\"ownerId\":\""+owner+"\",\"sha256\":\""+"a".repeat(64)+"\",\"permissions\":[\"read\"]}]");
        try {actual.tokensFile=file.toString();operation.run();}finally {actual.tokensFile=previous;}
    }
}
