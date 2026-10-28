# Shield Sentinel

**Desenvolvedor:** Shield Perícias · **Plataforma:** Android 12+ (API 31), prioridade Android 16/17 (API 36/37) · **Homologação inicial:** Google Pixel (Pixel 11 Pro XL)

Aplicativo profissional de segurança para Android **sem root**, implementado como **Device Policy Controller (DPC)** em modo **Device Owner**, totalmente **local** (sem VPS, sem servidor, sem telemetria). Aplica políticas de proteção física, controle USB, reinicialização automática, bloqueio de emergência e resposta a perda/roubo usando **apenas APIs públicas do Android** (`DevicePolicyManager`, `UserManager`, `AdvancedProtectionManager`, Android Keystore).

> Estado do projeto: **0.1.0-alpha** — todas as fases (1–8) têm implementação inicial; o CI gera o APK de debug (artefato `shield-sentinel-debug-apk`) e executa um smoke test em emulador (Android 14 e 16, Device Owner provisionado, 12 telas sem crash; resultados nos branches `ci-emulator-results-api34/36`); a homologação em aparelho real ainda não foi executada. Veja [docs/LIMITATIONS.md](docs/LIMITATIONS.md).

## Princípios (verificáveis no código)

| Princípio | Como é cumprido |
|---|---|
| Sem root/Magisk/KernelSU/Shizuku/exploits | Só `DevicePolicyManager`, `UserManager`, `AlarmManager`, Keystore. Nenhum binário, shell ou `su`. |
| Bootloader bloqueado / AVB preservados | O app não exige nem verifica unlock; funciona em firmware de fábrica. |
| Offline | Nenhuma permissão `INTERNET`. Zero rede. |
| Modular | 14 módulos Gradle (ver abaixo). |
| Dados mínimos | Device Protected Storage guarda só timestamps/flags com HMAC; segredos em Credential Encrypted + AES-GCM (Keystore). |
| Sem analytics/telemetria | Não há SDK de terceiros além do ZXing (QR). `allowBackup=false`, regras de extração vazias. |
| Estado real de cada recurso | Tipo `FeatureState { Active, Inactive, Unavailable(reason), Error(message) }` em toda a UI. |
| Nada destrutivo em testes | `WipeController` com `FakeWipeController`; `RebootExecutor` com `FakeRebootExecutor`; modo de ensaio (dry-run) ligado por padrão no Auto-Wipe. |
| Distinção DO / PO / Device Admin / app comum | `AdminMode` detectado em runtime; toda política declara o modo exigido. |

## Módulos

```
app               Jetpack Compose (12 telas) + grafo de dependências manual (SentinelGraph)
core-logic        JVM puro — lógica verificável: livro-razão de inatividade, planejador de
                  reboot, protocolo de comando assinado, PIN (PBKDF2), nível de segurança, hash chain
core-security     Detecção de capacidades, AdminStatus, DeviceInfo, relógio (parede/monotônico/boot)
secure-storage    Keystore (AES-GCM, HMAC), SecureKv (CE), DeviceProtectedKv (DP + HMAC),
                  VerifiedUnlockProbe (chave auth-bound), InactivityLedgerStore
audit             Registro append-only JSONL com hash chain; buffer BFU; redação de segredos
device-owner      SentinelDeviceAdminReceiver, DevicePolicyFacade, ProvisioningHelper (QR/ADB)
usb-security      Camadas A (Proteção Avançada), B (sinalização USB), C (file transfer) + perfis
device-hardening  12 políticas de hardening + alertas de configurações inseguras
auto-reboot       RebootScheduler (AlarmManager), config, executor real/fake
watchdog          SentinelGuardService (FGS), UnlockVerifier, WatchdogChecker, alertas, boot
emergency         Emergency Action Engine, 4 ações, Quick Settings Tile, intents
anti-theft        WipeController (real/fake), AutoWipeEngine, agendador 1h (Direct Boot)
remote-wipe       Verificador de comandos assinados, trust anchors, anti-replay, transporte manual
duress            PIN normal/duress, política (LOCK / LOCK_AND_REBOOT / FACTORY_RESET), executor
tools/jvm-verify  Build auxiliar sem Android SDK: testes do core-logic, type-check dos módulos
                  Android contra o framework Android 17 (Robolectric android-all) e admin-cli
```

Dependências entre módulos são acíclicas: `app → *`; `watchdog → usb, hardening, auto-reboot, anti-theft`; `emergency → watchdog`; `duress → emergency, anti-theft`; `remote-wipe → anti-theft`.

## Build

Requisitos: JDK 17+, Android SDK com platform Android 17 (API 37.1; o AGP baixa automaticamente com licenças aceitas) e `build-tools;36.0.0`. Gradle 9.6 via wrapper; AGP 9.4.0 (Kotlin embutido); Kotlin 2.4.21; Compose BOM 2026.09.00 (Compose 1.12 exige compileSdk ≥ 37.1).

```bash
cd shield-sentinel
./gradlew :core-logic:test            # testes unitários JVM
./gradlew assembleDebug               # APK em app/build/outputs/apk/debug/
./gradlew -PsentinelCompileSdk=37 -PsentinelCompileSdkMinor=2 assembleDebug   # outra versão menor do SDK 37
```

Release (R8 + encolhimento de recursos). Para assinar, crie `app/keystore.properties` (ignorado pelo git) com `storeFile`, `storePassword`, `keyAlias`, `keyPassword`, ou exporte `SENTINEL_KEYSTORE_FILE`, `SENTINEL_KEYSTORE_PASSWORD`, `SENTINEL_KEY_ALIAS`, `SENTINEL_KEY_PASSWORD`. Sem isso o APK de release sai não assinado. O checksum de assinatura usado no QR de provisionamento deve ser o da chave de release.

```bash
./gradlew assembleRelease   # app/build/outputs/apk/release/
```

Sem Android SDK (ex.: CI restrito, este contêiner de desenvolvimento):

```bash
cd shield-sentinel/tools/jvm-verify
./gradlew :core-logic-jvm:test                  # 24 testes
./gradlew :android-typecheck:compileKotlin      # type-check de 12 módulos vs. Android 17
./gradlew :admin-cli:installDist && admin-cli/build/install/admin-cli/bin/admin-cli keygen
```

O workflow `.github/workflows/shield-sentinel-android.yml` executa tudo isso e publica o **APK de debug** como artefato.

## Fases e estado

| Fase | Conteúdo | Estado |
|---|---|---|
| 1 | Arquitetura, MDM local, detecção de capacidades, provisionamento QR/ADB | Implementada |
| 2 | Proteção USB (3 camadas, 3 perfis), hardening (12 políticas), alertas | Implementada |
| 3 | Auto-Reboot (3 h padrão), Watchdog, serviço de guarda, verificação de desbloqueio | Implementada |
| 4 | Emergency Action Engine, botão, atalho, Quick Settings Tile | Implementada |
| 5 | Auto-Wipe por inatividade (7 d padrão, STRICT evidence, dry-run) | Implementada |
| 6 | Protocolo Remote Wipe assinado + transporte manual (opcional) | Implementada |
| 7 | Duress PIN (UI própria; LOCK / LOCK+REBOOT / FACTORY_RESET opcional) | Implementada |
| 8 | Auditoria, testes, documentação | Implementada (homologação em aparelho pendente) |

## Documentação

- [docs/PROVISIONING.md](docs/PROVISIONING.md) — guia de provisionamento Device Owner (QR e ADB)
- [docs/FEATURE_MATRIX.md](docs/FEATURE_MATRIX.md) — matriz de recursos × modo administrativo × API; permissões
- [docs/RECOVERY.md](docs/RECOVERY.md) — guia de recuperação administrativa
- [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) — modelo de ameaças
- [docs/TEST_PLAN.md](docs/TEST_PLAN.md) — plano de testes
- [docs/COMPATIBILITY_MATRIX.md](docs/COMPATIBILITY_MATRIX.md) — compatibilidade (Pixel / Samsung / Motorola / Android 12–17)
- [docs/LIMITATIONS.md](docs/LIMITATIONS.md) — limitações documentadas

## Conceitos-chave

**Desbloqueio verificado.** O contador compartilhado por Auto-Reboot e Auto-Wipe só é zerado quando (1) o aparelho tem bloqueio seguro, (2) o evento `USER_PRESENT`/`onPasswordSucceeded` ocorre e (3) uma chave HMAC do Keystore criada com `setUserAuthenticationParameters(60 s, DEVICE_CREDENTIAL | BIOMETRIC_STRONG)` é utilizável — prova atestada pelo sistema de autenticação forte recente.

**Prova de inatividade (STRICT).** O Auto-Wipe exige que **relógio de parede** e **tempo ligado acumulado** (soma monotônica de `elapsedRealtime` entre boots, persistida em Direct Boot) superem o prazo. Relógio adiantado não causa apagamento; relógio atrasado marca anomalia e bloqueia a ação. Reinicializações de 3 h não zeram o contador (testado).

**Nível de segurança.** Percentual ponderado das verificações ativas entre as disponíveis, limitado pelo modo administrativo. É uma síntese, não uma promessa de resistência forense.

## Referências e licenças

- Android: `DevicePolicyManager`, DPC guide, `AdvancedProtectionManager` (API 36).
- x13a/Sentry, Wasted, Duress — **GPL-3.0**: usados somente como referência conceitual; nenhum código copiado (licença incompatível com distribuição proprietária).
- GrapheneOS features — referência de requisitos; nenhuma equivalência alegada.
- ZXing core 3.5.4 — Apache-2.0.
