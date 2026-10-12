package net.resheim.timekeeper.server;

/** Reject JDBC startup SQL and persistent settings before opening any database. */
public final class DatabaseConfiguration {
    private DatabaseConfiguration() { }
    public static void validate(String url) {
        if(url==null || !url.startsWith("jdbc:h2:") || url.substring(8).isBlank()) throw new IllegalArgumentException("An explicit named H2 database is required");
        String[] parts=url.substring(8).split(";",-1);
        if(parts[0].equals("mem:") || parts[0].equals("file:")) throw new IllegalArgumentException("An explicit named H2 database is required");
        for(int i=1;i<parts.length;i++) {
            String[] setting=parts[i].split("=",2);if(setting.length!=2) throw new IllegalArgumentException("Unsupported server JDBC setting");
            String name=setting[0].toUpperCase(java.util.Locale.ROOT),value=setting[1];
            boolean supported=switch(name) {
                case "IFEXISTS","DB_CLOSE_ON_EXIT","AUTO_SERVER" -> value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
                case "LOCK_TIMEOUT","AUTO_SERVER_PORT" -> value.matches("[0-9]+");
                default -> false;
            };
            if(!supported) throw new IllegalArgumentException("Unsupported server JDBC setting");
        }
    }
}
