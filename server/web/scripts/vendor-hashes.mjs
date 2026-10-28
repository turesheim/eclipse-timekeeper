// Reproducible, local-only incremental SHA-256 for large encrypted uploads.
import {build} from 'esbuild';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
const root=new URL('../',import.meta.url),check=process.argv.includes('--check');
const pkg=JSON.parse(await readFile(new URL('node_modules/@noble/hashes/package.json',root)));
const manifest=JSON.parse(await readFile(new URL('package.json',root)));
if(pkg.version!=='2.4.0'||manifest.devDependencies[pkg.name]!==pkg.version)throw new Error('Review the pinned hashing version');
const result=await build({absWorkingDir:root.pathname,stdin:{contents:"export {sha256} from '@noble/hashes/sha2.js';",resolveDir:root.pathname},bundle:true,format:'esm',platform:'browser',target:['es2022'],minify:true,legalComments:'eof',write:false});
const files=[['sha256.js',result.outputFiles[0].contents],['LICENSE',await readFile(new URL('node_modules/@noble/hashes/LICENSE',root))],['README.txt',`@noble/hashes ${pkg.version}\nhttps://github.com/paulmillr/noble-hashes\nMIT license. SHA-256 only, bundled with esbuild by scripts/vendor-hashes.mjs.\nNo network requests or runtime package loading.\n`]];
await mkdir(new URL('src/assets/noble-hashes/',root),{recursive:true});
for(const [name,bytes] of files){const path=new URL('src/assets/noble-hashes/'+name,root);if(check){if(!Buffer.from(await readFile(path)).equals(Buffer.from(bytes)))throw new Error('Regenerate hashing assets: '+name);}else await writeFile(path,bytes);}
console.log(`${check?'Verified':'Bundled'} incremental SHA-256 ${pkg.version}.`);
