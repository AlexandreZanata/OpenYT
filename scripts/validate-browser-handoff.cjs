// Test tooling only. Executes the actual Kotlin compiler output with browser API fixtures.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const dir = 'browserExtension/build/dist/js/productionExecutable/';
for (const [url, valid] of [['https://example.com/vídeo?a=1&b=2', true], ['file:///private/file', false], ['https://user:pass@example.com/', false]]) {
  const nodes = Object.fromEntries(['open', 'address', 'hint'].map(id => [id, { addEventListener: (_, callback) => { nodes[id].click = callback; } }]));
  const context = { document: { getElementById: id => nodes[id] }, navigator: { language: 'pt-BR' }, window: { location: {} }, chrome: { tabs: { query: (_, callback) => callback([{ url }]) } }, encodeURIComponent, console };
  vm.createContext(context);
  for (const file of ['kotlin-kotlin-stdlib.js', 'openyt-browserExtension.js']) vm.runInContext(fs.readFileSync(dir + file, 'utf8'), context);
  assert.equal(nodes.open.disabled, !valid);
  if (valid) { nodes.open.click(); assert.equal(decodeURIComponent(context.window.location.href.split('?url=')[1]), url); }
}
console.log('PASS compiled browser handoff URL and protocol contracts');
