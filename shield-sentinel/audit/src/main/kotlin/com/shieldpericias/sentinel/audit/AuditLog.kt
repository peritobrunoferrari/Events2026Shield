package com.shieldpericias.sentinel.audit

import android.content.Context
import com.shieldpericias.sentinel.core.audit.HashChain
import org.json.JSONObject
import java.io.File

data class AuditEvent(
    val type: String,
    val atEpochMs: Long,
    val detail: String?,
    val previousHash: String,
    val hash: String,
)

/**
 * Registro mínimo de eventos, local, append-only, com encadeamento de hashes.
 * - Fica em `noBackupFilesDir` (Credential Encrypted; não vai para backup).
 * - Eventos ocorridos em BFU (antes do desbloqueio) vão para um buffer em
 *   Device Protected Storage contendo apenas tipo+instante, mesclado depois.
 * - Nunca registre PINs, chaves ou conteúdo pessoal em `detail`.
 */
class AuditLog(context: Context, private val maxEntries: Int = 2_000) {

    private val appContext = context.applicationContext
    private val file: File by lazy { File(appContext.noBackupFilesDir, "audit.jsonl") }
    private val bfuFile: File by lazy {
        File(appContext.createDeviceProtectedStorageContext().noBackupFilesDir, "audit-bfu.jsonl")
    }
    private val lock = Any()

    /** Registra evento. Se o usuário ainda não desbloqueou (CE indisponível), usa o buffer BFU. */
    fun append(type: String, detail: String? = null) {
        synchronized(lock) {
            if (!isUserUnlocked()) {
                appendBfu(type)
                return
            }
            mergeBfuLocked()
            appendLocked(type, System.currentTimeMillis(), Redaction.sanitize(detail))
        }
    }

    private fun appendLocked(type: String, at: Long, detail: String?) {
        val prev = lastHashLocked()
        val payload = JSONObject().put("t", type).put("ts", at).put("d", detail ?: JSONObject.NULL).toString()
        val hash = HashChain.next(prev, payload)
        val line = JSONObject().put("t", type).put("ts", at).put("d", detail ?: JSONObject.NULL).put("p", prev).put("h", hash)
        file.appendText(line.toString() + "\n")
        trimLocked()
    }

    private fun appendBfu(type: String) {
        runCatching { bfuFile.appendText(JSONObject().put("t", type).put("ts", System.currentTimeMillis()).toString() + "\n") }
    }

    private fun mergeBfuLocked() {
        if (!bfuFile.exists()) return
        val lines = runCatching { bfuFile.readLines() }.getOrDefault(emptyList())
        for (l in lines) {
            runCatching {
                val o = JSONObject(l)
                appendLocked(o.getString("t"), o.getLong("ts"), "registrado em BFU")
            }
        }
        bfuFile.delete()
    }

    private fun lastHashLocked(): String {
        if (!file.exists()) return HashChain.GENESIS
        val last = file.readLines().lastOrNull { it.isNotBlank() } ?: return HashChain.GENESIS
        return runCatching { JSONObject(last).getString("h") }.getOrDefault(HashChain.GENESIS)
    }

    private fun trimLocked() {
        val lines = file.readLines()
        if (lines.size > maxEntries) {
            // Mantém a cadeia válida: o primeiro remanescente continua apontando para o hash anterior real.
            file.writeText(lines.takeLast(maxEntries).joinToString("\n") + "\n")
        }
    }

    fun readAll(limit: Int = 500): List<AuditEvent> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        file.readLines().filter { it.isNotBlank() }.takeLast(limit).mapNotNull { l ->
            runCatching {
                val o = JSONObject(l)
                AuditEvent(o.getString("t"), o.getLong("ts"), o.optString("d", null), o.getString("p"), o.getString("h"))
            }.getOrNull()
        }.reversed()
    }

    /** Verifica a cadeia; retorna índice (do mais antigo) da primeira quebra ou -1 quando íntegra. */
    fun verifyChain(): Int = synchronized(lock) {
        if (!file.exists()) return -1
        val entries = file.readLines().filter { it.isNotBlank() }.mapNotNull { l ->
            runCatching {
                val o = JSONObject(l)
                val payload = JSONObject().put("t", o.getString("t")).put("ts", o.getLong("ts"))
                    .put("d", if (o.isNull("d")) JSONObject.NULL else o.getString("d")).toString()
                Triple(o.getString("p"), payload, o.getString("h"))
            }.getOrNull()
        }
        if (entries.isEmpty()) return -1
        // Após trim o primeiro `prev` pode não ser GENESIS: verifica a partir dele.
        var prev = entries.first().first
        entries.forEachIndexed { i, (p, payload, h) ->
            if (p != prev || HashChain.next(p, payload) != h) return i
            prev = h
        }
        -1
    }

    fun clear() = synchronized(lock) { file.delete(); bfuFile.delete(); Unit }

    private fun isUserUnlocked(): Boolean =
        appContext.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked ?: true
}

/** Remove padrões que pareçam PIN/senha ou chaves de `detail`. Defesa em profundidade. */
object Redaction {
    private val digitRun = Regex("\\b\\d{4,}\\b")
    private val base64Long = Regex("[A-Za-z0-9+/_-]{40,}={0,2}")

    fun sanitize(detail: String?): String? {
        if (detail == null) return null
        return detail.replace(base64Long, "[redigido]").replace(digitRun, "[####]").take(400)
    }
}

object AuditTypes {
    const val APP_START = "app_start"
    const val ADMIN_ENABLED = "admin_enabled"
    const val ADMIN_DISABLED = "admin_disabled"
    const val PROVISIONING_COMPLETE = "provisioning_complete"
    const val POLICY_APPLIED = "policy_applied"
    const val POLICY_REVERTED = "policy_reverted"
    const val POLICY_FAILED = "policy_failed"
    const val VERIFIED_UNLOCK = "verified_unlock"
    const val UNLOCK_NOT_VERIFIED = "unlock_not_verified"
    const val BOOT = "boot"
    const val AUTO_REBOOT_ARMED = "auto_reboot_armed"
    const val AUTO_REBOOT_EXECUTED = "auto_reboot_executed"
    const val AUTO_REBOOT_SKIPPED = "auto_reboot_skipped"
    const val WATCHDOG_RUN = "watchdog_run"
    const val WATCHDOG_ALERT = "watchdog_alert"
    const val EMERGENCY_TRIGGERED = "emergency_triggered"
    const val EMERGENCY_REFUSED = "emergency_refused"
    const val AUTO_WIPE_EVALUATED = "auto_wipe_evaluated"
    const val AUTO_WIPE_DRY_RUN = "auto_wipe_dry_run"
    const val AUTO_WIPE_EXECUTED = "auto_wipe_executed"
    const val REMOTE_COMMAND_REJECTED = "remote_command_rejected"
    const val REMOTE_COMMAND_ACCEPTED = "remote_command_accepted"
    const val REMOTE_COMMAND_EXECUTED = "remote_command_executed"
    const val DURESS_TRIGGERED = "duress_triggered"
    const val PIN_FAILED = "pin_failed"
    const val CONFIG_CHANGED = "config_changed"
    const val INTEGRITY_FAILURE = "integrity_failure"
}
