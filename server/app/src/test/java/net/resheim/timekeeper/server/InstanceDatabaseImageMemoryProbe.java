package net.resheim.timekeeper.server;

/** Separate constrained JVM: data larger than its heap must remain streamable. */
public final class InstanceDatabaseImageMemoryProbe {
    public static void main(String[] ignored) {
        try {
            String url=System.getenv("TIMEKEEPER_INSTANCE_PROBE_URL");
            if(url==null||!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/timekeeper_image_[a-f0-9]+"))throw new IllegalArgumentException();
            try(var connection=java.sql.DriverManager.getConnection(url,"postgres",System.getenv("TIMEKEEPER_INSTANCE_TEST_PG_PASSWORD")!=null?System.getenv("TIMEKEEPER_INSTANCE_TEST_PG_PASSWORD"):System.getenv("TIMEKEEPER_TEST_PG_PASSWORD"))) {
                connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);
                var count=new java.io.OutputStream(){long bytes;@Override public void write(int value){bytes++;}@Override public void write(byte[] value,int offset,int length){bytes+=length;}};
                InstanceDatabaseImage.write(connection,count);if(count.bytes<75_000_000L)throw new IllegalStateException();
            }
            System.out.println("INSTANCE_MEMORY_PROBE_OK");
        }catch(Throwable failure){System.err.println("INSTANCE_MEMORY_PROBE_FAILED");System.exit(1);}
    }
}
