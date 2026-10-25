package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import static net.resheim.timekeeper.server.JournalState.Kind.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Owner-transaction reconciliation. Only acknowledged offers advance a device's common base. */
final class JournalReconciliation {
    static final String ABSENT="ABSENT";
    private final ServerStore store;
    private final JournalState state;
    JournalReconciliation(ServerStore store){this.store=store;state=new JournalState(store);}
    private static UUID commonId(UUID device,String name){return ImportLedger.identifier("journal-common",device.toString(),name);}
    private static UUID documentId(String name){return ImportLedger.identifier("journal-document",name);}
    private static UUID offerId(UUID exchange,String name){return ImportLedger.identifier("journal-offer",exchange.toString(),name);}
    private ObjectNode device(UUID device,UUID client){
        var value=new JournalDevices(store).authorizeBidirectional(device);
        if(value.has("clientStateId")&&!value.path("clientStateId").asText().equals(client.toString()))throw new ApiFailure(409,"JOURNAL_CLIENT_STATE_CHANGED");
        if(!value.has("clientStateId"))value=state.update(JOURNAL_DEVICE,value.put("clientStateId",client.toString()));
        return value;
    }
    private void authority(ObjectNode offer,UUID device,String configuration){
        new JournalDevices(store).authorizeBidirectional(device);
        if(!offer.path("deviceId").asText().equals(device.toString()))throw ApiFailure.notFound();
        var account=AccountStore.account(store.current().connection(),store.owner(),true);
        if(offer.path("syncEpoch").asLong()!=new JournalDevices(store).settings().path("epoch").asLong()
                ||offer.path("accountVersion").asLong()!=account.path("version").asLong()
                ||!offer.path("configurationEpoch").asText().equals(configuration))throw new ApiFailure(409,"JOURNAL_AUTHORIZATION_CHANGED");
    }
    private ObjectNode current(String name){return new DayNotes(store).get(JournalMarkdown.date(name));}
    private boolean present(ObjectNode note){return note.path("version").asLong()>0&&!note.path("deleted").asBoolean();}
    private byte[] document(String name,ObjectNode note){
        if(!present(note))return null;
        byte[] original=state.find(JOURNAL_DOCUMENT,documentId(name)).map(value->Base64.getDecoder().decode(value.path("data").asText())).orElse(null);
        return JournalMarkdown.render(name,original,note.path("text").asText(),note.path("body").asText());
    }
    private void document(String name,byte[] data){
        var id=documentId(name);var old=state.find(JOURNAL_DOCUMENT,id);String hash=JournalMarkdown.hash(data);
        if(old.isPresent()&&old.get().path("hash").asText().equals(hash))return;
        var value=object().put("filename",name).put("hash",hash).put("data",Base64.getEncoder().encodeToString(data));
        if(old.isEmpty())state.insert(JOURNAL_DOCUMENT,id,value);else state.update(JOURNAL_DOCUMENT,old.get().put("hash",hash).put("data",value.path("data").asText()));
    }
    private static String hash(byte[] data){return data==null?ABSENT:JournalMarkdown.hash(data);}
    private boolean equivalent(String name,boolean exists,byte[] input,ObjectNode note,byte[] remote){
        if(exists!=present(note))return false;if(!exists)return true;
        var local=JournalMarkdown.document(name,input);var other=JournalMarkdown.document(name,remote);
        var left=new LinkedHashMap<>(local.metadata());var right=new LinkedHashMap<>(other.metadata());left.remove("timekeeper-label");right.remove("timekeeper-label");
        return local.note().label().equals(other.note().label())&&local.note().body().equals(other.note().body())&&left.equals(right);
    }
    private void latest(UUID device,String name,long sequence){
        if(state.list(JOURNAL_OFFER).stream().anyMatch(value->value.path("deviceId").asText().equals(device.toString())&&value.path("filename").asText().equals(name)&&value.path("sequence").asLong()>sequence))throw new ApiFailure(409,"JOURNAL_SUPERSEDED");
        if(state.list(JOURNAL_ITEM).stream().anyMatch(value->value.path("deviceId").asText().equals(device.toString())&&value.path("filename").asText().equals(name)&&value.path("sequence").asLong()>sequence))throw new ApiFailure(409,"JOURNAL_SUPERSEDED");
    }
    private void parent(ObjectNode offer){
        UUID device=uuid(offer.get("deviceId"));String name=offer.path("filename").asText();
        var common=state.find(JOURNAL_COMMON,commonId(device,name));String token=common.map(value->value.path("token").asText()).orElse("");
        if(!token.equals(offer.path("parentToken").asText())||common.map(value->value.path("sequence").asLong()>=offer.path("sequence").asLong()).orElse(false))throw new ApiFailure(409,"JOURNAL_SUPERSEDED");
        latest(device,name,offer.path("sequence").asLong());
    }
    ObjectNode offer(UUID exchange,UUID device,UUID client,long sequence,String name,String baseToken,boolean exists,byte[] input,String configuration){
        device(device,client);JournalMarkdown.date(name);
        if(sequence<1||sequence>=9007199254740991L||exists!=(input!=null)||configuration==null||!configuration.matches("[a-f0-9]{64}"))throw ApiFailure.validation();
        if(exists)JournalMarkdown.parse(name,input);
        String fingerprint=JsonValues.hash(device+"\n"+client+"\n"+sequence+"\n"+name+"\n"+Objects.toString(baseToken,"")+"\n"+exists+"\n"+hash(input));UUID id=offerId(exchange,name);
        var prior=state.find(JOURNAL_OFFER,id);
        if(prior.isPresent()){
            authority(prior.get(),device,configuration);
            if(!prior.get().path("fingerprint").asText().equals(fingerprint))throw new ApiFailure(409,"IDEMPOTENCY_KEY_REUSED");return summary(prior.get());
        }
        latest(device,name,sequence);
        if(state.list(JOURNAL_OFFER).stream().filter(value->!value.path("state").asText().equals("ACKNOWLEDGED")).count()>=500)throw new ApiFailure(409,"JOURNAL_PENDING_LIMIT");
        var common=state.find(JOURNAL_COMMON,commonId(device,name)).orElse(null);
        if(!Objects.toString(baseToken,"").equals(common==null?"":common.path("token").asText()))throw new ApiFailure(409,"JOURNAL_BASE_CHANGED");
        if(common!=null&&sequence<=common.path("sequence").asLong())throw new ApiFailure(409,"JOURNAL_SUPERSEDED");
        var note=current(name);byte[] remote=document(name,note);boolean same=equivalent(name,exists,input,note,remote);
        // A database-only note without a portable envelope may adopt an
        // identical initial file's otherwise unknown frontmatter properties.
        if(common==null&&exists&&present(note)&&state.find(JOURNAL_DOCUMENT,documentId(name)).isEmpty()){
            var parsed=JournalMarkdown.parse(name,input);same=parsed.label().equals(note.path("text").asText())&&parsed.body().equals(note.path("body").asText());
        }
        boolean localChanged=common!=null&&(common.path("exists").asBoolean()!=exists||!common.path("hash").asText().equals(hash(input)));
        boolean remoteChanged=common!=null&&(common.path("remoteVersion").asLong()!=note.path("version").asLong()||!common.path("remoteHash").asText().equals(hash(remote))||common.path("remoteDeleted").asBoolean()!=note.path("deleted").asBoolean());
        boolean conflict=!same&&(common==null?exists&&note.path("version").asLong()>0:localChanged&&remoteChanged);
        if(!conflict&&(same||(common==null&&exists)||(localChanged&&!remoteChanged))){
            if(exists){var parsed=JournalMarkdown.parse(name,input);note=new DayNotes(store).present(parsed.date(),etag(note),object().put("text",parsed.label()).put("body",parsed.body()));document(name,input);}
            else if(common!=null&&localChanged)note=new DayNotes(store).delete(JournalMarkdown.date(name),etag(note));
            remote=document(name,note);
        }
        var account=AccountStore.account(store.current().connection(),store.owner(),true);
        var offer=object().put("deviceId",device.toString()).put("clientStateId",client.toString()).put("filename",name).put("sequence",sequence)
                .put("fingerprint",fingerprint).put("parentToken",Objects.toString(baseToken,"")).put("token",UUID.randomUUID().toString())
                .put("expectedExists",exists).put("expectedHash",hash(input)).put("targetExists",present(note)).put("targetHash",hash(remote))
                .put("remoteVersion",note.path("version").asLong()).put("remoteDeleted",note.path("deleted").asBoolean())
                .put("syncEpoch",new JournalDevices(store).settings().path("epoch").asLong()).put("accountVersion",account.path("version").asLong()).put("configurationEpoch",configuration)
                .put("state",conflict?"CONFLICT":"OFFERED").put("createdAt",store.now().toString());
        state.insert(JOURNAL_SOURCE,id,object().put("incomingExists",exists).put("incomingData",input==null?"":Base64.getEncoder().encodeToString(input))
                .put("remoteData",remote==null?"":Base64.getEncoder().encodeToString(remote)).put("remoteExists",present(note)).put("remoteDeleted",note.path("deleted").asBoolean()));
        return summary(state.insert(JOURNAL_OFFER,id,offer));
    }
    byte[] outbound(UUID id,UUID device,String configuration){
        var offer=state.required(JOURNAL_OFFER,id);authority(offer,device,configuration);parent(offer);
        if(!offer.path("state").asText().equals("OFFERED")||!offer.path("targetExists").asBoolean())throw ApiFailure.conflict();
        byte[] data=Base64.getDecoder().decode(state.required(JOURNAL_SOURCE,id).path("remoteData").asText());
        if(!hash(data).equals(offer.path("targetHash").asText()))throw new ApiFailure(503,"JOURNAL_PRIVATE_STORAGE_UNAVAILABLE");return data;
    }
    ObjectNode acknowledge(UUID id,UUID device,String token,boolean exists,String installedHash,String configuration){
        new JournalDevices(store).authorizeBidirectional(device);
        var retained=state.find(JOURNAL_OFFER,id);
        if(retained.isEmpty()){
            // The per-note current receipt outlives bounded exchange history.
            // A lost acknowledgement must not lose its common base on restart.
            var common=state.list(JOURNAL_COMMON).stream().filter(value->value.path("deviceId").asText().equals(device.toString())&&value.path("offerId").asText().equals(id.toString())&&value.path("token").asText().equals(token)).findFirst().orElseThrow(ApiFailure::notFound);
            if(common.path("exists").asBoolean()!=exists||!common.path("hash").asText().equals(installedHash))throw ApiFailure.conflict();
            return ((ObjectNode)common.path("receipt")).deepCopy();
        }
        var offer=retained.get();authority(offer,device,configuration);
        if(!offer.path("token").asText().equals(token)||offer.path("targetExists").asBoolean()!=exists||!offer.path("targetHash").asText().equals(installedHash))throw ApiFailure.conflict();
        if(offer.path("state").asText().equals("ACKNOWLEDGED"))return summary(offer);
        if(!offer.path("state").asText().equals("OFFERED"))throw ApiFailure.conflict();parent(offer);
        var key=commonId(device,offer.path("filename").asText());var prior=state.find(JOURNAL_COMMON,key);
        var value=object().put("deviceId",device.toString()).put("filename",offer.path("filename").asText()).put("token",token).put("sequence",offer.path("sequence").asLong())
                .put("exists",exists).put("hash",installedHash).put("remoteVersion",offer.path("remoteVersion").asLong()).put("remoteHash",installedHash).put("remoteDeleted",offer.path("remoteDeleted").asBoolean());
        offer=state.update(JOURNAL_OFFER,offer.put("state","ACKNOWLEDGED").put("acknowledgedAt",store.now().toString()));state.remove(JOURNAL_SOURCE,state.required(JOURNAL_SOURCE,id));
        value.put("offerId",id.toString()).set("receipt",summary(offer));
        if(prior.isEmpty())state.insert(JOURNAL_COMMON,key,value);else{var copy=prior.get();value.fields().forEachRemaining(entry->copy.set(entry.getKey(),entry.getValue()));state.update(JOURNAL_COMMON,copy);}
        var acknowledged=state.list(JOURNAL_OFFER).stream().filter(item->item.path("state").asText().equals("ACKNOWLEDGED"))
                .sorted(Comparator.comparing((ObjectNode item)->item.path("acknowledgedAt").asText()).reversed()).skip(200).toList();acknowledged.forEach(item->state.remove(JOURNAL_OFFER,item));
        return summary(offer);
    }
    ObjectNode resolve(UUID id,String match,String currentEtag,boolean incoming,UUID device,String configuration){
        var offer=state.required(JOURNAL_OFFER,id);authority(offer,device,configuration);parent(offer);ApiReplies.match(offer,match);
        if(!offer.path("state").asText().equals("CONFLICT"))throw ApiFailure.conflict();String name=offer.path("filename").asText();var note=current(name);ApiReplies.match(note,currentEtag);
        var source=state.required(JOURNAL_SOURCE,id);
        if(incoming){
            if(source.path("incomingExists").asBoolean()){
                byte[] bytes=Base64.getDecoder().decode(source.path("incomingData").asText());var parsed=JournalMarkdown.parse(name,bytes);
                note=new DayNotes(store).present(parsed.date(),etag(note),object().put("text",parsed.label()).put("body",parsed.body()));document(name,bytes);
            }else note=new DayNotes(store).delete(JournalMarkdown.date(name),etag(note));
        }
        byte[] data=document(name,note);state.update(JOURNAL_SOURCE,source.put("remoteData",data==null?"":Base64.getEncoder().encodeToString(data)).put("remoteExists",present(note)).put("remoteDeleted",note.path("deleted").asBoolean()));
        offer.put("state","OFFERED").put("token",UUID.randomUUID().toString()).put("targetExists",present(note)).put("targetHash",hash(data))
                .put("remoteVersion",note.path("version").asLong()).put("remoteDeleted",note.path("deleted").asBoolean());
        return summary(state.update(JOURNAL_OFFER,offer));
    }
    ObjectNode candidate(UUID id){
        var offer=state.required(JOURNAL_OFFER,id);var source=state.required(JOURNAL_SOURCE,id);var result=summary(offer);
        for(String side:List.of("incoming","remote")){
            boolean exists=source.path(side+"Exists").asBoolean();var copy=result.putObject(side).put("exists",exists).put("deleted",!exists);
            if(exists){var parsed=JournalMarkdown.parse(offer.path("filename").asText(),Base64.getDecoder().decode(source.path(side+"Data").asText()));copy.put("text",parsed.label()).put("body",parsed.body());}
        }
        var current=current(offer.path("filename").asText());result.set("current",current);result.put("currentEtag",etag(current));return result;
    }
    static ObjectNode summary(ObjectNode offer){
        var result=object().put("candidateEtag",etag(offer));for(String field:List.of("id","deviceId","filename","sequence","state","token","parentToken","expectedExists","expectedHash","targetExists","targetHash","remoteVersion","remoteDeleted"))result.set(field,offer.get(field));return result;
    }
}
