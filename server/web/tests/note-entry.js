/** Paths identify files even when an event supplies their visible title. */
export function noteEntry(root,path){
  const literal=path.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
  return root.getByRole('button',{name:new RegExp('(?:^| · )'+literal+'$')});
}
