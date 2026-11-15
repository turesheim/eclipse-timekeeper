package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import static net.resheim.timekeeper.server.AccountStore.query;
import java.io.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Bounded access to JSON-backed vault bytes, including files larger than the JVM heap. */
final class ArchiveBinarySource {
    private static final int CHUNK=4*1024*1024;
    private static final JsonFactory JSON=JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxStringLength(JsonValues.STORED_STRING_LIMIT).build()).build();
    private final Connection connection;
    private final String owner,type;
    private final UUID id;
    private final long version;
    private final ObjectNode metadata;
    @FunctionalInterface interface Each {void accept(ArchiveBinarySource source)throws IOException;}
    static void each(Connection c,String owner,String type,Each output)throws SQLException,IOException {
        try(var statement=query(c,"SELECT id,version FROM tk_resource WHERE owner_id=? AND entity_type=? ORDER BY id",owner,type)){
            statement.setFetchSize(256);
            try(var rows=statement.executeQuery()){while(rows.next())output.accept(new ArchiveBinarySource(c,owner,type,(UUID)rows.getObject(1),rows.getLong(2)));}
        }
    }
    static ArchiveBinarySource get(Connection c,String owner,String type,UUID id)throws IOException {
        try(var statement=query(c,"SELECT version FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",owner,type,id);var rows=statement.executeQuery()){
            if(!rows.next())throw new IOException("Missing archive content");return new ArchiveBinarySource(c,owner,type,id,rows.getLong(1));
        }catch(SQLException failure){throw new IOException("Archive content read failed",failure);}
    }
    private ArchiveBinarySource(Connection c,String owner,String type,UUID id,long version)throws IOException {
        this.connection=c;this.owner=owner;this.type=type;this.id=id;this.version=version;this.metadata=read(null);
    }
    UUID id(){return id;}
    ObjectNode metadata(){return metadata;}
    long length(){return metadata.path("byteLength").asLong();}
    String digest(){return metadata.path("sha256").asText();}
    void writeTo(OutputStream output)throws IOException {read(output);}

    private ObjectNode read(OutputStream output)throws IOException {
        var value=object();boolean data=false;long count=0;final MessageDigest digest;
        try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        try(var input=reader();var parser=JSON.createParser(input).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)){
            if(parser.nextToken()!=JsonToken.START_OBJECT)throw new IOException("Invalid archive content");
            while(parser.nextToken()!=JsonToken.END_OBJECT){
                if(parser.currentToken()!=JsonToken.FIELD_NAME)throw new IOException("Invalid archive content");String field=parser.currentName();parser.nextToken();
                if(field.equals("data")){
                    if(parser.currentToken()!=JsonToken.VALUE_STRING)throw new IOException("Invalid archive content");data=true;
                    if(output==null)parser.skipChildren();
                    else count=parser.readBinaryValue(Base64Variants.getDefaultVariant(),new DigestOutputStream(output,digest));
                }else value.set(field,MAPPER.readTree(parser));
            }
            if(parser.nextToken()!=null)throw new IOException("Invalid archive content");
        }
        if(!data||!owner.equals(value.path("ownerId").asText())||!id.toString().equals(value.path("id").asText())||version!=value.path("version").asLong(-1)
                ||value.path("byteLength").asLong(-1)<0||!value.path("sha256").asText().matches("[a-f0-9]{64}"))throw new IOException("Invalid archive content");
        if(output!=null&&(count!=value.path("byteLength").asLong()||!HexFormat.of().formatHex(digest.digest()).equals(value.path("sha256").asText())))throw new IOException("Invalid archive content");
        return value;
    }
    private Reader reader()throws IOException {
        try{
            // H2 CLOB streams are genuinely incremental. PostgreSQL's text
            // getCharacterStream first materializes the entire field, so slice
            // it on the server instead. Each wire message stays bounded too.
            if(connection.getMetaData().getDatabaseProductName().equals("PostgreSQL"))return new Reader(){
                private String chunk="";private int at,position=1;private boolean ended;
                @Override public int read(char[] buffer,int offset,int length)throws IOException {
                    Objects.checkFromIndexSize(offset,length,buffer.length);if(length==0)return 0;
                    if(at==chunk.length()){
                        if(ended)return -1;
                        try(var statement=query(connection,"SELECT SUBSTRING(payload,?,?) FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",position,CHUNK,owner,type,id)){
                            statement.setQueryTimeout(30);
                            try(var rows=statement.executeQuery()){if(!rows.next())throw new IOException("Missing archive content");chunk=rows.getString(1);}
                        }catch(SQLException failure){throw new IOException("Archive content read failed",failure);}
                        at=0;if(chunk.isEmpty()){ended=true;return -1;}position+=chunk.codePointCount(0,chunk.length());
                    }
                    int count=Math.min(length,chunk.length()-at);chunk.getChars(at,at+count,buffer,offset);at+=count;return count;
                }
                @Override public void close(){chunk="";at=0;ended=true;}
            };
            var statement=query(connection,"SELECT payload FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",owner,type,id);
            try{
                var rows=statement.executeQuery();if(!rows.next()){rows.close();throw new IOException("Missing archive content");}
                return new FilterReader(rows.getCharacterStream(1)){
                    @Override public void close()throws IOException {try{super.close();}finally{try{rows.close();statement.close();}catch(SQLException failure){throw new IOException(failure);}}}
                };
            }catch(Throwable failure){statement.close();throw failure;}
        }catch(SQLException failure){throw new IOException("Archive content read failed",failure);}
    }
}
