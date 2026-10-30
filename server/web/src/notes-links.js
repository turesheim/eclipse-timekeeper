/** Resolve Markdown paths inside a logical collection, never the host filesystem. */
export function relativeNotePath(origin,reference,{kind,assetsFolder='assets',legacyAssets=true}={}){
  if(typeof reference!=='string'||!reference||reference.startsWith('/')||reference.includes('\\')||/[\u0000-\u001f\u007f]/.test(reference)||/^[a-z][a-z0-9+.-]*:/i.test(reference)||reference.startsWith('//'))return null;
  const raw=reference.split('#')[0];if(!raw||raw.includes('?'))return null;
  let parts;
  try{parts=raw.split('/').map(part=>decodeURIComponent(part));}catch{return null;}
  if(parts.some(part=>part.includes('/')||part.includes('\\')||/[\u0000-\u001f\u007f]/.test(part)))return null;
  const legacy=legacyAssets&&['DAY','WEEK','EVENT'].includes(kind)&&parts[0]==='assets';
  const result=(legacy?assetsFolder:origin.split('/').slice(0,-1).join('/')).split('/').filter(Boolean);
  for(const part of legacy?parts.slice(1):parts){if(part==='..'){if(!result.length)return null;result.pop();}else if(part&&part!=='.')result.push(part);}
  return result.length?result.join('/').normalize('NFC'):null;
}
export function rasterType(bytes){
  if(bytes.length>=8&&[137,80,78,71,13,10,26,10].every((value,index)=>bytes[index]===value))return 'image/png';
  if(bytes.length>=3&&bytes[0]===255&&bytes[1]===216&&bytes[2]===255)return 'image/jpeg';
  const text=String.fromCharCode(...bytes.slice(0,12));
  if(text.startsWith('GIF87a')||text.startsWith('GIF89a'))return 'image/gif';
  if(text.startsWith('RIFF')&&text.slice(8,12)==='WEBP')return 'image/webp';
  return null;
}
