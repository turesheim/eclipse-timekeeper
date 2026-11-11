import {test} from 'node:test';import assert from 'node:assert/strict';
import {wikiReference,wikiCandidates,wikiCreationPath,headingAnchor} from '../src/wiki-links.js';
test('wiki targets remain vault-local and preserve aliases and image dimensions',()=>{
  for(const value of ['https://example.org/x','../secret','/root','a\\b','a\nb'])assert.equal(wikiReference(value),null);
  assert.deepEqual(wikiReference('Notes/Øving#Choices|caption'),{path:'Notes/Øving',heading:'Choices',label:'caption',width:null,height:null});
  assert.equal(wikiReference('image.png|320x200',true).height,200);assert.equal(wikiReference('#Local').heading,'Local');assert.equal(headingAnchor(' Øving & Choices '),'øving-choices');
});
test('wiki resolution reports ambiguities and uses portable case folding',()=>{
  const entries=[{path:'Notes/Øving.md',kind:'MARKDOWN'},{path:'Other/Øving.md',kind:'MARKDOWN'},{path:'Deleted.md',kind:'MARKDOWN',deleted:true}];
  assert.equal(wikiCandidates(entries,'Øving','Root.md').length,2);assert.equal(wikiCandidates(entries,'notes/øVING','Root.md')[0].path,'Notes/Øving.md');assert.equal(wikiCandidates(entries,'#Local','Notes/Øving.md')[0].path,'Notes/Øving.md');assert.equal(wikiCandidates(entries,'Deleted').length,0);
});
test('bare wikilinks retain heading aliases while paths and filenames keep priority',()=>{
  const titled={path:'Notes/Imported investigation.md',kind:'MARKDOWN',title:'Imported investigation',heading:'CVE-2026-111943'},other={path:'Notes/Other.md',kind:'MARKDOWN',title:'Øving :smile:'};
  assert.deepEqual(wikiCandidates([titled],'cve-2026-111943#Analysis|Details','journal/2026-11-09.md'),[titled]);
  assert.deepEqual(wikiCandidates([other],'ØVING 😄'),[other]);
  const named={path:'Notes/CVE-2026-111943.md',kind:'MARKDOWN',title:'Another title'};
  assert.deepEqual(wikiCandidates([titled,named],'CVE-2026-111943'),[named]);
  assert.deepEqual(wikiCandidates([titled],'Notes/CVE-2026-111943'),[]);
  assert.deepEqual(wikiCandidates([titled,{...titled,path:'Other/Investigation.md'}],'CVE-2026-111943').length,2);
  assert.deepEqual(wikiCandidates([{...titled,deleted:true}],'CVE-2026-111943'),[]);
});

test('new wikilink targets use a predictable safe Markdown path',()=>{
  assert.equal(wikiCreationPath('New note#Decisions|caption','Notes/Source.md'),'Notes/New note.md');
  assert.equal(wikiCreationPath('Research/Øving.MD','Notes/Source.md'),'Research/Øving.MD');
  assert.equal(wikiCreationPath('Root','Source.md'),'Root.md');
  for(const value of ['#Heading','../secret','/root','https://example.org','a//b','./note','a/../b'])assert.equal(wikiCreationPath(value,'Notes/Source.md'),null);
});
