package net.resheim.timekeeper.server;

import java.util.*;
import org.eclipse.microprofile.config.spi.ConfigSource;

/** Prevent legacy auto-mapped environment names from bypassing DAGSVERK precedence. */
public final class DagsverkEnvironmentConfigSource implements ConfigSource {
    private final Map<String,String> environment;
    public DagsverkEnvironmentConfigSource(){this(System.getenv());}
    public DagsverkEnvironmentConfigSource(Map<String,String> environment){this.environment=Map.copyOf(environment);}
    @Override public int getOrdinal(){return 301;}
    @Override public String getName(){return "DagsverkEnvConfigSource";}
    @Override public Map<String,String> getProperties(){
        var result=new HashMap<String,String>();for(String name:environment.keySet())if(name.startsWith("DAGSVERK_")){
            String value=environment.get(name),legacy="TIMEKEEPER_"+name.substring(9);result.put(name,value);
            // Keep existing private-file inventory and recovery classification discoverable.
            result.put(legacy,value);result.put(legacy.toLowerCase(Locale.ROOT).replace('_','.'),value);
        }return result;
    }
    @Override public Set<String> getPropertyNames(){return getProperties().keySet();}
    @Override public String getValue(String name){
        String normalized=name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]","_");
        if(normalized.startsWith("TIMEKEEPER_"))normalized="DAGSVERK_"+normalized.substring(11);
        return normalized.startsWith("DAGSVERK_")?environment.get(normalized):null;
    }
}
