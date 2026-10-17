package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.file.Path;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonParser;
import io.quarkus.test.junit.*;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import net.resheim.eclipse.timekeeper.client.CsvSnapshot;

@QuarkusTest @TestProfile(WorkSessionLabelsTest.Profile.class)
class WorkSessionLabelsTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {
            String url=System.getenv("TIMEKEEPER_TEST_LABELS_DB_URL");
            if(url==null) return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:work_session_labels_tests");
            return Map.of("quarkus.datasource.jdbc.url",url,"quarkus.datasource.db-kind","postgresql",
                    "quarkus.datasource.username",System.getenv("TIMEKEEPER_TEST_PG_USER"),"quarkus.datasource.password",System.getenv("TIMEKEEPER_TEST_PG_PASSWORD"));
        }
    }
    @Inject ServerStore store;
    @TempDir Path temporary;
    private Response call(String token,String method,String path,JsonNode body,String match) {
        var request=given().header("Authorization","Bearer "+token);
        if(method.equals("POST")) {request.header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json");if(body==null) request.body("{}");}
        if(body!=null) request.contentType("application/json").body(body.toString());
        if(match!=null) request.header("If-Match",match);
        return request.request(method,"/api/v1"+path);
    }
    private JsonNode json(Response response,int status) {assertEquals(status,response.statusCode(),response.asString());return parse(response.asString());}
    private JsonNode label(String token,String name) {return json(call(token,"POST","/labels",object().put("name",name).put("color","#176653"),null),201);}
    private ObjectNode input() {return object().put("summary","Independent session notes").put("title","Labelled work").put("start","2025-10-20T06:03:00.123456789Z").put("end","2025-10-20T14:03:00.987654321Z");}
    private Set<String> ids(JsonNode node) {var ids=new HashSet<String>();node.path("labelIds").forEach(value->ids.add(value.asText()));return ids;}
    @AfterEach void clear() throws Exception {
        var actual=io.quarkus.arc.ClientProxy.unwrap(store);actual.stopMaintenance();
        try(var connection=actual.datasource.getConnection()) {
            connection.setAutoCommit(false);
            for(var table:List.of("tk_auth_session","tk_api_token","tk_auth_challenge","tk_account","tk_active","tk_relation","tk_external_reference","tk_receipt","tk_change","tk_resource","tk_owner"))
                try(var statement=connection.createStatement()) {statement.executeUpdate("DELETE FROM "+table+" WHERE "+(table.equals("tk_owner") ? "id" : "owner_id")+" IN ('a','b')");}
            connection.commit();
        }
    }
    @Test void optionalOwnedLabelsSurviveStartStopLegacyEditsAndProtectReferencedLabels() {
        var first=label("test-a","Office");var second=label("test-a","Billable");var foreign=label("test-b","Private foreign label");
        var body=object().put("summary","Running labels").put("title","Today");body.putArray("labelIds").add(first.path("id").asText()).add(second.path("id").asText());
        var started=call("test-a","POST","/work-sessions/start",body,null);var session=json(started,201);String path="/work-sessions/"+session.path("id").asText();
        assertEquals(Set.of(first.path("id").asText(),second.path("id").asText()),ids(session));
        assertEquals(404,call("test-b","GET",path,null,null).statusCode());
        var stopped=json(call("test-a","POST",path+"/stop",null,started.header("ETag")),200);assertEquals(ids(session),ids(stopped));
        var legacy=object().put("summary","Changed by interval-only client");legacy.set("start",stopped.get("start"));legacy.set("end",stopped.get("end"));
        var edited=json(call("test-a","PUT",path,legacy,etag(stopped)),200);assertEquals(ids(session),ids(edited));
        assertEquals(1,json(call("test-a","GET","/work-sessions?labelId="+first.path("id").asText(),null,null),200).path("items").size());
        assertEquals(404,call("test-a","GET","/work-sessions?labelId="+foreign.path("id").asText(),null,null).statusCode());
        assertEquals(409,call("test-a","DELETE","/labels/"+first.path("id").asText(),null,etag(first)).statusCode());
        var invalid=legacy.deepCopy();invalid.putArray("labelIds").add(foreign.path("id").asText());
        assertEquals(404,call("test-a","PUT",path,invalid,etag(edited)).statusCode());assertEquals(edited,json(call("test-a","GET",path,null,null),200));
        invalid.putArray("labelIds").add(first.path("id").asText()).add(first.path("id").asText());assertEquals(422,call("test-a","PUT",path,invalid,etag(edited)).statusCode());
        invalid.putNull("labelIds");assertEquals(422,call("test-a","PUT",path,invalid,etag(edited)).statusCode());
        assertEquals(403,call("test-read","PUT",path,legacy,etag(edited)).statusCode());
        legacy.putArray("labelIds");var cleared=json(call("test-a","PUT",path,legacy,etag(edited)),200);assertTrue(ids(cleared).isEmpty());
        assertEquals(412,call("test-a","PUT",path,legacy,etag(edited)).statusCode());
        assertEquals(204,call("test-a","DELETE","/labels/"+first.path("id").asText(),null,etag(first)).statusCode());
    }
    @Test void portableJsonAndCsvRemapLabelsAndRejectMissingSourceReferencesAtomically() throws Exception {
        var first=label("test-a","Transfer label");var body=input();body.putArray("labelIds").add(first.path("id").asText());
        var original=json(call("test-a","POST","/work-sessions",body,null),201);
        var exported=json(call("test-a","GET","/data/export",null,null),200);
        var folder=temporary.resolve("export");CsvSnapshot.write(JsonParser.parseString(exported.toString()).getAsJsonObject(),folder);
        var csv=parse(CsvSnapshot.read(folder).toString());assertEquals(exported,csv);
        json(call("test-b","POST","/data/import",csv,null),201);
        var target=json(call("test-b","GET","/data/export",null,null),200);var copied=target.path("workSessions").get(0);
        assertNotEquals(original.path("id"),copied.path("id"));assertEquals(1,ids(copied).size());assertNotEquals(ids(original),ids(copied));
        assertTrue(java.util.stream.StreamSupport.stream(target.path("labels").spliterator(),false).anyMatch(value->ids(copied).contains(value.path("id").asText()) && value.path("name").asText().equals("Transfer label")));
        var invalid=((ObjectNode)exported).deepCopy();invalid.putArray("labels");
        assertEquals(422,call("test-b","POST","/data/import",invalid,null).statusCode());assertEquals(target,json(call("test-b","GET","/data/export",null,null),200));
        var rejected=input();rejected.putArray("labelIds").add(first.path("id").asText());
        assertEquals(404,call("test-b","POST","/work-sessions",rejected,null).statusCode());assertEquals(target,json(call("test-b","GET","/data/export",null,null),200));
    }
    @Test void oldSessionsAreReadWithoutRewritingVersionsAndGainLabelsThroughNormalOwnedEdits() {
        var created=json(call("test-a","POST","/work-sessions",input(),null),201);String path="/work-sessions/"+created.path("id").asText();
        store.transaction("a",()->{var old=((ObjectNode)created).deepCopy();old.remove("labelIds");store.update("UPDATE tk_resource SET payload=? WHERE owner_id=? AND entity_type='WORK_SESSION' AND id=?",old.toString(),"a",uuid(old.get("id")));return null;});
        var legacy=json(call("test-a","GET",path,null,null),200);assertFalse(legacy.has("labelIds"));assertEquals(created.path("version"),legacy.path("version"));
        var label=label("test-a","New label");var update=input();update.putArray("labelIds").add(label.path("id").asText());
        var saved=json(call("test-a","PUT",path,update,etag(legacy)),200);assertEquals(Set.of(label.path("id").asText()),ids(saved));assertEquals(legacy.path("version").asLong()+1,saved.path("version").asLong());
    }
}
