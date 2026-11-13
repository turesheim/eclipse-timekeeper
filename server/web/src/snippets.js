/** Plain-text Alfred-style date placeholders. No scripts or clipboard access. */
export const defaultSnippets=Object.freeze([
  {keyword:';dt',content:'{isodate:yyyy-MM-dd HH:mm}'},
  {keyword:';dd',content:'{isodate:yyyy-MM-dd}'},
  {keyword:';tm',content:'{isotime:HH:mm}'}
]);
let preferences=null;
export function configureSnippets(profile){preferences=profile?{snippets:profile.textSnippets??defaultSnippets,zone:profile.zoneId,locale:profile.locale||undefined}:null;}
const pad=(n,size=2)=>String(n).padStart(size,'0');
function parts(now,zone){return Object.fromEntries(new Intl.DateTimeFormat('en-GB',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).formatToParts(now).filter(p=>p.type!=='literal').map(p=>[p.type,Number(p.value)]));}
const civilDate=p=>new Date(Date.UTC(p.year,p.month-1,p.day,p.hour,p.minute,p.second));
function shifted(now,zone,expression){
  let p=parts(now,zone),rest=expression.replaceAll(/\s/g,''),match;
  while(rest){
    match=/^([+-]\d+)([yYMDwdhms])/.exec(rest);if(!match)throw new Error('Unsupported date arithmetic');
    const n=Number(match[1]),unit=({Y:'y',D:'d'}[match[2]]||match[2]);if(Math.abs(n)>10000)throw new Error('Date arithmetic too large');
    const date=civilDate(p);
    if(unit==='M'||unit==='y'){
      date.setUTCDate(1);date.setUTCFullYear(p.year+(unit==='y'?n:0));date.setUTCMonth(p.month-1+(unit==='M'?n:0));
      const last=new Date(Date.UTC(date.getUTCFullYear(),date.getUTCMonth()+1,0)).getUTCDate();date.setUTCDate(Math.min(p.day,last));
    }else date.setUTCSeconds(date.getUTCSeconds()+n*({w:604800,d:86400,h:3600,m:60,s:1}[unit]));
    p={year:date.getUTCFullYear(),month:date.getUTCMonth()+1,day:date.getUTCDate(),hour:date.getUTCHours(),minute:date.getUTCMinutes(),second:date.getUTCSeconds()};
    rest=rest.slice(match[0].length);
  }
  return p;
}
function formatted(p,format,locale){
  const date=civilDate(p),name=options=>new Intl.DateTimeFormat(locale,{timeZone:'UTC',...options}).format(date);
  const tokens={yyyy:pad(p.year,4),yy:pad(p.year%100),MMMM:name({month:'long'}),MMM:name({month:'short'}),MM:pad(p.month),M:p.month,dd:pad(p.day),d:p.day,EEEE:name({weekday:'long'}),EEE:name({weekday:'short'}),HH:pad(p.hour),H:p.hour,hh:pad(p.hour%12||12),h:p.hour%12||12,mm:pad(p.minute),m:p.minute,ss:pad(p.second),s:p.second,a:p.hour<12?'AM':'PM'};
  return format.replace(/'([^']|'')*'|yyyy|MMMM|EEEE|MMM|EEE|yy|MM|dd|HH|hh|mm|ss|[MdHhmsa]|[A-Za-z]+/g,token=>{
    if(token.startsWith("'"))return token.slice(1,-1).replaceAll("''", "'");
    if(!Object.hasOwn(tokens,token))throw new Error('Unsupported date format');return tokens[token];
  });
}
export function expandTemplate(content,{now=new Date(),zone='UTC',locale='en-GB',arithmetic=''}={}){
  let cursor=null;
  const text=content.replace(/\{(isodate|isotime|datetime|date|time)((?:\s*[+-]\d+[yYMDwdhms])*)(?::([^{}]+))?\}|\{cursor\}/g,(match,kind,offset,format)=>{
    if(match==='{cursor}')return match;
    try{
      const p=shifted(now,zone,arithmetic+(offset||''));
      if(kind==='isodate'||kind==='isotime')return formatted(p,format||(kind==='isodate'?'yyyy-MM-dd':'HH:mm:ss'),'en-US');
      if(format&&!['short','medium','long','full'].includes(format))return formatted(p,format,locale);
      // Locale styles use the account's civil time. Synthetic UTC avoids a second zone conversion.
      return new Intl.DateTimeFormat(locale,{timeZone:'UTC',...(kind!=='time'?{dateStyle:format||'medium'}:{}),...(kind!=='date'?{timeStyle:format||'medium'}:{})}).format(civilDate(p));
    }catch{return match;}
  });
  const at=text.indexOf('{cursor}');if(at>=0)cursor=at;
  return {text:text.replaceAll('{cursor}',''),cursor};
}
export function snippetAt(prefix,options=preferences){
  if(!options)return null;
  let snippet,match;
  for(const value of [...options.snippets].sort((a,b)=>b.keyword.length-a.keyword.length)){
    const keyword=value.keyword.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
    const candidate=new RegExp('(?:^|[\\s(\\[{])('+keyword+')((?:[+-]\\d+[yMwdhms]?)*)$','i').exec(prefix);
    if(candidate){snippet=value;match=candidate;break;}
  }
  if(!snippet)return null;
  let arithmetic=match[2];
  // Shortcut suffixes default to days; lowercase m follows the requested month shortcut.
  arithmetic=arithmetic.replace(/([+-]\d+)([yMwdhms]?)/g,(_,n,unit)=>n+(unit==='m'?'M':unit||'d'));
  const expanded=expandTemplate(snippet.content,{...options,arithmetic});
  return {...expanded,length:match[1].length+match[2].length};
}
export function sourceSnippetAt(prefix){
  // Source editing must leave fenced/indented code, inline code, HTML and links literal.
  let fence=null;
  for(const line of prefix.split('\n')){
    const match=/^ {0,3}(`{3,}|~{3,})/.exec(line);
    if(match){if(!fence)fence=match[1];else if(match[1][0]===fence[0]&&match[1].length>=fence.length)fence=null;}
  }
  const line=prefix.slice(prefix.lastIndexOf('\n')+1);
  if(fence||/^(?: {4}|\t)|<|\]\([^)]*$|\[\[[^\]]*$/.test(line)||((line.match(/(?<!\\)`+/g)||[]).length%2))return null;
  return snippetAt(prefix);
}
