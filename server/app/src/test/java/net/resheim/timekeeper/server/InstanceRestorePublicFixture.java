package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** Isolated healthy source and an uninitialized destination for real public API acceptance. */
public final class InstanceRestorePublicFixture {
    private static void privateText(Path file,String text) throws Exception {Files.writeString(file,text);Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));}
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);
        try(var source=InstanceDatabaseImageTest.database();var configuration=InstanceConfiguration.capture(InstanceConfigurationTest.base(),Map.of())) {
            InstanceAuthenticationPreflightTest.seed(source);
            for(String table:List.of("tk_relation","tk_external_reference","tk_active","tk_resource","tk_receipt","tk_change"))execute(source,"DELETE FROM "+table);
            UUID client=UUID.fromString("11111111-1111-4111-8111-111111111111");
            execute(source,"INSERT INTO tk_resource VALUES('a','CLIENT',?,17,?)",client,object().put("id",client.toString()).put("ownerId","a").put("version",17).put("name","Restored client æ").put("color","#123456").toString());
            var day=LocalDate.parse("2026-10-27");UUID note=DayNotes.id(day);
            execute(source,"INSERT INTO tk_resource VALUES('a','DAY_LABEL',?,3,?)",note,object().put("id",note.toString()).put("ownerId","a").put("version",3).put("date",day.toString()).put("text","Restored label").toString());
            execute(source,"INSERT INTO tk_resource VALUES('a','DAY_NOTE',?,5,?)",note,object().put("id",note.toString()).put("ownerId","a").put("version",5).put("date",day.toString()).put("text","").put("labelIndependent",true).put("body","# Restored journal\n").put("markdown","# Restored journal\n").put("modifiedAt","2026-10-27T12:00:00Z").toString());
            UUID week=DayNotes.id(LocalDate.parse("2026-10-26"));execute(source,"INSERT INTO tk_resource VALUES('a','WEEK_NOTE',?,4,?)",week,object().put("id",week.toString()).put("ownerId","a").put("version",4).put("week","2026-W44").put("body","# Restored week\n").put("modifiedAt","2026-10-27T12:00:00Z").toString());
            execute(source,"INSERT INTO tk_api_token VALUES(?,?,'a','Restored public API token','READ_WRITE',NULL,FALSE,'MANAGED',?,NULL,11,NULL)",hash("tkp_disposable_public_restore_acceptance_token"),UUID.fromString("22222222-2222-4222-8222-222222222222"),"2026-10-27T12:00:00Z");
            byte[] bytes="Restored attachment bytes æ\n".getBytes(StandardCharsets.UTF_8);String filename="restored.txt";UUID asset=UUID.nameUUIDFromBytes(("a\0journal-asset\0"+filename).getBytes(StandardCharsets.UTF_8));
            execute(source,"INSERT INTO tk_resource VALUES('a','JOURNAL_ASSET',?,7,?)",asset,object().put("id",asset.toString()).put("ownerId","a").put("version",7).put("filename",filename).put("mediaType","text/plain").put("data",Base64.getEncoder().encodeToString(bytes)).put("byteLength",bytes.length).put("modifiedAt","2026-10-27T12:00:00Z").put("deleted",false).toString());source.commit();
            Path encrypted=InstanceBackupArchive.create(source,configuration,object().put("serverVersion","2.0.0").put("commit","0".repeat(40)).put("databaseType","h2").put("packageId","timekeeper").put("publicOrigin","https://source.example"),"Public fixture archive passphrase".toCharArray(),root);
            Files.move(encrypted,root.resolve("source.tkbackup"));
        }
        String code=AccountPasswords.secret();privateText(root.resolve("setup-code"),code);
        privateText(root.resolve("setup.json"),object().put("sha256",hash(code)).put("purpose","SETUP").put("expiresAt","2030-01-01T00:00:00Z").toString());
        privateText(root.resolve("database-secret"),"synthetic public target database password\n");
        var values=new Properties();values.setProperty("quarkus.datasource.jdbc.url","jdbc:h2:"+root.resolve("destination"));values.setProperty("quarkus.datasource.username","sa");values.setProperty("timekeeper.auth.setup-file",root.resolve("setup.json").toString());values.setProperty("timekeeper.auth.tokens-file","NONE");values.setProperty("timekeeper.package-id","dagsverk");values.setProperty("timekeeper.instance-backup.storage",root.resolve("backups").toString());values.setProperty("timekeeper.instance-restore.storage",root.resolve("restore").toString());
        Path settings=root.resolve("destination.properties");try(var output=Files.newOutputStream(settings)){values.store(output,"Isolated public API restore destination");}Files.setPosixFilePermissions(settings,PosixFilePermissions.fromString("rw-------"));
    }
}
