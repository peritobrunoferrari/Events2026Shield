# Roadmap e sugestões

## Fase 1 (entregue)
Navegador Chromium com botão de sessão, gravação real de vídeo+áudio, captura de rede/TLS/DNS/MHTML/screenshots/mídias, banco local com log encadeado e hash raiz, manifesto assinado CAdES‑T (A1/A3), ata PDF assinada automaticamente (PAdES), servidor de verificação em arquivo texto, CLI de verificação, instaladores (electron‑builder).

## Fase 2 — "ente IA" testemunha por chamada de vídeo
Objetivo: um agente de IA participa de uma chamada de vídeo (ou acompanha a sessão em tempo real), **descreve o que vê**, gera hash e assina um termo próprio, funcionando como segunda testemunha eletrônica independente.

Desenho proposto:
1. **Agente local** (`src/agent/`) que recebe os trechos do vídeo e os screenshots durante a sessão e, a cada N segundos, envia quadros para o modelo (API Claude, visão) com o prompt "descreva objetivamente o que está na tela; não infira intenções". As descrições são gravadas como eventos `ia.observacao` no log encadeado.
2. **Chamada de vídeo**: via WebRTC (sala própria no servidor de verificação, ou integração com Meet/Jitsi), onde o agente entra como participante, grava do seu lado (servidor) e gera hash independente do mesmo intervalo. Dois hashes (perito e agente) são cruzados no final (`ia.hash_cruzado`).
3. **Termo da testemunha IA**: PDF gerado e assinado com certificado do **serviço** (A1 institucional) + carimbo do tempo, listando as observações e os hashes cruzados. Transparência: o prompt, o modelo e a versão ficam no termo.
4. Limites a documentar: a IA não atesta autenticidade de conteúdo, apenas o que foi exibido; a verificação humana continua necessária.

## Fase 2 — assinaturas e exterior
- **gov.br (assinatura avançada)**: integração com a API de assinatura do gov.br (exige credenciamento da aplicação no Serpro).
- **eIDAS (UE)**: as assinaturas já são CAdES/PAdES (ETSI EN 319 122/142); falta validação de cadeias de TSPs europeus e carimbo de TSA qualificada da UE; perfis **‑LT/‑LTA** (inclusão de dados de revogação) para arquivamento de longo prazo.
- **EUA/Reino Unido**: compatibilidade com Adobe AATL e com e‑signature acts (ESIGN/UETA) — essencialmente requisitos de processo, já cobertos pelo log encadeado.
- Validação de cadeia ICP‑Brasil dentro do app (âncoras AC Raiz v5+, LCR/OCSP) e exibição do status no relatório.

## Sugestões adicionais
- **Ancoragem pública do hash raiz** (OpenTimestamps/blockchain) como terceira fonte de tempo, barata e independente.
- **Exportação de pacote** `.zip` com o conteúdo da sessão + script de verificação standalone para o juízo.
- **Extração estruturada de conversas** (WhatsApp Web): leitura do DOM para gerar transcrição em texto/CSV com hash próprio, facilitando citação no laudo.
- **Modo "duas telas"**: gravar simultaneamente a webcam do perito (prova de presença) e a tela.
- **Assinatura em lote** de várias sessões e **relatório consolidado** por caso.
- **Integração com o IPED**: importar a pasta da sessão como evidência (os formatos MHTML/WEBM/JSONL já são indexáveis).
- Build assinado com certificado de código (Authenticode) para evitar alertas do Windows SmartScreen.
