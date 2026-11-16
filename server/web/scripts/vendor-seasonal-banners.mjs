// Draw original, generic landscape illustrations without external assets or rendering dependencies.
import {writeFile,mkdir,readFile} from 'node:fs/promises';
import {deflateSync} from 'node:zlib';
import {fileURLToPath} from 'node:url';
import {seasonalCatalog} from '../src/seasonal-banner-catalog.js';
const root=new URL('../src/assets/seasonal/v1/',import.meta.url),width=1800,height=400;
const rgb=hex=>hex.match(/../g).map(value=>parseInt(value,16));
const palettes={winter:['b9d4e2','edf1ee','a5becb','6f939f','d8e7e8','3b6876'],spring:['b8d8d6','eef0d5','a5b6b1','678b83','a4bd82','385f59'],summer:['9ac9d7','f3e7c4','91b9b6','4f8b83','80a66c','2c625b'],autumn:['d3c3b1','f2dec2','b6aaa0','8c9a85','c7a46f','6e7559'],'late-autumn':['b7c4c9','e7ded0','a7b2b2','7c918c','b9ae94','4e6b65']};
function draw(art){
 const colors=palettes[art.season].map(rgb),pixels=new Uint8Array(width*height*3),wet=/rain|cloudy/.test(art.id),snow=/snow/.test(art.id);
 function pixel(x,y,color,alpha=1){if(x<0||y<0||x>=width||y>=height)return;const at=(Math.floor(y)*width+Math.floor(x))*3;for(let c=0;c<3;c++)pixels[at+c]=Math.round(pixels[at+c]*(1-alpha)+color[c]*alpha);}
 for(let y=0;y<height;y++)for(let x=0;x<width;x++){const at=(y*width+x)*3,t=y/height;for(let c=0;c<3;c++)pixels[at+c]=Math.round(colors[0][c]*(1-t)+colors[1][c]*t);}
 function polygon(points,color,alpha=1){points=points.map(([x,y])=>[x*width,y*height]);for(let y=Math.max(0,Math.floor(Math.min(...points.map(v=>v[1]))));y<=Math.min(height-1,Math.ceil(Math.max(...points.map(v=>v[1]))));y++){const cuts=[];for(let n=0;n<points.length;n++){const a=points[n],b=points[(n+1)%points.length];if((a[1]<=y&&b[1]>y)||(b[1]<=y&&a[1]>y))cuts.push(a[0]+(y-a[1])/(b[1]-a[1])*(b[0]-a[0]));}cuts.sort((a,b)=>a-b);for(let n=0;n<cuts.length;n+=2)for(let x=Math.max(0,Math.ceil(cuts[n]));x<=Math.min(width-1,cuts[n+1]);x++)pixel(x,y,color,alpha);}}
 function ellipse(cx,cy,rx,ry,color,alpha=1){cx*=width;cy*=height;rx*=width;ry*=height;for(let y=Math.max(0,Math.floor(cy-ry));y<=Math.min(height-1,cy+ry);y++){const span=rx*Math.sqrt(Math.max(0,1-((y-cy)/ry)**2));for(let x=Math.max(0,Math.ceil(cx-span));x<=Math.min(width-1,cx+span);x++)pixel(x,y,color,alpha);}}
 const white=[248,249,240];ellipse(.74,.25,.04,.18,[251,223,164],.85);
 if(wet)for(const [x,y,s]of [[.18,.18,1],[.58,.16,.8],[.84,.23,1.2]]){ellipse(x,y,.09*s,.05,white,.7);ellipse(x+.03,y-.02,.055*s,.08,white,.7);ellipse(x-.035,y,.05*s,.065,white,.7);}
 polygon([[0,.65],[.1,.48],[.21,.6],[.35,.25],[.45,.5],[.58,.21],[.71,.59],[.82,.35],[1,.65],[1,1],[0,1]],colors[2]);
 polygon([[.35,.25],[.31,.36],[.34,.34],[.37,.4],[.39,.34]],white,.75);polygon([[.58,.21],[.53,.36],[.57,.31],[.61,.38],[.63,.34]],white,.8);
 function hill(base,amplitude,phase,color){const points=Array.from({length:81},(_,n)=>[n/80,base+Math.sin(n/80*9+phase)*amplitude]);polygon([...points,[1,1],[0,1]],color);}
 hill(.65,.11,1,colors[3]);
 for(let n=0;n<50;n++){const x=n/49,y=.61+Math.sin(x*9+1)*.11,h=.06+(n%5)*.015;polygon([[x-.008,y],[x,y-h],[x+.008,y]],colors[5],.65);}
 hill(.86,.12,3,colors[4]);
 if(snow)hill(.82,.12,3,white);
 for(const [x,h]of [[.09,.36],[.13,.23],[.87,.34],[.92,.24],[.05,.2]]){const y=.89+Math.sin(x*8)*.04;polygon([[x-.002,y],[x-.002,y-h],[x+.002,y-h],[x+.002,y]],colors[5]);for(let level=0;level<4;level++){const top=y-h+level*h*.18,w=.012+level*.007;polygon([[x-w,top+h*.32],[x,top],[x+w,top+h*.32]],colors[5]);if(snow)polygon([[x-w*.65,top+h*.22],[x,top],[x+w*.65,top+h*.22]],white);}}
 if(snow)for(let n=0;n<72;n++)ellipse(((n*137)%1799)/1800,((n*83)%340)/400,.0008,.0035,white,.85);
 if(art.season==='spring')for(let n=0;n<65;n++)ellipse(((n*193)%1800)/1800,.8+((n*37)%70)/400,.0008,.003,[247,220,168]);
 const scan=Buffer.alloc((width*3+1)*height);for(let y=0;y<height;y++)Buffer.from(pixels.buffer,y*width*3,width*3).copy(scan,y*(width*3+1)+1);
 const crc=data=>{let result=0xffffffff;for(const byte of data){result^=byte;for(let n=0;n<8;n++)result=(result>>>1)^((result&1)?0xedb88320:0);}return (result^0xffffffff)>>>0;};
 const chunk=(kind,data)=>{const name=Buffer.from(kind),length=Buffer.alloc(4),checksum=Buffer.alloc(4);length.writeUInt32BE(data.length);checksum.writeUInt32BE(crc(Buffer.concat([name,data])));return Buffer.concat([length,name,data,checksum]);};
 const header=Buffer.alloc(13);header.writeUInt32BE(width);header.writeUInt32BE(height,4);header[8]=8;header[9]=2;
 return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',header),chunk('IDAT',deflateSync(scan)),chunk('IEND',Buffer.alloc(0))]);
}
await mkdir(root,{recursive:true});
for(const art of seasonalCatalog){const data=draw(art),file=new URL(art.file,root);if(process.argv.includes('--check')){if(!data.equals(await readFile(file)))throw new Error('Stale seasonal illustration: '+fileURLToPath(file));}else await writeFile(file,data);}
console.log('Seasonal illustrations '+(process.argv.includes('--check')?'verified':'generated')+': '+seasonalCatalog.length);
