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
        LocalDate date=date(filename);
        if(bytes.length>FILE_BYTES)throw invalid("FILE_TOO_LARGE");
        final String source;
        try {source=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException failure){throw invalid("INVALID_UTF8");}
        String label="",body=source;
        int first=source.startsWith("\ufeff")?1:0;
        int openingEnd=lineEnd(source,first);
        if(line(source,first,openingEnd).equals("---")&&openingEnd<source.length()){
            int yamlStart=openingEnd+1,closing=-1,bodyStart=-1;
            for(int start=yamlStart;start<=source.length();){
                int end=lineEnd(source,start);String delimiter=line(source,start,end);
                if(delimiter.equals("---")||delimiter.equals("...")){closing=start;bodyStart=end<source.length()?end+1:end;break;}
                if(end==source.length())break;
                start=end+1;
            }
            if(closing<0)throw invalid("INVALID_FRONTMATTER");
            if(source.substring(0,bodyStart).getBytes(StandardCharsets.UTF_8).length>FRONTMATTER_BYTES)throw invalid("FRONTMATTER_TOO_LARGE");
            var options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setWarnOnDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);options.setAllowRecursiveKeys(false);options.setNestingDepthLimit(16);
            options.setCodePointLimit(FRONTMATTER_BYTES);options.setTagInspector(tag->false);
            final Object metadata;
            try {metadata=new Yaml(new SafeConstructor(options)).load(source.substring(yamlStart,closing));}
            // SnakeYAML exceptions contain source snippets. Never retain their cause.
            catch(RuntimeException failure){throw invalid("INVALID_YAML");}
            if(metadata!=null&&!(metadata instanceof Map<?,?>))throw invalid("INVALID_FRONTMATTER");
            if(metadata instanceof Map<?,?> map){
                if(map.keySet().stream().anyMatch(key->!(key instanceof String)))throw invalid("INVALID_FRONTMATTER");
                Object value=map.get("timekeeper-label");
                if(value!=null&&!(value instanceof String))throw invalid("INVALID_LABEL");
                if(value instanceof String text)label=text.strip();
            }
            body=source.substring(bodyStart);
        }
        if(label.codePointCount(0,label.length())>80||label.codePoints().anyMatch(Character::isISOControl))throw invalid("INVALID_LABEL");
        if(body.getBytes(StandardCharsets.UTF_8).length>DayNotes.BODY_BYTES)throw invalid("BODY_TOO_LARGE");
        return new Note(date,label,body,hash(bytes));
    }
    private static int lineEnd(String text,int start){int end=text.indexOf('\n',start);return end<0?text.length():end;}
    private static String line(String text,int start,int end){return text.substring(start,end>start&&text.charAt(end-1)=='\r'?end-1:end);}
    static String hash(byte[] bytes){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}
    }
    private JournalMarkdown(){}
}
