package net.resheim.timekeeper.server;

import java.util.*;

/** Canonical owner-checked source references shared by independent note metadata. */
final class NoteReferences {
    private static final Set<String> SUPPORTED=Set.of(DayNotes.TYPE,WeekNotes.TYPE,EventNotes.TYPE,VaultContent.TYPE);
    private final ServerStore store;
    NoteReferences(ServerStore store){this.store=store;}
    static VaultSources.Ref key(String type,String key){
        if(type==null||key==null||!SUPPORTED.contains(type))throw ApiFailure.validation();
        return switch(type){
            case DayNotes.TYPE->{var day=DayNotes.date(key);yield new VaultSources.Ref(type,DayNotes.id(day),key);}
            case WeekNotes.TYPE->{var week=WeekNotes.week(key);yield new VaultSources.Ref(type,WeekNotes.id(week),key);}
            case EventNotes.TYPE->{var event=EventNotes.filename(key);yield new VaultSources.Ref(type,event.id(),key);}
            case VaultContent.TYPE->{UUID id;try{id=UUID.fromString(key);if(!id.toString().equals(key))throw ApiFailure.validation();}catch(IllegalArgumentException invalid){throw ApiFailure.validation();}
                yield new VaultSources.Ref(type,id,key);}
            default->throw ApiFailure.validation();
        };
    }
    VaultSources.Ref reference(String type,String key){
        var ref=key(type,key);
        if(type.equals(EventNotes.TYPE))store.find(type,ref.id()).orElseThrow(ApiFailure::notFound);
        if(type.equals(VaultContent.TYPE)){
            var entry=new VaultIndex(store).referenced(type,ref.id());if(entry==null||!entry.path("kind").asText().equals("MARKDOWN")||entry.path("deleted").asBoolean())throw ApiFailure.notFound();
            if(!new VaultSettings(store).allowed(VaultCollection.path(entry)))throw new ApiFailure(403,"VAULT_CONFIG_DISABLED");
        }
        return ref;
    }
}
