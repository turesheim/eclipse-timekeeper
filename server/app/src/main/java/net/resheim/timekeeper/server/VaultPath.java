package net.resheim.timekeeper.server;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Portable, account-relative names. These names are never filesystem paths. */
record VaultPath(String value) {
    static final int MAX_BYTES=1024, MAX_SEGMENT_BYTES=255, MAX_DEPTH=32;
    private static final Set<String> DEVICES=Set.of("CON","PRN","AUX","NUL","CONIN$","CONOUT$");

    VaultPath {
        if(value==null||!StandardCharsets.UTF_8.newEncoder().canEncode(value))throw ApiFailure.validation();
        value=Normalizer.normalize(value,Normalizer.Form.NFC);
        if(value.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw ApiFailure.validation();
        if(!value.isEmpty()) {
            String[] segments=value.split("/",-1);
            if(segments.length>MAX_DEPTH)throw ApiFailure.validation();
            for(String segment:segments)validateSegment(segment);
        }
    }

    static VaultPath root(){return new VaultPath("");}
    boolean isRoot(){return value.isEmpty();}
    List<String> segments(){return isRoot()?List.of():List.of(value.split("/"));}
    String name(){return isRoot()?"":value.substring(value.lastIndexOf('/')+1);}
    VaultPath parent(){int slash=value.lastIndexOf('/');return slash<0?root():new VaultPath(value.substring(0,slash));}

    /** Conservative portable collision key; never silently merge matching names. */
    String key(){return fold(value);}
    private static String fold(String text){
        return Normalizer.normalize(text.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT),Normalizer.Form.NFC);
    }
    static String searchKey(String text){
        if(text==null)return "";
        if(!StandardCharsets.UTF_8.newEncoder().canEncode(text)||text.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES
                ||text.codePoints().anyMatch(Character::isISOControl))throw ApiFailure.validation();
        return fold(text);
    }

    VaultPath resolve(String name){
        if(name==null||name.contains("/"))throw ApiFailure.validation();
        return new VaultPath(isRoot()?name:value+"/"+name);
    }
    boolean beneath(VaultPath parent){
        return parent.isRoot()||key().equals(parent.key())||key().startsWith(parent.key()+"/");
    }
    VaultPath relativeTo(VaultPath parent){
        if(!beneath(parent))throw ApiFailure.validation();
        return new VaultPath(String.join("/",segments().subList(parent.segments().size(),segments().size())));
    }
    VaultPath rebase(VaultPath source,VaultPath destination){
        if(source.isRoot()||!beneath(source)||destination.beneath(source)&&!destination.key().equals(source.key()))throw ApiFailure.validation();
        var suffix=relativeTo(source);
        return suffix.isRoot()?destination:new VaultPath(destination.isRoot()?suffix.value:destination.value+"/"+suffix.value);
    }

    /** Decode each URI segment exactly once; encoded separators cannot change hierarchy. */
    static VaultPath encoded(String path){
        if(path==null)throw ApiFailure.validation();
        if(path.isEmpty())return root();
        var decoded=new java.util.ArrayList<String>();
        for(String segment:path.split("/",-1)) {
            final String text;
            try{text=URLDecoder.decode(segment.replace("+","%2B"),StandardCharsets.UTF_8);}
            catch(IllegalArgumentException invalid){throw ApiFailure.validation();}
            if(text.contains("/")||text.contains("\\"))throw ApiFailure.validation();
            // URLDecoder replaces malformed UTF-8. Round-trip bytes to reject it instead.
            try {
                byte[] bytes=percentBytes(segment);
                String strict=StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                if(!strict.equals(text))throw ApiFailure.validation();
            }catch(java.nio.charset.CharacterCodingException invalid){throw ApiFailure.validation();}
            decoded.add(text);
        }
        return new VaultPath(String.join("/",decoded));
    }
    String encoded(){return String.join("/",segments().stream().map(VaultPath::encode).toList());}
    private static String encode(String segment){return URLEncoder.encode(segment,StandardCharsets.UTF_8).replace("+","%20");}
    private static byte[] percentBytes(String segment){
        var output=new java.io.ByteArrayOutputStream();
        for(int i=0;i<segment.length();) {
            if(segment.charAt(i)=='%') {
                if(i+2>=segment.length())throw ApiFailure.validation();
                int high=Character.digit(segment.charAt(i+1),16),low=Character.digit(segment.charAt(i+2),16);
                if(high<0||low<0)throw ApiFailure.validation();
                output.write(high*16+low);i+=3;
            }else {
                int code=segment.codePointAt(i);output.writeBytes(new String(Character.toChars(code)).getBytes(StandardCharsets.UTF_8));i+=Character.charCount(code);
            }
        }
        return output.toByteArray();
    }
    private static void validateSegment(String segment){
        if(segment.isEmpty()||segment.equals(".")||segment.equals("..")||segment.endsWith(".")||segment.endsWith(" ")
                ||segment.getBytes(StandardCharsets.UTF_8).length>MAX_SEGMENT_BYTES)throw ApiFailure.validation();
        if(segment.codePoints().anyMatch(code->Character.isISOControl(code)||"<>:\\|?*".indexOf(code)>=0))throw ApiFailure.validation();
        String stem=segment.split("\\.",2)[0].stripTrailing().toUpperCase(Locale.ROOT);
        if(DEVICES.contains(stem)||stem.matches("(?:COM|LPT)[1-9¹²³]"))throw ApiFailure.validation();
    }
}
