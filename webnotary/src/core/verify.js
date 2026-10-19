'use strict';
/**
 * Verificação completa de uma pasta de sessão (offline, com consulta remota opcional):
 *  1. hashes de cada artefato vs manifesto.json
 *  2. hash raiz recalculado
 *  3. cadeia de eventos (eventos.jsonl) e vínculo com o manifesto
 *  4. assinatura CAdES do manifesto (manifesto.json.p7s) e carimbo do tempo
 *  5. assinatura PAdES do relatório (relatorio-assinado.pdf)
 *  6. consulta ao servidor de verificação (se informado)
 */
const fs = require('node:fs');
const path = require('node:path');
const { hashFile, rootHash, listFiles, hashBuffer } = require('./hashing');
const { verifyEventChain } = require('./db');
const { verifyCms } = require('./sign/pkcs7');
const { verifySignedPdf } = require('./report/sign-pdf');

const OUTPUT_FILES = ['manifesto.json', 'manifesto.txt', 'manifesto.json.p7s', 'carimbo-manifesto.tsr', 'relatorio.pdf', 'relatorio-assinado.pdf', 'recibo-registro.json', 'VERIFICACAO.txt'];

async function verifySessionDir(dir, { registryUrl } = {}) {
  const R = { pasta: dir, ok: false, etapas: [], erros: [] };
  const step = (nome, ok, detalhe) => { R.etapas.push({ nome, ok, detalhe }); if (!ok) R.erros.push(`${nome}: ${detalhe}`); };

  const manifestPath = path.join(dir, 'manifesto.json');
  if (!fs.existsSync(manifestPath)) { step('manifesto', false, 'manifesto.json ausente'); return R; }
  const manifestBytes = fs.readFileSync(manifestPath);
  let manifest;
  try { manifest = JSON.parse(manifestBytes.toString('utf8')); } catch (e) { step('manifesto', false, 'JSON inválido: ' + e.message); return R; }
  R.sessao = manifest.sessao.id;
  R.hash_raiz_declarado = manifest.hash_raiz.valor;

  // 1. artefatos
  const recomputed = [];
  let divergentes = 0, ausentes = 0;
  for (const a of manifest.artefatos) {
    const p = path.join(dir, a.caminho);
    if (!fs.existsSync(p)) { ausentes++; R.erros.push(`artefato ausente: ${a.caminho}`); continue; }
    const h = await hashFile(p);
    recomputed.push({ path: a.caminho, sha256: h.sha256 });
    if (h.sha256 !== a.sha256 || h.sha512 !== a.sha512 || h.size !== a.tamanho) { divergentes++; R.erros.push(`artefato alterado: ${a.caminho}`); }
  }
  step('artefatos', divergentes === 0 && ausentes === 0, `${manifest.artefatos.length} artefatos, ${divergentes} alterados, ${ausentes} ausentes`);
  const extras = listFiles(dir).filter((f) => !OUTPUT_FILES.includes(f) && !manifest.artefatos.some((a) => a.caminho === f));
  if (extras.length) R.etapas.push({ nome: 'arquivos extras', ok: true, detalhe: `não listados no manifesto (não afetam a prova): ${extras.join(', ')}` });

  // 2. hash raiz
  const root = rootHash(manifest.artefatos.map((a) => ({ path: a.caminho, sha256: a.sha256 })));
  const rootRecomputed = recomputed.length === manifest.artefatos.length ? rootHash(recomputed) : null;
  step('hash raiz', root === manifest.hash_raiz.valor && rootRecomputed === root, `declarado ${manifest.hash_raiz.valor.slice(0, 16)}..., recalculado ${(rootRecomputed || '-').slice(0, 16)}...`);
  R.hash_raiz_recalculado = rootRecomputed;

  // 3. eventos
  const evPath = path.join(dir, 'eventos.jsonl');
  if (fs.existsSync(evPath)) {
    const events = fs.readFileSync(evPath, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
    const chain = verifyEventChain(events);
    const linked = chain.ok && chain.lastHash === manifest.eventos.hash_ultimo_evento && events.length === manifest.eventos.quantidade;
    step('cadeia de eventos', chain.ok && linked, chain.ok ? (linked ? `${events.length} eventos íntegros e vinculados ao manifesto` : 'cadeia íntegra mas não corresponde ao manifesto') : chain.reason);
  } else step('cadeia de eventos', false, 'eventos.jsonl ausente');

  // 4. assinatura do manifesto
  const p7sPath = path.join(dir, 'manifesto.json.p7s');
  if (fs.existsSync(p7sPath)) {
    try {
      const v = verifyCms(fs.readFileSync(p7sPath), manifestBytes);
      R.assinatura_manifesto = v;
      step('assinatura do manifesto (CAdES)', v.ok, v.ok ? `assinado por ${v.signatario}${v.carimbo ? '; carimbo ' + v.carimbo.genTime : ''}` : v.erros.join('; '));
    } catch (e) { step('assinatura do manifesto (CAdES)', false, e.message); }
  } else R.etapas.push({ nome: 'assinatura do manifesto (CAdES)', ok: true, detalhe: 'não há assinatura (sessão não assinada)' });

  // 5. relatório assinado
  const pdfPath = path.join(dir, 'relatorio-assinado.pdf');
  if (fs.existsSync(pdfPath)) {
    try {
      const v = verifySignedPdf(fs.readFileSync(pdfPath));
      R.assinatura_relatorio = v;
      step('assinatura do relatório (PAdES)', v.ok, v.ok ? `assinado por ${v.signatario}` : v.erros.join('; '));
    } catch (e) { step('assinatura do relatório (PAdES)', false, e.message); }
  }

  // 6. servidor remoto
  if (registryUrl) {
    try {
      const { verifyHash } = require('./registry-client');
      const r = await verifyHash(registryUrl, manifest.hash_raiz.valor);
      R.registro_remoto = r;
      step('registro remoto', r.found, r.found ? `registrado em ${r.records[0].registered_at} (recibo ${r.records[0].receipt_valid ? 'válido' : 'INVÁLIDO'})` : 'hash raiz não consta no servidor');
    } catch (e) { step('registro remoto', false, 'falha na consulta: ' + e.message); }
  }

  R.ok = R.erros.length === 0;
  return R;
}

function renderVerification(R) {
  const L = [`Verificação da sessão ${R.sessao || '?'} em ${R.pasta}`, ''];
  for (const e of R.etapas) L.push(`${e.ok ? '[OK]  ' : '[FALHA]'} ${e.nome}: ${e.detalhe}`);
  L.push('');
  L.push(R.ok ? 'RESULTADO: ÍNTEGRA - todos os controles conferem.' : `RESULTADO: FALHA - ${R.erros.length} problema(s): ${R.erros.join(' | ')}`);
  return L.join('\n') + '\n';
}

module.exports = { verifySessionDir, renderVerification, OUTPUT_FILES };
