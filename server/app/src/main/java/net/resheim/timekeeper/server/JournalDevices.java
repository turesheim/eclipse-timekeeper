package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import static net.resheim.timekeeper.server.JournalState.Kind.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Owner-locked journal settings and public credentials; never stores private keys. */
final class JournalDevices {
    private static final UUID SETTINGS=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final ServerStore store;
    private final JournalState state;
    JournalDevices(ServerStore store){this.store=store;state=new JournalState(store);}
    ObjectNode settings(){return state.find(JOURNAL_CFG,SETTINGS).orElseGet(()->object().put("enabled",false).put("epoch",0).put("version",0));}
    ObjectNode enabled(boolean enabled){
        return configure(enabled,settings().path("bidirectional").asBoolean());
    }
    ObjectNode configure(boolean enabled,boolean bidirectional){
        var current=settings();if(current.path("enabled").asBoolean()==enabled&&current.path("bidirectional").asBoolean()==bidirectional)return current;
        boolean wasEnabled=current.path("enabled").asBoolean(),wasBidirectional=current.path("bidirectional").asBoolean();
        current.put("enabled",enabled).put("bidirectional",bidirectional).put("epoch",Math.addExact(current.path("epoch").asLong(),1));
        var saved=state.find(JOURNAL_CFG,SETTINGS).isPresent()?state.update(JOURNAL_CFG,current):state.insert(JOURNAL_CFG,SETTINGS,current);
        if((wasEnabled&&!enabled)||(wasBidirectional&&!bidirectional))abort(null);
        if(wasEnabled!=enabled)state.audit(enabled?"SYNC_ENABLED":"SYNC_DISABLED",null);
        if(wasBidirectional!=bidirectional)state.audit(bidirectional?"BIDIRECTIONAL_ENABLED":"BIDIRECTIONAL_DISABLED",null);return saved;
    }
    ObjectNode enroll(String label,String publicKey,UUID rotate){
        return enroll(label,publicKey,rotate,false);
    }
    ObjectNode enroll(String label,String publicKey,UUID rotate,boolean bidirectional){
        if(bidirectional&&!settings().path("bidirectional").asBoolean())throw new ApiFailure(403,"JOURNAL_BIDIRECTIONAL_DISABLED");
        if(label==null||label.isBlank()||label.codePointCount(0,label.length())>80||label.codePoints().anyMatch(Character::isISOControl))throw ApiFailure.validation();
        var key=JournalKey.parse(publicKey);var devices=state.list(JOURNAL_DEVICE);
        if(devices.stream().filter(device->!device.path("revoked").asBoolean()).count()>=20&&rotate==null)throw new ApiFailure(409,"JOURNAL_DEVICE_LIMIT");
        if(devices.stream().anyMatch(device->!device.path("revoked").asBoolean()&&device.path("publicKey").asText().equals(key.publicKey())))throw new ApiFailure(409,"JOURNAL_KEY_ALREADY_ENROLLED");
        if(rotate!=null){if(state.required(JOURNAL_DEVICE,rotate).path("revoked").asBoolean())throw ApiFailure.conflict();revoke(rotate);}
        var value=state.insert(JOURNAL_DEVICE,UUID.randomUUID(),object().put("label",label.strip()).put("publicKey",key.publicKey()).put("fingerprint",key.fingerprint())
                .put("revoked",false).put("bidirectional",bidirectional).put("createdAt",store.now().toString()).put("nextSequence",1));
        state.audit(rotate==null?"DEVICE_ENROLLED":"DEVICE_ROTATED",uuid(value.get("id")));return management(value);
    }
    ObjectNode revoke(UUID device){
        var value=state.required(JOURNAL_DEVICE,device);if(!value.path("revoked").asBoolean()){
            value=state.update(JOURNAL_DEVICE,value.put("revoked",true).put("revokedAt",store.now().toString()));abort(device);state.audit("DEVICE_REVOKED",device);
        }return management(value);
    }
    private void abort(UUID device){
        for(var lease:state.list(JOURNAL_LEASE))if((device==null||lease.path("deviceId").asText().equals(device.toString()))&&lease.path("state").asText().equals("RECEIVING"))state.update(JOURNAL_LEASE,lease.put("state","ABORTED"));
        for(var item:state.list(JOURNAL_ITEM))if((device==null||item.path("deviceId").asText().equals(device.toString()))&&item.path("state").asText().equals("PENDING")){
            item.put("state","ABORTED").put("error","JOURNAL_AUTHORIZATION_WITHDRAWN");state.update(JOURNAL_ITEM,item);
        }
    }
    ObjectNode authorize(UUID id){
        if(!settings().path("enabled").asBoolean())throw new ApiFailure(403,"JOURNAL_DISABLED");
        var account=AccountStore.account(store.current().connection(),store.owner(),true);if(!account.path("state").asText().equals("ENABLED"))throw new ApiFailure(403,"JOURNAL_ACCOUNT_DISABLED");
        var device=state.required(JOURNAL_DEVICE,id);if(device.path("revoked").asBoolean())throw new ApiFailure(403,"JOURNAL_DEVICE_REVOKED");return device;
    }
    ObjectNode authorizeBidirectional(UUID id){
        var device=authorize(id);if(!settings().path("bidirectional").asBoolean())throw new ApiFailure(403,"JOURNAL_BIDIRECTIONAL_DISABLED");
        if(!device.path("bidirectional").asBoolean())throw new ApiFailure(403,"JOURNAL_DEVICE_WRITE_ONLY");return device;
    }
    List<ObjectNode> list(){return state.list(JOURNAL_DEVICE).stream().map(JournalDevices::management).toList();}
    static ObjectNode management(ObjectNode device){var result=object();for(String field:List.of("id","version","label","fingerprint","revoked","bidirectional","createdAt","revokedAt","lastVerifiedAt","lastTransferAt","lastImportAt"))if(device.has(field))result.set(field,device.get(field));return result;}
}
