/** Pinned, locally bundled syntax support; unknown fence names stay plain text. */
export const editorLanguages=['text','javascript','typescript','java','python','json','sql','html','css','shell','yaml','markdown'];
export const highlightedLanguages=new Set([...editorLanguages.filter(name=>name!=='text'),'js','jsx','ts','tsx','py','htm','bash','sh','zsh','yml','md']);
