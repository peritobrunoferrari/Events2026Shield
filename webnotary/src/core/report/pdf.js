'use strict';
/**
 * Relatório da sessão ("Ata de Registro de Sessão Web") em PDF, gerado com pdfkit.
 * Contém: identificação da sessão e do operador, ambiente, URLs, linha do tempo,
 * tabela de artefatos com hashes, hash raiz, dados de assinatura/carimbo e
 * instruções de verificação. É assinado em seguida (PAdES) por sign-pdf.js.
 */
const PDFDocument = require('pdfkit');

function generateReportPdf({ manifest, events, signatureInfo, timestampInfo, registryReceipt, environment, appVersion }) {
  return new Promise((resolve, reject) => {
    const doc = new PDFDocument({ size: 'A4', margins: { top: 56, bottom: 56, left: 50, right: 50 }, info: {
      Title: `WebNotary - Ata de Registro de Sessão ${manifest.sessao.id}`,
      Author: manifest.sessao.operador?.nome || 'WebNotary',
      Subject: 'Registro de sessão web com hash e assinatura digital',
      Creator: `WebNotary ${appVersion || ''}`.trim(),
    } });
    const chunks = [];
    doc.on('data', (c) => chunks.push(c));
    doc.on('end', () => resolve(Buffer.concat(chunks)));
    doc.on('error', reject);

    const H = (t) => { doc.moveDown(0.6); doc.font('Helvetica-Bold').fontSize(12).fillColor('#1a237e').text(t); doc.moveDown(0.2); doc.font('Helvetica').fontSize(9.5).fillColor('black'); };
    const KV = (k, v) => { if (v === undefined || v === null || v === '') return; doc.font('Helvetica-Bold').text(`${k}: `, { continued: true }).font('Helvetica').text(String(v)); };
    const mono = (t) => doc.font('Courier').fontSize(8).text(t).font('Helvetica').fontSize(9.5);

    // Cabeçalho
    doc.font('Helvetica-Bold').fontSize(16).text('ATA DE REGISTRO DE SESSÃO WEB', { align: 'center' });
    doc.font('Helvetica').fontSize(9).fillColor('#444').text('Gerada pelo WebNotary - navegador forense com gravação de vídeo, hash e assinatura digital', { align: 'center' });
    doc.fillColor('black').moveDown(0.5);
    doc.fontSize(9.5);

    H('1. Identificação da sessão');
    KV('Identificador', manifest.sessao.id);
    KV('Início (UTC)', manifest.sessao.inicio);
    KV('Fim (UTC)', manifest.sessao.fim);
    KV('Finalidade', manifest.sessao.finalidade);
    KV('Observações', manifest.sessao.observacoes);
    const op = manifest.sessao.operador || {};
    H('2. Operador responsável');
    KV('Nome', op.nome); KV('Documento (CPF)', op.documento); KV('Registro profissional', op.registro); KV('E-mail', op.email);

    H('3. Ambiente de captura');
    if (environment) {
      KV('Aplicativo', `WebNotary ${environment.app_version || ''}`);
      KV('Motor', `Electron ${environment.electron || '-'} / Chromium ${environment.chromium || '-'} / Node ${environment.node || '-'}`);
      KV('Sistema operacional', `${environment.os || ''} ${environment.os_release || ''} (${environment.arch || ''})`);
      KV('Computador', environment.hostname);
      KV('Usuário do SO', environment.user);
      KV('Fuso horário', `${environment.timezone || ''} (offset ${environment.tz_offset_min ?? '-'} min)`);
      KV('Endereços IP locais', (environment.local_ips || []).join(', '));
      KV('IP público (se obtido)', environment.public_ip);
    }

    H('4. URLs visitadas');
    for (const u of manifest.sessao.urls_visitadas || []) doc.text(`• ${u}`);
    if (!(manifest.sessao.urls_visitadas || []).length) doc.text('(nenhuma navegação registrada)');

    H('5. Linha do tempo de eventos (resumo)');
    const important = events.filter((e) => !['rede.requisicao', 'rede.resposta', 'video.trecho'].includes(e.type));
    for (const e of important.slice(0, 400)) {
      let d = {};
      try { d = JSON.parse(e.data || '{}'); } catch { d = {}; }
      const desc = describeEvent(e.type, d);
      doc.font('Courier').fontSize(7.5).text(`${e.ts}  ${e.type.padEnd(22)} ${desc}`.slice(0, 200));
    }
    doc.font('Helvetica').fontSize(9.5);
    if (important.length > 400) doc.text(`... e mais ${important.length - 400} eventos (ver eventos.jsonl).`);
    doc.text(`Total de eventos (incluindo rede e vídeo): ${events.length}. Cadeia de hashes: cada evento contém o hash do anterior.`);

    H('6. Artefatos e hashes');
    for (const a of manifest.artefatos) {
      doc.font('Helvetica-Bold').fontSize(9).text(a.caminho, { continued: true }).font('Helvetica').text(`  (${a.tipo}, ${a.tamanho} bytes)`);
      mono(`SHA-256: ${a.sha256}`);
      mono(`SHA-512: ${a.sha512}`);
      doc.moveDown(0.2);
    }

    H('7. Hash raiz da sessão');
    doc.text('O hash raiz é o SHA-256 da lista ordenada "caminho<TAB>sha256" de todos os artefatos. Qualquer alteração em qualquer arquivo o modifica.');
    doc.font('Courier-Bold').fontSize(10).text(manifest.hash_raiz.valor);
    doc.font('Helvetica').fontSize(9.5);

    H('8. Assinatura digital e carimbo do tempo');
    if (signatureInfo) {
      KV('Tipo de certificado', signatureInfo.tipo);
      KV('Signatário', signatureInfo.descricao?.subject);
      KV('Emissor', signatureInfo.descricao?.issuer);
      KV('Série', signatureInfo.descricao?.serial);
      KV('Validade', `${signatureInfo.descricao?.valido_de} a ${signatureInfo.descricao?.valido_ate}`);
      KV('Arquivo de assinatura do manifesto', 'manifesto.json.p7s (CMS/CAdES destacado)');
    } else {
      doc.text('Sessão sem assinatura digital configurada. O hash raiz e o registro remoto garantem integridade; a autoria depende do operador.');
    }
    if (timestampInfo) {
      KV('Carimbo do tempo (ACT)', timestampInfo.url);
      KV('Data/hora certificada (genTime)', timestampInfo.info?.genTime);
      KV('Política', timestampInfo.info?.policy);
      KV('Série do carimbo', timestampInfo.info?.serialNumber);
      KV('ACT (CN)', timestampInfo.info?.tsa?.cn);
    } else {
      doc.text('Carimbo do tempo não obtido para esta sessão.');
    }
    if (registryReceipt) {
      H('9. Registro no servidor de verificação');
      KV('Servidor', registryReceipt.server);
      KV('Registrado em (hora do servidor)', registryReceipt.registered_at);
      KV('Recibo', registryReceipt.receipt);
      KV('Verificar em', registryReceipt.verify_url);
    }

    H('10. Como verificar esta ata');
    doc.text('1) Recalcule o SHA-256 de cada artefato da pasta da sessão e compare com a seção 6 e com manifesto.json.');
    doc.text('2) Recalcule o hash raiz conforme a seção 7 e compare com o valor acima e com o servidor de verificação.');
    doc.text('3) Valide a assinatura destacada manifesto.json.p7s e o carimbo do tempo (ex.: Verificador de Conformidade do ITI, Adobe Reader para o PDF, OpenSSL).');
    doc.text('4) Use "webnotary-verify <pasta da sessão>" para executar todas as verificações automaticamente.');
    doc.moveDown(0.5);
    doc.fontSize(8).fillColor('#555').text('Este documento foi assinado digitalmente no padrão PAdES com o certificado indicado na seção 8. A assinatura pode ser conferida em leitores PDF compatíveis ou no Verificador de Conformidade do ITI (https://verificador.iti.gov.br).');
    doc.end();
  });
}

function describeEvent(type, d) {
  switch (type) {
    case 'sessao.inicio': return `Sessão iniciada${d.finalidade ? ': ' + d.finalidade : ''}`;
    case 'sessao.fim': return 'Sessão encerrada pelo operador';
    case 'navegacao': return `${d.url || ''}${d.titulo ? ' - ' + d.titulo : ''}`;
    case 'captura.tela': return `Screenshot ${d.arquivo || ''} (${d.url || ''})`;
    case 'captura.pagina': return `Página salva ${d.arquivo || ''}`;
    case 'captura.midia': return `Mídia salva ${d.arquivo || ''} (${d.content_type || ''}, ${d.tamanho || '?'} bytes) de ${d.url || ''}`;
    case 'anexo': return `Arquivo anexado ${d.arquivo || ''} sha256=${(d.sha256 || '').slice(0, 16)}...`;
    case 'nota': return `Nota do operador: ${d.texto || ''}`;
    case 'video.inicio': return `Gravação iniciada (${d.fonte || ''}, áudio: ${d.audio || ''})`;
    case 'video.fim': return `Gravação encerrada (${d.tamanho || '?'} bytes, ${d.duracao_s || '?'} s)`;
    case 'certificado.tls': return `TLS ${d.host || ''}: ${d.subject || ''} emitido por ${d.issuer || ''} (${d.protocolo || ''})`;
    case 'dns': return `${d.host || ''} -> ${(d.ips || []).join(', ')}`;
    case 'download': return `Download ${d.arquivo || ''} de ${d.url || ''}`;
    default: return JSON.stringify(d).slice(0, 150);
  }
}

module.exports = { generateReportPdf, describeEvent };
