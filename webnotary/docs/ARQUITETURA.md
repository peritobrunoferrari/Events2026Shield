# Arquitetura do WebNotary

## Visão geral

| Camada | Pasta | Responsabilidade |
|---|---|---|
| Processo principal (Electron) | `src/main/` | Janela, `WebContentsView` do site, IPC, captura via DevTools Protocol, escolha da fonte de gravação, assinatura ao encerrar |
| Interface | `src/renderer/` | Barra de ferramentas, botão Iniciar/Encerrar, `MediaRecorder` (vídeo+áudio), painéis (configurações, sessões, verificação) |
| Núcleo (sem Electron, testável) | `src/core/` | Hash, banco, manifesto, CMS/CAdES, PKCS#12/PKCS#11, RFC 3161, PDF + PAdES, verificação, cliente de registro, orquestrador de sessão |
| Servidor remoto | `server/verify-server.js` | Registro/consulta de hashes em arquivo texto; recibo HMAC |
| CLI | `cli/webnotary-verify.js` | Verificação offline de uma pasta de sessão |

## Fluxo de uma sessão

1. **Iniciar**: `RecordingSession.start()` cria a pasta, insere a sessão no banco e registra `sessao.inicio` (com ambiente: SO, versões, IPs locais, IP público, fuso). `TabCapture.attach()` liga o `webContents.debugger` (CDP 1.3) com `Network.enable`. O renderer recebe `video:comando iniciar` e chama `getDisplayMedia` (fonte escolhida no processo principal via `setDisplayMediaRequestHandler`: tela inteira ou janela; áudio de sistema por loopback no Windows; microfone opcional mixado via `AudioContext`). O `MediaRecorder` gera trechos de 1 s que são enviados por IPC e anexados a `gravacao.webm` imediatamente (queda do app não perde o já gravado).
2. **Durante**: cada `did-navigate` registra `navegacao`; `did-finish-load` dispara screenshot (`capturePage`) e MHTML (`savePage`). Eventos CDP: `requestWillBeSent`/`responseReceived` → `rede/trafego.jsonl` (IP remoto, protocolo, TLS, cabeçalhos selecionados); primeira resposta por origem → `Network.getCertificate` → `rede/cert-<host>.pem` + evento `certificado.tls`; primeiro host → `dns.lookup` → evento `dns`; `loadingFinished` de `image/*|video/*|audio/*` → `Network.getResponseBody` → `midia/` (dedupe por SHA‑256, limite de tamanho). Downloads vão para `anexos/`. Notas e anexos manuais também geram eventos.
3. **Encerrar**: `finalize()` para o vídeo, exporta `eventos.jsonl` do banco (canônico), calcula SHA‑256/512 de todos os arquivos, monta `manifesto.json` (+ `.txt`), calcula o **hash raiz**, assina o manifesto (CAdES‑BES com `contentType`, `messageDigest`, `signingTime`; carimbo do tempo sobre o valor da assinatura como atributo não assinado `id-aa-signatureTimeStampToken` → CAdES‑T), registra o hash raiz no servidor, gera a ata PDF (pdfkit) e a assina **automaticamente** (PAdES `ETSI.CAdES.detached`, com carimbo se houver ACT), escreve `VERIFICACAO.txt` e sela a sessão no banco com o hash raiz.

## Banco de dados local

`node:sqlite` (Node ≥ 22.13 / Electron ≥ 36). Tabelas `sessions` (id, início/fim, operador, pasta, status, `root_hash`, hashes do manifesto/assinatura/relatório, carimbo, recibo) e `events` (`session_id`, `seq`, `ts`, `type`, `data`, `prev_hash`, `hash`). O hash de cada evento = SHA‑256 de `[session_id, seq, ts, type, data, prev_hash]`; o primeiro usa `prev_hash` = 64 zeros. Sem SQLite, um backend JSONL append‑only com a mesma semântica é usado automaticamente.

## Hash raiz

`sha256(concat(sorted("caminho\tsha256\n")))` sobre todos os artefatos listados no manifesto. É o valor registrado remotamente, exibido na ata e no banco. Qualquer byte alterado em qualquer artefato muda o hash raiz; a cadeia de eventos é vinculada ao manifesto pelo hash do último evento.

## Assinaturas

- **CAdES** (`src/core/sign/pkcs7.js`): node‑forge; chave plugável — `forge` (A1) ou `externalKey()` que recebe o `DigestInfo` e devolve a assinatura RSA PKCS#1 v1.5 (A3 via `pkcs11js`, mecanismo `CKM_RSA_PKCS`; a chave nunca sai do token).
- **PAdES** (`src/core/report/sign-pdf.js`): `@signpdf` com placeholder e um `Signer` que reutiliza `signCms` (mesmo signatário e carimbo). Verificação própria lê `/ByteRange` e valida o CMS.
- **RFC 3161** (`src/core/sign/timestamp.js`): monta `TimeStampReq` (SHA‑256, nonce, `certReq`), POST `application/timestamp-query`, interpreta `TimeStampResp`/`TSTInfo` e confere o imprint. Compatível com ACTs ICP‑Brasil (configurar URL/credenciais da ACT contratada) e TSAs internacionais.

## Segurança e privacidade

- Nenhum conteúdo da prova é transmitido; o servidor remoto recebe apenas hashes e metadados mínimos.
- Senha do `.pfx` e PIN do token são pedidos no encerramento e não são gravados.
- O site navega em partição isolada (`persist:webnotary`), com `sandbox` e `contextIsolation`; a barra de ferramentas tem CSP sem `unsafe-inline`.
- User‑agent sem a marca Electron para compatibilidade com WhatsApp Web.

## Limitações conhecidas

- Áudio do sistema por loopback só no Windows; em Linux/macOS use o microfone ou um dispositivo de loopback virtual.
- Mídias servidas por `blob:`/streams criptografados (ex.: parte do WhatsApp) não têm corpo acessível via CDP — a gravação de vídeo e os screenshots cobrem o conteúdo exibido.
- O `report_sha256` registrado remotamente fica vazio porque a ata inclui o recibo (dependência circular); o hash da ata consta em `VERIFICACAO.txt` e no banco.
- Validação da cadeia ICP‑Brasil (âncoras, LCR/OCSP) não é feita no app; use o Verificador de Conformidade do ITI para a validação completa de certificados.
