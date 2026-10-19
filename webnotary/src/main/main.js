'use strict';
/**
 * WebNotary - processo principal (Electron).
 * Janela com barra de ferramentas (renderer) + WebContentsView com o site navegado.
 * Um botão inicia/encerra a sessão: gravação de vídeo real da tela, captura de
 * rede/página/mídias, log encadeado no banco local, hash, assinatura e relatório.
 */
const { app, BrowserWindow, WebContentsView, ipcMain, session: esession, desktopCapturer, dialog, shell } = require('electron');
const path = require('node:path');
const fs = require('node:fs');
const { Config } = require('./config');
const { collectEnvironment } = require('./environment');
const { TabCapture } = require('./capture');
const { AppDatabase } = require('../core/db');
const { RecordingSession } = require('../core/session');
const { verifySessionDir, renderVerification } = require('../core/verify');
const { pkcs12Signer } = require('../core/sign/pkcs12');
const pkcs11 = require('../core/sign/pkcs11');

const TOOLBAR_H = 92;
const APP_VERSION = app.getVersion();
const PARTITION = 'persist:webnotary';

let win, view, config, db, current = null, capture = null, panelOpen = false;

function sessionsDir() {
  return config.data.pasta_sessoes || path.join(app.getPath('documents'), 'WebNotary', 'sessoes');
}

function layout() {
  if (!win || !view) return;
  const [w, h] = win.getContentSize();
  view.setBounds(panelOpen ? { x: 0, y: TOOLBAR_H, width: 0, height: 0 } : { x: 0, y: TOOLBAR_H, width: w, height: Math.max(0, h - TOOLBAR_H) });
}

function sendStatus(extra = {}) {
  if (!win) return;
  win.webContents.send('status', {
    ativa: !!current, id: current?.id || null, dir: current?.dir || null, inicio: current?.record?.started_at || null,
    certificado: config.data.certificado.modo, pkcs11_disponivel: pkcs11.isAvailable(), ...extra,
  });
}

function sendNavState() {
  if (!win || !view) return;
  const wc = view.webContents;
  win.webContents.send('nav:state', { url: wc.getURL(), titulo: wc.getTitle(), podeVoltar: wc.navigationHistory.canGoBack(), podeAvancar: wc.navigationHistory.canGoForward(), carregando: wc.isLoading() });
}

function createWindow() {
  win = new BrowserWindow({
    width: 1400, height: 900, minWidth: 900, minHeight: 600, title: 'WebNotary',
    webPreferences: { preload: path.join(__dirname, '../renderer/preload.js'), contextIsolation: true, sandbox: false, nodeIntegration: false },
  });
  win.loadFile(path.join(__dirname, '../renderer/index.html'));

  view = new WebContentsView({ webPreferences: { partition: PARTITION, contextIsolation: true, sandbox: true, nodeIntegration: false } });
  win.contentView.addChildView(view);
  const wc = view.webContents;
  // WhatsApp Web e outros sites exigem user-agent de Chrome "puro".
  wc.setUserAgent(wc.getUserAgent().replace(/\s?Electron\/\S+/, '').replace(/\s?WebNotary\/\S+/, ''));
  wc.setWindowOpenHandler(({ url }) => { wc.loadURL(url); return { action: 'deny' }; });
  for (const ev of ['did-navigate', 'did-navigate-in-page', 'page-title-updated', 'did-start-loading', 'did-stop-loading']) wc.on(ev, sendNavState);
  wc.on('did-navigate', (_e, url) => { if (current) current.addUrl(url, wc.getTitle()); });
  wc.on('did-navigate-in-page', (_e, url, isMain) => { if (current && isMain) current.addUrl(url, wc.getTitle()); });
  wc.loadURL(config.data.pagina_inicial || 'about:blank');

  win.on('resize', layout);
  win.once('ready-to-show', layout);
  win.webContents.on('did-finish-load', () => { layout(); sendStatus(); sendNavState(); });
  win.on('closed', () => { win = null; view = null; });
}

/** Gravação de tela: o renderer chama getDisplayMedia; aqui escolhemos a fonte (tela inteira ou a janela do app). */
function setupDisplayMedia() {
  esession.defaultSession.setDisplayMediaRequestHandler(async (_req, callback) => {
    let answered = false;
    const answer = (opts) => { if (!answered) { answered = true; callback(opts); } };
    try {
      // Consulta telas e janelas separadamente (enumerar janelas pode travar em ambientes sem gerenciador de janelas).
      const withTimeout = (p, ms) => Promise.race([p, new Promise((_r, rej) => setTimeout(() => rej(new Error('tempo esgotado ao enumerar fontes de captura')), ms))]);
      let src = null;
      if (config.data.gravacao.fonte === 'janela' && win) {
        try { src = (await withTimeout(desktopCapturer.getSources({ types: ['window'], thumbnailSize: { width: 0, height: 0 } }), 5000)).find((s) => s.id === win.getMediaSourceId()) || null; } catch { src = null; }
      }
      if (!src) {
        const screens = await withTimeout(desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: 0, height: 0 } }), 8000);
        src = screens[0] || null;
      }
      if (!src) throw new Error('nenhuma fonte de captura de tela disponível');
      const opts = { video: src };
      // Áudio do sistema ('loopback') está disponível no Windows; em outros SOs o áudio vem do microfone, se habilitado.
      if (process.platform === 'win32') opts.audio = 'loopback';
      answer(opts);
    } catch (e) {
      console.error('captura de tela:', e);
      if (current) { try { current.log('video.erro', { erro: e.message }); } catch { /* */ } }
      answer({});
    }
  }, { useSystemPicker: false });

  const site = esession.fromPartition(PARTITION);
  site.setPermissionRequestHandler((_wc, permission, cb) => cb(['media', 'notifications', 'clipboard-read', 'clipboard-sanitized-write', 'fullscreen'].includes(permission)));
}

async function buildSigner(segredo) {
  const c = config.data.certificado;
  if (c.modo === 'A1') {
    if (!c.pfx || !fs.existsSync(c.pfx)) throw new Error('Arquivo .pfx não configurado ou inexistente');
    return pkcs12Signer(c.pfx, segredo || '');
  }
  if (c.modo === 'A3') {
    if (!c.pkcs11_lib) throw new Error('Biblioteca PKCS#11 não configurada');
    return pkcs11.pkcs11Signer({ libraryPath: c.pkcs11_lib, pin: segredo || '', slotIndex: Number(c.slot || 0) });
  }
  return null;
}

function registerIpc() {
  ipcMain.handle('nav', (_e, acao, url) => {
    const wc = view.webContents;
    if (acao === 'ir' && url) {
      let u = String(url).trim();
      if (!/^[a-z]+:\/\//i.test(u)) u = /^[\w.-]+\.[a-z]{2,}(\/|$)/i.test(u) ? 'https://' + u : 'https://www.google.com/search?q=' + encodeURIComponent(u);
      wc.loadURL(u);
    } else if (acao === 'voltar' && wc.navigationHistory.canGoBack()) wc.navigationHistory.goBack();
    else if (acao === 'avancar' && wc.navigationHistory.canGoForward()) wc.navigationHistory.goForward();
    else if (acao === 'recarregar') wc.reload();
    else if (acao === 'parar') wc.stop();
    else if (acao === 'abrir-arquivo') {
      const r = dialog.showOpenDialogSync(win, { title: 'Abrir arquivo local (imagem, vídeo, áudio, PDF, HTML)', properties: ['openFile'] });
      if (r && r[0]) wc.loadFile(r[0]);
    }
    sendNavState();
  });
  ipcMain.handle('ui:panel', (_e, aberto) => { panelOpen = !!aberto; layout(); });
  ipcMain.handle('config:get', () => ({ ...config.get(), pasta_sessoes_efetiva: sessionsDir(), versao: APP_VERSION, pkcs11_disponivel: pkcs11.isAvailable() }));
  ipcMain.handle('config:set', (_e, patch) => { const c = config.set(patch); sendStatus(); return c; });
  ipcMain.handle('dialog:arquivo', (_e, opts = {}) => { const r = dialog.showOpenDialogSync(win, { properties: ['openFile'], filters: opts.filters || [] }); return r ? r[0] : null; });
  ipcMain.handle('dialog:pasta', () => { const r = dialog.showOpenDialogSync(win, { properties: ['openDirectory', 'createDirectory'] }); return r ? r[0] : null; });
  ipcMain.handle('cert:testar', async (_e, segredo) => {
    try { const s = await buildSigner(segredo); if (!s) return { ok: false, erro: 'Nenhum certificado configurado' }; const d = s.descricao; if (s.close) s.close(); return { ok: true, tipo: s.tipo, ...d }; }
    catch (e) { return { ok: false, erro: e.message }; }
  });

  ipcMain.handle('sessao:iniciar', async (_e, dados = {}) => {
    if (current) return { ok: false, erro: 'Já existe uma sessão ativa' };
    try {
      const environment = await collectEnvironment({ appVersion: APP_VERSION, publicIpUrl: config.data.ip_publico_url });
      const op = config.data.operador;
      current = new RecordingSession({ baseDir: sessionsDir(), db, operator: op.nome ? op : null, purpose: dados.finalidade || null, environment, appVersion: APP_VERSION }).start();
      capture = new TabCapture(view.webContents, current, config.data);
      await capture.attach();
      await capture.snapshot('navegacao').catch(() => {});
      win.webContents.send('video:comando', { acao: 'iniciar', microfone: !!config.data.gravacao.microfone, fps: config.data.gravacao.fps, bitrate_kbps: config.data.gravacao.bitrate_kbps });
      sendStatus();
      return { ok: true, id: current.id, dir: current.dir };
    } catch (e) { current = null; capture = null; return { ok: false, erro: e.message }; }
  });

  ipcMain.handle('sessao:encerrar', async (_e, dados = {}) => {
    if (!current) return { ok: false, erro: 'Nenhuma sessão ativa' };
    const s = current;
    const progress = (m) => win && win.webContents.send('progress', m);
    let signer = null;
    try {
      if (config.data.certificado.modo !== 'nenhum') {
        progress('Carregando certificado...');
        signer = await buildSigner(dados.segredo);
      }
    } catch (e) { return { ok: false, erro: 'Certificado: ' + e.message, manter: true }; }

    // Para a gravação e aguarda o último trecho antes de selar.
    progress('Encerrando gravação de vídeo...');
    win.webContents.send('video:comando', { acao: 'parar' });
    await waitFor(() => !s.video, 15000);
    if (capture) { capture.detach(); capture = null; }
    lastClosing = s;
    current = null;
    try {
      const c = config.data;
      const r = await s.finalize({
        signer, notes: dados.observacoes || null,
        tsaUrl: c.carimbo.url || undefined, tsaAuth: c.carimbo.usuario ? { username: c.carimbo.usuario, password: c.carimbo.senha } : undefined,
        registry: c.registro.url ? { url: c.registro.url, token: c.registro.token } : undefined,
        onProgress: progress,
      });
      sendStatus();
      return { ok: true, ...r };
    } catch (e) { sendStatus(); return { ok: false, erro: e.message }; }
    finally { if (signer && signer.close) { try { signer.close(); } catch { /* */ } } }
  });

  ipcMain.handle('sessao:nota', (_e, texto) => { if (!current) return { ok: false }; current.addNote(String(texto || '').slice(0, 4000)); return { ok: true }; });
  ipcMain.handle('sessao:capturar', async () => { if (!current || !capture) return { ok: false, erro: 'Sem sessão ativa' }; const r = await capture.snapshot('manual'); return { ok: true, ...r }; });
  ipcMain.handle('sessao:anexar', async () => {
    if (!current) return { ok: false, erro: 'Sem sessão ativa' };
    const r = dialog.showOpenDialogSync(win, { properties: ['openFile', 'multiSelections'], title: 'Anexar arquivos à sessão (serão copiados e hasheados)' });
    if (!r) return { ok: false };
    const out = [];
    for (const f of r) out.push(await current.attachFile(f));
    return { ok: true, anexos: out };
  });
  ipcMain.handle('sessao:estado', () => ({ ativa: !!current, id: current?.id || null, dir: current?.dir || null }));
  ipcMain.handle('sessoes:listar', () => db.listSessions().map((s) => ({ ...s, eventos: db.listEvents(s.id).length })));
  ipcMain.handle('sessao:abrir-pasta', (_e, dir) => { if (dir && fs.existsSync(dir)) shell.openPath(dir); });
  ipcMain.handle('sessao:verificar', async (_e, dir) => {
    const R = await verifySessionDir(dir, { registryUrl: config.data.registro.url || undefined });
    return { ...R, texto: renderVerification(R) };
  });

  // Vídeo: trechos do MediaRecorder chegam do renderer e são anexados ao arquivo da sessão.
  ipcMain.handle('video:iniciado', (_e, info) => { if (current && !current.video) current.startVideo(info); });
  ipcMain.handle('video:trecho', (_e, buf) => { if (current && current.video) current.appendVideoChunk(Buffer.from(buf)); });
  ipcMain.handle('video:parado', () => { const s = current || lastClosing; if (s && s.video) s.stopVideo(); });
}

let lastClosing = null;
function waitFor(pred, ms) {
  return new Promise((resolve) => { const t0 = Date.now(); const i = setInterval(() => { if (pred() || Date.now() - t0 > ms) { clearInterval(i); resolve(); } }, 100); });
}

app.whenReady().then(() => {
  config = new Config(path.join(app.getPath('userData'), 'config.json'));
  db = new AppDatabase(path.join(app.getPath('userData'), 'webnotary.db'));
  setupDisplayMedia();
  registerIpc();
  createWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});

app.on('before-quit', (e) => {
  if (current) {
    const r = dialog.showMessageBoxSync({ type: 'warning', buttons: ['Cancelar', 'Sair sem selar'], defaultId: 0, title: 'Sessão ativa', message: 'Há uma sessão em andamento. Saia apenas após encerrá-la para gerar o manifesto e as assinaturas.' });
    if (r === 0) { e.preventDefault(); return; }
    try { current.stopVideo(); current.log('sessao.abortada', { motivo: 'aplicativo encerrado' }); } catch { /* */ }
  }
  if (db) db.close();
});
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
