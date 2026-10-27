package com.shieldpericias.sentinel.core.audit

import java.security.MessageDigest

/** Encadeamento de hashes para detectar remoção/alteração de linhas de auditoria. */
object HashChain {
    const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

    fun next(previousHex: String, payload: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(previousHex.toByteArray(Charsets.UTF_8))
        md.update(0x1F)
        md.update(payload.toByteArray(Charsets.UTF_8))
        return md.digest().toHex()
    }

    /** Verifica uma sequência (prev, payload, hash). Retorna índice da primeira quebra ou -1. */
    fun firstBreak(entries: List<Triple<String, String, String>>): Int {
        var prev = GENESIS
        entries.forEachIndexed { i, (p, payload, hash) ->
            if (p != prev) return i
            if (next(p, payload) != hash) return i
            prev = hash
        }
        return -1
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
