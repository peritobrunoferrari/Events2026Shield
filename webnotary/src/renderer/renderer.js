'use strict';
/* Interface da barra de ferramentas + gravação de vídeo (MediaRecorder) + painéis. */
const $ = (id) => document.getElementById(id);
const panel = $('panel');
let estado = { ativa: false };
let recorder = null, streamAtual = null, timer = null, inicioGravacao = 0, trechosPendentes = 0, pararResolve = null;

// ---------- navegação ----------
$('b-voltar').onclick = () => wn.nav('voltar');
$('b-avancar').onclick = () => wn.nav('avancar');
$('b-recarregar').onclick = () => wn.nav('recarregar');
$('b-ir').onclick = () => wn.nav('ir', $('url').value);
$('b-arquivo').onclick = () => wn.nav('abrir-arquivo');
$('url').addEventListener('keydown', (e) => { if (e.key === 'Enter') wn.nav('ir', $('url').value); });
wn.onNavState((s) => {
  if (document.activeElement !== $('url')) $('url').value = s.url && s.url !== 'about:blank' ? s.url : '';
  $('b-voltar').disabled = !s.podeVoltar; $('b-avancar').disabled = !s.podeAvancar;
  document.title = (s.titulo ? s.titulo + ' - ' : '') + 'WebNotary';
});

// ---------- estado da sessão ----------
wn.onStatus((s) => {
  estado = s;
  const on = s.ativa;
  $('dot').classList.toggle('on', on);
  $('b-sessao').textContent = on ? '■ Encerrar sessão' : '● Iniciar sessão';
  $('b-sessao').className = on ? 'rec' : 'primary';
  for (const id of ['b-capturar', 'b-nota', 'b-anexar']) $(id).disabled = !on;
  if (on) { inicioGravacao = inicioGravacao || Date.now(); if (!timer) timer = setInterval(tick, 1000); tick(); }
  else { clearInterval(timer); timer = null; inicioGravacao = 0; $('status-txt').textContent = `Sem sessão. Certificado: ${s.certificado || 'nenhum'}.`; }
});
function tick() {
  const s = Math.floor((Date.now() - inicioGravacao) / 1000);
  const f = (n) => String(n).padStart(2, '0');
  $('status-txt').textContent = `GRAVANDO ${f(Math.floor(s / 3600))}:${f(Math.floor((s % 3600) / 60))}:${f(s % 60)} · sessão ${estado.id} · ${recorder && recorder.state === 'recording' ? 'vídeo ok' : 'vídeo: aguardando permissão'}`;
}
wn.onProgress((m) => { const el = $('progress-txt'); if (el) el.textContent = m; });

// ---------- gravação de vídeo ----------
wn.onVideoComando(async (cmd) => {
  if (cmd.acao === 'iniciar') await iniciarGravacao(cmd);
  if (cmd.acao === 'parar') await pararGravacao();
});

async function iniciarGravacao({ microfone, fps, bitrate_kbps }) {
  try {
    const tela = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: fps || 15 }, audio: true });
    let stream = tela;
    let audioDesc = tela.getAudioTracks().length ? 'sistema' : 'nenhum';
    if (microfone) {
      try {
        const mic = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
        const ctx = new AudioContext();
        const dest = ctx.createMediaStreamDestination();
        for (const s of [tela, mic]) if (s.getAudioTracks().length) ctx.createMediaStreamSource(new MediaStream(s.getAudioTracks())).connect(dest);
        stream = new MediaStream([...tela.getVideoTracks(), ...dest.stream.getAudioTracks()]);
        audioDesc = audioDesc === 'sistema' ? 'sistema+microfone' : 'microfone';
      } catch (e) { console.warn('microfone indisponível', e); }
    }
    streamAtual = stream;
    const mime = ['video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm'].find((m) => MediaRecorder.isTypeSupported(m));
    recorder = new MediaRecorder(stream, { mimeType: mime, videoBitsPerSecond: (bitrate_kbps || 2500) * 1000 });
    await wn.videoIniciado({ fonte: 'tela', audio: audioDesc, mime, fps: fps || 15 });
    recorder.ondataavailable = async (e) => {
      if (!e.data || !e.data.size) return;
      trechosPendentes++;
      try { await wn.videoTrecho(new Uint8Array(await e.data.arrayBuffer())); } finally { trechosPendentes--; }
      if (recorder.state === 'inactive' && trechosPendentes === 0 && pararResolve) { const r = pararResolve; pararResolve = null; r(); }
    };
    recorder.onstop = () => { if (trechosPendentes === 0 && pararResolve) { const r = pararResolve; pararResolve = null; r(); } };
    tela.getVideoTracks()[0].onended = () => { if (recorder && recorder.state === 'recording') wn.sessaoNota('Gravação de tela interrompida pelo sistema/usuário').catch(() => {}); };
    recorder.start(1000); // trechos de 1 s gravados em disco imediatamente
  } catch (e) {
    console.error('gravação', e);
    wn.sessaoNota('FALHA ao iniciar gravação de vídeo: ' + (e.name || '') + ' ' + e.message).catch(() => {});
  }
}
async function pararGravacao() {
  if (!recorder || recorder.state === 'inactive') { await wn.videoParado(); return; }
  await new Promise((resolve) => { pararResolve = resolve; recorder.requestData(); recorder.stop(); setTimeout(resolve, 8000); });
  if (streamAtual) streamAtual.getTracks().forEach((t) => t.stop());
  recorder = null; streamAtual = null;
  await wn.videoParado();
}

// ---------- painéis ----------
function abrirPainel(html) { panel.innerHTML = html; panel.classList.add('show'); wn.panel(true); }
// CSP sem 'unsafe-inline': ações dos painéis por delegação de eventos (data-close / data-open / data-verify).
panel.addEventListener('click', (e) => {
  const b = e.target.closest('button'); if (!b) return;
  if (b.hasAttribute('data-close')) fecharPainel();
  else if (b.dataset.open) wn.sessaoAbrirPasta(b.dataset.open);
  else if (b.dataset.verify) verificar(b.dataset.verify);
});
function fecharPainel() { panel.classList.remove('show'); panel.innerHTML = ''; wn.panel(false); }
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

$('b-sessao').onclick = () => (estado.ativa ? painelEncerrar() : painelIniciar());
$('b-capturar').onclick = async () => { const r = await wn.sessaoCapturar(); flash(r.ok ? 'Captura salva: ' + (r.rel || '') : 'Falha: ' + r.erro); };
$('b-nota').onclick = () => {
  abrirPainel(`<div class="card"><h2>Nota do operador</h2><p class="hint">A nota entra no log encadeado com data/hora e será listada na ata.</p>
  <textarea id="nota" rows="5" style="width:100%"></textarea><div class="panel-actions"><button data-close>Cancelar</button><button class="primary" id="nota-ok">Registrar</button></div></div>`);
  $('nota-ok').onclick = async () => { const t = $('nota').value.trim(); if (t) await wn.sessaoNota(t); fecharPainel(); };
};
$('b-anexar').onclick = async () => { const r = await wn.sessaoAnexar(); if (r.ok) flash(`${r.anexos.length} arquivo(s) anexado(s)`); };
$('b-config').onclick = painelConfig;
$('b-sessoes').onclick = painelSessoes;

function flash(msg) { const prev = $('status-txt').textContent; $('status-txt').textContent = msg; setTimeout(() => { if ($('status-txt').textContent === msg) $('status-txt').textContent = prev; }, 4000); }

async function painelIniciar() {
  const cfg = await wn.configGet();
  abrirPainel(`<div class="card"><h2>Iniciar sessão de registro</h2>
    <p class="hint">Ao iniciar: a tela passa a ser gravada em vídeo (com áudio), cada navegação gera screenshot + página salva, o tráfego de rede, certificados TLS e DNS são registrados e todas as mídias abertas são copiadas. Tudo fica em <code>${esc(cfg.pasta_sessoes_efetiva)}</code>.</p>
    <div class="grid"><label>Finalidade / referência do caso<input id="fin" type="text" placeholder="ex.: Processo 0001234-56.2026.8.26.0100 - conversas WhatsApp"></label></div>
    <p class="hint">Operador: <b>${esc(cfg.operador.nome || '(não configurado)')}</b> · Certificado: <b>${esc(cfg.certificado.modo)}</b> · Carimbo do tempo: <b>${cfg.carimbo.url ? 'configurado' : 'não'}</b> · Registro remoto: <b>${cfg.registro.url ? 'configurado' : 'não'}</b></p>
    <div class="panel-actions"><button data-close>Cancelar</button><button class="rec" id="ini-ok">● Iniciar gravação</button></div><div id="ini-msg"></div></div>`);
  $('ini-ok').onclick = async () => {
    $('ini-ok').disabled = true;
    const r = await wn.sessaoIniciar({ finalidade: $('fin').value.trim() });
    if (r.ok) fecharPainel(); else { $('ini-msg').innerHTML = `<div class="msg err">${esc(r.erro)}</div>`; $('ini-ok').disabled = false; }
  };
}

async function painelEncerrar() {
  const cfg = await wn.configGet();
  const precisaSegredo = cfg.certificado.modo !== 'nenhum';
  abrirPainel(`<div class="card"><h2>Encerrar e selar a sessão ${esc(estado.id)}</h2>
    <p class="hint">Serão gerados: hashes SHA-256/512 de todos os artefatos, manifesto, assinatura CAdES do manifesto${cfg.carimbo.url ? ' com carimbo do tempo' : ''}, ata em PDF assinada automaticamente (PAdES)${cfg.registro.url ? ' e registro do hash raiz no servidor de verificação' : ''}.</p>
    <div class="grid"><label>Observações finais (entram no manifesto e na ata)<textarea id="obs" rows="4"></textarea></label>
    ${precisaSegredo ? `<label>${cfg.certificado.modo === 'A1' ? 'Senha do certificado (.pfx)' : 'PIN do token/cartão (A3)'}<input id="seg" type="password" autocomplete="off"></label>` : ''}</div>
    <div class="panel-actions"><button data-close>Continuar gravando</button><button class="primary" id="enc-ok">■ Encerrar e assinar</button></div>
    <div id="enc-msg"></div><p id="progress-txt" class="hint"></p></div>`);
  $('enc-ok').onclick = async () => {
    $('enc-ok').disabled = true;
    const r = await wn.sessaoEncerrar({ observacoes: $('obs').value.trim(), segredo: precisaSegredo ? $('seg').value : undefined });
    if (!r.ok) { $('enc-msg').innerHTML = `<div class="msg err">${esc(r.erro)}</div>`; $('enc-ok').disabled = false; return; }
    $('enc-msg').innerHTML = `<div class="msg ok">Sessão selada.</div>
      <table><tr><th>Pasta</th><td><code>${esc(r.dir)}</code></td></tr>
      <tr><th>Hash raiz</th><td><code>${esc(r.rootHash)}</code></td></tr>
      <tr><th>Manifesto (SHA-256)</th><td><code>${esc(r.manifestSha256)}</code></td></tr>
      <tr><th>Assinatura do manifesto</th><td>${r.signatureSha256 ? '<code>' + esc(r.signatureSha256) + '</code>' : 'não assinado'}</td></tr>
      <tr><th>Relatório</th><td>${esc(r.signedReport || 'relatorio.pdf (não assinado)')} · <code>${esc(r.reportSha256)}</code></td></tr>
      <tr><th>Carimbo do tempo</th><td>${r.timestampInfo ? esc(r.timestampInfo.info.genTime) : 'não obtido'}</td></tr>
      <tr><th>Registro remoto</th><td>${r.registryReceipt ? esc(r.registryReceipt.verify_url) : 'não realizado'}</td></tr>
      <tr><th>Artefatos / eventos</th><td>${r.artifacts} / ${r.events}</td></tr></table>
      ${r.warnings.length ? `<div class="msg err">Avisos:<br>${r.warnings.map(esc).join('<br>')}</div>` : ''}
      <div class="panel-actions"><button data-open="${esc(r.dir)}">Abrir pasta</button><button class="primary" data-close>Fechar</button></div>`;
    $('enc-ok').remove();
  };
}

async function painelConfig() {
  const c = await wn.configGet();
  abrirPainel(`<div class="card"><h2>Configurações</h2>
  <h3>Operador responsável</h3><div class="grid">
    <label>Nome completo<input id="op-nome" type="text" value="${esc(c.operador.nome)}"></label>
    <label>CPF<input id="op-doc" type="text" value="${esc(c.operador.documento)}"></label>
    <label>Registro profissional (CRA/OAB/CREA/Perito)<input id="op-reg" type="text" value="${esc(c.operador.registro)}"></label>
    <label>E-mail<input id="op-email" type="text" value="${esc(c.operador.email)}"></label></div>
  <h3>Pastas e navegação</h3><div class="grid">
    <label>Pasta das sessões<div class="row"><input id="pasta" type="text" value="${esc(c.pasta_sessoes)}" placeholder="${esc(c.pasta_sessoes_efetiva)}" style="flex:1"><button id="b-pasta">...</button></div></label>
    <label>Página inicial<input id="home" type="text" value="${esc(c.pagina_inicial)}"></label>
    <label>URL para obter IP público (vazio = não obter)<input id="ipurl" type="text" value="${esc(c.ip_publico_url)}"></label></div>
  <h3>Certificado digital (ICP-Brasil)</h3><div class="grid">
    <label>Modo<select id="cert-modo"><option value="nenhum" ${c.certificado.modo === 'nenhum' ? 'selected' : ''}>Nenhum (só hash + registro)</option><option value="A1" ${c.certificado.modo === 'A1' ? 'selected' : ''}>A1 - arquivo .pfx/.p12</option><option value="A3" ${c.certificado.modo === 'A3' ? 'selected' : ''}>A3 - token/cartão (PKCS#11)${c.pkcs11_disponivel ? '' : ' [módulo pkcs11js não instalado]'}</option></select></label>
    <label>Arquivo .pfx (A1)<div class="row"><input id="pfx" type="text" value="${esc(c.certificado.pfx)}" style="flex:1"><button id="b-pfx">...</button></div></label>
    <label>Biblioteca PKCS#11 (A3) ex.: C:\\Windows\\System32\\eTPKCS11.dll<div class="row"><input id="p11" type="text" value="${esc(c.certificado.pkcs11_lib)}" style="flex:1"><button id="b-p11">...</button></div></label>
    <label>Slot (A3)<input id="slot" type="number" value="${esc(c.certificado.slot)}"></label>
    <label>Testar: senha/PIN (não é gravado)<div class="row"><input id="teste-seg" type="password" style="flex:1"><button id="b-teste">Testar certificado</button></div></label></div>
  <div id="cert-msg"></div>
  <h3>Carimbo do tempo (ACT ICP-Brasil, RFC 3161)</h3><div class="grid">
    <label>URL da ACT<input id="tsa" type="text" value="${esc(c.carimbo.url)}" placeholder="https://..."></label>
    <label>Usuário (se exigido)<input id="tsa-u" type="text" value="${esc(c.carimbo.usuario)}"></label>
    <label>Senha (se exigido)<input id="tsa-p" type="password" value="${esc(c.carimbo.senha)}"></label></div>
  <h3>Servidor de verificação remoto (só recebe hashes)</h3><div class="grid">
    <label>URL<input id="reg" type="text" value="${esc(c.registro.url)}" placeholder="https://verifica.seudominio.com.br"></label>
    <label>Token de API<input id="reg-t" type="password" value="${esc(c.registro.token)}"></label></div>
  <h3>Gravação e captura</h3><div class="grid">
    <label>Fonte do vídeo<select id="fonte"><option value="tela" ${c.gravacao.fonte === 'tela' ? 'selected' : ''}>Tela inteira (recomendado: mostra relógio e contexto)</option><option value="janela" ${c.gravacao.fonte === 'janela' ? 'selected' : ''}>Somente a janela do WebNotary</option></select></label>
    <label>Quadros por segundo<input id="fps" type="number" min="5" max="60" value="${esc(c.gravacao.fps)}"></label>
    <label>Taxa de bits (kbps)<input id="kbps" type="number" min="500" max="20000" value="${esc(c.gravacao.bitrate_kbps)}"></label>
    <label><span><input id="mic" type="checkbox" ${c.gravacao.microfone ? 'checked' : ''}> Incluir microfone (narração do operador)</span></label>
    <label><span><input id="cap-shot" type="checkbox" ${c.captura.screenshot_em_navegacao ? 'checked' : ''}> Screenshot a cada navegação</span></label>
    <label><span><input id="cap-mhtml" type="checkbox" ${c.captura.salvar_pagina_mhtml ? 'checked' : ''}> Salvar página (MHTML) a cada navegação</span></label>
    <label><span><input id="cap-midia" type="checkbox" ${c.captura.salvar_midias_rede ? 'checked' : ''}> Copiar imagens/vídeos/áudios recebidos pela rede</span></label>
    <label>Tamanho máximo por mídia (MB)<input id="cap-max" type="number" value="${esc(c.captura.tamanho_max_midia_mb)}"></label></div>
  <p class="hint">WebNotary ${esc(c.versao)} · banco local e configuração ficam na pasta de dados do usuário. Senhas e PINs nunca são gravados.</p>
  <div class="panel-actions"><button data-close>Cancelar</button><button class="primary" id="cfg-ok">Salvar</button></div></div>`);
  $('b-pasta').onclick = async () => { const p = await wn.escolherPasta(); if (p) $('pasta').value = p; };
  $('b-pfx').onclick = async () => { const p = await wn.escolherArquivo({ filters: [{ name: 'Certificado PKCS#12', extensions: ['pfx', 'p12'] }] }); if (p) $('pfx').value = p; };
  $('b-p11').onclick = async () => { const p = await wn.escolherArquivo({ filters: [{ name: 'Biblioteca PKCS#11', extensions: ['dll', 'so', 'dylib'] }] }); if (p) $('p11').value = p; };
  const coletar = () => ({
    operador: { nome: $('op-nome').value.trim(), documento: $('op-doc').value.trim(), registro: $('op-reg').value.trim(), email: $('op-email').value.trim() },
    pasta_sessoes: $('pasta').value.trim(), pagina_inicial: $('home').value.trim(), ip_publico_url: $('ipurl').value.trim(),
    certificado: { modo: $('cert-modo').value, pfx: $('pfx').value.trim(), pkcs11_lib: $('p11').value.trim(), slot: Number($('slot').value || 0) },
    carimbo: { url: $('tsa').value.trim(), usuario: $('tsa-u').value.trim(), senha: $('tsa-p').value },
    registro: { url: $('reg').value.trim(), token: $('reg-t').value.trim() },
    gravacao: { fonte: $('fonte').value, fps: Number($('fps').value || 15), bitrate_kbps: Number($('kbps').value || 2500), microfone: $('mic').checked },
    captura: { screenshot_em_navegacao: $('cap-shot').checked, salvar_pagina_mhtml: $('cap-mhtml').checked, salvar_midias_rede: $('cap-midia').checked, tamanho_max_midia_mb: Number($('cap-max').value || 50) },
  });
  $('b-teste').onclick = async () => {
    await wn.configSet(coletar());
    const r = await wn.testarCertificado($('teste-seg').value);
    $('cert-msg').innerHTML = r.ok ? `<div class="msg ok">OK: ${esc(r.tipo)} · ${esc(r.subject)} · emissor ${esc(r.issuer_cn)} · válido até ${esc(r.valido_ate)}</div>` : `<div class="msg err">${esc(r.erro)}</div>`;
  };
  $('cfg-ok').onclick = async () => { await wn.configSet(coletar()); fecharPainel(); };
}

async function painelSessoes() {
  const lista = await wn.sessoesListar();
  abrirPainel(`<div class="card"><h2>Sessões registradas (banco local)</h2>
  <p class="hint">Cada sessão selada guarda o hash raiz no banco do aplicativo junto com o log encadeado de eventos. "Verificar" recalcula todos os hashes da pasta e valida assinaturas e carimbo.</p>
  <table><tr><th>Sessão</th><th>Início</th><th>Status</th><th>Hash raiz</th><th>Eventos</th><th></th></tr>
  ${lista.map((s) => `<tr><td>${esc(s.id)}<br><span class="hint">${esc(s.operator?.nome || '')}</span></td><td>${esc(s.started_at)}</td><td>${esc(s.status)}</td><td><code>${esc(s.root_hash || '-')}</code></td><td>${s.eventos}</td>
    <td><button data-open="${esc(s.dir_path)}">Pasta</button> <button data-verify="${esc(s.dir_path)}">Verificar</button></td></tr>`).join('') || '<tr><td colspan="6">Nenhuma sessão ainda.</td></tr>'}</table>
  <div id="ver-out"></div>
  <div class="panel-actions"><button class="primary" data-close>Fechar</button></div></div>`);
}
const verificar = async (dir) => { $('ver-out').innerHTML = '<div class="msg info">Verificando...</div>'; const r = await wn.sessaoVerificar(dir); $('ver-out').innerHTML = `<div class="msg ${r.ok ? 'ok' : 'err'}">${r.ok ? 'ÍNTEGRA' : 'FALHA'}</div><pre>${esc(r.texto)}</pre>`; };

wn.sessaoEstado().then((s) => wn.configGet().then((c) => { estado = { ...s, certificado: c.certificado.modo }; if (!s.ativa) $('status-txt').textContent = `Sem sessão. Certificado: ${c.certificado.modo}.`; }));
