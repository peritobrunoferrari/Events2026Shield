package com.shieldpericias.sentinel.deviceowner

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Suporte ao provisionamento Device Owner (QR Code na configuração inicial e ADB).
 * Nunca executa reset nem tenta promover privilégios: apenas gera material e
 * verifica pré-requisitos.
 */
class ProvisioningHelper(private val context: Context) {

    data class Prerequisite(val label: String, val satisfied: Boolean?, val hint: String)

    val componentFlattened: String get() = SentinelDeviceAdminReceiver.component(context).flattenToString()

    /** Comando ADB equivalente (aparelho sem contas, após setup ou factory reset). */
    fun adbCommand(): String = "adb shell dpm set-device-owner $componentFlattened"

    /** SHA-256 (base64 URL-safe, sem padding) do APK instalado — EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM. */
    fun apkChecksum(): String? = runCatching {
        val path = context.applicationInfo.sourceDir ?: return null
        val md = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        Base64.encodeToString(md.digest(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }.getOrNull()

    /** SHA-256 do certificado de assinatura — EXTRA_PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM (preferível ao checksum do APK). */
    fun signatureChecksum(): String? = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        val cert = signers.firstOrNull()?.toByteArray() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(cert)
        Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }.getOrNull()

    /**
     * Payload JSON do QR Code (lido pelo assistente de configuração após tocar 6x na tela inicial).
     * O APK precisa estar acessível em [apkDownloadUrl] (HTTPS) durante o setup.
     */
    fun qrPayload(
        apkDownloadUrl: String,
        skipEncryption: Boolean = false,
        leaveSystemAppsEnabled: Boolean = true,
        wifiSsid: String? = null,
        wifiPassword: String? = null,
        wifiSecurity: String? = "WPA",
        useSignatureChecksum: Boolean = true,
    ): String {
        val o = JSONObject()
        o.put("android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME", componentFlattened)
        o.put("android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION", apkDownloadUrl)
        val sig = if (useSignatureChecksum) signatureChecksum() else null
        if (sig != null) o.put("android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM", sig)
        else apkChecksum()?.let { o.put("android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM", it) }
        o.put("android.app.extra.PROVISIONING_SKIP_ENCRYPTION", skipEncryption)
        o.put("android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED", leaveSystemAppsEnabled)
        if (!wifiSsid.isNullOrBlank()) {
            o.put("android.app.extra.PROVISIONING_WIFI_SSID", wifiSsid)
            wifiPassword?.let { o.put("android.app.extra.PROVISIONING_WIFI_PASSWORD", it) }
            wifiSecurity?.let { o.put("android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE", it) }
        }
        o.put("android.app.extra.PROVISIONING_LOCALE", "pt_BR")
        return o.toString(2)
    }

    fun prerequisites(facade: DevicePolicyFacade): List<Prerequisite> {
        val st = facade.status()
        return listOf(
            Prerequisite("Android 12 ou superior", Build.VERSION.SDK_INT >= 31, "API ${Build.VERSION.SDK_INT}"),
            Prerequisite("Aparelho ainda não gerenciado por outro DPC", if (st.isDeviceOwner) true else st.provisioningAllowed,
                "isProvisioningAllowed(ACTION_PROVISION_MANAGED_DEVICE) retorna false quando há contas, usuários extras ou outro Device Owner."),
            Prerequisite("Nenhuma conta Google/corporativa adicionada", null,
                "O sistema não expõe essa verificação ao app. Remova todas as contas (ou faça reset) antes do `dpm set-device-owner`."),
            Prerequisite("Shield Sentinel já é Device Owner", st.isDeviceOwner, "Quando verdadeiro, nenhum provisionamento adicional é necessário."),
            Prerequisite("Bootloader bloqueado / Verified Boot preservados", null,
                "O Sentinel não exige nem verifica desbloqueio de bootloader. Mantenha o aparelho em estado de fábrica (sem root)."),
        )
    }
}
