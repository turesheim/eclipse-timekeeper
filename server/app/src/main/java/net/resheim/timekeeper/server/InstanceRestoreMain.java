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
            if(args.length!=1)throw new IllegalArgumentException();Path root=Path.of(args[0]).toAbsolutePath().normalize();
            var config=ConfigUtils.configBuilder(LaunchMode.NORMAL).build();String configured=config.getOptionalValue("timekeeper.instance-restore.storage",String.class).orElse("NONE");
            if(!configured.equals("NONE")&&!Path.of(configured).toAbsolutePath().normalize().equals(root))throw new IllegalArgumentException();
            String url=config.getValue("quarkus.datasource.jdbc.url",String.class);DatabaseConfiguration.validate(url);
            if(url.startsWith("jdbc:h2:mem:"))throw new IllegalArgumentException();
            var properties=new Properties();properties.setProperty("user",config.getOptionalValue("quarkus.datasource.username",String.class).orElse("sa"));properties.setProperty("password",config.getOptionalValue("quarkus.datasource.password",String.class).orElse(""));
            if(DatabaseConfiguration.postgres(url))properties.setProperty("connectTimeout","5");else url=url.replaceAll("(?i);IFEXISTS=(?:true|false)(?=;|$)","")+";IFEXISTS=TRUE";
            long limit=Long.parseLong(config.getOptionalValue("timekeeper.instance-backup.maximum-expanded-bytes",String.class).orElse("17179869184"));
            var intent=new InstanceRestoreIntent(root,Math.addExact(limit,128_000_000L));
            try(var connection=DriverManager.getConnection(url,properties)) {
                ServerSchema.validate(connection);var material=InstanceRestoreRecovery.resume(connection,intent,Instant.now(),InstanceRestoreMain::preflightConfiguration);
                // The path is private configuration, not a command to evaluate.
                output.println("INSTANCE_RESTORE_COMPLETE");output.println(material.configuration().toUri().toASCIIString().replace(",","\\,"));
            }finally{properties.clear();}
        }catch(Throwable failure){errors.println("Instance restore could not be completed. Keep the server stopped and follow the instance recovery instructions.");System.exit(1);}
    }
    private static void preflightConfiguration(InstanceRestoreMaterial.Installed material) throws java.io.IOException {
        String previous=System.getProperty("quarkus.config.locations");
        try {
            var initial=ConfigUtils.configBuilder(LaunchMode.NORMAL).build();String locations=initial.getOptionalValue("quarkus.config.locations",String.class).orElse("");
            String added=material.configuration().toUri().toASCIIString().replace(",","\\,");System.setProperty("quarkus.config.locations",locations.isBlank()?added:locations+","+added);
            var effective=ConfigUtils.configBuilder(LaunchMode.NORMAL).build();var properties=new Properties();try(var input=java.nio.file.Files.newInputStream(material.configuration())){properties.load(input);}
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
