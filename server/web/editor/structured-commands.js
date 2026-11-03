import {TextSelection} from '@milkdown/kit/prose/state';
import {CellSelection,TableMap,__pastedCells,__clipCells} from '@milkdown/kit/prose/tables';
import {addRowBefore,addRowAfter,addColumnBefore,addColumnAfter,deleteRow,deleteColumn,deleteTable,isInTable,selectedRect,moveTableRow,moveTableColumn} from '@milkdown/kit/prose/tables';
import {sinkListItem,liftListItem} from '@milkdown/kit/prose/schema-list';
export function structuredCommand(view,name,{singleCell=false}={}){
  if(!singleCell||!isInTable(view.state))return applyStructuredCommand(view,name);
  if(view.state.selection instanceof CellSelection)view.dispatch(view.state.tr.setSelection(TextSelection.near(view.state.doc.resolve(view.state.selection.$anchorCell.pos+1))));
  const before=selectedRect(view.state),tablePosition=before.tableStart-1;
  const applied=applyStructuredCommand(view,name);
  if(applied&&name!=='delete_table'){
    const table=view.state.doc.nodeAt(tablePosition);
    if(table?.type.spec.tableRole==='table'){
      const map=TableMap.get(table);
      const row=Math.max(0,Math.min(map.height-1,before.top+({row_after:1,row_up:-1,row_down:1}[name]||0)));
      const column=Math.max(0,Math.min(map.width-1,before.left+({column_after:1,column_left:-1,column_right:1}[name]||0)));
      const cell=tablePosition+1+map.positionAt(row,column,table);
      view.dispatch(view.state.tr.setSelection(TextSelection.near(view.state.doc.resolve(cell+1))));
    }
  }
  return applied;
}
function applyStructuredCommand(view,name){
  const state=view.state,dispatch=tr=>view.dispatch(tr);
  const actions={row_before:addRowBefore,row_after:addRowAfter,column_before:addColumnBefore,column_after:addColumnAfter,delete_row:deleteRow,delete_column:deleteColumn,delete_table:deleteTable,indent:sinkListItem(state.schema.nodes.list_item),outdent:liftListItem(state.schema.nodes.list_item)};
  if(['row_before','delete_row'].includes(name)&&isInTable(state)&&selectedRect(state).top===0)return false;
  if(name in actions){if(!['indent','outdent'].includes(name)&&!isInTable(state))return false;return actions[name](state,dispatch);}
  if(!isInTable(state))return false;
  const rect=selectedRect(state);
  if(name.startsWith('align_')){
    const alignment=name.slice(6);if(!['left','center','right'].includes(alignment))return false;
    let transaction=state.tr;
    rect.table.forEach((row,rowOffset)=>row.forEach((cell,cellOffset,index)=>{if(index>=rect.left&&index<rect.right)transaction=transaction.setNodeMarkup(rect.tableStart+rowOffset+cellOffset+1,undefined,{...cell.attrs,alignment});}));
    dispatch(transaction);return true;
  }
  if(['row_up','row_down'].includes(name)){const to=rect.top+(name==='row_up'?-1:1);if(rect.top===0||to<1||to>=rect.map.height)return false;return moveTableRow({from:rect.top,to})(state,dispatch);}
  if(['column_left','column_right'].includes(name)){const to=rect.left+(name==='column_left'?-1:1);if(to<0||to>=rect.map.width)return false;return moveTableColumn({from:rect.left,to})(state,dispatch);}
  return false;
}

/** Adapt pinned table paste helpers to Milkdown's distinct header/body row types. */
export function pasteTable(view,slice){
  if(!isInTable(view.state))return false;
  let cells=__pastedCells(slice);if(!cells)return false;
  const rect=selectedRect(view.state);
  if(view.state.selection instanceof CellSelection)cells=__clipCells(cells,rect.right-rect.left,rect.bottom-rect.top);
  const schema=view.state.schema,width=Math.max(rect.map.width,rect.left+cells.width),height=Math.max(rect.map.height,rect.top+cells.height,2);
  // GFM has no merged-cell notation. Expand clipboard spans to a plain grid,
  // retaining a merged cell's content at its first coordinate only.
  const pasted=Array.from({length:cells.height},()=>[]);
  cells.rows.forEach((row,index)=>{let column=0;row.forEach(cell=>{while(pasted[index][column])column++;for(let down=0;down<cell.attrs.rowspan;down++)for(let across=0;across<cell.attrs.colspan;across++){if(pasted[index+down])pasted[index+down][column+across]={cell,first:down===0&&across===0};}column+=cell.attrs.colspan;});});
  const rows=Array.from({length:height},(_,row)=>{
    const type=schema.nodes[row===0?'table_header':'table_cell'];
    const content=Array.from({length:width},(_,column)=>{
      const position=row<rect.map.height&&column<rect.map.width?rect.map.map[row*rect.map.width+column]:null;
      const existing=position===null?null:rect.table.nodeAt(position),incoming=row>=rect.top&&column>=rect.left?pasted[row-rect.top]?.[column-rect.left]:null;
      const header=column<rect.map.width?rect.table.nodeAt(rect.map.map[column]):null;
      const chosen=incoming?.cell||existing;
      const duplicate=position!==null&&rect.map.map.indexOf(position)!==row*rect.map.width+column;
      const body=incoming?incoming.first?incoming.cell.content:null:duplicate?null:existing?.content;
      return body?type.create({...chosen.attrs,colspan:1,rowspan:1,colwidth:null,alignment:header?.attrs.alignment||chosen.attrs.alignment},body):type.createAndFill({alignment:header?.attrs.alignment||'left'});
    });return schema.nodes[row===0?'table_header_row':'table_row'].createChecked(null,content);
  });
  const table=rect.table.type.createChecked(rect.table.attrs,rows),transaction=view.state.tr.replaceWith(rect.tableStart-1,rect.tableStart-1+rect.table.nodeSize,table),map=TableMap.get(table);
  transaction.setSelection(CellSelection.create(transaction.doc,rect.tableStart+map.positionAt(rect.top,rect.left,table),rect.tableStart+map.positionAt(rect.top+cells.height-1,rect.left+cells.width-1,table)));
  view.dispatch(transaction.scrollIntoView());return true;
}
