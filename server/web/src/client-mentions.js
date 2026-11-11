/** Match the prose assignment spelling understood by the note-task importer. */
export function clientMentionRanges(text){
  return [...text.matchAll(/(?<!\S)@([\p{L}\p{N}][\p{L}\p{N}_:.-]*)(?=$|\s|[,;!?])/gu)].map(match=>({from:match.index,to:match.index+match[0].length}));
}
