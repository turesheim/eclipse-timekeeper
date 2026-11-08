import {build} from 'esbuild';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {gzipSync} from 'node:zlib';
const root=new URL('../',import.meta.url),check=process.argv.includes('--check');
const manifest=JSON.parse(await readFile(new URL('package.json',root))),upstream=JSON.parse(await readFile(new URL('node_modules/@plantuml/core/package.json',root)));
if(upstream.version!=='1.2026.9'||manifest.devDependencies['@plantuml/core']!==upstream.version||upstream.license!=='MIT')throw new Error('Review the pinned official MIT PlantUML distribution before updating it');
// Normalize line endings only; preserve upstream code and complete license text.
const engine=Buffer.from((await readFile(new URL('node_modules/@plantuml/core/plantuml.js',root),'utf8')).replace(/\r\n/g,'\n')),license=Buffer.from((await readFile(new URL('node_modules/@plantuml/core/LICENSE',root),'utf8')).replace(/\r\n/g,'\n'));
const bundled=await build({absWorkingDir:root.pathname,entryPoints:['editor/plantuml-worker.js'],bundle:true,format:'esm',target:['es2022'],external:['./plantuml-engine.js'],write:false,minify:true,legalComments:'eof',outfile:'worker.js'});
const worker=bundled.outputFiles[0].contents,inventory=[{name:upstream.name,version:upstream.version,license:upstream.license}];
const sizes=[{file:'plantuml-engine.js',bytes:engine.length},{file:'worker.js',bytes:worker.length}];
for(const [name,content]of [['plantuml-engine.js',engine],['worker.js',worker],['LICENSE',license],['dependencies.json',JSON.stringify(inventory,null,2)+'\n'],['sizes.json',JSON.stringify(sizes,null,2)+'\n']]){
  const path=new URL('src/assets/plantuml/'+name,root);
  if(check){if(!Buffer.from(await readFile(path)).equals(Buffer.from(content)))throw new Error('Regenerate PlantUML assets: '+name);}
  else{await mkdir(new URL('.',path),{recursive:true});await writeFile(path,content);}
}
console.log(`${check?'Verified':'Bundled'} official PlantUML ${upstream.version}: ${JSON.stringify(sizes.map(item=>({...item,gzipBytes:gzipSync(item.file==='worker.js'?worker:engine).length})))}`);
