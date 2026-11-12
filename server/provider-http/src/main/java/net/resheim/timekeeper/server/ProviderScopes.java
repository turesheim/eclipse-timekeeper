package net.resheim.timekeeper.server;

import java.util.*;

/** Identical service-specific consent in direct and managed OAuth registrations. */
public final class ProviderScopes {
    private static final String TASK_READ="https://www.googleapis.com/auth/tasks.readonly",TASK_WRITE="https://www.googleapis.com/auth/tasks",CALENDAR="https://www.googleapis.com/auth/calendar.readonly";
    private ProviderScopes(){}
    public static String requested(String provider,boolean tasks,boolean calendars,boolean writable){
        if(!Set.of("google","microsoft").contains(provider)||!tasks&&!calendars||writable&&(!tasks||!provider.equals("google")))throw new IllegalArgumentException("Unsupported provider services");
        var scopes=new ArrayList<String>();scopes.add("openid");
        if(provider.equals("google")){
            scopes.add("email");scopes.add("profile");if(tasks)scopes.add(writable?TASK_WRITE:TASK_READ);if(calendars)scopes.add(CALENDAR);
        }else{
            scopes.add("offline_access");scopes.add("https://graph.microsoft.com/User.Read");if(tasks)scopes.add("https://graph.microsoft.com/Tasks.Read");if(calendars)scopes.add("https://graph.microsoft.com/Calendars.Read");
        }
        return String.join(" ",scopes);
    }
    public static boolean granted(String provider,Set<String> scopes,boolean tasks,boolean calendars,boolean writable){
        boolean google=provider.equals("google");
        return (google?scopes.contains("openid"):graph(scopes,"User.Read"))
                &&(!tasks||(google?scopes.contains(TASK_WRITE)||!writable&&scopes.contains(TASK_READ):graph(scopes,"Tasks.Read")||graph(scopes,"Tasks.ReadWrite")))
                &&(!calendars||(google?scopes.contains(CALENDAR):graph(scopes,"Calendars.Read")||graph(scopes,"Calendars.ReadWrite")))
                &&(!writable||google&&tasks&&scopes.contains(TASK_WRITE));
    }
    private static boolean graph(Set<String> scopes,String name){return scopes.contains(name)||scopes.contains("https://graph.microsoft.com/"+name);}
}
