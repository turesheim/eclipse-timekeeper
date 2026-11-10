package net.resheim.timekeeper.connect;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import jakarta.enterprise.context.ApplicationScoped;

/** Bound unauthenticated handoff creation without retaining client addresses. */
@ApplicationScoped
class ConnectLimits {
    private record Window(long start,int requests){}
    private final Map<String,Window> windows=new HashMap<>();
    void acquire(String peer){acquire(peer,Instant.now().getEpochSecond());}
    synchronized void acquire(String peer,long now){
        if(windows.size()>8192)windows.entrySet().removeIf(entry->entry.getValue().start()+600<=now);
        String key=ConnectProtocol.hash(peer);var before=windows.get(key);
        if(before==null||before.start()+600<=now){if(windows.size()>=10000&&!windows.containsKey(key))throw new ConnectFailure(429,"TRY_LATER");windows.put(key,new Window(now,1));}
        else{if(before.requests()>=100)throw new ConnectFailure(429,"TRY_LATER");windows.put(key,new Window(before.start(),before.requests()+1));}
    }
}
