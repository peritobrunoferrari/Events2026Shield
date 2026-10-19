'use strict';
/**
 * Funções de hash (SHA-256 e SHA-512) para arquivos, buffers e árvores de diretório.
 * Tudo é calculado localmente; nenhum conteúdo sai da máquina.
 */
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const ALGOS = ['sha256', 'sha512'];

function hashBuffer(buf, algo = 'sha256') {
  return crypto.createHash(algo).update(buf).digest('hex');
}

function hashString(str, algo = 'sha256') {
  return hashBuffer(Buffer.from(str, 'utf8'), algo);
}

/** Hash de arquivo em streaming (não carrega o arquivo inteiro em memória). */
function hashFile(filePath, algos = ALGOS) {
  return new Promise((resolve, reject) => {
    const hashes = Object.fromEntries(algos.map((a) => [a, crypto.createHash(a)]));
    let size = 0;
    const stream = fs.createReadStream(filePath);
    stream.on('data', (chunk) => {
      size += chunk.length;
      for (const a of algos) hashes[a].update(chunk);
    });
    stream.on('error', reject);
    stream.on('end', () => {
      const out = { size };
      for (const a of algos) out[a] = hashes[a].digest('hex');
      resolve(out);
    });
  });
}

/** Lista recursivamente os arquivos de um diretório (caminhos relativos, ordenados, separador '/'). */
function listFiles(rootDir, { ignore = [] } = {}) {
  const out = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      const rel = path.relative(rootDir, full).split(path.sep).join('/');
      if (ignore.some((p) => rel === p || rel.startsWith(p + '/'))) continue;
      if (entry.isDirectory()) walk(full);
      else if (entry.isFile()) out.push(rel);
    }
  };
  walk(rootDir);
  return out.sort();
}

/** Calcula hashes de todos os arquivos de um diretório. */
async function hashDirectory(rootDir, opts = {}) {
  const files = listFiles(rootDir, opts);
  const result = [];
  for (const rel of files) {
    const h = await hashFile(path.join(rootDir, rel));
    result.push({ path: rel, ...h });
  }
  return result;
}

/**
 * Hash raiz da sessão: SHA-256 da concatenação ordenada de "caminho\tsha256\n".
 * Qualquer alteração em qualquer arquivo altera o hash raiz.
 */
function rootHash(entries) {
  const lines = [...entries]
    .sort((a, b) => a.path.localeCompare(b.path))
    .map((e) => `${e.path}\t${e.sha256}\n`)
    .join('');
  return hashString(lines, 'sha256');
}

module.exports = { ALGOS, hashBuffer, hashString, hashFile, listFiles, hashDirectory, rootHash };
