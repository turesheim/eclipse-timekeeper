package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.UUID;

/** Content-free owner snapshot for cached calendar details and attached notes. */
final class CalendarSearchState {
    static final String TYPE="CALENDAR_SEARCH";
    static final UUID ID=ImportLedger.identifier("calendar-search","state");
    private final ServerStore store;
    CalendarSearchState(ServerStore store){this.store=store;}
    long generation(){
        var found=store.find(TYPE,ID);if(found.isEmpty())return 0;
        var value=found.get().path("version");long generation=value.asLong();
        if(!value.isIntegralNumber()||generation<1||generation>9007199254740991L)throw new ApiFailure(503,"NOTES_SEARCH_INDEXING");
        return generation;
    }
    void changed(){
        long previous=generation();if(previous>=9007199254740991L)throw ApiFailure.conflict();
        var value=object().put("id",ID.toString()).put("ownerId",store.owner()).put("version",previous+1);
        if(previous==0)store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",store.owner(),TYPE,ID,1,value.toString());
        else if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",previous+1,value.toString(),store.owner(),TYPE,ID,previous)!=1)throw new ApiFailure(412,"STALE_VERSION");
    }
}
