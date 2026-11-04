// Keep the HTML boundary local, pinned and reproducible, including its license.
import {build} from 'esbuild';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const root=new URL('../',import.meta.url),check=process.argv.includes('--check');
const pkg=JSON.parse(await readFile(new URL('node_modules/dompurify/package.json',root)));
const manifest=JSON.parse(await readFile(new URL('package.json',root)));
if(pkg.version!=='3.4.17'||manifest.devDependencies.dompurify!==pkg.version)throw new Error('Review the pinned HTML sanitizer version');
const result=await build({absWorkingDir:fileURLToPath(root),entryPoints:['dompurify'],bundle:true,format:'esm',platform:'browser',target:['es2022'],minify:true,legalComments:'eof',write:false});
const files=[['purify.js',result.outputFiles[0].contents],['LICENSE',await readFile(new URL('node_modules/dompurify/LICENSE',root))]];
await mkdir(new URL('src/assets/dompurify/',root),{recursive:true});
for(const [name,bytes] of files){const path=new URL('src/assets/dompurify/'+name,root);if(check){if(!Buffer.from(await readFile(path)).equals(Buffer.from(bytes)))throw new Error('Regenerate HTML sanitizer: '+name);}else await writeFile(path,bytes);}
console.log(`${check?'Verified':'Bundled'} DOMPurify ${pkg.version}.`);
