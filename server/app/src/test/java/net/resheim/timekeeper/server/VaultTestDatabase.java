package net.resheim.timekeeper.server;

import java.util.*;

/** The same vault acceptance cases run against isolated H2 or PostgreSQL databases. */
final class VaultTestDatabase {
    private VaultTestDatabase(){}
    static Map<String,String> configuration(String fixture,Map<String,String> overrides){
        var result=new HashMap<>(overrides);result.putAll(ArchiveTestDatabase.configuration("vault"+fixture,overrides.get("quarkus.datasource.jdbc.url")));return Map.copyOf(result);
    }
}
