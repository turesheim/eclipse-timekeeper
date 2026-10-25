import {build} from 'esbuild';
import {readFile,writeFile,mkdir,readdir} from 'node:fs/promises';
import {dirname,join,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {gzipSync} from 'node:zlib';
const root=fileURLToPath(new URL('../',import.meta.url)),check=process.argv.includes('--check');
const result=await build({absWorkingDir:root,entryPoints:['editor/milkdown.js'],bundle:true,format:'esm',target:['es2022'],minify:true,legalComments:'eof',write:false,outdir:'src/assets/milkdown',metafile:true});
const files=result.outputFiles.map(file=>[file.path,file.contents]);
// Follow the actual bundle graph, not every development dependency. Ship complete
// license texts for each included package and fail if an unknown license appears.
const packages=new Map();
for(const input of Object.keys(result.metafile.inputs)){
  if(!input.includes('node_modules/'))continue;
  let directory=dirname(resolve(root,input));
  let found=false;
  while(dirname(directory)!==directory){
    try{const pkg=JSON.parse(await readFile(join(directory,'package.json'),'utf8'));if(pkg.name&&pkg.version){packages.set(pkg.name+'@'+pkg.version,{...pkg,directory});found=true;break;}}catch{}
    directory=dirname(directory);
  }
  if(!found)throw new Error('Missing package metadata: '+input);
}
let notices='Complete licenses for packages included in the locally bundled Markdown editor.\n';
const inventory=[];
for(const pkg of [...packages.values()].sort((a,b)=>a.name.localeCompare(b.name,'en'))){
  if(!['MIT','ISC'].includes(pkg.license))throw new Error(`Review license for ${pkg.name}: ${pkg.license}`);
  const licenses=(await readdir(pkg.directory)).filter(file=>/^(licen[cs]e|copying)(\.|$)/i.test(file)).sort();
  if(!licenses.length)throw new Error('Missing license: '+pkg.name);
  inventory.push({name:pkg.name,version:pkg.version,license:pkg.license});
  for(const license of licenses)notices+=`\n\n${pkg.name}@${pkg.version} / ${license}\n\n`+await readFile(join(pkg.directory,license),'utf8');
}
const target=join(root,'src/assets/milkdown');
files.push([join(target,'LICENSE'),await readFile(join(root,'node_modules/@milkdown/kit/LICENSE'))],[join(target,'THIRD-PARTY-LICENSES.txt'),notices],[join(target,'dependencies.json'),JSON.stringify(inventory,null,2)+'\n']);
const sizes=result.outputFiles.map(file=>({file:file.path.split('/').at(-1),bytes:file.contents.length,gzipBytes:gzipSync(file.contents).length}));
files.push([join(target,'sizes.json'),JSON.stringify(sizes,null,2)+'\n']);
for(const [path,content] of files){
  if(check){if(!Buffer.from(await readFile(path)).equals(Buffer.from(content)))throw new Error('Regenerate Markdown editor: '+path);}
  else{await mkdir(dirname(path),{recursive:true});await writeFile(path,content);}
}
console.log(`${check?'Verified':'Bundled'} Markdown editor: ${JSON.stringify(sizes)}; ${packages.size} dependency licenses.`);
