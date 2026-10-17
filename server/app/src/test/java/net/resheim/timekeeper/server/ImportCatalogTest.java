package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import net.resheim.eclipse.timekeeper.service.Commands.CreateProject;

@QuarkusTest @TestProfile(ImportCatalogTest.Profile.class)
class ImportCatalogTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:import_catalog_tests");}
    }
    @Inject ServerStore store;
    private ObjectNode mapping(String key,String type,String name,String parent) {
        var value=object().put("key",hash(key)).put("resourceType",type).put("action","CREATE").put("name",name).put("color","#6750a4");
        if(parent!=null) value.put("parentKey",hash(parent));return value;
    }
    private ObjectNode job(ImportLedger ledger,ImportCatalog catalog,ArrayNode plan) {
        var value=ledger.create("Reviewed import",hash("source"),"Europe/Oslo",hash("file"),hash(canonical(plan).toString()));
        catalog.register(value,plan);return ledger.update(ImportLedger.Kind.IMPORT_JOB,value);
    }
    @Test void explicitMapsKeepEquallyNamedProjectsUnderDifferentClientsAndCreateEachRecordOnce() {
        String owner="import-catalog-"+UUID.randomUUID();var ledger=new ImportLedger(store);var catalog=new ImportCatalog(store);
        var plan=array().add(mapping("client-a","CLIENT","Client A",null)).add(mapping("client-b","CLIENT","Client B",null))
                .add(mapping("project-a","PROJECT","Same project name","client-a")).add(mapping("project-b","PROJECT","Same project name","client-b"))
                .add(mapping("task","TASK","Source task","project-a")).add(mapping("label","LABEL","Source tag",null));
        UUID job=uuid(store.transaction(owner,()->job(ledger,catalog,plan)).get("id"));
        store.transaction(owner,()->ledger.batch(job,0,object(),true,state->{
            var a=catalog.resolve(state,hash("project-a"),"PROJECT");var b=catalog.resolve(state,hash("project-b"),"PROJECT");
            assertNotEquals(a.path("id"),b.path("id"));assertNotEquals(a.path("clientId"),b.path("clientId"));
            assertEquals(a,catalog.resolve(state,hash("project-a"),"PROJECT"));
            var task=catalog.resolve(state,hash("task"),"TASK");assertEquals(a.path("id"),task.path("projectId"));
            catalog.resolve(state,hash("label"),"LABEL");return object();
        }));
        store.transaction(owner,()->{
            assertEquals(2,store.list("CLIENT").size(),"Explicit client mapping must not create an unreviewed General client");
            assertEquals(2,store.service().projects().size());assertEquals(1,store.service().tasks().size());
            var current=ledger.required(ImportLedger.Kind.IMPORT_JOB,job);assertEquals(6,current.path("catalogCount").asInt());assertEquals(6,current.path("itemCount").asInt());
            for(int index=0;index<6;index++) assertTrue(ledger.required(ImportLedger.Kind.IMPORT_ITEM,ledger.itemId(job,index)).path("created").asBoolean());return null;
        });
    }
    @Test void foreignReuseFailsAtomicallyAndAClientContextCannotEscapeItsScope() {
        String owner="import-reuse-map-"+UUID.randomUUID(),foreign="foreign-map-"+UUID.randomUUID();
        var ledger=new ImportLedger(store);var catalog=new ImportCatalog(store);
        var other=store.transaction(foreign,()->{
            var created=store.service().createProject(new CreateProject("Foreign project"));return store.find("PROJECT",created.id().value()).orElseThrow();
        });
        var parent=mapping("client","CLIENT","Reviewed client",null);
        var project=object().put("key",hash("project")).put("resourceType","PROJECT").put("action","REUSE")
                .put("id",other.path("id").asText()).put("etag",etag(other)).put("parentKey",hash("client"));
        var plan=array().add(parent).add(project);
        assertEquals(404,assertThrows(ApiFailure.class,()->store.transaction(owner,()->catalog.review(plan))).status);
        assertEquals(404,assertThrows(ApiFailure.class,()->store.transaction(owner,()->job(ledger,catalog,plan))).status);
        store.transaction(owner,()->{
            assertTrue(store.list("CLIENT").isEmpty());assertTrue(store.service().projects().isEmpty());
            assertTrue(store.list("IMPORT_JOB").isEmpty());assertTrue(store.list("IMPORT_MAP").isEmpty());
            var client=store.createClient(object().put("name","Existing client").put("color","#6750a4"));
            assertThrows(IllegalStateException.class,()->store.withCreationClient(uuid(client.get("id")),()->{throw new IllegalStateException("Cancelled");}));
            var ordinary=store.service().createProject(new CreateProject("Ordinary project"));
            assertNotEquals(client.path("id"),store.find("PROJECT",ordinary.id().value()).orElseThrow().path("clientId"));return null;
        });
    }
    @Test void aDestinationEditedAfterReviewRequiresNewConfirmationAndRollsBackMappingAudit() {
        String owner="import-stale-map-"+UUID.randomUUID();var ledger=new ImportLedger(store);var catalog=new ImportCatalog(store);
        var existing=store.transaction(owner,()->{
            var client=store.createClient(object().put("name","Reviewed client").put("color","#6750a4"));
            var project=store.withCreationClient(uuid(client.get("id")),()->store.service().createProject(new CreateProject("Reviewed project")));
            return Map.of("client",client,"project",store.find("PROJECT",project.id().value()).orElseThrow());
        });
        var client=existing.get("client");var project=existing.get("project");
        var plan=array().add(object().put("key",hash("client")).put("resourceType","CLIENT").put("action","REUSE").put("id",client.path("id").asText()).put("etag",etag(client)))
                .add(object().put("key",hash("project")).put("resourceType","PROJECT").put("action","REUSE").put("id",project.path("id").asText()).put("etag",etag(project)).put("parentKey",hash("client")));
        UUID job=uuid(store.transaction(owner,()->job(ledger,catalog,plan)).get("id"));
        store.transaction(owner,()->store.service().updateProject(new net.resheim.eclipse.timekeeper.service.Commands.UpdateProject(new net.resheim.eclipse.timekeeper.domain.ProjectId(uuid(project.get("id"))),project.path("version").asLong(),"Peer edit")));
        assertEquals(412,assertThrows(ApiFailure.class,()->store.transaction(owner,()->ledger.batch(job,0,object(),true,state->{
            catalog.resolve(state,hash("project"),"PROJECT");return object();
        }))).status);
        store.transaction(owner,()->{
            assertEquals("Peer edit",store.find("PROJECT",uuid(project.get("id"))).orElseThrow().path("name").asText());
            assertFalse(catalog.definition(job,hash("client")).has("targetId"));
            assertEquals(0,ledger.required(ImportLedger.Kind.IMPORT_JOB,job).path("itemCount").asInt());return null;
        });
    }
}
