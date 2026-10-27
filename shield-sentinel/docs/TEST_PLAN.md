# Plano de testes

Regra absoluta: **nenhum teste automatizado executa factory reset, wipe ou reinicialização real.** Toda ação destrutiva passa por `WipeController`/`RebootExecutor`, substituídos por `FakeWipeController`/`FakeRebootExecutor` em testes e pelo modo de ensaio em runtime.

## 1. Testes unitários (JVM, `core-logic`) — automatizados, executam no CI e em `tools/jvm-verify`

| Classe | Casos |
|---|---|
| `InactivityLedgerTest` | sem baseline → inconclusivo; dentro do prazo; **56 reboots de 3 h não zeram contador de 7 d**; relógio adiantado (STRICT vs WALL_CLOCK_ONLY); relógio atrasado → anomalia; desbloqueio zera e limpa anomalia; aparelho desligado semanas → não comprova; planejador de reboot (Armed/Due/NotArmedBfu/NoBaseline) |
| `CommandVerifierTest` | aceitação + round-trip de transporte; outra chave; adulteração; dispositivo errado; chave desconhecida/revogada; expirado / ainda não válido / validade longa; replay por sequência e por ID; malformado; desabilitado |
| `DuressPinTest` | classificação normal/duress/inválido; salt aleatório; duress ≠ normal; política FACTORY_RESET exige reconhecimento; eSIM false |
| `SecurityLevelAndChainTest` | limite por modo administrativo; indisponíveis não penalizam; críticos limitam; hash chain detecta alteração; impactos dos perfis USB |

Executar: `./gradlew :core-logic:test` (24 testes) ou `tools/jvm-verify$ ./gradlew :core-logic-jvm:test`.

## 2. Verificação estática dos módulos Android — automatizada
`tools/jvm-verify$ ./gradlew :android-typecheck:compileKotlin` compila os 12 módulos Android contra o framework Android 17 (API 37, `org.robolectric:android-all:17-robolectric-15733970`). Garante uso correto das APIs públicas sem SDK instalado.

## 2b. Smoke test em emulador (CI) — automatizado
Workflow `shield-sentinel-emulator.yml` (runner Linux com KVM, imagem `google_apis` x86_64, API 36 por padrão; `workflow_dispatch` aceita outro nível). Script `tools/emulator/smoke-test.sh`:
1. instala o APK debug e executa `dpm set-device-owner` (emulador sem contas) — exige `Success`;
2. define PIN de bloqueio (`locksettings set-pin 1234`) para habilitar a prova de desbloqueio;
3. abre cada uma das 12 telas via extra `sentinel.route` (só em builds debug), captura screenshot e falha se houver `FATAL EXCEPTION` no logcat;
4. registra `dumpsys device_policy`, estado do `SentinelGuardService`, um ciclo bloquear/desbloquear e a auditoria (`audit.jsonl` via `run-as`).
Nenhuma ação destrutiva: não há wipe nem reboot. Artefato: `emulator-smoke-results` (PNGs, logcat, dumpsys).

## 3. Testes de integração (instrumentados, aparelho/emulador) — a executar na homologação
Pré-requisito: emulador ou aparelho secundário com o Sentinel como Device Owner (`adb shell dpm set-device-owner …`). **Use APK debug com `dryRun` ligado.**

| ID | Cenário | Passos | Resultado esperado |
|---|---|---|---|
| I-01 | Detecção de modo | abrir MDM Local | Modo = DEVICE_OWNER; capacidades coerentes com o aparelho |
| I-02 | USB Elevada | aplicar perfil; conectar ao PC | MTP indisponível; ADB funciona; `restrictionState` = ATIVO |
| I-03 | USB Máxima | aplicar (confirmar); conectar ao PC | sem enumeração de dados (só carga) se `canUsbDataSignalingBeDisabled`; caso contrário estado INDISPONÍVEL e aviso |
| I-04 | Reversão USB | Reverter tudo | MTP/ADB voltam; auditoria `policy_reverted` |
| I-05 | Prova de desbloqueio | bloquear; desbloquear com PIN; abrir Diagnóstico em < 60 s | "AUTENTICADO"; auditoria `verified_unlock` |
| I-06 | Desbloqueio não verificado | remover bloqueio seguro; desbloquear | `unlock_not_verified`; contador não zera |
| I-07 | Auto-Reboot ensaio | intervalo 30 min, ensaio ON; não desbloquear | após 30 min: `auto_reboot_skipped (dry-run)` e nenhum reboot |
| I-08 | Auto-Reboot real (manual, aparelho secundário) | ensaio OFF, 30 min | aparelho reinicia e fica em BFU; após desbloquear, timer rearma |
| I-09 | Chamada em andamento | iniciar chamada antes do vencimento | `auto_reboot_skipped: falha…` e nova tentativa em 10 min |
| I-10 | Boot em BFU | reiniciar; **não** desbloquear; aguardar 1 h | `AntiTheftAlarmReceiver` executa; auditoria BFU é mesclada após desbloqueio |
| I-11 | Auto-Wipe ensaio | prazo 3 d simulado via `FakeClock`? **não em aparelho** — usar teste unitário; em aparelho verificar apenas "Avaliação atual" coerente | decisão WAIT com restante correto |
| I-12 | Relógio manual | alterar data +30 d sem `DISALLOW_CONFIG_DATE_TIME` | STRICT: WAIT (tempo ligado insuficiente); nunca `auto_wipe_executed` |
| I-13 | Emergência | armar, botão, confirmar | lockNow + USB máx + status; auditoria `emergency_triggered` |
| I-14 | Tile | adicionar tile; tocar com tela desbloqueada | abre EmergencyActivity; desarmado → tile indisponível |
| I-15 | Atalho | toque longo no ícone › Emergência | mesma tela |
| I-16 | Remote command | `admin-cli keygen`; registrar chave; `admin-cli sign … LOCK`; colar | executado (lock); colar de novo → `REPLAY_SEQUENCE` |
| I-17 | Remote wipe pendente | sign WIPE_DEVICE; colar | Pendente de confirmação; Confirmar → ensaio simulado |
| I-18 | Duress LOCK | definir PINs; política LOCK; digitar duress no portão | aparelho bloqueia; UI mostra "PIN incorreto"; auditoria `duress_triggered` |
| I-19 | Duress FACTORY_RESET (ensaio) | reconhecer `APAGAR`; ensaio ON; digitar duress | `auto_wipe_dry_run`/wipe simulado; nenhum apagamento |
| I-20 | Watchdog perda | desligar Proteção Avançada nas Configurações | alerta "Perda de proteção detectada" em ≤ 30 min ou no próximo "Executar agora" |
| I-21 | Integridade | editar `sentinel_dp.xml` via `run-as` (debug) | "Integridade das configurações: ERRO"; Auto-Wipe inconclusivo |
| I-22 | Remoção do DO | MDM Local › Remover | modo NONE; nível ≤ BAIXO; tile indisponível |

## 4. Testes de falha e reinicialização
- Matar o processo (`am force-stop`) e reiniciar: alarmes do watchdog/antirroubo rearmam no boot; serviço de guarda volta.
- Boot loop simulado (3 reboots rápidos): `poweredSinceUnlockMs` acumula apenas tempo ligado real (teste unitário cobre).
- Atualização do APK (`MY_PACKAGE_REPLACED`): receptor reagenda.

## 5. Dispositivo secundário
Toda validação de reboot/wipe real ocorre **somente** em aparelho de testes sem dados pessoais, com ensaio desligado por decisão explícita e registrada.

## 6. Validação Android 17 (API 37)
- Compilar com `compileSdk=37` (padrão) e `36`.
- Verificar: FGS `specialUse` inicia a partir de `BOOT_COMPLETED`; `AdvancedProtectionManager` responde; `registerReceiver(RECEIVER_NOT_EXPORTED)` recebe `USER_PRESENT`; restrições de áudio/FGS do Android 17 não afetam o Sentinel (não usa áudio).
- Repetir I-01…I-22 em Pixel com Android 17 e documentar na [COMPATIBILITY_MATRIX.md](COMPATIBILITY_MATRIX.md).
