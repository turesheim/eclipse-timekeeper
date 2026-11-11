package net.resheim.timekeeper.server;

import java.util.HashMap;
import java.util.Map;

/** The same owner-isolated persistence fixtures run on H2 locally and PostgreSQL in container CI. */
final class TaskProviderTestDatabase {
    static Map<String,String> settings(String name){
        var config=new HashMap<String,String>();String base=System.getenv("TIMEKEEPER_TEST_TASK_DB_URL");
        config.put("quarkus.datasource.jdbc.url",base==null?"jdbc:h2:mem:"+name:base+"_"+name);
        if(base!=null){config.put("quarkus.datasource.db-kind","postgresql");config.put("quarkus.datasource.username",System.getenv("TIMEKEEPER_TEST_PG_USER"));config.put("quarkus.datasource.password",System.getenv("TIMEKEEPER_TEST_PG_PASSWORD"));}
        return config;
    }
}
