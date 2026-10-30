package net.resheim.timekeeper.server;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Parses a completed external note; never mutates the external Markdown file. */
final class JournalMarkdown {
    static final int FRONTMATTER_BYTES=20000;
    static final int FILE_BYTES=DayNotes.BODY_BYTES+FRONTMATTER_BYTES;
    record Note(LocalDate date,String label,String body,String hash) {
        // Records must not accidentally put readable journals into diagnostic logs.
        @Override public String toString(){return "JournalMarkdown.Note[date="+date+"]";}
    }
    record Document(Note note,Map<String,Object> metadata,boolean frontmatter) {
        @Override public String toString(){return "JournalMarkdown.Document[date="+note.date()+"]";}
    }
    record Fragment(Map<String,Object> metadata,boolean frontmatter,String body){@Override public String toString(){return "JournalMarkdown.Fragment[]";}}
    static Fragment fragment(byte[] bytes,int maximumFrontmatter){var value=document((LocalDate)null,bytes,false,maximumFrontmatter);return new Fragment(value.metadata(),value.frontmatter(),value.note().body());}
    static final class Invalid extends RuntimeException {
        final String code;
        Invalid(String code){super(code);this.code=code;}
    }
    private static Invalid invalid(String code){return new Invalid(code);}
    static LocalDate date(String filename){
        if(filename==null||!filename.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.md"))throw invalid("INVALID_FILENAME");
        try{return DayNotes.date(filename.substring(0,10));}
        catch(ApiFailure failure){throw invalid("INVALID_DATE");}
    }
    static Note parse(String filename,byte[] bytes){
        return document(filename,bytes).note();
    }
    static Document document(String filename,byte[] bytes){return document(filename,bytes,true);}
    static Note parseOpaque(String filename,byte[] bytes){return document(filename,bytes,false).note();}
    private static Document document(String filename,byte[] bytes,boolean legacyLabel){
        return document(date(filename),bytes,legacyLabel,FRONTMATTER_BYTES);
    }
    private static Document document(LocalDate date,byte[] bytes,boolean legacyLabel,int maximumFrontmatter){
        if(bytes.length>DayNotes.BODY_BYTES+maximumFrontmatter)throw invalid("FILE_TOO_LARGE");
        final String source;
        try {source=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException failure){throw invalid("INVALID_UTF8");}
        String label="",body=source;boolean frontmatter=false;var properties=new LinkedHashMap<String,Object>();
        int first=source.startsWith("\ufeff")?1:0;
        int openingEnd=lineEnd(source,first);
        if(line(source,first,openingEnd).equals("---")&&openingEnd<source.length()){
            frontmatter=true;
            int yamlStart=openingEnd+1,closing=-1,bodyStart=-1;
            for(int start=yamlStart;start<=source.length();){
                int end=lineEnd(source,start);String delimiter=line(source,start,end);
                if(delimiter.equals("---")||delimiter.equals("...")){closing=start;bodyStart=end<source.length()?end+1:end;break;}
                if(end==source.length())break;
                start=end+1;
            }
            if(closing<0)throw invalid("INVALID_FRONTMATTER");
            if(source.substring(0,bodyStart).getBytes(StandardCharsets.UTF_8).length>maximumFrontmatter)throw invalid("FRONTMATTER_TOO_LARGE");
            var options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setWarnOnDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);options.setAllowRecursiveKeys(false);options.setNestingDepthLimit(16);
            options.setCodePointLimit(maximumFrontmatter);options.setTagInspector(tag->false);
            final Object metadata;
            try {metadata=new Yaml(new SafeConstructor(options)).load(source.substring(yamlStart,closing));}
            // SnakeYAML exceptions contain source snippets. Never retain their cause.
            catch(RuntimeException failure){throw invalid("INVALID_YAML");}
            if(metadata!=null&&!(metadata instanceof Map<?,?>))throw invalid("INVALID_FRONTMATTER");
            if(metadata instanceof Map<?,?> map){
                if(map.keySet().stream().anyMatch(key->!(key instanceof String)))throw invalid("INVALID_FRONTMATTER");
                map.forEach((key,value)->properties.put((String)key,value));
                if(legacyLabel){Object value=map.get("timekeeper-label");
                    if(value!=null&&!(value instanceof String))throw invalid("INVALID_LABEL");
                    if(value instanceof String text)label=text.strip();}
            }
            body=source.substring(bodyStart);
        }
        if(label.codePointCount(0,label.length())>80||label.codePoints().anyMatch(Character::isISOControl))throw invalid("INVALID_LABEL");
        if(body.getBytes(StandardCharsets.UTF_8).length>DayNotes.BODY_BYTES)throw invalid("BODY_TOO_LARGE");
        return new Document(new Note(date,label,body,hash(bytes)),Collections.unmodifiableMap(properties),frontmatter);
    }
    static byte[] render(String filename,byte[] original,String label,String body){
        date(filename);Document prior=original==null?null:document(filename,original);
        if(prior!=null&&prior.note().label().equals(label)&&prior.note().body().equals(body))return original.clone();
        var properties=new LinkedHashMap<String,Object>();if(prior!=null)properties.putAll(prior.metadata());
        if(label.isEmpty())properties.remove("timekeeper-label");else properties.put("timekeeper-label",label);
        String source=body;
        int first=body.startsWith("\ufeff")?1:0,end=lineEnd(body,first);
        boolean ambiguousBody=line(body,first,end).equals("---")&&end<body.length();
        if(!properties.isEmpty()||(prior!=null&&prior.frontmatter())||ambiguousBody){
            var options=new DumperOptions();options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);options.setSplitLines(false);
            final String yaml;
            try{yaml=new Yaml(options).dump(properties);}
            catch(RuntimeException failure){throw invalid("INVALID_EXPORT");}
            source="---\n"+yaml+"---\n"+body;
        }
        byte[] bytes=source.getBytes(StandardCharsets.UTF_8);Note exported=parse(filename,bytes);
        if(!exported.label().equals(label)||!exported.body().equals(body))throw invalid("INVALID_EXPORT");
        return bytes;
    }
    /** Replace only the body; legacy metadata is inert and its bytes stay exact. */
    static byte[] replaceBody(String filename,byte[] original,String body){
        if(original==null)return render(filename,null,"",body);
        var prior=document(filename,original,false);
        if(prior.note().body().equals(body))return original.clone();
        if(!prior.frontmatter())return render(filename,null,"",body);
        byte[] suffix=body.getBytes(StandardCharsets.UTF_8);
        int prefix=original.length-prior.note().body().getBytes(StandardCharsets.UTF_8).length;
        byte[] result=java.util.Arrays.copyOf(original,prefix+suffix.length);
        System.arraycopy(suffix,0,result,prefix,suffix.length);
        if(!parseOpaque(filename,result).body().equals(body))throw invalid("INVALID_EXPORT");
        return result;
    }
    static byte[] replaceFragmentBody(byte[] original,String body,int maximumFrontmatter){
        var prior=fragment(original,maximumFrontmatter);if(prior.body().equals(body))return original.clone();
        int prefix=original.length-prior.body().getBytes(StandardCharsets.UTF_8).length;
        String separator=prefix>0&&original[prefix-1]!='\n'&&!body.isEmpty()?(new String(original,StandardCharsets.UTF_8).contains("\r\n")?"\r\n":"\n"):"";
        byte[] suffix=(separator+body).getBytes(StandardCharsets.UTF_8),result=java.util.Arrays.copyOf(original,prefix+suffix.length);System.arraycopy(suffix,0,result,prefix,suffix.length);
        if(!fragment(result,maximumFrontmatter).body().equals(body))throw invalid("INVALID_EXPORT");return result;
    }
    private static int lineEnd(String text,int start){int end=text.indexOf('\n',start);return end<0?text.length():end;}
    private static String line(String text,int start,int end){return text.substring(start,end>start&&text.charAt(end-1)=='\r'?end-1:end);}
    static String hash(byte[] bytes){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}
    }
    private JournalMarkdown(){}
}
