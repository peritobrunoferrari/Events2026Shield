# Guia de provisionamento Device Owner

O Shield Sentinel só aplica a maior parte das políticas como **Device Owner (DO)**. O Android permite definir um DO apenas em aparelho **sem contas de usuário** e **sem outro DO** — na prática, logo após a configuração inicial ou após restauração de fábrica. O app **nunca** executa reset nem tenta se promover; os dois caminhos abaixo são conduzidos pelo administrador.

## Pré-requisitos (tela MDM Local › Pré-requisitos)

| Requisito | Verificação no app | Observação |
|---|---|---|
| Android 12+ | automática | API ≥ 31 |
| Nenhum outro DPC | `isProvisioningAllowed(ACTION_PROVISION_MANAGED_DEVICE)` | retorna `false` também quando há contas |
| Nenhuma conta (Google, corporativa) | **não verificável pelo app** | remova em Configurações › Contas, ou faça reset |
| Bloqueio seguro definido depois do provisionamento | `KeyguardManager.isDeviceSecure` | necessário para verificação de desbloqueio |
| Bootloader bloqueado / AVB | não verificável por API pública | mantenha o aparelho em estado de fábrica |

**Aviso ao usuário:** em aparelhos já em uso o provisionamento pode exigir **restauração de fábrica** (apaga todos os dados). Faça backup antes.

## Caminho A — QR Code no assistente de configuração (recomendado para produção)

1. Hospede o APK (assinado com a chave de release) em uma URL **HTTPS** acessível durante o setup.
2. Em um aparelho já com o Sentinel instalado (ou no CI), gere o payload: tela **MDM Local › Via QR Code**, informe a URL e toque **Gerar QR**. O JSON contém:
   - `android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME` = `com.shieldpericias.sentinel/com.shieldpericias.sentinel.deviceowner.SentinelDeviceAdminReceiver`
   - `android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION` = URL do APK
   - `android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM` = SHA-256 (base64url) do certificado de assinatura — calculado automaticamente a partir do APK instalado
   - `PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED=true`, `PROVISIONING_SKIP_ENCRYPTION=false`, `PROVISIONING_LOCALE=pt_BR`
   - opcional: `PROVISIONING_WIFI_SSID/PASSWORD/SECURITY_TYPE`
3. No aparelho alvo, após o reset, na tela de boas-vindas **toque 6 vezes** no mesmo ponto → leitor de QR → conecte ao Wi-Fi → leia o QR.
4. O sistema baixa o APK, valida o checksum, instala e chama `onProfileProvisioningComplete`. O Sentinel registra `provisioning_complete` na auditoria e executa o Watchdog.
5. Defina PIN/senha de bloqueio na sequência do assistente.

> O checksum de **assinatura** (não do arquivo) permite atualizar o APK sem regenerar o QR. Use o checksum do APK só se a assinatura não estiver disponível.

## Caminho B — ADB (desenvolvimento e homologação)

Pré-condições: Opções do desenvolvedor + Depuração USB ativas; nenhuma conta adicionada; usuário primário.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell dpm set-device-owner com.shieldpericias.sentinel/com.shieldpericias.sentinel.deviceowner.SentinelDeviceAdminReceiver
```

Saída esperada: `Success: Device owner set to package ComponentInfo{...}` e `Active admin set to component ...`.

Erros comuns:

| Mensagem | Causa | Ação |
|---|---|---|
| `Not allowed to set the device owner because there are already some accounts on the device` | contas presentes | remover contas ou reset |
| `... already has a device owner` | outro DPC | `adb shell dpm remove-active-admin` do outro DPC só funciona se ele for testOnly; caso contrário, reset |
| `... device is already set-up` (Android 15+ em alguns OEMs) | restrição de setup concluído | provisionar via QR após reset |
| `Unknown admin` | component name errado | copie da tela MDM Local |

Remoção para testes (aparelho de desenvolvimento): tela **MDM Local › Remover Device Owner** (confirmação digitada `REMOVER`) ou `adb shell dpm remove-active-admin ...` (somente APK `testOnly`).

## Após provisionar

1. Permita notificações e alarmes exatos (tela **Watchdog**); opcionalmente, conceda `POST_NOTIFICATIONS` via Device Owner.
2. Verifique na tela **Diagnóstico** que a *prova de desbloqueio* fica AUTENTICADO logo após desbloquear.
3. Ative módulos progressivamente: USB (perfil Elevada antes de Máxima), Auto-Reboot em modo de ensaio, Watchdog, Emergência (armar), Antirroubo (manter ensaio até homologar), Duress por último.
4. Guarde o procedimento de recuperação ([RECOVERY.md](RECOVERY.md)) **antes** de aplicar o perfil USB Máxima ou `DISALLOW_DEBUGGING_FEATURES`.
