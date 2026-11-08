package net.resheim.timekeeper.server;

import java.io.IOException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.pattern.PatternTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/** Exact, Unicode-normalized tags; punctuation and code are not query syntax. */
final class NotesHashtags {
    static final int MAX_LENGTH=128;
    private static final String SEGMENT="[\\p{L}\\p{N}_][\\p{L}\\p{M}\\p{N}_-]*";
    private static final Pattern TOKEN=Pattern.compile("(?<![\\p{L}\\p{M}\\p{N}_/#\\\\])#"+SEGMENT+"(?:/"+SEGMENT+")*");
    private static final Pattern WIKI=Pattern.compile("!?\\[\\[[^\\]\\r\\n]*\\]\\]");
    static String key(String value){
        String folded=Normalizer.normalize(value,Normalizer.Form.NFC).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
        folded=Normalizer.normalize(folded,Normalizer.Form.NFC);
        return folded.length()<=MAX_LENGTH&&TOKEN.matcher(folded).matches()?folded:null;
    }
    static String prose(String value){return WIKI.matcher(NotesSearchText.normalized(value)).replaceAll(" ");}
    static Analyzer analyzer(){return new Analyzer(){
        @Override protected TokenStreamComponents createComponents(String field){
            var tokenizer=new PatternTokenizer(TOKEN,0);
            var filtered=new TokenFilter(tokenizer){
                private final CharTermAttribute term=addAttribute(CharTermAttribute.class);
                @Override public boolean incrementToken()throws IOException{
                    while(input.incrementToken()){String key=key(term.toString());if(key!=null){term.setEmpty().append(key);return true;}}
                    return false;
                }
            };
            return new TokenStreamComponents(tokenizer,filtered);
        }
    };}
}
