package com.shieldpericias.sentinel.core.remote

import java.nio.ByteBuffer
import java.util.Base64

/**
 * Codificação canônica determinística (length-prefixed) usada tanto para assinar
 * quanto para transportar. Evita ambiguidades de JSON (ordem/espaços).
 */
object CommandCodec {
    private const val MAGIC = "SSRW"

    fun canonicalBytes(c: RemoteCommand): ByteArray {
        val fields = listOf(
            MAGIC,
            c.version.toString(),
            c.deviceId,
            c.commandId,
            c.sequence.toString(),
            c.issuedAtEpochMs.toString(),
            c.expiresAtEpochMs.toString(),
            c.action.name,
            c.keyId,
        )
        val encoded = fields.map { it.toByteArray(Charsets.UTF_8) }
        val size = encoded.sumOf { 4 + it.size }
        val buf = ByteBuffer.allocate(size)
        encoded.forEach { buf.putInt(it.size); buf.put(it) }
        return buf.array()
    }

    fun decodeCanonical(bytes: ByteArray): RemoteCommand {
        val buf = ByteBuffer.wrap(bytes)
        val fields = ArrayList<String>(9)
        while (buf.remaining() >= 4) {
            val len = buf.getInt()
            require(len in 0..4096 && len <= buf.remaining()) { "malformed field length" }
            val arr = ByteArray(len)
            buf.get(arr)
            fields += String(arr, Charsets.UTF_8)
        }
        require(buf.remaining() == 0) { "trailing bytes" }
        require(fields.size == 9) { "expected 9 fields, got ${fields.size}" }
        require(fields[0] == MAGIC) { "bad magic" }
        return RemoteCommand(
            version = fields[1].toInt(),
            deviceId = fields[2],
            commandId = fields[3],
            sequence = fields[4].toLong(),
            issuedAtEpochMs = fields[5].toLong(),
            expiresAtEpochMs = fields[6].toLong(),
            action = RemoteAction.valueOf(fields[7]),
            keyId = fields[8],
        )
    }

    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()

    /** Formato de transporte: base64url(canônico) + "." + base64url(assinatura). */
    fun encodeTransport(signed: SignedCommand): String =
        b64.encodeToString(canonicalBytes(signed.command)) + "." + b64.encodeToString(signed.signature)

    fun decodeTransport(text: String): SignedCommand {
        val parts = text.trim().split('.')
        require(parts.size == 2) { "transport format must be <payload>.<signature>" }
        val payload = b64d.decode(parts[0])
        val sig = b64d.decode(parts[1])
        return SignedCommand(decodeCanonical(payload), sig)
    }
}
