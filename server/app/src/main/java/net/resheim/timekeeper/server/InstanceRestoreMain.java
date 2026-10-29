package net.resheim.timekeeper.server;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Properties;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.configuration.ConfigUtils;

/** Manual offline entry point; never starts the HTTP application or prints private failures. */
public final class InstanceRestoreMain {
    public static void main(String[] args) {
        var output=System.out;var errors=System.err;
        // Configuration sources and JDBC providers must not print credentials or
        // private archive diagnostics; only our fixed result reaches the caller.
        System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        try {
            if(args.length==1&&args[0].equals("--preboot")) {
                var incoming=new Properties();byte[] bytes=System.in.readNBytes(8_388_609);if(bytes.length>8_388_608)throw new IllegalArgumentException();
                try(var input=new java.io.ByteArrayInputStream(bytes)){incoming.load(input);}finally{java.util.Arrays.fill(bytes,(byte)0);}
                for(String name:incoming.stringPropertyNames())if(!java.util.Set.of("java.class.path","jdk.module.path","sun.java.command","java.home").contains(name))System.setProperty(name,incoming.getProperty(name));incoming.clear();
                var result=preboot();result.setProperty("status","READY");result.store(output,"Private startup result");result.clear();return;
            }
            boolean cancel=args.length==3&&args[0].equals("--cancel");
            if(!cancel&&args.length!=1)throw new IllegalArgumentException();Path root=Path.of(args[cancel?1:0]).toAbsolutePath().normalize();
            var config=configuration();String configured=config.getOptionalValue("timekeeper.instance-restore.storage",String.class).orElse("NONE");
            if(!configured.equals("NONE")&&!Path.of(configured).toAbsolutePath().normalize().equals(root))throw new IllegalArgumentException();
            String url=config.getValue("quarkus.datasource.jdbc.url",String.class);DatabaseConfiguration.validate(url);
            if(url.startsWith("jdbc:h2:mem:"))throw new IllegalArgumentException();
            var properties=new Properties();properties.setProperty("user",config.getOptionalValue("quarkus.datasource.username",String.class).orElse("sa"));properties.setProperty("password",config.getOptionalValue("quarkus.datasource.password",String.class).orElse(""));
            if(DatabaseConfiguration.postgres(url))properties.setProperty("connectTimeout","5");else url=url.replaceAll("(?i);IFEXISTS=(?:true|false)(?=;|$)","")+";IFEXISTS=TRUE";
            long limit=Long.parseLong(config.getOptionalValue("timekeeper.instance-backup.maximum-expanded-bytes",String.class).orElse("17179869184"));
            var intent=new InstanceRestoreIntent(root,Math.addExact(limit,128_000_000L));
            try(var connection=DriverManager.getConnection(url,properties)) {
                ServerSchema.validate(connection);
                if(cancel) {
                    byte[] bytes=System.in.readNBytes(8193);if(bytes.length>8192)throw new IllegalArgumentException();
                    com.fasterxml.jackson.databind.JsonNode credentials;
                    try{credentials=JsonValues.MAPPER.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);}finally{java.util.Arrays.fill(bytes,(byte)0);}
                    if(credentials==null||!credentials.isObject()||credentials.size()!=2||!credentials.path("username").isTextual()||!credentials.path("currentPassword").isTextual()||credentials.path("currentPassword").asText().length()>1024)throw new IllegalArgumentException();
                    try(var cancellation=intent.cancellation(connection,java.util.UUID.fromString(args[2]));var exclusive=InstanceRestoreFence.exclusive(connection)) {
                        cancellation.cancelOffline(connection,exclusive,AccountStore.username(credentials.path("username").asText()),credentials.path("currentPassword").asText(),Instant.now());cancellation.cleanup(connection);
                    }finally{((com.fasterxml.jackson.databind.node.ObjectNode)credentials).removeAll();}
                    output.println("INSTANCE_RESTORE_CANCELLED");return;
                }
                var material=InstanceRestoreRecovery.resume(connection,intent,Instant.now(),InstanceRestoreMain::preflightConfiguration);
                // The path is private configuration, not a command to evaluate.
                output.println("INSTANCE_RESTORE_COMPLETE");output.println(material.configuration().toUri().toASCIIString().replace(",","\\,"));
            }finally{properties.clear();}
        }catch(Throwable failure){errors.println("Instance restore could not be completed. Keep the server stopped and follow the instance recovery instructions.");System.exit(1);}
    }
    /** Read-only for routine startup; exclusive replacement only for committed pending consent. */
    private static Properties preboot() throws Exception {
        var config=configuration();var updates=new Properties();
        for(var entry:resolvedSecrets().entrySet())if(System.getProperty(entry.getKey())==null)updates.setProperty(entry.getKey(),entry.getValue());
        String url=config.getValue("quarkus.datasource.jdbc.url",String.class);DatabaseConfiguration.validate(url);
        String storage=config.getOptionalValue("timekeeper.instance-restore.storage",String.class).orElse("NONE");Path root=storage.equals("NONE")?null:Path.of(storage).toAbsolutePath().normalize();
        boolean recovery=root!=null&&(java.nio.file.Files.exists(root.resolve("prepared.json"),java.nio.file.LinkOption.NOFOLLOW_LINKS)||java.nio.file.Files.exists(root.resolve("active.json"),java.nio.file.LinkOption.NOFOLLOW_LINKS));
        if(url.startsWith("jdbc:h2:mem:")){if(recovery)throw new java.io.IOException("RESTORE_REQUIRES_PERSISTENT_TARGET");return updates;}
        var properties=new Properties();properties.setProperty("user",config.getOptionalValue("quarkus.datasource.username",String.class).orElse("sa"));properties.setProperty("password",config.getOptionalValue("quarkus.datasource.password",String.class).orElse(""));
        boolean postgres=DatabaseConfiguration.postgres(url);if(postgres)properties.setProperty("connectTimeout","5");else url=url.replaceAll("(?i);IFEXISTS=(?:true|false)(?=;|$)","")+";IFEXISTS=TRUE";
        try(var connection=DriverManager.getConnection(url,properties)) {
            com.fasterxml.jackson.databind.JsonNode control;
            try(var statement=connection.createStatement()) {
                statement.setQueryTimeout(5);
                try(var rows=statement.executeQuery("SELECT payload FROM tk_instance WHERE kind='CONTROL'")) {
                    if(!rows.next()){if(recovery)throw new java.io.IOException("INVALID_INSTANCE_CONTROL");return updates;}
                    control=JsonValues.MAPPER.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(rows.getString(1));
                    if(!control.isObject()||rows.next())throw new java.io.IOException("INVALID_INSTANCE_CONTROL");
                }
            }catch(java.sql.SQLException missing) {if(!recovery&&java.util.Set.of("42P01","42S02").contains(missing.getSQLState()))return updates;throw missing;}
            InstanceRestoreMaterial.Installed material=null;
            if(control.path("restorePending").asBoolean()) {
                if(root==null||!java.nio.file.Files.isDirectory(root,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new java.io.IOException("RESTORE_STORAGE_REQUIRED");
                long limit=Long.parseLong(config.getOptionalValue("timekeeper.instance-backup.maximum-expanded-bytes",String.class).orElse("17179869184"));
                material=InstanceRestoreRecovery.resume(connection,new InstanceRestoreIntent(root,Math.addExact(limit,128_000_000L)),Instant.now(),InstanceRestoreMain::preflightConfiguration);
            }else if(control.path("restore").path("phase").asText().equals("COMPLETE")) {
                if(root==null)throw new java.io.IOException("RESTORE_STORAGE_REQUIRED");material=InstanceRestoreMaterial.active(root);
                if(!material.generation().toString().equals(control.path("restoreGeneration").asText())||!material.operation().toString().equals(control.path("restore").path("operationId").asText())||!material.digest().equals(control.path("restore").path("materialSha256").asText())||!material.intentDigest().equals(control.path("restoreIntent").path("sha256").asText()))throw new java.io.IOException("INVALID_INSTANCE_RESTORE_MATERIAL");
                if(java.nio.file.Files.exists(root.resolve(material.generation().toString()),java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    try{new InstanceRestoreIntent(root,128_000_000L).cleanupCompleted(connection,material);}catch(java.io.IOException busy){if(!"INSTANCE_RESTORE_BUSY".equals(busy.getMessage()))throw busy;}
                }
            }
            if(material!=null) {
                String location=material.configuration().toUri().toASCIIString().replace(",","\\,");String original=config.getOptionalValue("quarkus.config.locations",String.class).orElse("");
                updates.setProperty("quarkus.config.locations",original.isBlank()?location:original+","+location);
            }
            return updates;
        }catch(java.sql.SQLException missing){if(!postgres&&!recovery&&java.util.Set.of(90013,90146).contains(missing.getErrorCode()))return updates;throw missing;}
        finally{properties.clear();}
    }
    private static java.util.Map<String,String> resolvedSecrets() throws java.io.IOException {
        var resolved=new java.util.HashMap<String,String>();
        for(String name:java.util.List.of("TIMEKEEPER_SERVER_DB_PASSWORD","TIMEKEEPER_SMTP_PASSWORD")) {
            String path=System.getenv(name+"_FILE");if(path==null||path.isBlank())continue;
            if(java.nio.file.Files.size(Path.of(path))>1_048_576)throw new java.io.IOException("INVALID_SECRET_FILE");
            String value=java.nio.file.Files.readString(Path.of(path));while(value.endsWith("\n"))value=value.substring(0,value.length()-1);resolved.put(name,value);
        }
        return resolved;
    }
    private static io.smallrye.config.SmallRyeConfig configuration() throws java.io.IOException {
        var resolved=resolvedSecrets();
        // Match the entrypoint's file-backed environment variables, while
        // retaining direct canonical properties and higher-priority files.
        var builder=ConfigUtils.configBuilder(LaunchMode.NORMAL);if(!resolved.isEmpty())builder.withSources(new io.smallrye.config.EnvConfigSource(resolved,310));return builder.build();
    }
    private static void preflightConfiguration(InstanceRestoreMaterial.Installed material) throws java.io.IOException {
        String previous=System.getProperty("quarkus.config.locations");
        try {
            var initial=configuration();String locations=initial.getOptionalValue("quarkus.config.locations",String.class).orElse("");
            String added=material.configuration().toUri().toASCIIString().replace(",","\\,");System.setProperty("quarkus.config.locations",locations.isBlank()?added:locations+","+added);
            var effective=configuration();var properties=new Properties();try(var input=java.nio.file.Files.newInputStream(material.configuration())){properties.load(input);}
            var expected=new io.smallrye.config.SmallRyeConfigBuilder().addDefaultInterceptors().withSources(new io.smallrye.config.PropertiesConfigSource(properties,"recovered configuration",450)).build();
            for(String key:java.util.List.of("timekeeper.auth.tokens-file","timekeeper.auth.setup-file","timekeeper.service-settings.recover","timekeeper.package-id","timekeeper.instance-restore.storage","timekeeper.instance-restore.generation","timekeeper.calendars.automatic")) {
                if(!effective.getValue(key,String.class).equals(expected.getValue(key,String.class)))throw new java.io.IOException("RECOVERED_CONFIGURATION_OVERRIDDEN");
            }
            if(!effective.getOptionalValue("timekeeper.calendars.trusted-origins",String.class).orElse("").equals(expected.getOptionalValue("timekeeper.calendars.trusted-origins",String.class).orElse("")))throw new java.io.IOException("RECOVERED_CONFIGURATION_OVERRIDDEN");
            if(!java.util.Arrays.equals(effective.getValue("timekeeper.instance-restored.files",String[].class),expected.getValue("timekeeper.instance-restored.files",String[].class)))throw new java.io.IOException("RECOVERED_CONFIGURATION_OVERRIDDEN");
            // Prove the completed installation's active and inactive material
            // fits the normal backup inventory before replacing database rows.
            try(var backup=InstanceConfiguration.capture(effective)){ }
        }catch(java.io.IOException failure){throw failure;}catch(RuntimeException failure){throw new java.io.IOException("INVALID_RECOVERED_CONFIGURATION");}
        finally{if(previous==null)System.clearProperty("quarkus.config.locations");else System.setProperty("quarkus.config.locations",previous);}
    }
}
