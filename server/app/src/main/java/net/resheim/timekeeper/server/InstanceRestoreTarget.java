package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.sql.*;
import java.util.*;
import net.resheim.eclipse.timekeeper.domain.DefaultLabels;

/** Advisory, consistent database inspection. Activation must recheck under its exclusive fence. */
final class InstanceRestoreTarget {
    private InstanceRestoreTarget() { }
    enum Blocker { TARGET_OTHER_ACCOUNTS, TARGET_BUSINESS_DATA, TARGET_API_TOKENS, TARGET_BUSINESS_HISTORY }
    record Check(Set<Blocker> blockers) {
        Check {var copy=EnumSet.noneOf(Blocker.class);copy.addAll(blockers);blockers=Collections.unmodifiableSet(copy);}
        boolean databaseEligible(){return blockers.isEmpty();}
    }
    static Check inspect(Connection connection,String bootstrapOwner) throws SQLException {
        if(connection.getAutoCommit()||connection.getTransactionIsolation()!=Connection.TRANSACTION_SERIALIZABLE||bootstrapOwner==null||bootstrapOwner.isBlank())throw new SQLException("Restore target inspection requires a consistent authorized transaction");
        ServerSchema.validate(connection);InstanceDatabaseImage.verifyCoverage(connection);
        var blockers=EnumSet.noneOf(Blocker.class);
        if(count(connection,"SELECT COUNT(*) FROM tk_owner")!=1||count(connection,"SELECT COUNT(*) FROM tk_account")!=1
            ||count(connection,"SELECT COUNT(*) FROM tk_account WHERE owner_id=? AND administrator=TRUE AND state='ENABLED'",bootstrapOwner)!=1)blockers.add(Blocker.TARGET_OTHER_ACCOUNTS);
        var defaults=new HashMap<String,String>();for(var label:DefaultLabels.definitions())defaults.put(label.name(),label.hexColor());
        var defaultIds=new HashSet<String>();var names=new HashSet<String>();
        long resources=count(connection,"SELECT COUNT(*) FROM tk_resource");
        if(resources!=0&&resources!=defaults.size())blockers.add(Blocker.TARGET_BUSINESS_DATA);
        // Bound payload reads even if this is accidentally invoked on a large,
        // populated installation containing multi-megabyte attachments.
        try(var statement=query(connection,"SELECT owner_id,entity_type,id,version,payload FROM tk_resource WHERE owner_id=? AND entity_type='LABEL' AND CHAR_LENGTH(payload)<=2048 FETCH FIRST 9 ROWS ONLY",bootstrapOwner);var rows=statement.executeQuery()) {
            while(rows.next()) {
                if(!bootstrapOwner.equals(rows.getString(1))||!rows.getString(2).equals("LABEL")||rows.getLong(4)!=0){blockers.add(Blocker.TARGET_BUSINESS_DATA);continue;}
                var value=parse(rows.getString(5));var fields=new HashSet<String>();value.fieldNames().forEachRemaining(fields::add);
                String name=value.path("name").asText();
                if(!fields.equals(Set.of("id","ownerId","version","name","color"))||!value.path("id").asText().equals(rows.getString(3))
                    ||!bootstrapOwner.equals(value.path("ownerId").asText())||!value.path("version").isIntegralNumber()||value.path("version").asLong()!=0
                    ||!Objects.equals(defaults.get(name),value.path("color").asText())||!names.add(name))blockers.add(Blocker.TARGET_BUSINESS_DATA);
                else defaultIds.add(rows.getString(3));
            }
        }
        // A partial set can represent a user's deletion; preserve that decision.
        if(defaultIds.size()!=resources||!names.isEmpty()&&!names.equals(defaults.keySet()))blockers.add(Blocker.TARGET_BUSINESS_DATA);
        for(String table:List.of("tk_relation","tk_external_reference","tk_active"))if(count(connection,"SELECT COUNT(*) FROM "+table)!=0)blockers.add(Blocker.TARGET_BUSINESS_DATA);
        if(count(connection,"SELECT COUNT(*) FROM tk_api_token")!=0)blockers.add(Blocker.TARGET_API_TOKENS);
        if(count(connection,"SELECT COUNT(*) FROM tk_receipt")!=0)blockers.add(Blocker.TARGET_BUSINESS_HISTORY);
        var allowed=new ArrayList<Object>();allowed.add(bootstrapOwner);allowed.add(bootstrapOwner);allowed.addAll(defaultIds);
        String labelIds=defaultIds.isEmpty()?"''":String.join(",",Collections.nCopies(defaultIds.size(),"?"));
        if(count(connection,"SELECT COUNT(*) FROM tk_change WHERE NOT (owner_id=? AND ((entity_type='PROFILE' AND change_type='UPDATED' AND entity_id=?) OR (entity_type='LABEL' AND change_type='CREATED' AND entity_id IN ("+labelIds+"))))",allowed.toArray())!=0)blockers.add(Blocker.TARGET_BUSINESS_HISTORY);
        return new Check(blockers);
    }
    private static long count(Connection c,String sql,Object... args) throws SQLException {
        try(var statement=query(c,sql,args);var rows=statement.executeQuery()){if(!rows.next())throw new SQLException("Missing count");return rows.getLong(1);}
    }
}
