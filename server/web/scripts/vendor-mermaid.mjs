import {build} from 'esbuild';
import {readFile,writeFile,mkdir,readdir} from 'node:fs/promises';
import {dirname,join,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {gzipSync} from 'node:zlib';

const root=fileURLToPath(new URL('../',import.meta.url)),check=process.argv.includes('--check');
const result=await build({absWorkingDir:root,entryPoints:['editor/mermaid.js'],bundle:true,format:'esm',target:['es2022'],minify:true,legalComments:'eof',write:false,outfile:'src/assets/mermaid/mermaid.js',metafile:true});
const packages=new Map();
for(const input of Object.keys(result.metafile.inputs)){
  if(!input.includes('node_modules/'))continue;
  let directory=dirname(resolve(root,input)),found=false;
  while(dirname(directory)!==directory){
    try{const pkg=JSON.parse(await readFile(join(directory,'package.json'),'utf8'));if(pkg.name&&pkg.version){packages.set(pkg.name+'@'+pkg.version,{...pkg,directory});found=true;break;}}catch{}
    directory=dirname(directory);
  }
  if(!found)throw new Error('Missing package metadata: '+input);
}
let notices='Complete licenses for packages included in the locally bundled Mermaid renderer.\n';
const inventory=[];
for(const pkg of [...packages.values()].sort((a,b)=>a.name.localeCompare(b.name,'en'))){
  const license=pkg.name==='khroma'&&!pkg.license?'MIT':pkg.name==='dompurify'?'Apache-2.0':pkg.license;
  if(!['MIT','ISC','BSD-3-Clause','Apache-2.0','EPL-2.0','Unlicense'].includes(license))throw new Error(`Review license for ${pkg.name}: ${pkg.license}`);
  const files=(await readdir(pkg.directory)).filter(file=>/^(licen[cs]e|copying)(\.|$)/i.test(file)).sort();
  if(!files.length)throw new Error('Missing license: '+pkg.name);
  inventory.push({name:pkg.name,version:pkg.version,license});
  for(const file of files)notices+=`\n\n${pkg.name}@${pkg.version} / ${file}\n\n`+await readFile(join(pkg.directory,file),'utf8');
}
const target=join(root,'src/assets/mermaid'),output=result.outputFiles[0];
const sizes={file:'mermaid.js',bytes:output.contents.length};
const files=[[join(target,'mermaid.js'),output.contents],[join(target,'LICENSE'),await readFile(join(root,'node_modules/mermaid/LICENSE'))],[join(target,'THIRD-PARTY-LICENSES.txt'),notices],[join(target,'dependencies.json'),JSON.stringify(inventory,null,2)+'\n'],[join(target,'sizes.json'),JSON.stringify([sizes],null,2)+'\n']];
for(const [path,content] of files){
  if(check){if(!Buffer.from(await readFile(path)).equals(Buffer.from(content)))throw new Error('Regenerate Mermaid renderer: '+path);}
  else{await mkdir(dirname(path),{recursive:true});await writeFile(path,content);}
}
console.log(`${check?'Verified':'Bundled'} Mermaid renderer: ${JSON.stringify({...sizes,gzipBytes:gzipSync(output.contents).length})}; ${packages.size} dependency licenses.`);
