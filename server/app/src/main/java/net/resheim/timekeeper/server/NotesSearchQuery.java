package net.resheim.timekeeper.server;

import java.io.IOException;
import java.util.*;
import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.util.BytesRef;

/** Restricted words/phrases, with deterministic field weights across replicas. */
final class NotesSearchQuery {
    static final int MAX_CHARACTERS=512,MAX_TERMS=32,BLOCK_GAP=100;
    record Parsed(String text,Query expression){
        @Override public String toString(){return "NotesSearchQuery.Parsed[]";}
    }
    static Analyzer analyzer(){
        var standard=new StandardAnalyzer();standard.setMaxTokenLength(MAX_CHARACTERS);var tags=NotesHashtags.analyzer();
        return new AnalyzerWrapper(Analyzer.PER_FIELD_REUSE_STRATEGY){
            @Override protected Analyzer getWrappedAnalyzer(String field){return field.equals("tag")?tags:standard;}
            @Override protected TokenStreamComponents wrapComponents(String field,TokenStreamComponents components){
                if(!field.equals("path"))return components;
                int flags=WordDelimiterGraphFilter.GENERATE_WORD_PARTS|WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS|WordDelimiterGraphFilter.PRESERVE_ORIGINAL;
                return new TokenStreamComponents(components.getSource(),new WordDelimiterGraphFilter(components.getTokenStream(),flags,null));
            }
            @Override public int getPositionIncrementGap(String field){return field.equals("body")||field.equals("path")?BLOCK_GAP:0;}
            @Override public int getOffsetGap(String field){return field.equals("body")||field.equals("path")?2:1;}
            @Override public void close(){super.close();standard.close();tags.close();}
        };
    }
    static Parsed parse(String input){
        if(input==null||input.isBlank()||input.length()>MAX_CHARACTERS)throw ApiFailure.validation();
        String text=NotesSearchText.normalized(input.strip());var required=new BooleanQuery.Builder();int count=0;
        try(var analyzer=analyzer()){
            for(int offset=0;offset<text.length();){
                if(Character.isWhitespace(text.charAt(offset))){offset++;continue;}
                String field="";
                for(String candidate:List.of("tag","file","event","journal","note")){
                    if(text.regionMatches(true,offset,candidate+":",0,candidate.length()+1)){field=candidate;offset+=candidate.length()+1;break;}
                }
                boolean phrase=offset<text.length()&&text.charAt(offset)=='"';var part=new StringBuilder();
                if(phrase){
                    offset++;boolean closed=false;
                    while(offset<text.length()){
                        char c=text.charAt(offset++);
                        if(c=='"'){closed=true;break;}
                        if(c=='\\'&&offset<text.length()&&(text.charAt(offset)=='"'||text.charAt(offset)=='\\'))c=text.charAt(offset++);
                        part.append(c);
                    }
                    if(!closed)throw ApiFailure.validation();
                }else while(offset<text.length()&&!Character.isWhitespace(text.charAt(offset))&&text.charAt(offset)!='"')part.append(text.charAt(offset++));
                if(field.equals("tag")){
                    String value=part.toString(),key=NotesHashtags.key(value);if(key==null||++count>MAX_TERMS)throw ApiFailure.validation();
                    var tagged=new BooleanQuery.Builder().add(new ConstantScoreQuery(new TermQuery(new Term("tag",key))),BooleanClause.Occur.MUST);
                    // Optional zero-weight body terms supply useful excerpt offsets;
                    // only the exact tag field determines membership and ranking.
                    var words=tokens(analyzer,value);if(!words.isEmpty())tagged.add(new BoostQuery(across(words),0),BooleanClause.Occur.SHOULD);
                    required.add(tagged.build(),BooleanClause.Occur.MUST);continue;
                }
                if(field.equals("file")){
                    String value=VaultPath.searchKey(part.toString());if(value.isBlank()||++count>MAX_TERMS)throw ApiFailure.validation();
                    // The qualifier is a literal path substring, never user-supplied
                    // wildcard syntax. The existing search deadline still applies.
                    String literal=value.replace("\\","\\\\").replace("*","\\*").replace("?","\\?");
                    required.add(new WildcardQuery(new Term("path_key","*"+literal+"*")),BooleanClause.Occur.FILTER);
                    required.add(new TermQuery(new Term("source_type","CALENDAR_EVENT")),BooleanClause.Occur.MUST_NOT);continue;
                }
                if(Set.of("event","journal","note").contains(field)){
                    if(++count>MAX_TERMS)throw ApiFailure.validation();
                    List<BytesRef> kinds=switch(field){case "event"->List.of(new BytesRef("EVENT"));case "journal"->List.of(new BytesRef("DAY"),new BytesRef("WEEK"));default->List.of(new BytesRef("MARKDOWN"));};
                    required.add(new TermInSetQuery("kind",kinds),BooleanClause.Occur.FILTER);
                }
                var terms=tokens(analyzer,part.toString());count+=terms.size();if(count>MAX_TERMS)throw ApiFailure.validation();
                if(phrase&&!terms.isEmpty())required.add(across(terms),BooleanClause.Occur.MUST);
                else for(String term:terms)required.add(across(List.of(term)),BooleanClause.Occur.MUST);
            }
        }catch(IOException unavailable){throw new IllegalStateException("Search query analysis failed");}
        return new Parsed(text,count==0?new MatchNoDocsQuery():required.build());
    }
    private static List<String> tokens(Analyzer analyzer,String text)throws IOException{
        var result=new ArrayList<String>();
        try(var stream=analyzer.tokenStream("query",text)){var term=stream.addAttribute(CharTermAttribute.class);stream.reset();while(stream.incrementToken())result.add(term.toString());stream.end();}
        return result;
    }
    private static Query across(List<String> terms){
        var alternatives=new ArrayList<Query>();
        for(String field:List.of("title","path","body")){
            Query match;
            if(terms.size()==1)match=new TermQuery(new Term(field,terms.getFirst()));
            else{var phrase=new PhraseQuery.Builder();for(int i=0;i<terms.size();i++)phrase.add(new Term(field,terms.get(i)),i);match=phrase.build();}
            // Corpus/segment-dependent IDF would make independently rebuilt
            // replicas disagree about relevance and portable continuation.
            float weight=field.equals("title")?6:field.equals("path")?3:1;
            alternatives.add(new BoostQuery(new ConstantScoreQuery(match),weight));
        }
        return new DisjunctionMaxQuery(alternatives,0.1f);
    }
    static Query scoped(Parsed parsed,String owner,boolean configurationAllowed,String hiddenJournalRoot){
        var query=new BooleanQuery.Builder().add(parsed.expression(),BooleanClause.Occur.MUST)
                .add(new TermQuery(new Term("owner",owner)),BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term("deleted","false")),BooleanClause.Occur.FILTER)
                .add(new TermInSetQuery("kind",List.of(new BytesRef("MARKDOWN"),new BytesRef("DAY"),new BytesRef("WEEK"),new BytesRef("EVENT"))),BooleanClause.Occur.FILTER);
        // A small corpus-independent bonus preserves portable relevance/cursors
        // and cannot outrank a stronger title/path match on bookmark alone.
        query.add(new BoostQuery(new ConstantScoreQuery(new TermQuery(new Term("bookmarked","true"))),0.1f),BooleanClause.Occur.SHOULD);
        if(!configurationAllowed)query.add(new TermQuery(new Term("configuration","true")),BooleanClause.Occur.MUST_NOT);
        if(hiddenJournalRoot!=null&&!hiddenJournalRoot.isBlank())
            query.add(new PrefixQuery(new Term("path_key",VaultPath.searchKey(hiddenJournalRoot)+"/")),BooleanClause.Occur.MUST_NOT);
        return query.build();
    }
}
