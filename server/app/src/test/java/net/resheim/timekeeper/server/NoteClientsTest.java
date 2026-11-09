package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(NoteClientsTest.Profile.class)
class NoteClientsTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return VaultTestDatabase.configuration("noteclients",Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:note_clients_test"));}
    }
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @Inject ContractInputs inputs;
    private UUID client(String name){
        UUID id=UUID.randomUUID();var value=object().put("id",id.toString()).put("ownerId",store.owner()).put("version",1).put("name",name).put("color","#305070");
        store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,'CLIENT',?,1,?)",store.owner(),id,value.toString());return id;
    }
    @Test void associationChangesAreConditionalAndDoNotRewriteTheSource(){
        store.transaction("note-client-own",()->{
            var vault=new VaultCollection(store,limits);vault.initialize();byte[] body="# Private\r\n\r\nPreserve exact bytes  \r\n".getBytes(StandardCharsets.UTF_8);
            var entry=vault.put(new VaultPath("note.md"),null,true,body);var ref=VaultSources.fromEntry(entry);var clients=new NoteClients(store);UUID first=client("First"),second=client("Second");
            String initial=clients.tag(ref),contentTag=vault.tag(entry);var before=entry.deepCopy();
            assertTrue(clients.get(ref).path("clientId").isNull());assertEquals(1,clients.save(ref,initial,first).path("version").asLong());String firstTag=clients.tag(ref);
            assertEquals(first.toString(),clients.get(ref).path("clientId").asText());assertEquals(1,clients.save(ref,firstTag,first).path("version").asLong());
            assertEquals(2,clients.save(ref,firstTag,second).path("version").asLong());assertEquals(412,assertThrows(ApiFailure.class,()->clients.save(ref,firstTag,null)).status);assertEquals(second.toString(),clients.get(ref).path("clientId").asText());
            var after=vault.get(VaultCollection.id(entry));assertEquals(contentTag,vault.tag(after));assertEquals(before.path("createdAt"),after.path("createdAt"));assertEquals(before.path("modifiedAt"),after.path("modifiedAt"));assertArrayEquals(body,new VaultSources(store,limits,ref).read());
            assertTrue(store.related("client","CLIENT",second));
            vault.delete(after,vault.tag(after));assertEquals(second.toString(),clients.get(ref).path("clientId").asText());
            var restored=vault.restore(vault.get(VaultCollection.id(after)),vault.tag(vault.get(VaultCollection.id(after))));assertEquals(second.toString(),clients.get(ref).path("clientId").asText());assertArrayEquals(body,new VaultSources(store,limits,ref).read());
            assertTrue(clients.save(ref,clients.tag(ref),null).path("clientId").isNull());return null;
        });
    }
    @Test void emptyJournalAssociationIsPrivateAndDoesNotCreateBodyContent(){
        UUID foreign=store.transaction("note-client-foreign",()->client("Foreign"));
        String alice=store.transaction("note-client-empty",()->{
            var clients=new NoteClients(store);var ref=clients.reference(DayNotes.TYPE,"2026-11-09");String before=clients.tag(ref);long revision=store.revision();
            assertEquals(0,clients.save(ref,before,null).path("version").asLong());assertEquals(revision,store.revision());
            assertEquals(404,assertThrows(ApiFailure.class,()->clients.save(ref,before,foreign)).status);assertTrue(clients.get(ref).path("clientId").isNull());
            UUID own=client("Own");clients.save(ref,before,own);assertTrue(store.find(DayNotes.TYPE,ref.id()).isEmpty());return before;
        });
        store.transaction("note-client-other",()->{var clients=new NoteClients(store);var ref=clients.reference(DayNotes.TYPE,"2026-11-09");assertNotEquals(alice,clients.tag(ref));assertTrue(clients.get(ref).path("clientId").isNull());return null;});
    }
    private io.restassured.response.Response read(String token,String type,String key){return given().header("Authorization","Bearer "+token).queryParam("sourceType",type).queryParam("sourceKey",key).get("/api/v1/notes/client");}
    private io.restassured.response.Response save(String token,String type,String key,String tag,Object body){
        var request=given().header("Authorization","Bearer "+token).queryParam("sourceType",type).queryParam("sourceKey",key).contentType("application/json");if(tag!=null)request.header("If-Match",tag);return request.body(body).put("/api/v1/notes/client");
    }
    @Test void httpAssociationRequiresOwnedReferencesWriteAuthorityAndItsOwnValidator(){
        UUID own=store.transaction("a",()->client("HTTP own")),foreign=store.transaction("b",()->client("HTTP foreign"));String day="2026-11-17",type=DayNotes.TYPE;
        var first=read("test-a",type,day);assertEquals(200,first.statusCode());inputs.validate("NoteClientState",parse(first.asString()));assertTrue(parse(first.asString()).path("clientId").isNull());String tag=first.header("ETag");
        var body=object().put("clientId",own.toString());
        assertEquals(428,save("test-a",type,day,null,body.toString()).statusCode());
        assertEquals(422,save("test-a",type,day,tag,"{\"clientId\":true}").statusCode());
        assertEquals(400,save("test-a",type,day,tag,"{}").statusCode());
        assertEquals(403,save("test-read",type,day,tag,body.toString()).statusCode());
        assertEquals(404,save("test-a",type,day,tag,object().put("clientId",foreign.toString()).toString()).statusCode());
        assertEquals(412,save("test-b",type,day,tag,object().put("clientId",foreign.toString()).toString()).statusCode());
        var saved=save("test-a",type,day,tag,body.toString());assertEquals(200,saved.statusCode());assertEquals(own.toString(),saved.jsonPath().getString("clientId"));inputs.validate("NoteClientState",parse(saved.asString()));
        var client=given().header("Authorization","Bearer test-a").get("/api/v1/clients/"+own);assertEquals(200,client.statusCode());assertEquals(409,given().header("Authorization","Bearer test-a").header("If-Match",client.header("ETag")).delete("/api/v1/clients/"+own).statusCode());
        assertEquals(saved.header("ETag"),save("test-a",type,day,saved.header("ETag"),body.toString()).header("ETag"));
        assertTrue(parse(read("test-b",type,day).asString()).path("clientId").isNull());assertEquals(own.toString(),read("test-read",type,day).jsonPath().getString("clientId"));
        assertEquals(412,save("test-a",type,day,tag,"{\"clientId\":null}").statusCode());
        var cleared=save("test-a",type,day,saved.header("ETag"),"{\"clientId\":null}");assertEquals(200,cleared.statusCode());assertTrue(parse(cleared.asString()).path("clientId").isNull());
        assertEquals(204,given().header("Authorization","Bearer test-a").header("If-Match",client.header("ETag")).delete("/api/v1/clients/"+own).statusCode());
        var entry=given().header("Authorization","Bearer test-a").queryParam("path","Client private.md").header("If-None-Match","*").contentType("application/octet-stream").body("Private".getBytes(StandardCharsets.UTF_8)).put("/api/v1/notes/files");assertEquals(201,entry.statusCode());String key=entry.jsonPath().getString("sourceKey");
        assertEquals(200,read("test-a",VaultContent.TYPE,key).statusCode());assertEquals(404,read("test-b",VaultContent.TYPE,key).statusCode());
        assertEquals(400,given().header("Authorization","Bearer test-a").queryParam("sourceType",type).queryParam("sourceKey",day).queryParam("extra","x").get("/api/v1/notes/client").statusCode());
    }
}
