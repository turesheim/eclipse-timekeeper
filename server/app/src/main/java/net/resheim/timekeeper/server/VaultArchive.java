package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.io.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.zip.*;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Portable logical records plus checksummed bytes; no database indexes or authority. */
final class VaultArchive {
    private static final Set<String> ENTRY_FIELDS=Set.of("id","path","kind","parentId","deleted","deletionBatch","sourceType","sourceId","sourceKey");
    private static final Set<String> SETTINGS_FIELDS=Set.of("journalFolder","eventsFolder","assetsFolder","syncObsidianConfig");
    @FunctionalInterface interface File {void accept(String path,byte[] bytes)throws IOException;}
    static boolean owns(String name){return name!=null&&name.matches("vault/(content|recycle)/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.bin");}
    private static String filename(String type,UUID id){return "vault/"+(type.equals(VaultContent.TYPE)?"content":"recycle")+"/"+id+".bin";}
    private static ObjectNode logical(JsonNode value,Set<String> fields){var result=object();for(String field:fields)if(value.has(field))result.set(field,value.get(field));return result;}
    static void files(Connection connection,String owner,File output)throws SQLException,IOException{
        for(String type:List.of(VaultContent.TYPE,VaultRecycle.TYPE))AccountArchive.records(connection,owner,type,value->{
            final byte[] bytes;try{bytes=Base64.getDecoder().decode(value.path("data").asText());}catch(IllegalArgumentException invalid){throw new IOException("Invalid vault bytes");}
            if(bytes.length!=value.path("byteLength").asLong(-1)||!ArchiveJournalFiles.digest(bytes).equals(value.path("sha256").asText()))throw new IOException("Invalid vault bytes");
            output.accept(filename(type,uuid(value.path("id"))),bytes);
        });
    }
    static void metadata(Connection connection,String owner,JsonGenerator json)throws SQLException,IOException{
        json.writeObjectFieldStart("vault");json.writeNumberField("format",1);var settings=object().put("journalFolder","journal").put("eventsFolder","events").put("assetsFolder","assets").put("syncObsidianConfig",false);
        AccountArchive.records(connection,owner,VaultSettings.TYPE,value->settings.setAll(logical(value,SETTINGS_FIELDS)));json.writeObjectField("settings",settings);
        json.writeArrayFieldStart("entries");AccountArchive.records(connection,owner,VaultIndex.ENTRY,value->json.writeTree(logical(value,ENTRY_FIELDS)));json.writeEndArray();
        json.writeArrayFieldStart("files");files(connection,owner,(path,bytes)->json.writeTree(ArchiveJournalFiles.metadata(path,bytes)));json.writeEndArray();json.writeEndObject();
    }
    static byte[] read(ZipFile zip,JsonNode file)throws IOException{
        String path=file.path("path").asText();long size=file.path("bytes").asLong(-1);
        int maximum=org.eclipse.microprofile.config.ConfigProvider.getConfig().getOptionalValue("timekeeper.notes.max-file-bytes",Integer.class).orElse(16000000);
        if(!owns(path)||size<0||size>maximum||size>=Integer.MAX_VALUE)throw ApiFailure.validation();var entry=zip.getEntry(path);
        if(entry==null||entry.isDirectory()||entry.getSize()!=size)throw ApiFailure.validation();
        try(var stream=zip.getInputStream(entry)){byte[] bytes=stream.readNBytes((int)size+1);if(bytes.length!=size||!ArchiveJournalFiles.digest(bytes).equals(file.path("sha256").asText()))throw ApiFailure.validation();return bytes;}
    }
    static Set<String> verify(ZipFile zip,JsonNode vault)throws IOException{
        if(!vault.isObject()||vault.path("format").asInt()!=1||!names(vault).equals(Set.of("format","settings","entries","files"))||!vault.path("entries").isArray()||!vault.path("files").isArray())throw ApiFailure.validation();
        var paths=new HashSet<String>();
        for(var file:vault.path("files")){if(!names(file).equals(Set.of("path","bytes","sha256"))||!paths.add(file.path("path").asText()))throw ApiFailure.validation();read(zip,file);}
        graph(vault);return paths;
    }
    private static Map<UUID,ObjectNode> graph(JsonNode vault){
        var entries=new LinkedHashMap<UUID,ObjectNode>();var activePaths=new HashSet<String>();var refs=new HashSet<String>();
        if(vault.path("entries").size()>250000||vault.path("files").size()>500000)throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
        for(var value:vault.path("entries")){
            if(!value.isObject()||!ENTRY_FIELDS.containsAll(names(value))||!names(value).containsAll(Set.of("id","path","kind","parentId","deleted"))||!value.path("deleted").isBoolean())throw ApiFailure.validation();
            UUID id=identifier(value.path("id").asText());identifier(value.path("parentId").asText());
            var path=new VaultPath(value.path("path").asText());if(!path.value().equals(value.path("path").asText())||entries.put(id,((ObjectNode)value).deepCopy())!=null)throw ApiFailure.validation();
            final VaultIndex.Kind kind;try{kind=VaultIndex.Kind.valueOf(value.path("kind").asText());}catch(IllegalArgumentException invalid){throw ApiFailure.validation();}
            if(!value.path("deleted").asBoolean()&&!activePaths.add(path.key()))throw ApiFailure.validation();
            if(kind==VaultIndex.Kind.FOLDER){if(value.has("sourceType")||value.has("sourceId")||value.has("sourceKey"))throw ApiFailure.validation();}
            else{
                if(!value.path("sourceType").isTextual()||!value.path("sourceKey").isTextual())throw ApiFailure.validation();UUID source=identifier(value.path("sourceId").asText());String type=value.path("sourceType").asText(),key=value.path("sourceKey").asText();
                UUID expected=switch(kind){
                    case DAY->{if(!type.equals(DayNotes.TYPE))throw ApiFailure.validation();yield DayNotes.id(DayNotes.date(key));}
                    case WEEK->{if(!type.equals(WeekNotes.TYPE))throw ApiFailure.validation();yield WeekNotes.id(WeekNotes.week(key));}
                    case EVENT->{if(!type.equals(EventNotes.TYPE))throw ApiFailure.validation();yield EventNotes.filename(key).id();}
                    case ASSET->{if(!type.equals(JournalAssets.TYPE))throw ApiFailure.validation();JournalAssets.name(key);yield source;}
                    case MARKDOWN,BINARY->{if(!type.equals(VaultContent.TYPE)||!key.equals(source.toString()))throw ApiFailure.validation();yield source;}
                    default->throw ApiFailure.validation();
                };
                if(!source.equals(expected)||!refs.add(type+":"+(type.equals(VaultContent.TYPE)?source:key)))throw ApiFailure.validation();
            }
            if(value.path("deleted").asBoolean())identifier(value.path("deletionBatch").asText());
        }
        if(entries.isEmpty()){if(vault.path("files").size()>0)throw ApiFailure.validation();return entries;}
        var root=entries.get(VaultIndex.ROOT);if(root==null||!root.path("path").asText().isEmpty()||!root.path("parentId").asText().equals(VaultIndex.ROOT.toString())||root.path("deleted").asBoolean()||!VaultCollection.folder(root))throw ApiFailure.validation();
        for(var value:entries.values()){
            var path=VaultCollection.path(value);if(VaultCollection.id(value).equals(VaultIndex.ROOT))continue;
            var parent=entries.get(uuid(value.path("parentId")));if(path.isRoot()||parent==null||!VaultCollection.folder(parent)||!value.path("deleted").asBoolean()&&(!path.parent().equals(VaultCollection.path(parent))||parent.path("deleted").asBoolean()))throw ApiFailure.validation();
            var seen=new HashSet<UUID>();var ancestor=value;
            while(!VaultCollection.id(ancestor).equals(VaultIndex.ROOT)){if(!seen.add(VaultCollection.id(ancestor))||seen.size()>VaultPath.MAX_DEPTH)throw ApiFailure.validation();ancestor=entries.get(uuid(ancestor.path("parentId")));if(ancestor==null)throw ApiFailure.validation();}
        }
        var settings=vault.path("settings");
        var roots=new HashMap<String,VaultPath>();
        for(String field:List.of("journalFolder","eventsFolder","assetsFolder")){
            var path=new VaultPath(settings.path(field).asText());roots.put(field,path);
            if(path.isRoot()||entries.values().stream().noneMatch(entry->!entry.path("deleted").asBoolean()&&VaultCollection.folder(entry)&&VaultCollection.path(entry).equals(path)))throw ApiFailure.validation();
        }
        for(var entry:entries.values())if(!VaultCollection.folder(entry)){
            String field=switch(entry.path("kind").asText()){case "DAY","WEEK"->"journalFolder";case "EVENT"->"eventsFolder";case "ASSET"->"assetsFolder";default->null;};
            if(field!=null){
                var parent=entries.get(uuid(entry.path("parentId")));
                String leaf=entry.path("sourceKey").asText()+(Set.of("DAY","WEEK").contains(entry.path("kind").asText())?".md":"");
                if(parent==null||!VaultCollection.path(parent).equals(roots.get(field))||!VaultCollection.path(entry).name().equals(leaf)
                        ||!entry.path("deleted").asBoolean()&&!VaultCollection.path(entry).equals(roots.get(field).resolve(leaf)))throw ApiFailure.validation();
            }
        }
        return entries;
    }
    private static UUID identifier(String value){try{var id=UUID.fromString(value);if(!id.toString().equals(value))throw ApiFailure.validation();return id;}catch(IllegalArgumentException invalid){throw ApiFailure.validation();}}
    private static Set<String> names(JsonNode value){var result=new HashSet<String>();value.fieldNames().forEachRemaining(result::add);return result;}
    /** Refuse collection merges; the preview transaction presents this conflict. */
    static void prepare(JsonNode vault,ServerStore store){
        if(vault.isMissingNode()||vault.path("entries").isEmpty())return;
        var settings=new VaultSettings(store).get();var allowed=new HashSet<String>();allowed.add("");
        for(String field:SETTINGS_FIELDS)if(!field.equals("syncObsidianConfig")){var path=new VaultPath(settings.path(field).asText());while(!path.isRoot()){allowed.add(path.value());path=path.parent();}}
        for(var entry:store.list(VaultIndex.ENTRY))if(!VaultCollection.folder(entry)||entry.path("deleted").asBoolean()||!allowed.contains(entry.path("path").asText()))throw new ApiFailure(409,"VAULT_IMPORT_NOT_EMPTY");
        // Preserve deterministic-resource version fences even when rebuilding
        // derived indexes in a pristine target. A restore cannot revive an old
        // directory/settings validator, including under a frozen test clock.
        new VaultState(store,store.vaultLimits()).resetForImport();
        for(String type:List.of(VaultIndex.ENTRY,VaultIndex.PATH,VaultIndex.REFERENCE))store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=?",store.owner(),type);
        store.update("DELETE FROM tk_relation WHERE owner_id=? AND from_type=? AND kind=?",store.owner(),VaultIndex.ENTRY,"vault_child");
    }
    static void restore(Path archive,JsonNode vault,ServerStore store){
        if(vault.isMissingNode()||vault.path("entries").isEmpty())return;
        var entries=graph(vault);var settings=new VaultSettings(store);settings.save(etag(settings.get()),vault.path("settings"));
        var files=new HashMap<String,JsonNode>();for(var file:vault.path("files"))files.put(file.path("path").asText(),file);var used=new HashSet<String>();
        var ordered=new ArrayList<>(entries.values());ordered.sort(Comparator.comparingInt(value->VaultCollection.path(value).segments().size()));
        var index=new VaultIndex(store);var state=new VaultState(store,store.vaultLimits());
        try(var zip=new ZipFile(archive.toFile())){
            for(var entry:ordered){
                if(entry.path("sourceType").asText().equals(JournalAssets.TYPE))entry.put("sourceId",JournalAssets.id(store.owner(),entry.path("sourceKey").asText()).toString());
                boolean deleted=entry.path("deleted").asBoolean();long retained=0;VaultSources.Snapshot snapshot=null;
                if(!VaultCollection.folder(entry)){
                    var ref=VaultSources.fromEntry(entry);var source=new VaultSources(store,store.vaultLimits(),ref);var current=source.snapshot();
                    if(ref.type().equals(VaultContent.TYPE)){
                        String filename=filename(VaultContent.TYPE,ref.id());var file=files.get(filename);if(file==null||!used.add(filename))throw ApiFailure.validation();byte[] bytes=read(zip,file);retained=bytes.length;
                        if(current.version()>0)throw ApiFailure.conflict();source.write(current.tag(),bytes,entry.path("kind").asText().equals("MARKDOWN"));if(deleted)source.delete(source.snapshot().tag());
                    }else if(deleted){
                        if(!current.deleted()&&current.version()>0)throw ApiFailure.conflict();String filename=filename(VaultRecycle.TYPE,VaultCollection.id(entry));var file=files.get(filename);if(file==null||!used.add(filename))throw ApiFailure.validation();
                        byte[] bytes=read(zip,file);retained=bytes.length;source.write(current.tag(),bytes,true);source.delete(source.snapshot().tag());new VaultRecycle(store).retain(VaultCollection.id(entry),source.snapshot().version(),bytes);
                    }else{if(current.deleted())throw ApiFailure.validation();retained=current.byteLength();}
                    snapshot=source.snapshot();
                }
                index.install(VaultCollection.id(entry),entry);
                if(snapshot!=null)index.content(VaultCollection.id(entry),snapshot,retained);
                if(!VaultCollection.id(entry).equals(VaultIndex.ROOT))state.changed(retained,1,false);
            }
            if(!used.equals(files.keySet()))throw ApiFailure.validation();
        }catch(IOException unavailable){throw new ApiFailure(503,"UNAVAILABLE");}
        // Also index any pre-existing canonical journal documents absent from the
        // archive; an additive account import cannot hide that existing content.
        new VaultCatalog(store,store.vaultLimits()).initialize();
        var usage=state.get();
        if(usage.path("totalBytes").asLong()>store.vaultLimits().totalBytes()||usage.path("entries").asLong()>store.vaultLimits().entries())throw new ApiFailure(507,"VAULT_QUOTA");
    }
}
