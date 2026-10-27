package com.shieldpericias.sentinel.securestorage

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

/**
 * Armazenamento em Device Protected Storage (Direct Boot): disponível ANTES do
 * primeiro desbloqueio. Deve conter o mínimo necessário (timestamps, flags de
 * configuração) e NUNCA segredos. Cada valor recebe um HMAC do Keystore para
 * detectar adulteração; valores com MAC inválido são tratados como ausentes e
 * contabilizados em [integrityFailures].
 */
class DeviceProtectedKv(context: Context, private val crypto: KeystoreCrypto, name: String = "sentinel_dp") {

    private val prefs: SharedPreferences = context.applicationContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(name, Context.MODE_PRIVATE)

    @Volatile
    var integrityFailures: Int = 0
        private set

    fun put(key: String, value: String) {
        val data = (key + "\u0000" + value).toByteArray(Charsets.UTF_8)
        val mac = Base64.encodeToString(crypto.mac(data), Base64.NO_WRAP)
        prefs.edit().putString(key, value).putString(macKey(key), mac).apply()
    }

    fun get(key: String): String? {
        val value = prefs.getString(key, null) ?: return null
        val mac = prefs.getString(macKey(key), null)
        if (mac == null) { integrityFailures++; return null }
        val data = (key + "\u0000" + value).toByteArray(Charsets.UTF_8)
        return try {
            if (crypto.macMatches(data, Base64.decode(mac, Base64.NO_WRAP))) value else { integrityFailures++; null }
        } catch (e: Exception) {
            integrityFailures++; null
        }
    }

    fun putLong(key: String, value: Long) = put(key, value.toString())
    fun getLong(key: String): Long? = get(key)?.toLongOrNull()
    fun putInt(key: String, value: Int) = put(key, value.toString())
    fun getInt(key: String): Int? = get(key)?.toIntOrNull()
    fun putBoolean(key: String, value: Boolean) = put(key, if (value) "1" else "0")
    fun getBoolean(key: String, default: Boolean = false): Boolean = get(key)?.let { it == "1" } ?: default
    fun remove(key: String) = prefs.edit().remove(key).remove(macKey(key)).apply()
    fun contains(key: String) = prefs.contains(key)

    /** Verifica a integridade de todas as entradas. Retorna chaves com MAC inválido. */
    fun verifyAll(): List<String> = prefs.all.keys.filter { !it.endsWith(MAC_SUFFIX) }.filter { get(it) == null }

    private fun macKey(key: String) = key + MAC_SUFFIX

    companion object { private const val MAC_SUFFIX = ".mac" }
}
