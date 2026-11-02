import {addRowBefore,addRowAfter,addColumnBefore,addColumnAfter,deleteRow,deleteColumn,deleteTable,isInTable,selectedRect,moveTableRow,moveTableColumn} from '@milkdown/kit/prose/tables';
import {sinkListItem,liftListItem} from '@milkdown/kit/prose/schema-list';
export function structuredCommand(view,name){
  const state=view.state,dispatch=tr=>view.dispatch(tr);
  const actions={row_before:addRowBefore,row_after:addRowAfter,column_before:addColumnBefore,column_after:addColumnAfter,delete_row:deleteRow,delete_column:deleteColumn,delete_table:deleteTable,indent:sinkListItem(state.schema.nodes.list_item),outdent:liftListItem(state.schema.nodes.list_item)};
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
