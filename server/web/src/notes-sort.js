import {noteTitle} from './note-title.js';

export const noteSortOrders=[
  {value:'name-asc',field:'name',direction:1,label:'notes.sort_name_asc',icon:'sort-alpha-down'},
  {value:'name-desc',field:'name',direction:-1,label:'notes.sort_name_desc',icon:'sort-alpha-up'},
  {value:'created-asc',field:'createdAt',direction:1,label:'notes.sort_created_asc',icon:'sort-numeric-down'},
  {value:'created-desc',field:'createdAt',direction:-1,label:'notes.sort_created_desc',icon:'sort-numeric-up'},
  {value:'modified-asc',field:'modifiedAt',direction:1,label:'notes.sort_modified_asc',icon:'sort-down-alt'},
  {value:'modified-desc',field:'modifiedAt',direction:-1,label:'notes.sort_modified_desc',icon:'sort-down'}
];

/** Sort a complete folder/search result; unknown dates follow known dates. */
export function sortedNotes(entries,order='name-asc',locale='en-GB'){
  const sort=noteSortOrders.find(value=>value.value===order)||noteSortOrders[0];
  const names=new Intl.Collator(locale,{numeric:true,sensitivity:'base'});
  const name=(a,b)=>names.compare(noteTitle(a,a.path),noteTitle(b,b.path))||names.compare(a.path,b.path)||a.id.localeCompare(b.id);
  return [...entries].sort((a,b)=>{
    const folders=Number(b.kind==='FOLDER')-Number(a.kind==='FOLDER');if(folders)return folders;
    if(sort.field==='name')return name(a,b)*sort.direction;
    const left=Date.parse(a[sort.field]),right=Date.parse(b[sort.field]);
    if(Number.isFinite(left)!==Number.isFinite(right))return Number.isFinite(left)?-1:1;
    return (Number.isFinite(left)?(left-right)*sort.direction:0)||name(a,b);
  });
}
