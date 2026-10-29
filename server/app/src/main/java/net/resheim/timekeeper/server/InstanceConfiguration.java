package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/** Private resolved application configuration and its explicitly inventoried inputs. */
final class InstanceConfiguration {
    private InstanceConfiguration() { }
    static final String FORMAT="timekeeper-instance-configuration-v1";
    static final int MAX_FILE_BYTES=20_000_000,MAX_TOTAL_BYTES=64_000_000,MAX_FILES=64;
    private static final String DEFAULT_TRUST="timekeeper.instance-source.default-jsse-trust-store-file";
    private static final Map<String,String> DEFAULT_POSTGRES=Map.of(
        "timekeeper.instance-source.default-postgresql-client-certificate-file","postgresql.crt",
        "timekeeper.instance-source.default-postgresql-client-key-file","postgresql.pk8",
        "timekeeper.instance-source.default-postgresql-root-certificate-file","root.crt");
    private static final Set<String> REQUIRED=Set.of("quarkus.datasource.jdbc.url","quarkus.datasource.db-kind","quarkus.datasource.username","timekeeper.auth.tokens-file","timekeeper.auth.setup-file");
    private static final Set<String> SINGLE=Set.of("timekeeper.auth.tokens-file","timekeeper.auth.setup-file","timekeeper.server.db.password.file","timekeeper.smtp.password.file",
        "quarkus.http.ssl.certificate.key-store-file","quarkus.http.ssl.certificate.trust-store-file","javax.net.ssl.keyStore","javax.net.ssl.trustStore",
        DEFAULT_TRUST,"timekeeper.instance-source.default-postgresql-client-certificate-file",
        "timekeeper.instance-source.default-postgresql-client-key-file","timekeeper.instance-source.default-postgresql-root-certificate-file");
    private static final Set<String> MULTIPLE=Set.of("timekeeper.instance-restored.files","quarkus.http.ssl.certificate.files","quarkus.http.ssl.certificate.key-files","quarkus.config.locations");
    /** Internal secret-bearing object; never return this as a JSON API reply. */
    record Snapshot(ObjectNode document,Map<String,byte[]> files) implements AutoCloseable {
        Snapshot {files=Map.copyOf(files);}
        @Override public String toString(){return "Private instance configuration";}
        boolean matches(Snapshot other){return document.equals(other.document)&&files.keySet().equals(other.files.keySet())&&files.entrySet().stream().allMatch(entry->Arrays.equals(entry.getValue(),other.files.get(entry.getKey())));}
        @Override public void close(){files.values().forEach(bytes->Arrays.fill(bytes,(byte)0));document.removeAll();}
    }

    static Snapshot captureRuntime() throws IOException {return capture(ConfigProvider.getConfig());}
    static Snapshot capture(Config config) throws IOException {
        var properties=new TreeMap<String,String>();var lists=new HashMap<String,List<String>>();
        try {
            // Environment variables referenced by application.properties also appear
            // as synthetic dotted names (TIMEKEEPER_SERVER_TOKENS_FILE becomes
            // timekeeper.server.tokens.file). Capture their resolved canonical
            // property once; do not reinterpret an alias as a second file input.
            var aliases=environmentAliases(config);
            for(String name:config.getPropertyNames())if(owned(name)&&!(aliases.contains(name)&&config.getConfigValue(name).getSourceName().contains("EnvConfigSource"))) {
                // Empty JSSE stores and PostgreSQL client certificates can be
                // intentional. Preserve them rather than applying defaults.
                if(name.startsWith("javax.net.ssl.")||jdbcMaterial(name)) {
                    String value=config.getConfigValue(name).getValue();if(value!=null)properties.put(name,value);
                }else config.getOptionalValue(name,String.class).ifPresent(value->properties.put(name,value));
            }
            // Both application inputs intentionally treat an absent/blank path
            // as disabled. MicroProfile removes empty converted values.
            for(String name:List.of("timekeeper.auth.tokens-file","timekeeper.auth.setup-file"))if(!active(properties.get(name)))properties.put(name,"NONE");
            defaultMaterial(config,properties);
            for(var entry:properties.entrySet())if(kind(entry.getKey())>=2&&active(entry.getKey(),entry.getValue())) {
                String[] paths=config.getValue(entry.getKey(),String[].class);lists.put(entry.getKey(),List.of(paths));
            }
            return capture(properties,lists);
        }catch(IOException|RuntimeException failure){throw invalid();}
    }
    /** Inventory conventional runtime stores as material, not target TLS overrides. */
    private static void defaultMaterial(Config config,Map<String,String> properties) throws IOException {
        properties.remove(DEFAULT_TRUST);DEFAULT_POSTGRES.keySet().forEach(properties::remove);
        var javaHome=config.getOptionalValue("java.home",String.class);
        if(javaHome.isPresent())for(String name:List.of("jssecacerts","cacerts")) {
            Path path=Path.of(javaHome.get(),"lib","security",name);
            if(regular(path)){properties.put(DEFAULT_TRUST,path.toString());break;}
        }
        if("postgresql".equals(properties.get("quarkus.datasource.db-kind"))) {
            boolean windows=config.getOptionalValue("os.name",String.class).orElse("").toLowerCase(Locale.ROOT).contains("windows");
            var home=config.getOptionalValue(windows?"APPDATA":"user.home",String.class);
            if(home.isPresent())for(var entry:DEFAULT_POSTGRES.entrySet()) {
                Path path=Path.of(home.get(),windows?"postgresql":".postgresql",entry.getValue());
                if(regular(path))properties.put(entry.getKey(),path.toString());
            }
        }
    }
    private static boolean regular(Path path) throws IOException {
        try{return Files.readAttributes(path,BasicFileAttributes.class).isRegularFile();}
        catch(NoSuchFileException missing){return false;}
    }
    private static Set<String> environmentAliases(Config config) {
        var aliases=new HashSet<String>();var declared=new HashSet<String>();
        var expression=java.util.regex.Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)(?=[:}])");
        for(var source:config.getConfigSources()) {
            if(source.getName().contains("EnvConfigSource"))continue;
            for(String name:source.getPropertyNames()) {
                declared.add(name);String raw=source.getValue(name);if(raw==null)continue;
                var matcher=expression.matcher(raw);while(matcher.find())aliases.add(matcher.group(1).toLowerCase(Locale.ROOT).replace('_','.'));
            }
        }
        aliases.removeAll(declared);return aliases;
    }
    static Snapshot capture(Map<String,String> properties,Map<String,List<String>> lists) throws IOException {
        var document=object().put("format",FORMAT);var values=document.putObject("properties");var inputs=document.putObject("files");
        var contents=new LinkedHashMap<String,byte[]>();long total=0;
        try {
            for(var entry:new TreeMap<>(properties).entrySet()) {
                String name=entry.getKey(),value=entry.getValue();if(!owned(name)||value==null||name.length()>512||value.length()>1_000_000)throw invalid();
                values.put(name,value);if(!active(name,value))continue;
                int kind=kind(name);
                if(kind==0) {
                    // A new input path requires an explicit decision. Known output
                    // directories and database files are represented by metadata
                    // and the logical image, never recursively copied as inputs.
                    if(looksLikeInput(name)&&!output(name))throw invalid();
                    continue;
                }
                List<String> paths=kind==1?List.of(value):lists.get(name);if(paths==null||paths.isEmpty())throw invalid();
                if(kind==3) {
                    var expanded=new ArrayList<String>();
                    for(String directory:paths)try(var children=Files.list(Path.of(directory))) {
                        for(Path file:children.sorted().toList()){if(!Files.isRegularFile(file))throw invalid();expanded.add(file.toString());}
                    }
                    paths=expanded;if(paths.isEmpty())throw invalid();
                }
                var references=inputs.putArray(name);
                for(String path:paths) {
                    if(contents.size()>=MAX_FILES||path==null||path.isBlank())throw invalid();
                    byte[] bytes=read(path,name.startsWith("quarkus.")&&!jdbcMaterial(name));total+=bytes.length;if(total>MAX_TOTAL_BYTES)throw invalid();
                    String suffix=path.replaceFirst("^.*[./\\\\]","").toLowerCase(Locale.ROOT);if(!Set.of("p12","pfx","jks","pem","crt","key","json","properties","der").contains(suffix))suffix="bin";
                    String archiveName=String.format(Locale.ROOT,"configuration/%04d.%s",contents.size(),suffix);
                    contents.put(archiveName,bytes);references.add(archiveName);
                }
            }
            validate(document,contents.keySet());return new Snapshot(document,contents);
        }catch(IOException|RuntimeException failure){contents.values().forEach(bytes->Arrays.fill(bytes,(byte)0));document.removeAll();throw invalid();}
    }

    static void validate(JsonNode document,Set<String> archiveFiles) throws IOException {
        try {
            if(!document.isObject()||!fields(document).equals(Set.of("format","properties","files"))||!FORMAT.equals(document.path("format").asText())
                ||!document.path("properties").isObject()||!document.path("files").isObject()||archiveFiles.size()>MAX_FILES)throw invalid();
            if(!fields(document.path("properties")).containsAll(REQUIRED))throw invalid();
            var used=new HashSet<String>();
            for(var properties=document.path("properties").fields();properties.hasNext();) {
                var entry=properties.next();String name=entry.getKey();var value=entry.getValue();
                if(!owned(name)||name.length()>512||!value.isTextual()||value.asText().length()>1_000_000)throw invalid();
                int kind=kind(name);boolean input=active(name,value.asText())&&kind!=0;var files=document.path("files").get(name);
                if(!input) {
                    if(files!=null||active(name,value.asText())&&kind==0&&looksLikeInput(name)&&!output(name))throw invalid();
                    continue;
                }
                if(files==null||!files.isArray()||files.isEmpty()||kind==1&&files.size()!=1)throw invalid();
                for(var file:files)if(!file.isTextual()||!validFileName(file.asText())||!used.add(file.asText()))throw invalid();
            }
            for(var files=document.path("files").fields();files.hasNext();)if(!document.path("properties").has(files.next().getKey()))throw invalid();
            if(!used.equals(archiveFiles))throw invalid();
        }catch(RuntimeException failure){throw invalid();}
    }
    private static boolean owned(String name){return name!=null&&(name.startsWith("timekeeper.")||name.startsWith("quarkus.")||name.startsWith("smallrye.config.")||name.startsWith("javax.net.ssl."))&&!name.chars().anyMatch(Character::isISOControl);}
    private static boolean active(String value){return value!=null&&!value.isBlank()&&!value.equals("NONE");}
    private static boolean active(String name,String value){return active(value)||jdbcMaterial(name)&&value!=null&&!value.isBlank();}
    private static boolean jdbcMaterial(String name) {
        return name.matches("quarkus\\.datasource\\.(?:\"[^\"]+\"\\.|[^.]+\\.)?jdbc\\.additional-jdbc-properties\\.(?:sslcert|sslkey|sslrootcert|\"sslcert\"|\"sslkey\"|\"sslrootcert\")");
    }
    private static int kind(String name) {
        if(SINGLE.contains(name)||jdbcMaterial(name)||name.matches("smallrye\\.config\\.source\\.keystore\\..+\\.path")||name.matches("quarkus\\.(?:http|management)\\.ssl\\.certificate\\.(?:key-store|trust-store)-file")
            ||name.matches("quarkus\\.tls\\.(?:.+\\.)?(?:key-store|trust-store)\\.(?:p12|jks)\\.path")
            ||name.matches("quarkus\\.tls\\.(?:.+\\.)?key-store\\.pem\\.(?:.+\\.)?(?:key|cert)"))return 1;
        if(MULTIPLE.contains(name)||name.matches("quarkus\\.(?:http|management)\\.ssl\\.certificate\\.(?:files|key-files|trust-store-files)")
            ||name.matches("quarkus\\.tls\\.(?:.+\\.)?(?:key-store|trust-store)\\.pem\\.(?:key-paths|cert-paths|certs)"))return 2;
        if(name.matches("quarkus\\.tls\\.(?:.+\\.)?trust-store\\.pem\\.cert-dirs"))return 3;
        return 0;
    }
    static boolean validFileName(String name){return name.matches("configuration/[0-9]{4}\\.(?:bin|p12|pfx|jks|pem|crt|key|json|properties|der)");}
    static boolean coversDeclaredProperty(String name){return owned(name)&&(!looksLikeInput(name)||kind(name)!=0||output(name));}
    private static boolean looksLikeInput(String name){return name.startsWith("javax.net.ssl.")&&name.toLowerCase(Locale.ROOT).endsWith("store")||(name.startsWith("timekeeper.")||name.startsWith("smallrye.config.")||name.startsWith("quarkus.tls.")||name.startsWith("quarkus.http.ssl.")||name.startsWith("quarkus.management.ssl.")||name.startsWith("quarkus.datasource.")||name.startsWith("javax.net.ssl."))&&name.matches(".*(?:[.-](?:file|files|path|paths|dir|dirs|directory|directories|location|locations))$");}
    private static boolean output(String name){return name.startsWith("quarkus.log.")||name.startsWith("quarkus.http.access-log.")||name.equals("timekeeper.instance-backup.storage")||name.equals("timekeeper.maintenance.file");}
    private static Set<String> fields(JsonNode object){var result=new HashSet<String>();object.fieldNames().forEachRemaining(result::add);return result;}
    private static byte[] read(String value,boolean tls) throws IOException {
        if(tls&&!value.startsWith("classpath:")&&!Path.of(value).isAbsolute()&&InstanceConfiguration.class.getClassLoader().getResource(value)!=null)value="classpath:"+value;
        if(value.startsWith("classpath:")) {
            String name=value.substring(10);if(name.startsWith("/"))name=name.substring(1);if(name.isBlank()||Arrays.asList(name.split("/")).contains(".."))throw invalid();
            try(var input=InstanceConfiguration.class.getClassLoader().getResourceAsStream(name)) {
                if(input==null)throw invalid();byte[] bytes=input.readNBytes(MAX_FILE_BYTES+1);if(bytes.length>MAX_FILE_BYTES)throw invalid();return bytes;
            }
        }
        Path path=value.startsWith("file:")?Path.of(java.net.URI.create(value)):Path.of(value);var before=Files.readAttributes(path,BasicFileAttributes.class);
        if(!before.isRegularFile()||before.size()>MAX_FILE_BYTES)throw invalid();
        byte[] bytes;
        try(var input=Files.newInputStream(path)){bytes=input.readNBytes(MAX_FILE_BYTES+1);}
        var after=Files.readAttributes(path,BasicFileAttributes.class);
        if(bytes.length>MAX_FILE_BYTES||bytes.length!=before.size()||before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||!Objects.equals(before.fileKey(),after.fileKey()))throw invalid();
        return bytes;
    }
    private static IOException invalid(){return new IOException("INVALID_INSTANCE_CONFIGURATION");}
}
