# Roadmap — próximas atualizações

Cada item abaixo é um **prompt autocontido**: pode ser colado numa nova sessão de desenvolvimento sem contexto adicional. Ordem = prioridade sugerida.

---

## 1. Modo "Cofre": WhatsApp (e outros apps sensíveis) em perfil gerenciado pelo Sentinel

```text
Atue como arquiteto sênior de segurança Android (Android Enterprise, DevicePolicyManager,
Kotlin, Jetpack Compose, criptografia, DFIR) no projeto Shield Sentinel (Shield Perícias).

CONTEXTO DO REPOSITÓRIO
- Projeto Gradle multi-módulo em shield-sentinel/ (AGP 9.4, Kotlin 2.4, compileSdk 37.1,
  minSdk 31). Módulos: app, core-logic (JVM puro), core-security, secure-storage, audit,
  device-owner, usb-security, device-hardening, auto-reboot, watchdog, emergency,
  anti-theft, remote-wipe, duress. Leia README.md, docs/FEATURE_MATRIX.md,
  docs/LIMITATIONS.md e docs/TEST_PLAN.md antes de codificar.
- Hoje o Sentinel opera como DEVICE OWNER (DPC: SentinelDeviceAdminReceiver). O estado
  real de cada recurso é exposto por FeatureState {Active, Inactive, Unavailable, Error}.
- CI: .github/workflows (android: APK debug/release; emulator: smoke test de 12 telas +
  13 testes instrumentados com Device Owner nas APIs 34 e 36, via
  tools/emulator/smoke-test.sh e tools/emulator/instrumented-test.sh).
- Nunca executar reboot, wipe ou reset reais em testes: use FakeRebootExecutor,
  FakeWipeController e o modo de ensaio (dry-run).

FATO ARQUITETURAL JÁ VERIFICADO (não rediscutir)
- Com Device Owner, o Android NÃO permite perfil de trabalho nem Private Space:
  UserManager.DISALLOW_ADD_MANAGED_PROFILE é true por padrão com DO (perfil gerenciado em
  aparelho totalmente gerenciado foi removido da plataforma na API 30) e
  DISALLOW_ADD_PRIVATE_PROFILE é true por padrão com DO e o DO não pode alterá-lo.
- Logo, o "Cofre" é um MODO DE IMPLANTAÇÃO ALTERNATIVO em que o Sentinel é PROFILE OWNER
  de um perfil de trabalho. Nesse modo não existe DevicePolicyManager.reboot(); o retorno
  do cofre ao estado "chave descartada" é feito por
  lockNow(DevicePolicyManager.FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY), permitido ao profile
  owner quando getStorageEncryptionStatus() == ENCRYPTION_STATUS_ACTIVE_PER_USER.

OBJETIVO
Implementar o módulo :vault-profile e a tela "Cofre", com duas variantes:
A) COFRE PESSOAL (aparelho do próprio usuário): o app inicia
   ACTION_PROVISION_MANAGED_PROFILE (sem reset de fábrica, sem exigir aparelho sem
   contas); o Sentinel vira Profile Owner do perfil; o usuário instala o WhatsApp dentro
   do perfil (Play no perfil ou sideload). Sem poderes sobre o lado pessoal.
B) COFRE CORPORATIVO (aparelho de propriedade da organização, Android 11+): perfil
   gerenciado provisionado no assistente de configuração por QR, com o DPC respondendo a
   ACTION_GET_PROVISIONING_MODE (PROVISIONING_MODE_MANAGED_PROFILE) e
   ACTION_ADMIN_POLICY_COMPLIANCE. Ganha poderes via getParentProfileInstance():
   setUsbDataSignalingEnabled, políticas de bloqueio do lado pessoal, wipeDevice()
   (factory reset) para o Duress/Auto-Wipe, DISALLOW_ADD_PRIVATE_PROFILE no pai.

POLÍTICAS DO PERFIL (aplicar com verificação de estado real e reversão)
- Senha própria do perfil obrigatória: DISALLOW_UNIFIED_PASSWORD + setRequiredPasswordComplexity
  (HIGH) no perfil; exibir isUsingUnifiedPassword() como alerta.
- Bloqueio por inatividade do perfil: reutilizar core-logic (InactivityLedger/RebootPlanner
  → renomear o conceito para "VaultLockPlanner") para disparar lockNow(FLAG_EVICT_...)
  após N minutos sem desbloqueio verificado do PERFIL (chave auth-bound criada no contexto
  do perfil). Padrão 15 min; opções 5/15/30/60. Também setMaximumTimeToLock no perfil.
- setScreenCaptureDisabled(true) no perfil; DISALLOW_CROSS_PROFILE_COPY_PASTE;
  DISALLOW_SHARE_INTO_MANAGED_PROFILE conforme opção; setCrossProfilePackages mínimo;
  DISALLOW_DEBUGGING_FEATURES no perfil; backup do perfil desativado
  (setBackupServiceEnabled(false)); DISALLOW_INSTALL_UNKNOWN_SOURCES no perfil opcional.
- Emergência/Duress no modo cofre: ação "Trancar cofre" (lockNow com evict), ação
  "Apagar cofre" (wipeData(0, reason) → remove SÓ o perfil, preservando o lado pessoal).
  Em B, manter opção de wipeDevice via instância pai com o mesmo reconhecimento digitado.
- Auto-Wipe por inatividade: no modo cofre o escopo padrão é PROFILE.

DETECÇÃO E UI
- core-security/CapabilityDetector: detectar modo (NONE / DEVICE_OWNER / PROFILE_OWNER /
  PROFILE_OWNER_ORG_OWNED via isOrganizationOwnedDeviceWithManagedProfile()),
  isProvisioningAllowed(ACTION_PROVISION_MANAGED_PROFILE), se o processo corre no perfil
  (UserManager.isManagedProfile) e se o usuário pessoal tem Private Space possível.
- Tela "Cofre": estado do perfil (existe? trancado? chave descartada?), botão "Criar
  cofre" (variante A), checklist pós-criação (definir senha própria, instalar WhatsApp no
  perfil, desligar backup do WhatsApp), políticas com switches, timer de trancamento,
  explicação honesta: com o cofre ABERTO o conteúdo está acessível; a proteção vem do
  trancamento automático e da senha própria.
- Matriz de modos em docs/FEATURE_MATRIX.md: colunas DEVICE_OWNER | COFRE PESSOAL |
  COFRE CORPORATIVO, marcando reboot() como indisponível fora de DO.

TESTES (CI, emulador, sem ações destrutivas)
- No emulador, criar o perfil sem DO:
    adb shell pm create-user --profileOf 0 --managed Cofre   → id N
    adb shell am start-user N
    adb shell dpm set-profile-owner --user N
       com.shieldpericias.sentinel/com.shieldpericias.sentinel.deviceowner.SentinelDeviceAdminReceiver
  e instalar o APK no perfil (adb install --user N). Adicionar job "vault" ao
  workflow do emulador (API 34 e 36) com testes instrumentados executados no usuário N
  (am instrument --user N): políticas do perfil aplicadas/revertidas, lockNow com
  FLAG_EVICT (verificar KeyguardManager/UserManager.isUserUnlocked(perfil) == false),
  wipeData do perfil SOMENTE com FakeWipeController.
- Testes unitários JVM para o planejador de trancamento (reaproveitar FakeClock).

DOCUMENTAÇÃO
- docs/VAULT.md: como instalar o WhatsApp no cofre (variante A e B), limitação do mesmo
  número (modo "dispositivo complementar"), Private Space como alternativa manual em
  Android 15+ sem Device Owner (o Sentinel não automatiza o Private Space).
- Atualizar PROVISIONING.md (QR para variante B), RECOVERY.md (remover perfil pela UI do
  sistema apaga o cofre), THREAT_MODEL.md, LIMITATIONS.md, TEST_PLAN.md.

RESTRIÇÕES
- Sem root. Apenas APIs públicas. Nenhuma telemetria. pt-BR na UI.
- Não copiar código GPL (x13a/*, Cryptomator).
- Preservar o modo Device Owner existente; o instalador escolhe UM modo por aparelho.
```

---

## 2. Internacionalização completa (pt-BR → recursos + en)

```text
No projeto Shield Sentinel (shield-sentinel/app), extraia TODOS os textos literais das
telas Compose (app/src/main/kotlin/.../ui/screens/*.kt e components/Common.kt) para
app/src/main/res/values/strings.xml (pt-BR, idioma padrão) e crie values-en/strings.xml
com tradução. Use stringResource()/pluralStringResource(). Mantenha os textos técnicos
(nomes de APIs como DISALLOW_USB_FILE_TRANSFER) idênticos nos dois idiomas. Não altere
lógica. Verifique com o CI (workflow android) que o APK compila e, no smoke test do
emulador, capture também screenshots com `adb shell settings put system system_locales en-US`.
```

---

## 3. Homologação em hardware (Pixel) — roteiro

```text
Siga docs/TEST_PLAN.md seções 3 a 6 em um Pixel de testes sem dados pessoais, com APK
debug e modo de ensaio ligado. Registre em docs/COMPATIBILITY_MATRIX.md: sinalização USB
(canUsbDataSignalingBeDisabled real), Auto-Reboot real com intervalo de 30 min,
desbloqueio biométrico vs. PIN na prova de desbloqueio (chave auth-bound), alarmes exatos
em Doze, tile de emergência na tela bloqueada, Proteção Avançada (Android 16+).
Só então gerar a chave de release e documentar o fluxo de atualização do APK via URL HTTPS.
```

---

## 4. Transporte opcional de comandos remotos

```text
Implementar um RemoteCommandTransport adicional (push via FCM ou polling HTTPS com
certificado pinado), mantendo a autorização exclusivamente pela assinatura verificada em
core-logic (CommandVerifier). O transporte não pode conceder nada: comando sem assinatura
válida é descartado e auditado. Desativado por padrão; sem permissão INTERNET no build
padrão (usar product flavor "remote" para incluir a permissão).
```

---

## 5. Landing page e kit de implantação

```text
Criar site estático (pt-BR) para venda da licença e do serviço de implantação do Shield
Sentinel: planos, FAQ técnico honesto baseado em docs/LIMITATIONS.md, identificação da
empresa (CNPJ, responsável técnico), termos com exclusão de garantia contra extração
forense e consentimento para reset de fábrica na instalação. Hospedagem estática; não
coletar dados além do contato. Incluir página de download do APK assinado com checksum
de assinatura (usado no QR de provisionamento).
```
