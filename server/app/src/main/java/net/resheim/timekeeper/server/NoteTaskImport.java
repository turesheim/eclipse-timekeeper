package net.resheim.timekeeper.server;

import java.util.function.Supplier;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Reviewed imports choose task enrollment inside the same owner transaction as their bytes. */
enum NoteTaskImport {
    CHECKBOX, COMPLETED, IGNORE;

    static NoteTaskImport parse(String value){
        if(value==null)return CHECKBOX;
        try{return valueOf(value);}catch(IllegalArgumentException invalid){throw ApiFailure.validation();}
    }
    ObjectNode write(ServerStore store,VaultImportDates dates,Supplier<ObjectNode> write){
        var entry=store.withNoteTaskSync(write);
        if(java.util.Set.of("MARKDOWN","DAY","WEEK","EVENT").contains(entry.path("kind").asText())){
            var ref=VaultSources.fromEntry(entry);var source=new VaultSources(store,store.vaultLimits(),ref);
            new NoteTasks(store).imported(ref,source.read(),this,dates.modifiedAt()==null?store.now():dates.modifiedAt());
            entry=new VaultIndex(store).get(VaultCollection.id(entry));
        }
        return dates.apply(store,entry);
    }
}
