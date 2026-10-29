package net.resheim.timekeeper.server;

import java.nio.file.*;
import java.sql.*;

/** Separate AUTO_SERVER process; no real credentials or user data are used. */
public final class InstanceRestoreFencePeer {
    public static void main(String[] args) throws Exception {
        Connection connection=DriverManager.getConnection(args[0]);
        try {
            connection.setAutoCommit(false);connection.createStatement().executeUpdate("UPDATE tk_owner SET revision=7");Files.writeString(Path.of(args[1]),"ready");
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while(!Files.exists(Path.of(args[2]))){if(System.nanoTime()>deadline)throw new IllegalStateException("Peer release timed out");Thread.sleep(10);}
            connection.commit();
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT revision FROM tk_owner")){if(!rows.next()||rows.getLong(1)!=7)throw new IllegalStateException("Peer work was lost");}
            System.out.println("FENCE_PEER_COMMITTED");
        }finally{try{connection.close();}catch(SQLException expected){/* Process shutdown releases its private test connection. */}}
    }
}
