package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.Instant;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

@QuarkusTest @TestProfile(WorkSessionDescriptionTest.Profile.class)
class WorkSessionDescriptionTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:work_session_descriptions");}
    }
    @Inject ServerStore store;
    @Test void foldingTitlesPreservesDistinctNotesAndAvoidsDuplicatingDescriptions() {
        assertEquals("Planning\n\nNotes",WorkSessionDescriptions.summary(object().put("title","Planning").put("summary","Notes")));
        assertEquals("Planning",WorkSessionDescriptions.summary(object().put("title","Planning").put("summary","")));
        assertEquals("Planning notes",WorkSessionDescriptions.summary(object().put("title","Planning").put("summary","Planning notes")));
        assertEquals("",WorkSessionDescriptions.summary(object().put("summary","")));
        String title="T".repeat(1000),notes="N".repeat(2000);
        assertEquals(title+"\n\n"+notes,WorkSessionDescriptions.summary(object().put("title",title).put("summary",notes)));
    }
    @Test void startupMigratesEveryOwnerOnceAndPreservesActiveTimersRelationsAndExactTimes() {
        var owners=List.of("summary-owner-"+UUID.randomUUID(),"summary-owner-"+UUID.randomUUID());
        var ids=new HashMap<String,UUID>();var metadata=new HashMap<String,com.fasterxml.jackson.databind.JsonNode>();
        for(String owner:owners) store.transaction(owner,()->{
            UUID client=uuid(store.createClient(object().put("name","Existing client").put("color","#176653")).get("id"));
            var project=store.withCreationClient(client,()->store.service().createProject(new CreateProject("Existing project")));
            var value=store.withCreationClient(client,()->store.workSessionService().createWorkSession(new CreateWorkSession(new OwnerId(owner),Instant.parse("2025-10-20T06:03:00.123456789Z"),Optional.empty(),"Existing notes")));
            var details=object().put("projectId",project.id().value().toString());details.putArray("labelIds").add(store.list("LABEL").getFirst().path("id").asText());
            metadata.put(owner,store.clientDetails("WORK_SESSION",value.id().value(),details).deepCopy());
            var node=((com.fasterxml.jackson.databind.node.ObjectNode)store.find("WORK_SESSION",value.id().value()).orElseThrow()).deepCopy();
            node.put("title","Existing title");ids.put(owner,value.id().value());
            store.update("UPDATE tk_resource SET payload=? WHERE owner_id=? AND entity_type='WORK_SESSION' AND id=?",node.toString(),owner,value.id().value());return null;
        });
        store.initializeConfiguredOwners();
        for(String owner:owners) store.transaction(owner,()->{
            var value=store.find("WORK_SESSION",ids.get(owner)).orElseThrow();assertFalse(value.has("title"));
            assertEquals("Existing title\n\nExisting notes",value.path("summary").asText());assertEquals(1,value.path("version").asLong());
            assertEquals("2025-10-20T06:03:00.123456789Z",value.path("start").asText());assertTrue(value.path("end").isNull());
            assertEquals(ids.get(owner),store.workSessionService().activeWorkSession(new OwnerId(owner)).orElseThrow().id().value());
            assertEquals(owner,value.path("ownerId").asText());assertTrue(store.find("CLIENT",uuid(value.get("clientId"))).isPresent());
            for(String field:List.of("clientId","projectId","labelIds"))assertEquals(metadata.get(owner).get(field),value.get(field));
            long revision=store.revision();store.migrateWorkSessionDescriptions();assertEquals(revision,store.revision());
            var stopped=store.workSessionService().stopWorkSession(new StopWorkSession(new WorkSessionId(ids.get(owner)),1));
            assertEquals(value.path("summary").asText(),stopped.summary());assertFalse(store.find("WORK_SESSION",ids.get(owner)).orElseThrow().has("title"));return null;
        });
        store.initializeConfiguredOwners();
        for(String owner:owners) store.transaction(owner,()->{
            var value=store.find("WORK_SESSION",ids.get(owner)).orElseThrow();assertFalse(value.has("title"));assertEquals(2,value.path("version").asLong());
            for(String field:List.of("clientId","projectId","labelIds"))assertEquals(metadata.get(owner).get(field),value.get(field));return null;
        });
    }
}
