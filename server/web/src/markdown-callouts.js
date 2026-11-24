export const toolbarCalloutTypes = ['note', 'tip', 'important', 'warning', 'caution'];

export const calloutIcons = new Map([
  ['note', 'pencil-square'],
  ['abstract', 'card-text'],
  ['info', 'info-circle'],
  ['todo', 'list-check'],
  ['tip', 'lightbulb'],
  ['important', 'exclamation-circle'],
  ['success', 'check-circle'],
  ['question', 'question-circle'],
  ['warning', 'exclamation-triangle'],
  ['caution', 'exclamation-diamond'],
  ['failure', 'x-circle'],
  ['danger', 'x-octagon'],
  ['bug', 'bug'],
  ['example', 'list-ul'],
  ['quote', 'quote']
]);

// Obsidian’s built-in aliases share the type presentation. The authored marker
// is retained in the document and always serialized using its original spelling.
export const calloutAliases = new Map([
  ['summary', 'abstract'], ['tldr', 'abstract'],
  ['hint', 'tip'],
  ['check', 'success'], ['done', 'success'],
  ['help', 'question'], ['faq', 'question'],
  ['attention', 'warning'],
  ['fail', 'failure'], ['missing', 'failure'],
  ['error', 'danger'],
  ['cite', 'quote']
]);
