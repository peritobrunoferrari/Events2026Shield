package com.shieldpericias.sentinel.duress

import android.util.Base64
import com.shieldpericias.sentinel.audit.AuditLog
import com.shieldpericias.sentinel.audit.AuditTypes
import com.shieldpericias.sentinel.core.duress.DuressAction
import com.shieldpericias.sentinel.core.duress.DuressPolicy
import com.shieldpericias.sentinel.core.duress.PinHasher
import com.shieldpericias.sentinel.core.duress.PinKind
import com.shieldpericias.sentinel.core.duress.PinRecord
import com.shieldpericias.sentinel.core.duress.PinVerifier
import com.shieldpericias.sentinel.securestorage.SecureKv
import org.json.JSONObject

/**
 * PIN da interface do Shield Sentinel (NÃO é o PIN do lockscreen do Android).
 * Registros PBKDF2 ficam cifrados no SecureKv; o PIN nunca é armazenado.
 */
class DuressPinManager(private val kv: SecureKv, private val audit: AuditLog) {

    companion object {
        private const val K_NORMAL = "duress.pin.normal"
        private const val K_DURESS = "duress.pin.duress"
        private const val K_POLICY = "duress.policy"
        private const val K_FAILS = "duress.fails"
        const val MIN_LENGTH = 4
    }

    fun hasNormalPin() = kv.contains(K_NORMAL)
    fun hasDuressPin() = kv.contains(K_DURESS)

    fun setNormalPin(pin: CharArray): Boolean {
        if (pin.size < MIN_LENGTH) return false
        val duress = load(K_DURESS)
        if (duress != null && PinHasher.matches(pin, duress)) return false
        save(K_NORMAL, PinHasher.create(pin))
        audit.append(AuditTypes.CONFIG_CHANGED, "pin normal definido")
        return true
    }

    fun setDuressPin(pin: CharArray): Boolean {
        if (!PinVerifier.validateNewDuressPin(pin, load(K_NORMAL), MIN_LENGTH)) return false
        save(K_DURESS, PinHasher.create(pin))
        audit.append(AuditTypes.CONFIG_CHANGED, "pin de emergência definido")
        return true
    }

    fun clearDuressPin() { kv.remove(K_DURESS); audit.append(AuditTypes.CONFIG_CHANGED, "pin de emergência removido") }
    fun clearAll() { kv.remove(K_NORMAL); kv.remove(K_DURESS); kv.remove(K_POLICY) }

    fun classify(pin: CharArray): PinKind {
        val kind = PinVerifier.classify(pin, load(K_NORMAL), load(K_DURESS))
        if (kind == PinKind.INVALID) {
            kv.putLong(K_FAILS, (kv.getLong(K_FAILS) ?: 0L) + 1)
            audit.append(AuditTypes.PIN_FAILED, null)
        } else kv.putLong(K_FAILS, 0L)
        return kind
    }

    fun failedAttempts(): Long = kv.getLong(K_FAILS) ?: 0L

    /** Atraso progressivo após falhas (anti força bruta na UI). */
    fun backoffMs(): Long { val f = failedAttempts(); return if (f < 3) 0L else minOf(60_000L, 1_000L shl (f - 3).toInt().coerceAtMost(6)) }

    fun policy(): DuressPolicy = kv.getString(K_POLICY)?.let {
        runCatching {
            val o = JSONObject(it)
            DuressPolicy(
                enabled = o.optBoolean("enabled", false),
                action = DuressAction.valueOf(o.optString("action", DuressAction.LOCK.name)),
                ownerAcknowledgedFactoryReset = o.optBoolean("ack", false),
                wipeEsim = o.optBoolean("esim", false),
            )
        }.getOrNull()
    } ?: DuressPolicy()

    fun savePolicy(p: DuressPolicy) {
        val o = JSONObject().put("enabled", p.enabled).put("action", p.action.name).put("ack", p.ownerAcknowledgedFactoryReset).put("esim", p.wipeEsim)
        kv.putString(K_POLICY, o.toString())
        audit.append(AuditTypes.CONFIG_CHANGED, "política duress: ${p.action} enabled=${p.enabled}")
    }

    private fun save(key: String, r: PinRecord) {
        val o = JSONObject().put("s", Base64.encodeToString(r.salt, Base64.NO_WRAP)).put("h", Base64.encodeToString(r.hash, Base64.NO_WRAP))
            .put("i", r.iterations).put("a", r.algorithm)
        kv.putString(key, o.toString())
    }

    private fun load(key: String): PinRecord? = kv.getString(key)?.let {
        runCatching {
            val o = JSONObject(it)
            PinRecord(Base64.decode(o.getString("s"), Base64.NO_WRAP), Base64.decode(o.getString("h"), Base64.NO_WRAP), o.getInt("i"), o.optString("a", PinHasher.ALGORITHM))
        }.getOrNull()
    }
}
