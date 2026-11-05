import {test} from 'node:test';import assert from 'node:assert/strict';
import {wikiReference,wikiCandidates,headingAnchor} from '../src/wiki-links.js';
test('wiki targets remain vault-local and preserve aliases and image dimensions',()=>{
  for(const value of ['https://example.org/x','../secret','/root','a\\b','a\nb'])assert.equal(wikiReference(value),null);
  assert.deepEqual(wikiReference('Notes/Øving#Choices|caption'),{path:'Notes/Øving',heading:'Choices',label:'caption',width:null,height:null});
  assert.equal(wikiReference('image.png|320x200',true).height,200);assert.equal(wikiReference('#Local').heading,'Local');assert.equal(headingAnchor(' Øving & Choices '),'øving-choices');
});
test('wiki resolution reports ambiguities and uses portable case folding',()=>{
  const entries=[{path:'Notes/Øving.md',kind:'MARKDOWN'},{path:'Other/Øving.md',kind:'MARKDOWN'},{path:'Deleted.md',kind:'MARKDOWN',deleted:true}];
  assert.equal(wikiCandidates(entries,'Øving','Root.md').length,2);assert.equal(wikiCandidates(entries,'notes/øVING','Root.md')[0].path,'Notes/Øving.md');assert.equal(wikiCandidates(entries,'#Local','Notes/Øving.md')[0].path,'Notes/Øving.md');assert.equal(wikiCandidates(entries,'Deleted').length,0);
});
