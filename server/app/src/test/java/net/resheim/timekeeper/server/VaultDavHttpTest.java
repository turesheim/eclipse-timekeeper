package net.resheim.timekeeper.server;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import io.quarkus.test.junit.*;
import io.restassured.specification.RequestSpecification;

@QuarkusTest @TestProfile(VaultDavHttpTest.Profile.class)
class VaultDavHttpTest {
    static final String ROOT="/dav/notes/",JOURNAL=ROOT+"journal/";
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return VaultTestDatabase.configuration("http",Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:vault_dav_http","timekeeper.auth.public-origin","https://timekeeper.example.test"));}}
    private RequestSpecification client(String token){return given().header("Authorization","Basic "+Base64.getEncoder().encodeToString(("vault:"+token).getBytes(StandardCharsets.UTF_8)));}
    private RequestSpecification api(String token){return given().header("Authorization","Bearer "+token);}
    @Test void editableTitleRenamesTheFileAndPersistsWithoutChangingCanonicalBytes(){
        String folder="title-"+UUID.randomUUID(),body="# Imported heading\r\n\r\nExact original bytes.\r\n";
        assertEquals(201,api("test-a").contentType("application/json").body(Map.of("path",folder)).post("/api/v1/notes/folders").statusCode());
        var original=api("test-a").queryParam("path",folder+"/Imported.MD").header("If-None-Match","*").contentType("application/octet-stream").body(body.getBytes(StandardCharsets.UTF_8)).put("/api/v1/notes/files");assertEquals(201,original.statusCode());
        String id=original.jsonPath().getString("id"),source=original.jsonPath().getString("sourceId"),contentTag=original.jsonPath().getString("etag"),endpoint="/api/v1/notes/entries/"+id;
        var renamed=api("test-a").header("If-Match",original.header("ETag")).contentType("application/json").body(Map.of("title","CVE-2026-111943")).put(endpoint+"/title");assertEquals(200,renamed.statusCode());
        assertEquals(folder+"/CVE-2026-111943.MD",renamed.jsonPath().getString("path"));assertEquals("CVE-2026-111943",renamed.jsonPath().getString("title"));assertEquals(source,renamed.jsonPath().getString("sourceId"));assertEquals(contentTag,renamed.jsonPath().getString("etag"));
        for(String field:List.of("createdAt","modifiedAt","contentVersion","sha256","byteLength"))assertEquals(original.jsonPath().getString(field),renamed.jsonPath().getString(field));
        assertEquals(body,api("test-a").get(endpoint+"/content").asString());assertEquals("CVE-2026-111943",api("test-a").get(endpoint).jsonPath().getString("title"));
        assertEquals(404,api("test-a").queryParam("path",folder+"/Imported.MD").get("/api/v1/notes/resolve").statusCode());
        var moved=api("test-a").header("If-Match",renamed.header("ETag")).contentType("application/json").body(Map.of("path",folder+"/Manual.md")).put(endpoint+"/path");assertEquals(200,moved.statusCode());assertEquals("Manual",moved.jsonPath().getString("title"));
    }
    @Test void titleRenamesRejectCollisionsStaleGuardsInvalidNamesAndForeignOrReadonlyAccounts(){
        String prefix="title-guard-"+UUID.randomUUID(),path=prefix+".md";
        var original=api("test-a").queryParam("path",path).header("If-None-Match","*").contentType("application/octet-stream").body("# Before\n".getBytes(StandardCharsets.UTF_8)).put("/api/v1/notes/files");assertEquals(201,original.statusCode());
        assertEquals(201,api("test-a").queryParam("path",prefix+"-taken.md").header("If-None-Match","*").contentType("application/octet-stream").body("Keep existing\n".getBytes(StandardCharsets.UTF_8)).put("/api/v1/notes/files").statusCode());
        String endpoint="/api/v1/notes/entries/"+original.jsonPath().getString("id")+"/title",guard=original.header("ETag");
        assertEquals(409,api("test-a").header("If-Match",guard).contentType("application/json").body(Map.of("title",prefix+"-taken")).put(endpoint).statusCode());
        for(String title:List.of(""," ","../escape","bad:name","bad\\name"))assertEquals(422,api("test-a").header("If-Match",guard).contentType("application/json").body(Map.of("title",title)).put(endpoint).statusCode());
        assertEquals(428,api("test-a").contentType("application/json").body(Map.of("title","Another")).put(endpoint).statusCode());
        assertEquals(412,api("test-a").header("If-Match","\"stale\"").contentType("application/json").body(Map.of("title","Another")).put(endpoint).statusCode());
        assertEquals(403,api("test-read").header("If-Match",guard).contentType("application/json").body(Map.of("title","Another")).put(endpoint).statusCode());
        assertEquals(404,api("test-b").header("If-Match",guard).contentType("application/json").body(Map.of("title","Another")).put(endpoint).statusCode());
        var after=api("test-a").get(endpoint.replace("/title",""));assertEquals(guard,after.header("ETag"));assertEquals(path,after.jsonPath().getString("path"));assertEquals("Before",after.jsonPath().getString("title"));
    }
    @Test void revisionsCoverDavWritesAndStayOwnerPrivateWithReadOnlyComparison(){
        String path="revision-http.md",first="First\r\n",second="Second\n";
        var created=client("test-a").header("If-None-Match","*").body(first.getBytes(StandardCharsets.UTF_8)).put(ROOT+path);assertEquals(201,created.statusCode());
        assertEquals(204,client("test-a").header("If-Match",created.header("ETag")).body(second.getBytes(StandardCharsets.UTF_8)).put(ROOT+path).statusCode());
        var entry=api("test-a").queryParam("path",path).get("/api/v1/notes/resolve");assertEquals(200,entry.statusCode());String id=entry.jsonPath().getString("sourceId"),type=entry.jsonPath().getString("sourceType"),query="?sourceType="+type+"&sourceId="+id;
        var history=api("test-read").get("/api/v1/notes/revisions"+query);assertEquals(200,history.statusCode());assertEquals(1,history.jsonPath().getList("items").size());assertTrue(history.header("Cache-Control").contains("no-store"));String revision=history.jsonPath().getString("items[0].id");
        var snapshot=api("test-read").get("/api/v1/notes/revisions/"+revision+query);assertEquals(200,snapshot.statusCode());assertEquals(first,snapshot.jsonPath().getString("markdown"));assertEquals(404,api("test-b").get("/api/v1/notes/revisions"+query).statusCode());assertEquals(404,api("test-b").get("/api/v1/notes/revisions/"+revision+query).statusCode());assertEquals(401,given().header("Origin","https://timekeeper.example.test").get("/api/v1/notes/revisions"+query).statusCode());
        assertEquals(403,client("test-read").header("If-Match",entry.jsonPath().getString("etag")).body(first).put(ROOT+path).statusCode());assertEquals(second,client("test-a").get(ROOT+path).asString());
    }
    @Test void deletingOneRevisionRequiresItsGuardAndPreservesLiveContentAndOtherOwners(){
        String path="Delete-history-"+UUID.randomUUID()+".md",first="First\r\n",second="Current\n";
        assertEquals(201,client("test-a").header("If-None-Match","*").body(first).put(ROOT+path).statusCode());
        String old=client("test-a").get(ROOT+path).header("ETag");assertEquals(204,client("test-a").header("If-Match",old).body(second).put(ROOT+path).statusCode());
        var entry=api("test-a").queryParam("path",path).get("/api/v1/notes/resolve");String query="?sourceType="+entry.jsonPath().getString("sourceType")+"&sourceId="+entry.jsonPath().getString("sourceId");
        var history=api("test-a").get("/api/v1/notes/revisions"+query);String revision=history.jsonPath().getString("items[0].id"),url="/api/v1/notes/revisions/"+revision+query;
        var snapshot=api("test-read").get(url);assertEquals(200,snapshot.statusCode());String tag=snapshot.header("ETag");assertNotNull(tag);
        String current=client("test-a").get(ROOT+path).header("ETag");assertEquals(428,api("test-a").delete(url).statusCode());assertEquals(412,api("test-a").header("If-Match","\"stale\"").delete(url).statusCode());
        assertEquals(403,api("test-read").header("If-Match",tag).delete(url).statusCode());assertEquals(404,api("test-b").header("If-Match",tag).delete(url).statusCode());
        String other="Other-history-"+UUID.randomUUID()+".md";assertEquals(201,client("test-a").header("If-None-Match","*").body("Other note").put(ROOT+other).statusCode());
        String otherSource=api("test-a").queryParam("path",other).get("/api/v1/notes/resolve").jsonPath().getString("sourceId");
        assertEquals(404,api("test-a").header("If-Match",tag).delete("/api/v1/notes/revisions/"+revision+"?sourceType=VAULT_CONTENT&sourceId="+otherSource).statusCode());
        assertEquals(204,api("test-a").header("If-Match",tag).delete(url).statusCode());assertEquals(404,api("test-a").get(url).statusCode());assertEquals(0,api("test-a").get("/api/v1/notes/revisions"+query).jsonPath().getList("items").size());
        var live=client("test-a").get(ROOT+path);assertEquals(second,live.asString());assertEquals(current,live.header("ETag"));
        assertEquals(204,client("test-a").header("If-Match",current).body("Next").put(ROOT+path).statusCode());assertEquals(1,api("test-a").get("/api/v1/notes/revisions"+query).jsonPath().getList("items").size());
    }
    @Test void removedNamespacesAreNotAliasesOrRedirects(){
        for(String path:java.util.List.of("/dav/journal/","/dav/journal/assets/","/dav/vault/","/remote.php/webdav/","/remote.php/webdav/vault/","/remote.php/webdav/notes/")){
            for(String method:java.util.List.of("GET","HEAD","PROPFIND","PUT")){
                var reply=client("test-a").request(method,path);assertEquals(404,reply.statusCode(),method+" "+path);assertNull(reply.header("Location"));
            }
        }
    }
    @Test void nestedCollectionIsPrivateAndGuarded(){
        assertEquals(401,given().request("PROPFIND",ROOT).statusCode());
        assertEquals(201,client("test-a").request("MKCOL",ROOT+"Nested/").statusCode());assertEquals(201,client("test-a").request("MKCOL",ROOT+"Nested/Child/").statusCode());
        String path="Nested/Child/Exact.md",body="---\r\nunknown: [x, 1]\r\n---\r\n[[Note]]  \r\n";
        var created=client("test-a").header("If-None-Match","*").body(body.getBytes(StandardCharsets.UTF_8)).put(ROOT+path);assertEquals(201,created.statusCode());String tag=created.header("ETag");
        assertEquals(body,client("test-a").get(ROOT+path).asString());assertEquals(tag,client("test-a").head(ROOT+path).header("ETag"));
        assertEquals(404,client("test-b").get(ROOT+path).statusCode());assertEquals(403,client("test-read").header("If-Match",tag).body("blocked").put(ROOT+path).statusCode());
        assertEquals(428,client("test-a").body("unguarded").put(ROOT+path).statusCode());assertEquals(428,client("test-a").delete(ROOT+path).statusCode());
        var listing=client("test-a").header("Depth","infinity").request("PROPFIND",ROOT);assertEquals(207,listing.statusCode());assertTrue(listing.asString().contains(ROOT+path));
        assertDoesNotThrow(()->javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(listing.asString()))));
        var moved=client("test-a").header("If-Match",tag).header("Overwrite","F").header("Destination","/dav/vault/Nested/Moved.md").request("MOVE",ROOT+path);
        assertEquals(400,moved.statusCode());
        moved=client("test-a").header("If-Match",tag).header("Overwrite","F").header("Destination",ROOT+"Nested/Moved.md").request("MOVE",ROOT+path);assertEquals(201,moved.statusCode());
        assertEquals(404,client("test-a").get(ROOT+path).statusCode());assertEquals(body,client("test-a").get(ROOT+"Nested/Moved.md").asString());
        assertEquals(204,client("test-a").header("If-Match",moved.header("ETag")).delete(ROOT+"Nested/Moved.md").statusCode());
        var trash=api("test-a").get("/api/v1/notes/entries?trash=true");assertEquals(200,trash.statusCode());String id=trash.jsonPath().getString("items.find { it.path == 'Nested/Moved.md' }.id");String deletedTag=trash.jsonPath().getString("items.find { it.path == 'Nested/Moved.md' }.entryEtag");
        assertEquals(200,api("test-a").header("If-Match",deletedTag).post("/api/v1/notes/entries/"+id+"/restore").statusCode());assertEquals(body,client("test-a").get(ROOT+"Nested/Moved.md").asString());
        assertEquals(404,api("test-b").get("/api/v1/notes/entries/"+id).statusCode());
    }
    private String resourceId(String token,String path){
        var response=client(token).header("Depth","0").request("PROPFIND",path);assertEquals(207,response.statusCode());
        try{var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);var xml=factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(response.asString())));return xml.getElementsByTagNameNS("DAV:","resource-id").item(0).getTextContent();}
        catch(Exception invalid){throw new AssertionError(invalid);}
    }
    @Test void resourceIdentitySurvivesMovesButNotDeleteRecreate(){
        assertEquals(201,client("test-a").request("MKCOL",ROOT+"Identity/").statusCode());String first=resourceId("test-a",ROOT+"Identity/");
        assertEquals(first,resourceId("test-a",ROOT+"Identity/"));assertNotEquals(resourceId("test-a",ROOT),resourceId("test-b",ROOT));
        String tag=client("test-a").header("Depth","0").request("PROPFIND",ROOT+"Identity/").xmlPath().getString("multistatus.response.propstat.prop.getetag");
        var moved=client("test-a").header("If-Match",tag).header("Overwrite","F").header("Destination",ROOT+"Identity-moved/").request("MOVE",ROOT+"Identity/");assertEquals(201,moved.statusCode());assertEquals(first,resourceId("test-a",ROOT+"Identity-moved/"));
        assertEquals(204,client("test-a").header("If-Match",moved.header("ETag")).delete(ROOT+"Identity-moved/").statusCode());assertEquals(201,client("test-a").request("MKCOL",ROOT+"Identity-moved/").statusCode());assertNotEquals(first,resourceId("test-a",ROOT+"Identity-moved/"));
    }
    @Test void journalEditsShareCanonicalVersionsAndLeaveLabelsIndependent(){
        String filename="2026-10-30.md";
        var create=client("test-a").header("If-None-Match","*").body("# Daily note").put(ROOT+"journal/"+filename);assertEquals(201,create.statusCode());
        assertEquals(create.header("ETag"),client("test-a").head("/dav/notes/journal/"+filename).header("ETag"));
        var label=api("test-a").get("/api/v1/day-labels/2026-10-30");assertEquals(200,label.statusCode());
        assertEquals(200,api("test-a").header("If-Match",label.header("ETag")).contentType("application/json").body("{\"text\":\"Independent\"}").put("/api/v1/day-labels/2026-10-30").statusCode());
        assertEquals(create.header("ETag"),client("test-a").head(ROOT+"journal/"+filename).header("ETag"));
        var edit=client("test-a").header("If-Match",create.header("ETag")).body("# Vault edit").put("/dav/notes/journal/"+filename);assertEquals(204,edit.statusCode());
        assertEquals("# Vault edit",client("test-a").get(ROOT+"journal/"+filename).asString());assertEquals(412,client("test-a").header("If-Match",create.header("ETag")).delete(ROOT+"journal/"+filename).statusCode());
        assertEquals(204,client("test-a").header("If-Match",edit.header("ETag")).delete(ROOT+"journal/"+filename).statusCode());assertEquals("Independent",api("test-a").get("/api/v1/day-labels/2026-10-30").jsonPath().getString("text"));
    }
    @Test void configurationAndUnsafePathsAreRejected(){
        assertEquals(403,client("test-a").request("MKCOL",ROOT+".obsidian/").statusCode());
        assertEquals(422,client("test-a").urlEncodingEnabled(false).body("x").put(ROOT+"a%2Fb.md").statusCode());
        assertEquals(422,client("test-a").body("x").put(ROOT+"CON.md").statusCode());
        assertEquals(400,client("test-a").header("Depth","2").request("PROPFIND",ROOT).statusCode());
        assertEquals(403,client("test-read").request("MKCOL",ROOT+"Blocked/").statusCode());
    }
    @Test void absoluteMoveDestinationsUseTheTrustedPublicHttpsOriginBehindAProxy(){
        var created=client("test-a").header("Host","timekeeper.example.test").header("If-None-Match","*").body("Proxy source").put(ROOT+"Proxy.md");assertEquals(201,created.statusCode());
        assertEquals(400,client("test-a").header("Host","timekeeper.example.test").header("If-Match",created.header("ETag")).header("Destination","https://foreign.example/dav/notes/Foreign.md").request("MOVE",ROOT+"Proxy.md").statusCode());
        assertEquals(400,client("test-a").header("Host","timekeeper.example.test").header("If-Match",created.header("ETag")).header("Destination","//foreign.example/dav/notes/Foreign.md").request("MOVE",ROOT+"Proxy.md").statusCode());
        var moved=client("test-a").header("Host","timekeeper.example.test").header("If-Match",created.header("ETag")).header("Overwrite","F").header("Destination","https://TIMEKEEPER.example.test:443/dav/notes/Proxy-moved.md").request("MOVE",ROOT+"Proxy.md");assertEquals(201,moved.statusCode());
        assertEquals("Proxy source",client("test-a").get(ROOT+"Proxy-moved.md").asString());assertEquals(404,client("test-a").get(ROOT+"Proxy.md").statusCode());
    }
    @Test void metadataValidatorsProtectStructuralApiWritesAcrossAConcurrentRename(){
        var created=api("test-a").header("If-None-Match","*").contentType("application/octet-stream").body("Metadata source".getBytes(StandardCharsets.UTF_8)).put("/api/v1/notes/files?path=Structural.md");assertEquals(201,created.statusCode());
        String id=created.jsonPath().getString("id"),metadataTag=created.header("ETag"),contentTag=created.jsonPath().getString("etag");assertNotEquals(contentTag,metadataTag);
        var moved=api("test-a").header("If-Match",metadataTag).contentType("application/json").body("{\"path\":\"Structural-renamed.md\"}").put("/api/v1/notes/entries/"+id+"/path");assertEquals(200,moved.statusCode());assertEquals(contentTag,moved.jsonPath().getString("etag"));assertNotEquals(metadataTag,moved.header("ETag"));
        assertEquals(412,api("test-a").header("If-Match",metadataTag).delete("/api/v1/notes/entries/"+id).statusCode());
        assertEquals("Metadata source",api("test-a").get("/api/v1/notes/entries/"+id+"/content").asString());
        var edited=api("test-a").header("If-Match",contentTag).contentType("application/octet-stream").body("Body saved after rename".getBytes(StandardCharsets.UTF_8)).put("/api/v1/notes/entries/"+id+"/content");assertEquals(200,edited.statusCode());
        assertEquals(412,api("test-a").header("If-Match",moved.header("ETag")).delete("/api/v1/notes/entries/"+id).statusCode());
        var current=api("test-a").get("/api/v1/notes/entries/"+id);assertEquals(current.header("ETag"),current.jsonPath().getString("entryEtag"));
        assertEquals(204,api("test-a").header("If-Match",current.header("ETag")).delete("/api/v1/notes/entries/"+id).statusCode());
    }
    @Test void weeklyDocumentsUseIsoFilenamesAndConditionalWebDavWrites(){
        String file=JOURNAL+"2026-W44.md",body="# Weekly plan\r\n- [ ] Ship  \r\n";
        assertEquals(422,client("test-a").body("bad").put(JOURNAL+"2025-W53.md").statusCode());
        var created=client("test-a").header("If-None-Match","*").contentType("text/markdown").body(body).put(file);
        assertEquals(201,created.statusCode());String tag=created.header("ETag");
        assertEquals(body,client("test-a").get(file).asString());
        assertTrue(client("test-a").header("Depth","1").request("PROPFIND",JOURNAL).asString().contains("2026-W44.md"));
        assertEquals(body,given().header("Authorization","Bearer test-a").get("/api/v1/week-notes/2026-W44").jsonPath().getString("body"));
        assertEquals(428,client("test-a").body("unguarded").put(file).statusCode());
        assertEquals(412,client("test-a").header("If-Match","\"stale\"").body("stale").put(file).statusCode());
        assertEquals(204,client("test-a").header("If-Match",tag).delete(file).statusCode());
        assertEquals(404,client("test-a").get(file).statusCode());
    }
    @Test void accountScopedDocumentsRoundTripWithStrongConditionalVersions(){
        String filename="2026-09-20.md",day="2026-09-20",file=JOURNAL+filename;
        String source="---\r\ntimekeeper-label: Planning\r\ncustom: first\r\n---\r\n# Body\r\n  spaces  \r\n";
        var unauthenticated=given().request("PROPFIND",JOURNAL);assertEquals(401,unauthenticated.statusCode());
        assertTrue(unauthenticated.header("WWW-Authenticate").startsWith("Basic "));
        assertEquals(401,given().header("Authorization","Basic invalid").request("PROPFIND",JOURNAL).statusCode());
        var root=client("test-a").header("Depth","1").request("PROPFIND",JOURNAL);assertEquals(207,root.statusCode());
        assertFalse(root.asString().contains(filename));
        assertEquals(207,client("test-a").header("Host","timekeeper.example.test").header("Depth","0").request("PROPFIND",JOURNAL).statusCode());
        assertEquals("1",client("test-a").options(JOURNAL).header("DAV"));
        assertEquals(428,client("test-a").delete(JOURNAL).statusCode());
        assertEquals(405,client("test-a").request("MKCOL",JOURNAL).statusCode());
        assertEquals(201,client("test-a").body(new byte[0]).put(JOURNAL+"2026-09-19.md").statusCode());
        var created=client("test-a").header("If-None-Match","*").contentType("text/markdown").body(source.getBytes(StandardCharsets.UTF_8)).put(file);
        assertEquals(201,created.statusCode());String firstTag=created.header("ETag");assertNotNull(firstTag);
        assertEquals(204,client("test-a").body(source).put(file).statusCode());
        assertEquals(428,client("test-a").body("unconditional overwrite").put(file).statusCode());
        assertEquals(source,client("test-a").get(file).asString());
        var head=client("test-a").head(file);assertEquals(200,head.statusCode());
        assertEquals(source.getBytes(StandardCharsets.UTF_8).length,Integer.parseInt(head.header("Content-Length")));
        assertEquals(firstTag,head.header("ETag"));
        assertEquals(304,client("test-a").header("If-None-Match",firstTag).get(file).statusCode());
        var listing=client("test-a").header("Depth","1").request("PROPFIND",JOURNAL);
        assertEquals(207,listing.statusCode());assertTrue(listing.asString().contains(file));
        assertEquals(firstTag,listing.xmlPath().getString("multistatus.response.find { it.href == '"+file+"' }.propstat.prop.getetag"));
        assertDoesNotThrow(()->javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new java.io.StringReader(listing.asString()))));
        assertEquals(207,client("test-a").header("Depth","0").request("PROPFIND",file).statusCode());
        assertEquals(400,client("test-a").header("Depth","2").request("PROPFIND",JOURNAL).statusCode());
        assertEquals(403,client("test-read").header("If-Match",firstTag).body(source).put(file).statusCode());
        assertEquals(404,client("test-b").get(file).statusCode());
        assertFalse(client("test-b").header("Depth","1").request("PROPFIND",JOURNAL).asString().contains(filename));
        var rest=client("test-a").get("/api/v1/day-notes/"+day); // Basic credentials are reserved for WebDAV.
        assertEquals(401,rest.statusCode());
        rest=given().header("Authorization","Bearer test-a").get("/api/v1/day-notes/"+day);
        assertEquals("",rest.jsonPath().getString("text"));assertEquals("# Body\r\n  spaces  \r\n",rest.jsonPath().getString("body"));
        assertFalse(rest.asString().contains("custom"));assertFalse(rest.asString().contains("modifiedAt"));
        var calendar=given().header("Authorization","Bearer test-a").header("If-Match",rest.header("ETag"))
                .contentType("application/json").body("{\"text\":\"Updated\"}").put("/api/v1/day-notes/"+day);
        assertEquals(200,calendar.statusCode());
        assertEquals(428,client("test-a").header("If-Match","*").body(source).put(file).statusCode());
        String afterCalendar=client("test-a").get(file).asString();assertTrue(afterCalendar.contains("custom: first"));
        assertTrue(afterCalendar.endsWith("# Body\r\n  spaces  \r\n"));
        String secondTag=client("test-a").head(file).header("ETag");assertEquals(firstTag,secondTag);
        String metadataOnly=afterCalendar.replace("custom: first","custom: second");
        var metadata=client("test-a").header("If-Match",secondTag).contentType("text/markdown").body(metadataOnly).put(file);
        assertEquals(204,metadata.statusCode());assertNotEquals(secondTag,metadata.header("ETag"));
        assertEquals(metadataOnly,client("test-a").get(file).asString());
        assertEquals(412,client("test-a").header("If-Match",firstTag).body(source).put(file).statusCode());
        assertEquals(412,client("test-a").header("If-Match",firstTag).delete(file).statusCode());
        assertEquals(calendar.jsonPath().getLong("version")+1,given().header("Authorization","Bearer test-a").get("/api/v1/day-notes/"+day).jsonPath().getLong("version"));
        assertEquals(412,client("test-a").header("If-None-Match","*").body("overwrite").put(file).statusCode());
        assertEquals(428,client("test-a").delete(file).statusCode());
        assertEquals(204,client("test-a").header("If-Match",metadata.header("ETag")).delete(file).statusCode());
        assertEquals(404,client("test-a").get(file).statusCode());
        assertFalse(client("test-a").header("Depth","1").request("PROPFIND",JOURNAL).asString().contains(filename));
        var recreated=client("test-a").header("If-None-Match","*").body(new byte[0]).put(file);assertEquals(201,recreated.statusCode());
        assertNotEquals(firstTag,recreated.header("ETag"));assertEquals("",client("test-a").get(file).asString());
        assertTrue(client("test-a").header("Depth","1").request("PROPFIND",JOURNAL).asString().contains(filename));
        assertEquals(422,client("test-a").header("If-Match",recreated.header("ETag")).body(new byte[]{(byte)0xc3,0x28}).put(file).statusCode());
    }
}
