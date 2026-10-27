package com.shieldpericias.sentinel.securestorage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Prova criptográfica de desbloqueio autenticado recente.
 *
 * Uma chave HMAC do Keystore é criada com `setUserAuthenticationRequired(true)` e
 * validade de [timeoutSeconds] após autenticação forte (credencial do aparelho ou
 * biometria classe 3). Se o uso da chave for bem-sucedido logo após USER_PRESENT,
 * o sistema atestou que o usuário se autenticou nos últimos segundos. Um simples
 * "deslizar para desbloquear" ou um aparelho sem bloqueio seguro NÃO passa.
 */
class VerifiedUnlockProbe(
    private val alias: String = ALIAS,
    private val timeoutSeconds: Int = 60,
) {
    companion object { const val ALIAS = "sentinel.auth-bound.v1" }

    sealed class Result {
        data object Authenticated : Result()
        data object NotAuthenticated : Result()
        data object KeyInvalidated : Result()
        data class Unavailable(val reason: String) : Result()
    }

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KeystoreCrypto.PROVIDER).apply { load(null) } }

    @Synchronized
    private fun keyOrCreate(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(
                timeoutSeconds,
                KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG,
            )
            .setInvalidatedByBiometricEnrollment(false)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KeystoreCrypto.PROVIDER)
            .apply { init(spec) }.generateKey()
    }

    fun probe(): Result {
        val key = try {
            keyOrCreate()
        } catch (e: IllegalStateException) {
            // Sem bloqueio de tela seguro não é possível criar chave auth-bound.
            return Result.Unavailable(e.message ?: "secure lock screen required")
        } catch (e: Exception) {
            return Result.Unavailable(e.javaClass.simpleName)
        }
        return try {
            Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal("sentinel".toByteArray())
            Result.Authenticated
        } catch (e: UserNotAuthenticatedException) {
            Result.NotAuthenticated
        } catch (e: KeyPermanentlyInvalidatedException) {
            runCatching { keyStore.deleteEntry(alias) }
            Result.KeyInvalidated
        } catch (e: Exception) {
            Result.Unavailable(e.javaClass.simpleName)
        }
    }

    fun reset() { runCatching { keyStore.deleteEntry(alias) } }
}
