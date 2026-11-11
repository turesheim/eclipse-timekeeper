package net.resheim.timekeeper.server;

import java.util.*;

/** Match source items without putting storage IDs into the user's Markdown. */
final class NoteTaskMatches {
    private NoteTaskMatches(){}
    static int[] previous(List<NoteTaskMarkup.Item> old,List<NoteTaskMarkup.Item> next){
        int[] result=new int[next.size()];Arrays.fill(result,-1);boolean[] used=new boolean[old.size()];
        var byTitle=new HashMap<String,ArrayDeque<Integer>>();for(int i=0;i<old.size();i++)byTitle.computeIfAbsent(old.get(i).summary(),key->new ArrayDeque<>()).add(i);
        // Identical titles remain separate and retain their relative order.
        for(int i=0;i<next.size();i++){var candidates=byTitle.get(next.get(i).summary());if(candidates!=null&&!candidates.isEmpty()){int match=candidates.removeFirst();result[i]=match;used[match]=true;}}
        var before=new ArrayList<Integer>();var after=new ArrayList<Integer>();for(int i=0;i<old.size();i++)if(!used[i])before.add(i);for(int i=0;i<next.size();i++)if(result[i]<0)after.add(i);
        if(before.size()==1&&after.size()==1){result[after.getFirst()]=before.getFirst();return result;}
        // A bounded comparison also recognizes an edited title next to newly
        // added items. Ambiguous replacements get new identities; existing
        // tasks with recorded work are retained instead of silently repurposed.
        if(before.size()>64||after.size()>64)return result;
        record Candidate(int old,int next,double score){}
        var candidates=new ArrayList<Candidate>();
        for(int index:after){
            double best=0,second=0;int match=-1;
            for(int previous:before){double score=similarity(old.get(previous).summary(),next.get(index).summary());if(score>best){second=best;best=score;match=previous;}else second=Math.max(second,score);}
            if(best>=.45&&best-second>=.15)candidates.add(new Candidate(match,index,best));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
        for(var candidate:candidates)if(!used[candidate.old()]&&result[candidate.next()]<0){used[candidate.old()]=true;result[candidate.next()]=candidate.old();}
        return result;
    }
    private static double similarity(String left,String right){
        var a=words(left);var b=words(right);int common=0;for(String word:a)if(b.contains(word))common++;
        double tokens=a.isEmpty()||b.isEmpty()?0:(2.0*common)/(a.size()+b.size());
        int prefix=0;while(prefix<left.length()&&prefix<right.length()&&left.charAt(prefix)==right.charAt(prefix))prefix++;
        int suffix=0;while(suffix<left.length()-prefix&&suffix<right.length()-prefix&&left.charAt(left.length()-suffix-1)==right.charAt(right.length()-suffix-1))suffix++;
        double edges=(double)(prefix+suffix)/Math.max(1,Math.max(left.length(),right.length()));return Math.max(tokens,edges);
    }
    private static Set<String> words(String text){var result=new HashSet<String>();for(String word:text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))if(!word.isEmpty())result.add(word);return result;}
}
