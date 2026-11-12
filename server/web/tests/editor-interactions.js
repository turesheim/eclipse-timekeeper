import {expect,test} from '@playwright/test';

export async function noteActions(page,scope){
  const trigger=scope.getByRole('button',{name:'Note tools',exact:true});
  if(await trigger.getAttribute('aria-expanded')!=='true')await trigger.click();
  const menu=page.getByRole('menu',{name:'Note tools',exact:true});await expect(menu).toBeVisible();return menu;
}
export async function clickNoteAction(page,scope,name){
  const id=await scope.getAttribute('id');
  if(['today-note-editor','notes-panel'].includes(id)){
    const action=page.locator(id==='notes-panel'?'#notes-main-actions':'#today-main-actions').getByRole('button',{name,exact:true});
    if(await action.count()){await action.click();return;}
  }
  await(await noteActions(page,scope)).getByRole('menuitem',{name,exact:true}).click();
}
export async function notesSource(page){
  await page.locator('#notes-panel button[aria-controls="notes-body"]').click();
  const body=page.locator('#notes-body');await expect(body).toBeVisible();return body;
}

/** Flush deterministically via the supported shortcut; autosave timing has its own scenarios. */
export async function saveMarkdown(scope,{wait=true,close=false}={}){
  await scope.getByRole('button',{name:/^(Formatted|Formatert)$/,exact:true}).press('ControlOrMeta+s');
  if(wait)await expect(scope.locator('.dialog-actions > [role=status],.notes-footer > [role=status]')).toHaveText(/^(Saved|Lagret)$/);
  if(close)await scope.getByRole('button',{name:/^(Close|Lukk)$/,exact:true}).click();
}


/** Select the paragraph end explicitly: End has different native macOS semantics. */
export async function appendEditorText(paragraph,text){
  await expect(paragraph).toBeVisible();
  await expect.poll(()=>paragraph.evaluate(node=>node.isContentEditable&&!node.closest('[inert]')),
    {message:'The target paragraph must be writable before typing'}).toBe(true);
  await paragraph.evaluate(node=>{
    node.closest('[contenteditable="true"]').focus();
    const range=document.createRange();range.selectNodeContents(node);range.collapse(false);
    const selection=getSelection();selection.removeAllRanges();selection.addRange(range);
    document.dispatchEvent(new Event('selectionchange'));
  });
  await paragraph.page().keyboard.insertText(text);
  await expect(paragraph).toContainText(text);
}

/** Own native dialogs for an action; reject missing, extra or wrong-type prompts. */
export function nativeConfirmations(page){
  let pending=null;const unexpected=[];
  const listener=async dialog=>{
    if(!pending){unexpected.push('Unexpected '+dialog.type()+' dialog outside a confirmation action');await dialog.dismiss();return;}
    const decision=pending.answers.shift();
    if(dialog.type()!=='confirm'||decision===undefined)pending.errors.push('Unexpected '+dialog.type()+' prompt: '+dialog.message());
    await(decision===true?dialog.accept():dialog.dismiss());
  };
  page.on('dialog',listener);
  return {
    async run(name,answers,action){return test.step(name,async()=>{
      expect(unexpected).toEqual([]);expect(pending,'Confirmation actions cannot overlap').toBeNull();pending={answers:[...answers],errors:[]};
      try{await action();expect(pending.errors).toEqual([]);expect(pending.answers,'Every expected confirmation must occur').toEqual([]);}finally{pending=null;}
    });},
    dispose(){page.off('dialog',listener);expect(unexpected).toEqual([]);}
  };
}
