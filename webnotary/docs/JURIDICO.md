# Fundamentos jurídicos e boas práticas (Brasil)

> Este documento orienta o uso técnico-probatório do WebNotary. Não substitui a análise do advogado/perito no caso concreto.

## Base legal

- **CPC, art. 369** — admissibilidade de todos os meios legais e moralmente legítimos de prova; **art. 422** — reprodução mecânica (fotográfica, cinematográfica, fonográfica) prova fatos ou coisas representadas, se sua conformidade não for impugnada; **§1º** — fotografias digitais e extraídas da internet fazem prova quando acompanhadas da autenticação eletrônica (ou, se impugnadas, perícia); **art. 384** — ata notarial (o WebNotary não substitui o tabelião, mas produz o material técnico que pode embasar ata notarial ou laudo).
- **MP 2.200‑2/2001** — institui a ICP‑Brasil; documentos eletrônicos assinados com certificado ICP‑Brasil presumem‑se verdadeiros em relação aos signatários (art. 10, §1º).
- **Lei 14.063/2020** — níveis de assinatura eletrônica (simples, avançada, qualificada). A assinatura **qualificada** (ICP‑Brasil, A1/A3) é a produzida pelo WebNotary; gov.br avançada entra na fase 2.
- **Lei 12.965/2014 (Marco Civil)** e **LGPD (Lei 13.709/2018)** — guarda de registros e tratamento de dados pessoais presentes na prova: a prova fica local, sob controle do perito; o servidor remoto recebe apenas hashes.
- **CPP, arts. 158‑A a 158‑F** — cadeia de custódia (vestígios digitais por analogia): reconhecimento, coleta, acondicionamento, documentação. O manifesto, o log encadeado e o hash raiz implementam esses passos.
- **Norma ABNT NBR ISO/IEC 27037** — identificação, coleta, aquisição e preservação de evidência digital.

## O que o WebNotary entrega como garantias

| Garantia | Mecanismo |
|---|---|
| Integridade | SHA‑256/512 por artefato + hash raiz; log encadeado por hash |
| Autoria/responsabilidade | Assinatura CAdES do manifesto e PAdES da ata com certificado ICP‑Brasil do operador |
| Tempo | Carimbo do tempo RFC 3161 de ACT ICP‑Brasil sobre a assinatura (CAdES‑T / PAdES‑T) + registro remoto com hora do servidor e recibo |
| Contexto técnico | IP remoto, TLS, DNS, cabeçalhos, versões, fuso, IP público, dados do operador |
| Reprodutibilidade | Vídeo contínuo da tela com áudio, screenshots e páginas MHTML |
| Verificabilidade por terceiros | `manifesto.txt` legível, `VERIFICACAO.txt`, CLI, OpenSSL, Verificador ITI, página pública do servidor |

## Boas práticas recomendadas

1. Configure operador e certificado **antes** da sessão; teste o certificado em ⚙.
2. Grave a **tela inteira**, com relógio do SO visível; narre pelo microfone o que está fazendo (opcional).
3. Comece a sessão **antes** de abrir o conteúdo-alvo: mostre o endereço, o certificado do site (cadeado) e a identificação do interlocutor (número de telefone, perfil).
4. Não edite nada na pasta da sessão depois de encerrada. Guarde cópia em mídia somente leitura e registre o hash raiz no laudo/ata notarial.
5. Em caso de impugnação, entregue a pasta íntegra e indique o comando de verificação; qualquer alteração será detectada.
6. Para força probatória máxima, combine: ata notarial do tabelião presenciando a sessão **+** o pacote técnico do WebNotary.
