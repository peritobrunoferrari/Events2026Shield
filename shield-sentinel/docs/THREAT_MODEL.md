# Modelo de ameaças — Shield Sentinel

## Ativos
1. Dados do usuário em Credential Encrypted storage (fotos, mensagens, credenciais de apps).
2. Chaves do Keystore e segredos do Sentinel (registros de PIN, chaves públicas administrativas).
3. Integridade das políticas de segurança aplicadas.
4. Disponibilidade do aparelho para o proprietário legítimo (evitar auto-apagamento indevido).

## Adversários
| ID | Adversário | Capacidades | Fora de escopo |
|---|---|---|---|
| A1 | Ladrão oportunista | posse física; tenta USB/MTP, pendrive, manter aparelho ligado em AFU | — |
| A2 | Agente com ferramenta de extração | posse física; cabo USB; exploits AFU/BFU de terceiros; manipulação de relógio sem rede | exploits de bootloader/firmware (fora do alcance de qualquer app) |
| A3 | Usuário local coagido | acesso à UI com o proprietário sob coação | interceptar o lockscreen nativo (não implementado) |
| A4 | Atacante remoto | tenta disparar wipe/lock sem autorização (SMS, broadcast, link) | — |
| A5 | App malicioso no aparelho | permissões normais; tenta remover políticas ou enviar comandos | root (fora do modelo: aparelho sem root) |

## Controles por ameaça

| Ameaça | Controle | Limite |
|---|---|---|
| Extração via USB em AFU (A1, A2) | Camada A (Proteção Avançada), B (`setUsbDataSignalingEnabled(false)`), C (`DISALLOW_USB_FILE_TRANSFER`, `DISALLOW_MOUNT_PHYSICAL_MEDIA`) | B depende do suporte OEM; nenhuma camada equivale a controle de hardware |
| Aparelho mantido ligado em AFU para ataque posterior (A2) | Auto-Reboot após 3 h sem desbloqueio verificado → BFU | alarmes podem ser adiados em Doze sem permissão de alarme exato; `reboot()` falha em chamada ativa (nova tentativa 10 min) |
| Tentativa de desbloqueio sem credencial para "resetar" contadores (A1, A2) | contador zera só com prova criptográfica de autenticação forte (chave auth-bound) | biometria classe 1/2 não conta; sem bloqueio seguro nada conta |
| Manipulação de relógio para forçar ou evitar wipe (A2) | política STRICT: relógio **e** tempo ligado; relógio retrocedido → anomalia → não apaga; `DISALLOW_CONFIG_DATE_TIME` + hora automática | adversário pode evitar o wipe mantendo o aparelho desligado (aceito: BFU) |
| Perda/roubo prolongado (A1) | Auto-Wipe 7 d (3/7/14/30), desativado por padrão, ensaio por padrão | Profile Owner apaga só o perfil; Device Admin legado não apaga |
| Comando remoto forjado (A4) | ECDSA P-256, vínculo com deviceId, validade ≤ 24 h, contador + UUID anti-replay, revogação, confirmação local, desativado por padrão; sem SMS/broadcast/webhook | transporte não implementado além do manual; a chave privada do admin é ponto único de falha (guardar offline) |
| Coação para abrir o app (A3) | PIN duress executa LOCK / LOCK+REBOOT / FACTORY_RESET (opcional) sem confirmação, UI neutra | só dentro do app; sem disfarce; não atua no lockscreen nativo |
| Remoção das políticas (A5, A1 com credencial) | Device Owner (apps comuns não removem), `setUninstallBlocked`, Watchdog com alertas de perda | quem tem a credencial pode remover o DO; reset via recovery sempre possível |
| Vazamento de segredos do app (A5) | SecureKv AES-GCM (Keystore, hardware), DP storage só com timestamps + HMAC, redação na auditoria, `FLAG_SECURE`, `allowBackup=false` | Keystore em software em aparelhos sem TEE (exibido em Diagnóstico) |
| Força bruta no PIN do app (A3, A5) | PBKDF2 210k iterações, backoff progressivo, trabalho constante entre PIN normal e duress | PIN de 4 dígitos é fraco por natureza; recomende ≥ 6 |
| Auto-apagamento indevido (ativo 4) | ensaio por padrão; confirmação digitada `APAGAR`; estado inconclusivo nunca apaga; eSIM preservada; auditoria | — |

## Suposições
- Aparelho com bootloader bloqueado, Verified Boot verde e sem root.
- FBE funcional (Android 10+ exige). "Bloqueio de tela ≠ BFU": só o reboot leva a BFU.
- O proprietário mantém credencial forte e o PIN do app documentado em local seguro.

## Não-objetivos
- Resistir a exploits de firmware/bootloader ou a extração BFU por vulnerabilidades do SoC.
- Impedir reset de fábrica via recovery.
- Substituir Proteção Avançada, GrapheneOS ou um MDM corporativo com servidor.
