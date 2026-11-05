/** Paths identify files even when a heading or event supplies their visible title. */
export function noteEntry(root,path){
  const literal=path.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
  return root.getByRole('button',{name:new RegExp('(?:^| · )'+literal+'$')});
}
