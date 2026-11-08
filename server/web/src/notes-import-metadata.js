import {fileSha256} from './file-sha256.js';
import {t} from './i18n.js';

export const NOTES_METADATA_FORMAT='timekeeper-notes-v1';
export const notesMetadataName=name=>/^timekeeper-notes-metadata(?:-[a-f0-9-]{36})?\.json$/.test(name);
const key=path=>path.toUpperCase().toLowerCase().normalize('NFC');
const validDate=value=>{
  if(typeof value!=='string'||value.length>40)return false;
  const match=/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.exec(value);if(!match)return false;
  const [,year,month,day,hour,minute,second]=match.map(Number),leap=year%4===0&&(year%100!==0||year%400===0);
  return year>0&&month>=1&&month<=12&&day>=1&&day<=[31,leap?29:28,31,30,31,30,31,31,30,31,30,31][month-1]&&hour<24&&minute<60&&second<60&&Number.isFinite(Date.parse(value))&&new Date(value).getUTCFullYear()>=1&&new Date(value).getUTCFullYear()<=9999;
};
const validPath=path=>typeof path==='string'&&path===path.normalize('NFC')&&path.length>0&&new TextEncoder().encode(path).length<=1024&&path.split('/').length<=32&&!path.split('/').some(part=>!part||part==='.'||part==='..'||/[<>:"\\|?*\u0000-\u001f\u007f]/.test(part)||/[. ]$/.test(part)||new TextEncoder().encode(part).length>255||/^(CON|PRN|AUX|NUL|CONIN\$|CONOUT\$|COM[1-9¹²³]|LPT[1-9¹²³])$/i.test(part.split('.')[0].trimEnd()));
export function parseNotesMetadata(value){
  const invalid=()=>{throw new Error(t('notes.import_metadata_invalid'));};
  if(!value||value.format!==NOTES_METADATA_FORMAT||!Array.isArray(value.entries)||value.entries.length>250000||Object.keys(value).some(name=>!['format','entries'].includes(name)))invalid();
  const entries=new Map();
  for(const item of value.entries){
    if(!item||!validPath(item.path)||!['FILE','FOLDER'].includes(item.kind)||entries.has(key(item.path))||Object.keys(item).some(name=>!['path','kind','createdAt','modifiedAt','createdAtSource','bytes','sha256'].includes(name)))invalid();
    for(const name of ['createdAt','modifiedAt'])if(name in item&&!validDate(item[name]))invalid();
    if('createdAtSource' in item&&(!item.createdAt||!['ORIGINAL','ADDED','UNKNOWN'].includes(item.createdAtSource)))invalid();
    if(item.kind==='FILE'&&(!Number.isSafeInteger(item.bytes)||item.bytes<0||!/^[a-f0-9]{64}$/.test(item.sha256)))invalid();
    if(item.kind==='FOLDER'&&('bytes' in item||'sha256' in item))invalid();
    entries.set(key(item.path),{...item});
  }
  for(const item of entries.values()){
    const parts=item.path.split('/');parts.pop();while(parts.length){if(entries.get(key(parts.join('/')))?.kind==='FILE')invalid();parts.pop();}
  }
  return entries;
}
/** Metadata has no authority beyond dates on files explicitly selected by the user. */
export async function readNotesMetadata(files){
  const candidates=files.filter(file=>{const parts=file.webkitRelativePath.split('/');return parts.length===2&&notesMetadataName(parts[1]);});
  if(!candidates.length)return null;
  const valid=[];
  for(const file of candidates){
    try{if(file.size>64*1024*1024)throw new Error();const value=JSON.parse(await file.text());valid.push({file,entries:parseNotesMetadata(value)});}catch{/* A matching source filename can itself be listed by the real manifest. */}
  }
  const roots=valid.filter(candidate=>!valid.some(other=>other!==candidate&&other.entries.has(key(candidate.file.name))));
  if(roots.length!==1)throw new Error(t('notes.import_metadata_invalid'));
  for(const file of files){if(file===roots[0].file)continue;const entry=roots[0].entries.get(key(file.webkitRelativePath.split('/').slice(1).join('/')));if(entry&&entry.kind!=='FILE')throw new Error(t('notes.import_metadata_invalid'));}
  return roots[0];
}
export function metadataEntry(metadata,path){return metadata?.entries.get(key(path));}
export async function importedFileDates(file,metadata,options={}){
  const dates={};if(Number.isFinite(file.lastModified))dates.modifiedAt=new Date(file.lastModified).toISOString();
  if(!metadata)return {dates,changed:false};
  if(metadata.createdAt){dates.createdAt=metadata.createdAt;dates.createdAtSource=metadata.createdAtSource||'UNKNOWN';}
  const unchanged=file.size===metadata.bytes&&await fileSha256(file,options)===metadata.sha256;
  if(unchanged&&metadata.modifiedAt)dates.modifiedAt=metadata.modifiedAt;
  return {dates,changed:!unchanged};
}
