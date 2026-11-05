const limit=2000;
const escaped=value=>JSON.stringify(value).replaceAll('\uFEFF','\\uFEFF');
const visible=value=>value.replace(/^\uFEFF/,'').replace(/\r\n?/g,'\n');

/** Bounded Myers line comparison. The fallback still preserves every input line. */
export function lineChanges(before,after,{budget=1000000}={}){
  const n=before.length,m=after.length,max=Math.min(n+m,700),offset=max+1;
  const frontier=new Int32Array(2*max+3).fill(-1),trace=[];frontier[offset+1]=0;let work=0;
  for(let distance=0;distance<=max;distance++){
    trace.push(frontier.slice());
    for(let diagonal=-distance;diagonal<=distance;diagonal+=2){
      if(++work>budget)return [...before.map(text=>({kind:'removed',text})),...after.map(text=>({kind:'added',text}))];
      const at=offset+diagonal;
      let x=diagonal===-distance||diagonal!==distance&&frontier[at-1]<frontier[at+1]?frontier[at+1]:frontier[at-1]+1,y=x-diagonal;
      while(x<n&&y<m&&before[x]===after[y]){x++;y++;if(++work>budget)break;}
      frontier[at]=x;
      if(x>=n&&y>=m){
        const result=[];x=n;y=m;
        for(let d=distance;d>=0;d--){
          const previous=trace[d],k=x-y,at=offset+k;
          const previousK=k===-d||k!==d&&previous[at-1]<previous[at+1]?k+1:k-1;
          const previousX=previous[offset+previousK],previousY=previousX-previousK;
          while(x>previousX&&y>previousY){result.push({kind:'context',text:before[--x]});--y;}
          if(d>0){if(x===previousX)result.push({kind:'added',text:after[--y]});else result.push({kind:'removed',text:before[--x]});}
        }
        return result.reverse();
      }
    }
  }
  return [...before.map(text=>({kind:'removed',text})),...after.map(text=>({kind:'added',text}))];
}

/** Unified hunks for display only. Neither saved text nor draft text is modified. */
export function revisionDiff(current,previous){
  if(current===previous)return {hunks:[],truncated:false};
  let operations,truncated=false;
  if(current.length+previous.length>2000000){
    operations=[{kind:'removed',text:escaped(previous.slice(0,4000))},{kind:'added',text:escaped(current.slice(0,4000))}];truncated=true;
  }else if(visible(current)===visible(previous)){
    operations=[{kind:'removed',text:escaped(previous)},{kind:'added',text:escaped(current)}];
  }else{
    const before=previous.split(/\r\n|\n|\r/),after=current.split(/\r\n|\n|\r/);
    if(before.length+after.length>100000){
      operations=[{kind:'removed',text:escaped(previous.slice(0,4000))},{kind:'added',text:escaped(current.slice(0,4000))}];truncated=true;
    }else operations=lineChanges(before,after);
  }
  let oldLine=1,newLine=1;for(const row of operations){row.oldLine=oldLine;row.newLine=newLine;if(row.kind!=='added')oldLine++;if(row.kind!=='removed')newLine++;}
  const ranges=[];for(let index=0;index<operations.length;index++)if(operations[index].kind!=='context'){
    const start=Math.max(0,index-3),end=Math.min(operations.length,index+4),last=ranges.at(-1);
    if(last&&start<=last.end)last.end=end;else ranges.push({start,end});
  }
  const hunks=[];let remaining=limit;
  for(const range of ranges){
    if(!remaining){truncated=true;break;}
    const rows=operations.slice(range.start,Math.min(range.end,range.start+remaining));remaining-=rows.length;
    if(rows.length<range.end-range.start)truncated=true;
    hunks.push({oldStart:rows[0].oldLine,newStart:rows[0].newLine,oldCount:rows.filter(row=>row.kind!=='added').length,newCount:rows.filter(row=>row.kind!=='removed').length,rows});
  }
  return {hunks,truncated};
}
