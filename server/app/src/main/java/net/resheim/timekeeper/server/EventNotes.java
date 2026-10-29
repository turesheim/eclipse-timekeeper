package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Private notes belong to a subscription/occurrence identity, never to its mutable title or time. */
final class EventNotes {
    static final String TYPE="EVENT_NOTE";
    static final int FILE_BYTES=DayNotes.BODY_BYTES+32768;
    private static final String PREFIX="---\ntimekeeper-event: ",END="\n---\n";
    record Key(UUID subscription,String event){
        Key {if(event==null||!event.matches("[a-f0-9]{64}"))throw ApiFailure.validation();}
        UUID id(){return ImportLedger.identifier("calendar-note",subscription.toString(),event);}
        String filename(){return "event-"+subscription+"-"+event+".md";}
    }
    record Document(Key key,ObjectNode event,String body){}
    private final ServerStore store;
    EventNotes(ServerStore store){this.store=store;}
    static Key filename(String name){
        if(name==null||!name.matches("event-[a-f0-9-]{36}-[a-f0-9]{64}\\.md"))throw ApiFailure.validation();
        var key=new Key(ContractInputs.pathId(name.substring(6,42)),name.substring(43,107));
        if(!key.filename().equals(name))throw ApiFailure.validation();return key;
    }
    ObjectNode get(Key key){
        var value=store.find(TYPE,key.id()).map(v->((ObjectNode)v).deepCopy()).orElseGet(()->object().put("id",key.id().toString()).put("ownerId",store.owner()).put("version",0)
                .put("subscriptionId",key.subscription().toString()).put("eventKey",key.event()).put("body","").put("deleted",false));
        value.remove("modifiedAt");return value.put("hasBody",!value.path("deleted").asBoolean()&&!value.path("body").asText().isEmpty());
    }
    static boolean present(JsonNode value){return value.path("version").asLong()>0&&!value.path("deleted").asBoolean();}
    Set<String> presentKeys(){var result=new HashSet<String>();for(var value:store.list(TYPE))if(present(value)&&!value.path("body").asText().isEmpty())result.add(value.path("subscriptionId").asText()+"/"+value.path("eventKey").asText());return result;}
    List<Key> presentFiles(){return store.list(TYPE).stream().filter(EventNotes::present).map(v->new Key(uuid(v.path("subscriptionId")),v.path("eventKey").asText())).toList();}
    ObjectNode list(){var result=object().put("revision",store.revision());var items=result.putArray("items");for(var key:presentFiles()){var value=get(key);value.remove("body");items.add(value);}return result;}
    Instant modifiedAt(Key key){return store.find(TYPE,key.id()).map(v->Instant.parse(v.path("modifiedAt").asText())).orElse(Instant.EPOCH);}
    static byte[] document(JsonNode value){
        var metadata=object().put("subscriptionId",value.path("subscriptionId").asText()).put("eventKey",value.path("eventKey").asText());metadata.set("event",value.path("event"));
        return (PREFIX+canonical(metadata)+END+value.path("body").asText()).getBytes(StandardCharsets.UTF_8);
    }
    byte[] document(Key key){var value=get(key);return present(value)?document(value):null;}
    static Document parse(String name,byte[] bytes){
        Key key=filename(name);if(bytes.length>FILE_BYTES)throw ApiFailure.validation();final String text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException invalid){throw ApiFailure.validation();}
        int end=text.indexOf(END,PREFIX.length());if(!text.startsWith(PREFIX)||end<0||text.substring(0,end).getBytes(StandardCharsets.UTF_8).length>32768)throw ApiFailure.validation();
        final JsonNode metadata;try{metadata=JsonValues.parse(text.substring(PREFIX.length(),end));}catch(RuntimeException invalid){throw ApiFailure.validation();}
        if(!metadata.isObject()||metadata.size()!=3||!key.subscription().toString().equals(metadata.path("subscriptionId").asText())||!key.event().equals(metadata.path("eventKey").asText()))throw ApiFailure.validation();
        var event=metadata.path("event");if(!event.isObject()||event.size()!=3)throw ApiFailure.validation();
        for(String field:List.of("uid","recurrenceId","title"))if(!event.path(field).isTextual()||event.path(field).asText().length()>4096)throw ApiFailure.validation();
        String body=text.substring(end+END.length());body(body);return new Document(key,((ObjectNode)event).deepCopy(),body);
    }
    private static void body(String body){if(!StandardCharsets.UTF_8.newEncoder().canEncode(body)||body.getBytes(StandardCharsets.UTF_8).length>DayNotes.BODY_BYTES)throw ApiFailure.validation();}
    ObjectNode save(Key key,String match,JsonNode input){
        var previous=get(key);ApiReplies.match(previous,match);ObjectNode event;
        if(previous.path("version").asLong()==0){
            var occurrence=new CalendarSubscriptions(store).noteEvent(key.subscription(),key.event(),input.path("fromDate").asText(),input.path("toDate").asText());
            event=object();for(String field:List.of("uid","recurrenceId","title"))event.set(field,occurrence.path(field));
        }else event=((ObjectNode)previous.path("event")).deepCopy();
        return write(key,match,event,input.path("body").asText(),false);
    }
    ObjectNode delete(Key key,String match){var value=get(key);ApiReplies.match(value,match);if(value.path("version").asLong()==0)throw ApiFailure.notFound();return write(key,match,(ObjectNode)value.path("event"),"",true);}
    ObjectNode saveDocument(Key key,String match,byte[] bytes){var parsed=parse(key.filename(),bytes);return write(key,match,parsed.event(),parsed.body(),false);}
    private ObjectNode write(Key key,String match,ObjectNode event,String body,boolean deleted){
        var previous=get(key);ApiReplies.match(previous,match);body(body);long version=previous.path("version").asLong();
        if(version>0&&previous.path("body").asText().equals(body)&&previous.path("event").equals(event)&&previous.path("deleted").asBoolean()==deleted)return previous;
        if(version>=9007199254740991L)throw ApiFailure.conflict();
        var value=previous.deepCopy().put("version",version+1).put("body",body).put("deleted",deleted).put("modifiedAt",store.now().toString());value.set("event",event);value.remove("hasBody");parse(key.filename(),document(value));
        if(version==0)store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,1,?)",store.owner(),TYPE,key.id(),value.toString());
        else if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",version+1,value.toString(),store.owner(),TYPE,key.id(),version)!=1)throw new ApiFailure(412,"STALE_VERSION");
        store.change("PROFILE","UPDATED",store.owner());return get(key);
    }
    void restore(String filename,byte[] bytes){var parsed=parse(filename,bytes);var current=get(parsed.key());if(present(current)){
        if(Arrays.equals(document(parsed.key()),bytes))return;throw ApiFailure.conflict();
    }saveDocument(parsed.key(),etag(current),bytes);}
}
