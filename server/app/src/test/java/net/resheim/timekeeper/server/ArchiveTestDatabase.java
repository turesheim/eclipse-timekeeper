package net.resheim.timekeeper.server;

import java.util.Map;

/** Separate databases keep independent Quarkus account fixtures isolated on real PostgreSQL too. */
final class ArchiveTestDatabase {
    private ArchiveTestDatabase() { }
    static Map<String,String> configuration(String fixture,String h2) {
        String prefix=System.getenv("TIMEKEEPER_TEST_ARCHIVE_DB_URL");
        if(prefix==null) return Map.of("quarkus.datasource.jdbc.url",h2);
        String user=System.getenv("TIMEKEEPER_TEST_PG_USER"),password=System.getenv("TIMEKEEPER_TEST_PG_PASSWORD");
        if(user==null || password==null || !fixture.matches("[a-z]+")) throw new IllegalStateException("Set the isolated PostgreSQL test credentials and archive database prefix");
        int query=prefix.indexOf('?');String url=query<0 ? prefix+"_"+fixture : prefix.substring(0,query)+"_"+fixture+prefix.substring(query);
        return Map.of("quarkus.datasource.jdbc.url",url,"quarkus.datasource.db-kind","postgresql","quarkus.datasource.username",user,"quarkus.datasource.password",password);
    }
}
