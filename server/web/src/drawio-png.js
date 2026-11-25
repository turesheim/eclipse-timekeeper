// Editable PNG data remains opaque. Never decode diagram XML or re-encode pixels.
const signature=[137,80,78,71,13,10,26,10],ascii=bytes=>String.fromCharCode(...bytes);
const crcTable=Uint32Array.from({length:256},(_,value)=>{for(let bit=0;bit<8;bit++)value=value&1?0xedb88320^(value>>>1):value>>>1;return value>>>0;});
const crc32=bytes=>{let crc=0xffffffff;for(const byte of bytes)crc=crcTable[(crc^byte)&255]^(crc>>>8);return (crc^0xffffffff)>>>0;};
const descriptionHeader=new TextEncoder().encode('Dagsverk Description\0\0\0\0\0');
const descriptionChunk=(type,chunk)=>type==='iTXt'&&chunk.length>=descriptionHeader.length
  &&descriptionHeader.every((byte,index)=>chunk[index]===byte);
const maxPngSize=5_000_000;
const plainDiagram=bytes=>{try{return /^\s*(?:<\?xml[^>]*>\s*)?<(?:mxfile|mxGraphModel)(?:\s|>)/.test(decodeURIComponent(new TextDecoder().decode(bytes)));}catch{return false;}};

/** Bounded PNG structure/CRC checks, plus draw.io's text-chunk marker. */
export function drawioPngInfo(bytes){
  if(!(bytes instanceof Uint8Array)||bytes.length<45||bytes.length>maxPngSize||signature.some((byte,index)=>bytes[index]!==byte))return null;
  const data=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);let at=8,width,height,description="",pixels=false,editable=false,count=0;
  while(at+12<=bytes.length&&++count<=2048){
    const length=data.getUint32(at),end=at+8+length;if(end+4>bytes.length)return null;
    const type=ascii(bytes.subarray(at+4,at+8)),chunk=bytes.subarray(at+8,end);
    if(!/^[A-Za-z]{4}$/.test(type)||crc32(bytes.subarray(at+4,end))!==data.getUint32(end))return null;
    if(count===1){if(type!=='IHDR'||length!==13)return null;width=data.getUint32(at+8);height=data.getUint32(at+12);if(!width||!height)return null;}
    else if(type==='IHDR')return null;
    if(type==='IDAT')pixels=true;
    if(descriptionChunk(type,chunk)&&chunk.length<=descriptionHeader.length+1020){
      description=new TextDecoder().decode(chunk.subarray(descriptionHeader.length)).trim().slice(0,255);
    }
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
    if(type==='IEND')return length===0&&pixels&&end+4===bytes.length?{width,height,editable,description}:null;
    at=end+4;
  }
  return null;
}

/** Store the shared caption alongside the editable drawing without re-encoding it. */
export function withDrawioDescription(bytes,description){
  if(!drawioPngInfo(bytes)?.editable)return null;
  const text=new TextEncoder().encode(description.trim().slice(0,255));
  const data=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength),parts=[bytes.subarray(0,8)];
  let size=8;
  for(let at=8;at<bytes.length;){
    const length=data.getUint32(at),end=at+length+12,type=ascii(bytes.subarray(at+4,at+8));
    if(type==='IEND'&&text.length){
      const chunk=new Uint8Array(12+descriptionHeader.length+text.length);
      const header=new DataView(chunk.buffer);
      header.setUint32(0,chunk.length-12);
      chunk.set([105,84,88,116],4);
      chunk.set(descriptionHeader,8);
      chunk.set(text,8+descriptionHeader.length);
      header.setUint32(chunk.length-4,crc32(chunk.subarray(4,-4)));
      parts.push(chunk);size+=chunk.length;
    }
    if(!descriptionChunk(type,bytes.subarray(at+8,end-4))){
      parts.push(bytes.subarray(at,end));size+=end-at;
    }
    at=end;
  }
  if(size>maxPngSize)return null;
  const output=new Uint8Array(size);
  let offset=0;
  for(const part of parts){output.set(part,offset);offset+=part.length;}
  return output;
}

export const samePngBytes=(a,b)=>a.length===b.length&&a.every((value,index)=>value===b[index]);
