# WebNotary

Navegador forense (Chromium via Electron) que **registra sessões web com valor probatório**: ao apertar um botão, grava **vídeo real da tela com áudio**, salva páginas, screenshots, mídias, tráfego de rede, certificados TLS e DNS, mantém um **log encadeado por hash no banco local**, e ao encerrar gera **hashes SHA‑256/512, manifesto assinado (CAdES, ICP‑Brasil A1/A3) com carimbo do tempo (RFC 3161), ata em PDF assinada automaticamente (PAdES)** e registra **apenas o hash raiz** em um servidor remoto de verificação (arquivo texto).

Serve para WhatsApp Web, redes sociais, e‑mail web, sites em geral e para **arquivos locais de vídeo, áudio, imagem e PDF abertos dentro do navegador**. Finalidade: prova técnica com cadeia de custódia (perícia, notarial, jurídica).

> Alternativa local e auditável a serviços como Verifact: **nada da prova sai do computador**. O servidor remoto recebe só hashes.

## Como funciona

```
┌──────────────── WebNotary (seu computador) ────────────────┐      ┌─ servidor de verificação ─┐
│ Navegador Chromium  ──► captura (CDP): rede, TLS, DNS,     │      │  registry.txt             │
│ [● Iniciar sessão]      screenshots, MHTML, mídias         │      │  (só hashes + recibo HMAC)│
│ MediaRecorder  ──► gravacao.webm (tela + áudio, em fluxo)  │ hash │  GET /api/verify/<hash>   │
│ Banco SQLite   ──► log de eventos encadeado (prev_hash)    │ ───► │  página pública de        │
│ [■ Encerrar]   ──► hashes → manifesto → CAdES-T (.p7s)     │      │  verificação              │
│                     → ata PDF → PAdES automático → recibo  │      └───────────────────────────┘
└────────────────────────────────────────────────────────────┘
```

Pasta de cada sessão (`Documentos/WebNotary/sessoes/<id>/`):

| Arquivo | Conteúdo |
|---|---|
| `gravacao.webm` | Vídeo real da tela (VP9/Opus), gravado em trechos de 1 s direto em disco |
| `capturas/*.png`, `paginas/*.mhtml` | Screenshot e página completa a cada navegação (e sob demanda) |
| `midia/*` | Imagens, vídeos e áudios recebidos pela rede (via DevTools Protocol) |
| `anexos/*` | Arquivos anexados pelo operador e downloads |
| `rede/trafego.jsonl`, `rede/cert-<host>.pem` | Requisições/respostas (IP remoto, TLS, cabeçalhos) e cadeias de certificados |
| `eventos.jsonl` | Log encadeado exportado do banco (cada evento referencia o hash do anterior) |
| `manifesto.json` / `manifesto.txt` | Lista de artefatos + SHA‑256/512 + **hash raiz** |
| `manifesto.json.p7s`, `carimbo-manifesto.tsr` | Assinatura CAdES destacada (com carimbo embutido) e token RFC 3161 |
| `relatorio.pdf`, `relatorio-assinado.pdf` | Ata de registro; a versão assinada é **PAdES** (abre no Adobe Reader / Verificador ITI) |
| `recibo-registro.json`, `VERIFICACAO.txt` | Recibo do servidor remoto e instruções de verificação |

## Instalação

Requer **Node.js 22** (ou 20+) — https://nodejs.org

```bash
cd webnotary
./scripts/install.sh        # Linux/macOS
# ou, no Windows:  powershell -ExecutionPolicy Bypass -File scripts\install.ps1
npm start                   # abre o WebNotary
npm run dist:win            # instalador Windows (NSIS) em dist/
npm run dist:linux          # AppImage + .deb
```

Certificado A3 (token/cartão): instale o driver do fabricante e, em **⚙ Configurações → Certificado → A3**, informe a biblioteca PKCS#11 (ex.: `C:\Windows\System32\eTPKCS11.dll`, `/usr/lib/libeTPkcs11.so`). O módulo nativo `pkcs11js` é opcional; se faltar, rode `npm install pkcs11js` (precisa de compilador C++ ou binário pré‑compilado).

## Uso

1. **⚙ Configurações**: nome/CPF/registro do operador, certificado (A1 `.pfx` ou A3), URL da ACT de carimbo do tempo, URL+token do servidor de verificação, fonte do vídeo (tela inteira ou janela), microfone.
2. Navegue até o site (WhatsApp Web, etc.) ou abra um arquivo local (**📂 Arquivo**).
3. **● Iniciar sessão** → informe a finalidade/caso. A gravação começa; o status mostra o cronômetro.
4. Durante a sessão: **📷 Capturar** (screenshot + MHTML agora), **📝 Nota**, **📎 Anexar**.
5. **■ Encerrar sessão** → observações finais + senha/PIN do certificado. O app gera tudo e mostra o hash raiz.
6. **📁 Sessões**: histórico no banco local com hash raiz e botão **Verificar** (recalcula hashes, valida assinaturas, carimbo e registro remoto).

Verificação por linha de comando (funciona em qualquer máquina com Node):

```bash
node cli/webnotary-verify.js "/caminho/da/sessao" --servidor https://verifica.exemplo.com.br
```

Verificação independente com ferramentas padrão:

```bash
sha256sum -c <(awk ...)                                   # ou compare manualmente com manifesto.txt
openssl cms -verify -binary -inform DER -in manifesto.json.p7s -content manifesto.json -noverify
openssl ts -reply -in carimbo-manifesto.tsr -text
```

## Servidor de verificação

Node puro, sem dependências; guarda **um arquivo texto** (`registry.txt`, uma linha por sessão) e devolve recibo HMAC:

```bash
PORT=8787 WEBNOTARY_API_TOKEN=um-token-secreto PUBLIC_URL=https://verifica.exemplo.com.br node server/verify-server.js
```

- `POST /api/register` (Bearer token) — recebe `root_hash`, `session_id`, `manifest_sha256`, `signature_sha256`, operador, genTime.
- `GET /api/verify/<hash>` — consulta pública; `GET /` — página de verificação para terceiros (advogados, juízo).

## Testes

```bash
npm test     # 12 testes: hash, banco/cadeia, CAdES/CAdES-T, PKCS#12, RFC 3161, sessão completa, servidor, verificação e adulteração
```

## Documentação

- [docs/ARQUITETURA.md](docs/ARQUITETURA.md) — módulos, formatos, decisões técnicas
- [docs/JURIDICO.md](docs/JURIDICO.md) — fundamentos legais (Brasil) e boas práticas de uso probatório
- [docs/ROADMAP.md](docs/ROADMAP.md) — fase 2: testemunha IA por chamada de vídeo, gov.br, eIDAS/exterior, sugestões

## Licença

MIT.
