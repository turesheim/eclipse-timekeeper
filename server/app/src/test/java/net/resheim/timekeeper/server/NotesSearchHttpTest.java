package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(NotesSearchHttpTest.Profile.class)
class NotesSearchHttpTest {
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return VaultTestDatabase.configuration("searchhttp",Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:notes_search_http_test"));}}
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @Inject ContractInputs inputs;
    private void create(String owner,String path,String body){store.transaction(owner,()->{var vault=new VaultCollection(store,limits);vault.initialize();try{vault.get(new VaultPath("search-http"));}catch(ApiFailure absent){if(absent.status!=404)throw absent;vault.mkdir(new VaultPath("search-http"));}vault.put(new VaultPath(path),null,true,body.getBytes(StandardCharsets.UTF_8));return null;});}
    private io.restassured.response.Response request(String token,String query,int count,String cursor){
        var request=given().header("Authorization","Bearer "+token).queryParam("q",query).queryParam("limit",count);if(cursor!=null)request.queryParam("cursor",cursor);
        return request.get("/api/v1/notes/search");
    }
    private JsonNode page(String query,int limit,String cursor)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        do{var reply=request("test-a",query,limit,cursor);if(reply.statusCode()==200){assertEquals("private, no-store",reply.header("Cache-Control"));var result=parse(reply.asString());inputs.validate("NotesSearchPage",result);return result;}
            assertEquals(503,reply.statusCode(),reply.asString());Thread.sleep(20);
        }while(System.nanoTime()<deadline);throw new AssertionError("Notes search did not become ready");
    }
    @Test void httpSearchPagesArePrivateRankedPlainTextAndExpireAfterCommittedChanges()throws Exception{
        String word="httpneedle";for(int i=0;i<5;i++)create("a","search-http/"+i+".md","# Title "+i+"\n\n😀 "+word+" owner text");
        create("b","search-http/foreign.md",word+" private foreign text");
        JsonNode first=page(word,2,null);assertEquals(2,first.path("items").size());assertTrue(first.has("nextCursor"));
        var seen=new HashSet<String>();JsonNode current=first;
        do{
            for(var hit:current.path("items")){
                assertTrue(seen.add(hit.path("entry").path("id").asText()));assertFalse(hit.toString().contains("foreign"));var excerpt=hit.path("excerpt");String text=excerpt.path("text").asText();assertTrue(text.length()<=320);
                for(var range:excerpt.path("highlights")){int start=range.path("start").asInt(),end=range.path("end").asInt();assertEquals(word,text.substring(start,end));}
                assertFalse(excerpt.path("highlights").isEmpty());
            }
            current=current.has("nextCursor")?page(word,2,current.path("nextCursor").asText()):null;
        }while(current!=null);
        assertEquals(5,seen.size());String cursor=first.path("nextCursor").asText();
        assertEquals(412,request("test-b",word,2,cursor).statusCode());assertEquals(412,request("test-a","different",2,cursor).statusCode());
        assertEquals(412,request("test-a",word,2,cursor+"x").statusCode());
        create("a","search-http/new.md",word+" newly committed");assertEquals(412,request("test-a",word,2,cursor).statusCode());assertEquals(6,page(word,200,null).path("items").size());
    }
    @Test void statusRebuildAndInputValidationUseNormalAuthenticationAndPermissions()throws Exception{
        assertEquals(401,given().queryParam("q","needle").get("/api/v1/notes/search").statusCode());
        assertEquals(422,request("test-a"," ",50,null).statusCode());assertEquals(422,request("test-a","x".repeat(513),50,null).statusCode());
        assertEquals(422,request("test-a","needle",201,null).statusCode());
        assertEquals(400,given().header("Authorization","Bearer test-a").queryParam("q","needle").queryParam("q","other").get("/api/v1/notes/search").statusCode());
        assertEquals(403,given().header("Authorization","Bearer test-read").post("/api/v1/notes/search/rebuild").statusCode());
        var status=given().header("Authorization","Bearer test-a").get("/api/v1/notes/search/status");assertEquals(200,status.statusCode());inputs.validate("NotesSearchStatus",parse(status.asString()));
        assertEquals("private, no-store",status.header("Cache-Control"));
        var rebuild=given().header("Authorization","Bearer test-a").post("/api/v1/notes/search/rebuild");assertEquals(202,rebuild.statusCode());assertEquals("private, no-store",rebuild.header("Cache-Control"));
    }
}
