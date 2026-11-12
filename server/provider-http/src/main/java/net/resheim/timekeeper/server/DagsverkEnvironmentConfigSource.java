package net.resheim.timekeeper.server;

import java.util.*;
import org.eclipse.microprofile.config.spi.ConfigSource;

/** Prevent legacy auto-mapped environment names from bypassing DAGSVERK precedence. */
public final class DagsverkEnvironmentConfigSource implements ConfigSource {
    private static final java.util.regex.Pattern SEPARATOR=java.util.regex.Pattern.compile("[^A-Z0-9_]");
    private final Map<String,String> properties;
    public DagsverkEnvironmentConfigSource(){this(System.getenv());}
    public DagsverkEnvironmentConfigSource(Map<String,String> environment){
        var values=new HashMap<String,String>();for(String name:environment.keySet())if(name.startsWith("DAGSVERK_")){
            String value=environment.get(name),legacy="TIMEKEEPER_"+name.substring(9);values.put(name,value);
            // Keep existing private-file inventory and recovery classification discoverable.
            values.put(legacy,value);values.put(legacy.toLowerCase(Locale.ROOT).replace('_','.'),value);
        }properties=Map.copyOf(values);
    }
    @Override public int getOrdinal(){return 301;}
    @Override public String getName(){return "DagsverkEnvConfigSource";}
    @Override public Map<String,String> getProperties(){return properties;}
    @Override public Set<String> getPropertyNames(){return properties.keySet();}
    @Override public String getValue(String name){
        if(properties.isEmpty()||!name.regionMatches(true,0,"timekeeper",0,10)&&!name.regionMatches(true,0,"dagsverk",0,8))return null;
        String direct=properties.get(name);if(direct!=null)return direct;
        String normalized=SEPARATOR.matcher(name.toUpperCase(Locale.ROOT)).replaceAll("_");
        if(normalized.startsWith("TIMEKEEPER_"))normalized="DAGSVERK_"+normalized.substring(11);
        return properties.get(normalized);
    }
}
