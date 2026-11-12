package net.resheim.timekeeper.connect;

import static net.resheim.timekeeper.connect.ConnectProtocol.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Durable, encrypted service records. CAS transitions work across service replicas. */
@ApplicationScoped
@io.quarkus.runtime.Startup
class ConnectStore {
    @Inject DataSource datasource;
    @Inject ConnectSettings settings;
    private SecretKeySpec key;
    record Row(String id,String kind,long version,long expires,ObjectNode value){@Override public String toString(){return "Private shared-sign-in record";}}
    @PostConstruct void start(){
        net.resheim.timekeeper.server.EnvironmentAliases.report();
        try{byte[] bytes=Base64.getDecoder().decode(settings.encryptionKey());if(bytes.length!=32)throw new IllegalArgumentException();key=new SecretKeySpec(bytes,"AES");}
        catch(RuntimeException invalid){throw new IllegalStateException("A 256-bit shared-sign-in encryption key is required");}
        try(var connection=datasource.getConnection();var statement=connection.createStatement()){
            statement.execute("CREATE TABLE IF NOT EXISTS tk_connect_record (record_key VARCHAR(100) PRIMARY KEY, kind VARCHAR(20) NOT NULL, version BIGINT NOT NULL, expires_at BIGINT NOT NULL, payload TEXT NOT NULL)");
        }catch(SQLException failure){throw new ConnectFailure(503,"SERVICE_UNAVAILABLE");}
    }
    void ready(){
        try(var connection=datasource.getConnection();var query=connection.prepareStatement("SELECT 1 FROM tk_connect_record WHERE record_key=?")){query.setString(1,"health");query.setQueryTimeout(3);try(var rows=query.executeQuery()){} }
        catch(SQLException unavailable){throw new ConnectFailure(503,"SERVICE_UNAVAILABLE");}
    }
    DataSource database(){return datasource;}
    Row insert(String id,String kind,long expires,ObjectNode value){
        try(var connection=datasource.getConnection();var statement=connection.prepareStatement("INSERT INTO tk_connect_record(record_key,kind,version,expires_at,payload) VALUES(?,?,0,?,?)")){
            statement.setString(1,id);statement.setString(2,kind);statement.setLong(3,expires);statement.setString(4,seal(id,value));statement.executeUpdate();return new Row(id,kind,0,expires,value.deepCopy());
        }catch(SQLException failure){throw new ConnectFailure(503,"SERVICE_UNAVAILABLE");}
    }
    Row get(String id){
        try(var connection=datasource.getConnection();var statement=connection.prepareStatement("SELECT kind,version,expires_at,payload FROM tk_connect_record WHERE record_key=?")){
            statement.setString(1,id);try(var rows=statement.executeQuery()){
                if(!rows.next())throw new ConnectFailure(404,"NOT_FOUND");
                var row=new Row(id,rows.getString(1),rows.getLong(2),rows.getLong(3),open(id,rows.getString(4)));
                if(row.expires()<Instant.now().getEpochSecond()){delete(row);throw new ConnectFailure(410,"AUTHORIZATION_EXPIRED");}return row;
            }
        }catch(SQLException failure){throw new ConnectFailure(503,"SERVICE_UNAVAILABLE");}
    }
    Row save(Row previous,ObjectNode value){
        return save(previous,value,previous.expires());
    }
    Row save(Row previous,ObjectNode value,long expires){
        try(var connection=datasource.getConnection();var statement=connection.prepareStatement("UPDATE tk_connect_record SET version=version+1,payload=?,expires_at=? WHERE record_key=? AND version=?")){
            statement.setString(1,seal(previous.id(),value));statement.setLong(2,expires);statement.setString(3,previous.id());statement.setLong(4,previous.version());
            if(statement.executeUpdate()!=1)throw new ConnectFailure(409,"AUTHORIZATION_CHANGED");return new Row(previous.id(),previous.kind(),previous.version()+1,expires,value.deepCopy());
        }catch(SQLException failure){throw new ConnectFailure(503,"SERVICE_UNAVAILABLE");}
    }
    /** Consume a handoff and create exactly one installation credential in the same commit. */
    void redeem(Row handoff,String grant,ObjectNode value,long expires){
        try(var connection=datasource.getConnection()){
            connection.setAutoCommit(false);
            try(var remove=connection.prepareStatement("DELETE FROM tk_connect_record WHERE record_key=? AND version=?");
                    var insert=connection.prepareStatement("INSERT INTO tk_connect_record(record_key,kind,version,expires_at,payload) VALUES(?,'GRANT',0,?,?)")){
                remove.setString(1,handoff.id());remove.setLong(2,handoff.version());if(remove.executeUpdate()!=1)throw new ConnectFailure(409,"AUTHORIZATION_CHANGED");
                insert.setString(1,grant);insert.setLong(2,expires);insert.setString(3,seal(grant,value));insert.executeUpdate();connection.commit();
            }catch(Exception failure){connection.rollback();if(failure instanceof ConnectFailure expected)throw expected;throw new ConnectFailure(503,"SERVICE_UNAVAILABLE");}
        }catch(SQLException failure){throw new ConnectFailure(503,"SERVICE_UNAVAILABLE");}
    }
    void delete(Row previous){
        try(var connection=datasource.getConnection();var statement=connection.prepareStatement("DELETE FROM tk_connect_record WHERE record_key=? AND version=?")){
            statement.setString(1,previous.id());statement.setLong(2,previous.version());if(statement.executeUpdate()!=1)throw new ConnectFailure(409,"AUTHORIZATION_CHANGED");
        }catch(SQLException failure){throw new ConnectFailure(503,"SERVICE_UNAVAILABLE");}
    }
    void clean(){
        try(var connection=datasource.getConnection();var statement=connection.prepareStatement("DELETE FROM tk_connect_record WHERE expires_at<?")){
            statement.setLong(1,Instant.now().getEpochSecond());statement.executeUpdate();
        }catch(SQLException failure){throw new ConnectFailure(503,"SERVICE_UNAVAILABLE");}
    }
    long count(String kind){
        try(var connection=datasource.getConnection();var statement=connection.prepareStatement("SELECT COUNT(*) FROM tk_connect_record WHERE kind=?")){
            statement.setString(1,kind);try(var rows=statement.executeQuery()){rows.next();return rows.getLong(1);}
        }catch(SQLException failure){throw new ConnectFailure(503,"SERVICE_UNAVAILABLE");}
    }
    private String seal(String id,ObjectNode value){
        try{byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(nonce)+"."+Base64.getEncoder().encodeToString(cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8)));
        }catch(Exception failure){throw new ConnectFailure(503,"SERVICE_UNAVAILABLE");}
    }
    private ObjectNode open(String id,String value){
        try{var parts=value.split("\\.",-1);var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));return (ObjectNode)JSON.readTree(cipher.doFinal(Base64.getDecoder().decode(parts[1])));}
        catch(Exception failure){throw new ConnectFailure(503,"SERVICE_UNAVAILABLE");}
    }
}
