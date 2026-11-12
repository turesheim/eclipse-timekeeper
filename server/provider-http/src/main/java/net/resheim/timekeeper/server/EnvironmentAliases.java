package net.resheim.timekeeper.server;

import java.util.*;

/** External configuration migration; technical property and archive names remain stable. */
public final class EnvironmentAliases {
    private EnvironmentAliases(){}
    public static String value(Map<String,String> environment,String canonical){
        return environment.containsKey(canonical)?environment.get(canonical):environment.get("TIMEKEEPER_"+canonical.substring("DAGSVERK_".length()));
    }
    public static List<String> conflicts(Map<String,String> environment){
        return environment.keySet().stream().filter(name->name.startsWith("DAGSVERK_"))
                .filter(name->{String legacy="TIMEKEEPER_"+name.substring(9);return environment.containsKey(legacy)&&!Objects.equals(environment.get(name),environment.get(legacy));})
                .sorted().toList();
    }
    public static void report(){
        var logger=System.getLogger("net.resheim.timekeeper.configuration");
        for(String name:conflicts(System.getenv()))logger.log(System.Logger.Level.WARNING,name+" overrides deprecated TIMEKEEPER_"+name.substring(9)+"; values are redacted.");
    }
}
