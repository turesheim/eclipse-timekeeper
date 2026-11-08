/** Shared completion ranges; positions are UTF-16 offsets in the caller's text. */
export function escapedBracket(text,at){
  let count=0;for(let index=at-1;index>=0&&text[index]==='\\';index--)count++;
  return count%2===1;
}

export function wikiCompletion(text,at){
  const match=/(!?)\[\[([^\[\]\r\n]*)$/.exec(text.slice(0,at));if(!match)return null;
  const from=at-match[0].length;if(escapedBracket(text,from))return null;
  let to=at;while(to<at+2&&text[to]===']')to++;
  return {query:match[2],embed:!!match[1],from,to};
}

/** Source stays literal inside fences, indented code, inline code and destinations. */
export function literalBracketContext(text,at){
  let fence=null,line='',delimiter=0,onFence=false;
  for(const current of text.slice(0,at).split(/\r\n|\n|\r/)){
    line=current;onFence=false;const marker=current.match(/^ {0,3}(`{3,}|~{3,})(.*)$/);
    if(fence){onFence=true;if(marker&&marker[1][0]===fence[0]&&marker[1].length>=fence.length&&!marker[2].trim())fence=null;continue;}
    if(marker&&!(/`/.test(marker[2])&&marker[1][0]==='`')){fence=marker[1];delimiter=0;onFence=true;continue;}
    if(!current.trim())delimiter=0;
    for(const match of current.matchAll(/`+/g)){if(!delimiter&&escapedBracket(current,match.index))continue;if(!delimiter)delimiter=match[0].length;else if(match[0].length===delimiter)delimiter=0;}
  }
  return !!(fence||onFence||/^(?: {4}|\t)/.test(line)&&!/^\s*(?:[-+*]|\d+[.)])\s/.test(line)||delimiter||/\]\([^)]*$/.test(line)||escapedBracket(text,at)||text[at-1]==='['&&escapedBracket(text,at-1));
}
