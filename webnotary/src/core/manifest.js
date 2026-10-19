'use strict';
/**
 * Manifesto da sessão: lista todos os artefatos com seus hashes, metadados da
 * sessão e o hash raiz. É gerado em JSON (máquina) e em texto (leitura humana).
 * O manifesto é o objeto assinado (CAdES) e carimbado (RFC 3161).
 */
const { rootHash, hashString } = require('./hashing');

const MANIFEST_VERSION = '1.0';

function buildManifest({ session, artifacts, events, environment }) {
  const root = rootHash(artifacts);
  const manifest = {
    formato: 'webnotary-manifesto',
    versao: MANIFEST_VERSION,
    gerado_em: new Date().toISOString(),
    sessao: {
      id: session.id,
      inicio: session.started_at,
      fim: session.ended_at || new Date().toISOString(),
      operador: session.operator || null,
      finalidade: session.purpose || null,
      observacoes: session.notes || null,
      urls_visitadas: session.urls || [],
    },
    ambiente: environment || null,
    artefatos: artifacts.map((a) => ({
      caminho: a.path,
      tamanho: a.size,
      sha256: a.sha256,
      sha512: a.sha512,
      tipo: a.kind || inferKind(a.path),
    })),
    eventos: {
      quantidade: events.length,
      hash_ultimo_evento: events.length ? events[events.length - 1].hash : null,
      arquivo: 'eventos.jsonl',
    },
    hash_raiz: {
      algoritmo: 'sha256(concat_ordenado(caminho\\tsha256\\n))',
      valor: root,
    },
  };
  return manifest;
}

function inferKind(p) {
  const ext = p.split('.').pop().toLowerCase();
  if (['webm', 'mp4', 'mkv'].includes(ext)) return 'video';
  if (['png', 'jpg', 'jpeg', 'webp', 'gif'].includes(ext)) return 'imagem';
  if (['mp3', 'ogg', 'opus', 'wav', 'm4a', 'aac'].includes(ext)) return 'audio';
  if (['mhtml', 'html'].includes(ext)) return 'pagina';
  if (['jsonl', 'json', 'har', 'txt', 'log'].includes(ext)) return 'registro';
  if (['pdf'].includes(ext)) return 'documento';
  if (['p7s', 'tsr', 'pem', 'cer', 'der'].includes(ext)) return 'criptografia';
  return 'outro';
}

/** Serialização canônica do manifesto (é este texto exato que é assinado). */
function serializeManifest(manifest) {
  return JSON.stringify(manifest, null, 2) + '\n';
}

/** Versão em texto legível, para leitura em juízo sem ferramentas. */
function manifestToText(manifest) {
  const L = [];
  L.push('WEBNOTARY - MANIFESTO DE SESSÃO DE REGISTRO WEB');
  L.push('='.repeat(60));
  L.push(`Sessão:        ${manifest.sessao.id}`);
  L.push(`Início (UTC):  ${manifest.sessao.inicio}`);
  L.push(`Fim (UTC):     ${manifest.sessao.fim}`);
  if (manifest.sessao.operador) {
    const o = manifest.sessao.operador;
    L.push(`Operador:      ${o.nome || ''}${o.documento ? ' - ' + o.documento : ''}${o.registro ? ' - ' + o.registro : ''}`);
  }
  if (manifest.sessao.finalidade) L.push(`Finalidade:    ${manifest.sessao.finalidade}`);
  if (manifest.sessao.urls_visitadas?.length) {
    L.push('URLs visitadas:');
    for (const u of manifest.sessao.urls_visitadas) L.push(`  - ${u}`);
  }
  L.push('');
  L.push('ARTEFATOS (caminho | tamanho | SHA-256 | SHA-512)');
  L.push('-'.repeat(60));
  for (const a of manifest.artefatos) {
    L.push(`${a.caminho}`);
    L.push(`  tamanho: ${a.tamanho} bytes  tipo: ${a.tipo}`);
    L.push(`  sha256:  ${a.sha256}`);
    L.push(`  sha512:  ${a.sha512}`);
  }
  L.push('');
  L.push(`Eventos registrados: ${manifest.eventos.quantidade} (último hash: ${manifest.eventos.hash_ultimo_evento || '-'})`);
  L.push('');
  L.push(`HASH RAIZ (SHA-256): ${manifest.hash_raiz.valor}`);
  L.push(`Método: ${manifest.hash_raiz.algoritmo}`);
  L.push('');
  L.push('Como verificar: recalcule o SHA-256 de cada artefato, compare com este manifesto,');
  L.push('recalcule o hash raiz e confira no servidor de verificação ou no carimbo do tempo.');
  return L.join('\n') + '\n';
}

function manifestHash(manifestText) { return hashString(manifestText, 'sha256'); }

module.exports = { buildManifest, serializeManifest, manifestToText, manifestHash, inferKind, MANIFEST_VERSION };
