package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import java.util.Map;
import jakarta.inject.Inject;
import static net.resheim.timekeeper.server.JsonValues.*;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(DayNoteHttpTest.Profile.class)
class DayNoteHttpTest {
    @Inject ServerStore store;
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:day_note_http");}
    }
    private Response get(String token,String date){return given().header("Authorization","Bearer "+token).get("/api/v1/day-notes/"+date);}
    private Response save(String token,String date,String etag,String text){return given().header("Authorization","Bearer "+token).header("If-Match",etag).contentType("application/json").body(Map.of("text",text)).put("/api/v1/day-notes/"+date);}
    private Response list(String token,String first,String last){return given().header("Authorization","Bearer "+token).queryParam("fromDate",first).queryParam("toDate",last).get("/api/v1/day-notes");}
    private Response update(String token,String date,String etag,com.fasterxml.jackson.databind.JsonNode input){return given().header("Authorization","Bearer "+token).header("If-Match",etag).contentType("application/json").body(input.toString()).put("/api/v1/day-notes/"+date);}
    @Test void markdownIsExactIndependentAndSummariesNeverIncludeBodies(){
        String day="2026-08-01",body="\r\n# Private journal marker\r\n\r\n- [ ] Øvelse\t\r\n  trailing spaces  \r\n";
        var empty=get("test-a",day);assertEquals("",empty.jsonPath().getString("body"));assertFalse(empty.jsonPath().getBoolean("hasBody"));
        var saved=update("test-a",day,empty.header("ETag"),object().put("body",body));assertEquals(200,saved.statusCode());assertEquals(body,saved.jsonPath().getString("body"));assertEquals("",saved.jsonPath().getString("text"));
        assertEquals("private, no-store",saved.header("Cache-Control"));
        var summary=list("test-a",day,day);assertEquals(1,summary.jsonPath().getList("items").size());assertTrue(summary.jsonPath().getBoolean("items[0].hasBody"));assertFalse(parse(summary.asString()).path("items").get(0).has("body"));assertFalse(summary.asString().contains("Private journal marker"));
        assertEquals(body,get("test-read",day).jsonPath().getString("body"));assertEquals("",get("test-b",day).jsonPath().getString("body"));assertTrue(list("test-b",day,day).jsonPath().getList("items").isEmpty());
        var label=save("test-a",day,saved.header("ETag"),"  Planning  ");assertEquals(body,label.jsonPath().getString("body"));assertEquals("Planning",label.jsonPath().getString("text"));
        var same=save("test-a",day,label.header("ETag"),"Planning");assertEquals(label.header("ETag"),same.header("ETag"));
        var cleared=update("test-a",day,label.header("ETag"),object().put("body",""));assertEquals(200,cleared.statusCode());assertEquals("Planning",cleared.jsonPath().getString("text"));assertFalse(cleared.jsonPath().getBoolean("hasBody"));
        var bodyOnly=update("test-a",day,cleared.header("ETag"),object().put("text","").put("body","\n \t\r\n"));assertTrue(bodyOnly.jsonPath().getBoolean("hasBody"));
        assertEquals(412,update("test-a",day,label.header("ETag"),object().put("body","stale")).statusCode());
        var removed=given().header("Authorization","Bearer test-a").header("If-Match",bodyOnly.header("ETag")).delete("/api/v1/day-notes/"+day);assertEquals(204,removed.statusCode());assertNotNull(removed.header("ETag"));
        assertTrue(list("test-a",day,day).jsonPath().getList("items").isEmpty());assertEquals("",get("test-a",day).jsonPath().getString("body"));
        assertEquals(412,update("test-a",day,empty.header("ETag"),object().put("body","stale create")).statusCode());
        var recreated=update("test-a",day,removed.header("ETag"),object().put("body",body));assertEquals(200,recreated.statusCode());assertNotEquals(bodyOnly.header("ETag"),recreated.header("ETag"));
        assertEquals(412,given().header("Authorization","Bearer test-a").header("If-Match",removed.header("ETag")).delete("/api/v1/day-notes/"+day).statusCode());
        assertFalse(given().header("Authorization","Bearer test-a").get("/api/v1/changes?after=0").asString().contains("Private journal marker"));
    }
    @Test void legacyLabelsAcquireAnEmptyBodyWithoutRewritingTheirVersion(){
        String day="2026-08-02";var date=DayNotes.date(day);
        store.transaction("a",()->{var legacy=object().put("id",DayNotes.id(date).toString()).put("ownerId","a").put("version",7).put("date",day).put("text","Legacy label");store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,7,?)","a",DayNotes.TYPE,DayNotes.id(date),legacy.toString());return null;});
        var loaded=get("test-a",day);assertEquals(7,loaded.jsonPath().getLong("version"));assertEquals("Legacy label",loaded.jsonPath().getString("text"));assertEquals("",loaded.jsonPath().getString("body"));
        var changed=update("test-a",day,loaded.header("ETag"),object().put("body","# New body\n"));assertEquals(200,changed.statusCode());assertEquals(8,changed.jsonPath().getLong("version"));assertEquals("Legacy label",changed.jsonPath().getString("text"));
        assertEquals(412,update("test-b",day,changed.header("ETag"),object().put("body","foreign")).statusCode());assertEquals("",get("test-b",day).jsonPath().getString("body"));
        assertEquals(401,given().header("Authorization","Bearer test-b").header(OwnerBinding.HEADER,OwnerBinding.encode("a")).get("/api/v1/day-notes/"+day).statusCode());
    }
    @Test void deletionClearingAndNeverSeenDatesHaveDistinctDurableVersions(){
        String day="2026-08-06";var initial=get("test-a",day);
        assertFalse(initial.jsonPath().getBoolean("deleted"));assertEquals(0,initial.jsonPath().getLong("version"));
        var created=update("test-a",day,initial.header("ETag"),object().put("body","Keep deletion separate"));
        var cleared=update("test-a",day,created.header("ETag"),object().put("body",""));assertFalse(cleared.jsonPath().getBoolean("deleted"));
        var removed=given().header("Authorization","Bearer test-a").header("If-Match",cleared.header("ETag")).delete("/api/v1/day-notes/"+day);assertEquals(204,removed.statusCode());
        var tombstone=get("test-a",day);assertTrue(tombstone.jsonPath().getBoolean("deleted"));assertEquals(3,tombstone.jsonPath().getLong("version"));
        var retry=given().header("Authorization","Bearer test-a").header("If-Match",tombstone.header("ETag")).delete("/api/v1/day-notes/"+day);assertEquals(204,retry.statusCode());assertEquals(tombstone.header("ETag"),retry.header("ETag"));
        assertEquals(412,update("test-a",day,cleared.header("ETag"),object().put("body","stale resurrection")).statusCode());
        var emptyFile=update("test-a",day,tombstone.header("ETag"),object().put("body",""));assertEquals(200,emptyFile.statusCode());assertFalse(emptyFile.jsonPath().getBoolean("deleted"));assertEquals(4,emptyFile.jsonPath().getLong("version"));
        assertTrue(list("test-a",day,day).jsonPath().getList("items").isEmpty());assertEquals(0,get("test-b",day).jsonPath().getLong("version"));
        store.transaction("a",()->{var notes=new DayNotes(store);var date=DayNotes.date("2026-08-07");var value=notes.present(date,etag(notes.get(date)),object().put("body",""));assertEquals(1,value.path("version").asLong());assertFalse(value.path("deleted").asBoolean());assertEquals(1,notes.present(date,etag(value),object().put("body","")).path("version").asLong());return null;});
    }
    @Test void bodyLimitCountsUtf8BytesAndRejectsInvalidInputsWithoutEchoingContent(){
        String day="2026-08-03";var initial=get("test-a",day);String body="ø".repeat(100000);
        var full=update("test-a",day,initial.header("ETag"),object().put("body",body));assertEquals(200,full.statusCode());assertEquals(body,full.jsonPath().getString("body"));
        var rejected=update("test-a",day,full.header("ETag"),object().put("body",body+"private-too-long"));assertEquals(422,rejected.statusCode());assertFalse(rejected.asString().contains("private-too-long"));
        assertEquals(422,update("test-a",day,full.header("ETag"),object().putNull("body")).statusCode());
        assertEquals(422,update("test-a",day,full.header("ETag"),object()).statusCode());
        assertEquals(400,update("test-a",day,full.header("ETag"),object().put("body","injected").put("ownerId","b")).statusCode());
        assertEquals(body,get("test-a",day).jsonPath().getString("body"));
    }
    @Test void simultaneousLabelAndBodyEditsRequireOneWriterToReload() throws Exception {
        String day="2026-08-05",match=get("test-a",day).header("ETag");var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->{start.await();return update("test-a",day,match,object().put("text","New label")).statusCode();});
            var second=pool.submit(()->{start.await();return update("test-a",day,match,object().put("body","# New body\n")).statusCode();});
            start.countDown();assertEquals(java.util.Set.of(200,412),java.util.Set.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS)));
        }
        var value=get("test-a",day);assertEquals(1,value.jsonPath().getLong("version"));assertTrue(value.jsonPath().getString("text").isEmpty()^value.jsonPath().getString("body").isEmpty());
    }
    @Test void journalReadsWritesSummariesAndArchivesHonorAuthenticationRevocationAndDisabling(){
        String day="2026-08-04";var created=update("test-a",day,get("test-a",day).header("ETag"),object().put("body","Confidential journal"));assertEquals(200,created.statusCode());
        assertEquals(403,update("test-read",day,created.header("ETag"),object().put("body","Confidential journal")).statusCode());
        assertEquals(403,given().header("Authorization","Bearer test-read").header("If-Match",created.header("ETag")).delete("/api/v1/day-notes/"+day).statusCode());
        for(String path:new String[]{"/day-notes/"+day,"/day-notes?fromDate="+day+"&toDate="+day,"/changes?after=0","/data/account/export"})assertEquals(401,given().get("/api/v1"+path).statusCode());
        assertEquals(401,given().contentType("application/json").body("{\"body\":\"unauthenticated\"}").put("/api/v1/day-notes/"+day).statusCode());
        assertEquals(401,given().delete("/api/v1/day-notes/"+day).statusCode());
        var exchanged=given().header("Authorization","Bearer test-a").post("/api/v1/auth/token-login");assertEquals(200,exchanged.statusCode());String cookie=exchanged.header("Set-Cookie").split(";",2)[0];
        assertEquals(200,given().header("Cookie",cookie).get("/api/v1/day-notes/"+day).statusCode());
        assertEquals(204,given().header("Cookie",cookie).header("X-CSRF-Token",exchanged.jsonPath().getString("csrf")).post("/api/v1/auth/logout").statusCode());
        assertEquals(401,given().header("Cookie",cookie).get("/api/v1/day-notes/"+day).statusCode());
        String state=store.transaction("a",()->AccountStore.account(store.current().connection(),"a",false).path("state").asText());
        assertEquals("UNCLAIMED",state);String password=AccountPasswords.hash("Synthetic journal account password");
        try{
            store.transaction("a",()->{store.update("UPDATE tk_account SET state='DISABLED',username='disabled-journal-test',password_hash=? WHERE owner_id=?",password,"a");return null;});
            assertEquals(401,get("test-a",day).statusCode());assertEquals(401,list("test-a",day,day).statusCode());assertEquals(401,update("test-a",day,created.header("ETag"),object().put("body","blocked")).statusCode());
            assertEquals(401,given().header("Authorization","Bearer test-a").get("/api/v1/data/account/export").statusCode());
        }finally{store.transaction("a",()->{store.update("UPDATE tk_account SET state=?,username=NULL,password_hash=NULL WHERE owner_id=?",state,"a");return null;});}
        assertEquals("Confidential journal",get("test-a",day).jsonPath().getString("body"));
    }
    @Test void labelsAreOwnedCivilDatesAndLeaveWorkAndProfileValuesUntouched(){
        String day="2026-05-17";var before=given().header("Authorization","Bearer test-a").get("/api/v1/me");
        var empty=get("test-a",day);assertEquals(200,empty.statusCode());assertEquals("",empty.jsonPath().getString("text"));assertEquals(0,empty.jsonPath().getLong("version"));
        var saved=save("test-a",day,empty.header("ETag"),"  Avspasering  ");assertEquals(200,saved.statusCode());assertEquals("Avspasering",saved.jsonPath().getString("text"));assertEquals(day,saved.jsonPath().getString("date"));
        assertEquals("Avspasering",get("test-read",day).jsonPath().getString("text"));assertEquals("",get("test-b",day).jsonPath().getString("text"));assertNotEquals(saved.header("ETag"),get("test-b",day).header("ETag"));
        assertEquals(1,list("test-a",day,day).jsonPath().getList("items").size());assertEquals(0,list("test-b",day,day).jsonPath().getList("items").size());
        var after=given().header("Authorization","Bearer test-a").get("/api/v1/me");assertEquals(before.header("ETag"),after.header("ETag"));
        assertEquals(403,save("test-read",day,saved.header("ETag"),"Ferie").statusCode());
        assertEquals(401,given().get("/api/v1/day-notes/"+day).statusCode());
    }
    @Test void staleWritesCannotOverwriteCreateDeleteOrRecreation(){
        String day="2026-06-01";var initial=get("test-a",day);var first=save("test-a",day,initial.header("ETag"),"Ferie");assertEquals(200,first.statusCode());
        assertEquals(412,save("test-a",day,initial.header("ETag"),"Peer").statusCode());
        var removed=save("test-a",day,first.header("ETag"),"");assertEquals(200,removed.statusCode());assertEquals(0,list("test-a",day,day).jsonPath().getList("items").size());
        assertEquals(412,save("test-a",day,initial.header("ETag"),"Old draft").statusCode());
        var recreated=save("test-a",day,removed.header("ETag"),"Avspasering");assertEquals(200,recreated.statusCode());assertEquals(3,recreated.jsonPath().getLong("version"));
        assertEquals(412,save("test-a",day,first.header("ETag"),"Stale").statusCode());assertEquals("Avspasering",get("test-a",day).jsonPath().getString("text"));
    }
    @Test void invalidDatesTextRangesAndInjectedFieldsAreRejected(){
        String day="2026-07-01";var initial=get("test-a",day);String etag=initial.header("ETag");
        for(String invalid:new String[]{"2026-02-30","0000-01-01","2026-1-1","not-a-date"})assertEquals(422,get("test-a",invalid).statusCode());
        for(String invalid:new String[]{"x".repeat(81),"Ferie\nAnnet","Ferie\tAnnet","x\u0000"})assertEquals(422,save("test-a",day,etag,invalid).statusCode());
        assertEquals(400,given().header("Authorization","Bearer test-a").header("If-Match",etag).contentType("application/json").body(Map.of("text","Ferie","ownerId","other")).put("/api/v1/day-notes/"+day).statusCode());
        assertEquals(428,given().header("Authorization","Bearer test-a").contentType("application/json").body(Map.of("text","Ferie")).put("/api/v1/day-notes/"+day).statusCode());
        assertEquals(422,list("test-a","2026-01-01","2026-04-01").statusCode());assertEquals(422,list("test-a","2026-05-02","2026-05-01").statusCode());
        assertEquals(200,save("test-a",day,etag,"ø".repeat(80)).statusCode());
    }
}
