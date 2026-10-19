'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { hashBuffer, hashFile, hashDirectory, rootHash, listFiles } = require('../src/core/hashing');

test('hashBuffer calcula SHA-256 conhecido', () => {
  assert.strictEqual(hashBuffer(Buffer.from('abc')), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
});

test('hashFile e hashDirectory são consistentes e o hash raiz muda com qualquer alteração', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'wn-hash-'));
  fs.mkdirSync(path.join(dir, 'sub'));
  fs.writeFileSync(path.join(dir, 'a.txt'), 'A');
  fs.writeFileSync(path.join(dir, 'sub', 'b.txt'), 'B');
  const h = await hashFile(path.join(dir, 'a.txt'));
  assert.strictEqual(h.size, 1);
  assert.strictEqual(h.sha256, hashBuffer(Buffer.from('A')));
  assert.strictEqual(h.sha512, hashBuffer(Buffer.from('A'), 'sha512'));
  const entries = await hashDirectory(dir, { ignore: ['sub/b.txt'] });
  assert.deepStrictEqual(entries.map((e) => e.path), ['a.txt']);
  const all = await hashDirectory(dir);
  assert.deepStrictEqual(listFiles(dir), ['a.txt', 'sub/b.txt']);
  const r1 = rootHash(all);
  fs.writeFileSync(path.join(dir, 'sub', 'b.txt'), 'C');
  const r2 = rootHash(await hashDirectory(dir));
  assert.notStrictEqual(r1, r2);
  // ordem de entrada não afeta o hash raiz
  assert.strictEqual(rootHash([...all].reverse()), r1);
});
