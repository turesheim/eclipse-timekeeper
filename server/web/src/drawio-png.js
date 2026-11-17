// Editable PNG data remains opaque. Never decode diagram XML or re-encode pixels.
const signature=[137,80,78,71,13,10,26,10],ascii=bytes=>String.fromCharCode(...bytes);
const crcTable=Uint32Array.from({length:256},(_,value)=>{for(let bit=0;bit<8;bit++)value=value&1?0xedb88320^(value>>>1):value>>>1;return value>>>0;});
const crc32=bytes=>{let crc=0xffffffff;for(const byte of bytes)crc=crcTable[(crc^byte)&255]^(crc>>>8);return (crc^0xffffffff)>>>0;};
const plainDiagram=bytes=>{try{return /^\s*(?:<\?xml[^>]*>\s*)?<(?:mxfile|mxGraphModel)(?:\s|>)/.test(decodeURIComponent(new TextDecoder().decode(bytes)));}catch{return false;}};

/** Bounded PNG structure/CRC checks, plus draw.io's text-chunk marker. */
export function drawioPngInfo(bytes){
  if(!(bytes instanceof Uint8Array)||bytes.length<45||bytes.length>5_000_000||signature.some((byte,index)=>bytes[index]!==byte))return null;
  const data=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);let at=8,width,height,pixels=false,editable=false,count=0;
  while(at+12<=bytes.length&&++count<=2048){
    const length=data.getUint32(at),end=at+8+length;if(end+4>bytes.length)return null;
    const type=ascii(bytes.subarray(at+4,at+8)),chunk=bytes.subarray(at+8,end);
    if(!/^[A-Za-z]{4}$/.test(type)||crc32(bytes.subarray(at+4,end))!==data.getUint32(end))return null;
    if(count===1){if(type!=='IHDR'||length!==13)return null;width=data.getUint32(at+8);height=data.getUint32(at+12);if(!width||!height)return null;}
    else if(type==='IHDR')return null;
    if(type==='IDAT')pixels=true;
    if(['tEXt','zTXt','iTXt'].includes(type)){
      const zero=chunk.indexOf(0);
      if(zero===6&&ascii(chunk.subarray(0,zero))==='mxfile'){
        let text=chunk.subarray(zero+1);
        if(type==='tEXt')editable||=plainDiagram(text);
        else if(type==='zTXt')editable||=text.length>2&&text[0]===0;
        else if(text.length>4&&[0,1].includes(text[0])&&text[1]===0){
          const compressed=text[0]===1;let start=2;
          for(let part=0;part<2;part++){const next=text.indexOf(0,start);if(next<0){start=text.length;break;}start=next+1;}
          text=text.subarray(start);editable||=text.length>0&&(compressed||plainDiagram(text));
        }
      }
    }
    if(type==='IEND')return length===0&&pixels&&end+4===bytes.length?{width,height,editable}:null;
    at=end+4;
  }
  return null;
}

export const samePngBytes=(a,b)=>a.length===b.length&&a.every((value,index)=>value===b[index]);
