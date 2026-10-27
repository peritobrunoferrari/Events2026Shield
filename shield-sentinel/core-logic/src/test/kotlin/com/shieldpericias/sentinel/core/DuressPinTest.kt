package com.shieldpericias.sentinel.core

import com.shieldpericias.sentinel.core.duress.DuressAction
import com.shieldpericias.sentinel.core.duress.DuressPolicy
import com.shieldpericias.sentinel.core.duress.PinHasher
import com.shieldpericias.sentinel.core.duress.PinKind
import com.shieldpericias.sentinel.core.duress.PinVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuressPinTest {
    // Iterações reduzidas apenas para velocidade dos testes.
    private val normal = PinHasher.create("135790".toCharArray(), iterations = 1_000)
    private val duress = PinHasher.create("246800".toCharArray(), iterations = 1_000)

    @Test
    fun `classifica normal duress e invalido`() {
        assertEquals(PinKind.NORMAL, PinVerifier.classify("135790".toCharArray(), normal, duress))
        assertEquals(PinKind.DURESS, PinVerifier.classify("246800".toCharArray(), normal, duress))
        assertEquals(PinKind.INVALID, PinVerifier.classify("000000".toCharArray(), normal, duress))
        assertEquals(PinKind.INVALID, PinVerifier.classify("246800".toCharArray(), normal, null))
    }

    @Test
    fun `registro nao contem o pin e usa salt aleatorio`() {
        val a = PinHasher.create("1234".toCharArray(), 1_000)
        val b = PinHasher.create("1234".toCharArray(), 1_000)
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(a.hash.contentEquals(b.hash))
        assertFalse(String(a.hash, Charsets.ISO_8859_1).contains("1234"))
    }

    @Test
    fun `duress nao pode coincidir com o normal`() {
        assertFalse(PinVerifier.validateNewDuressPin("135790".toCharArray(), normal))
        assertFalse(PinVerifier.validateNewDuressPin("12".toCharArray(), normal))
        assertTrue(PinVerifier.validateNewDuressPin("9876".toCharArray(), normal))
    }

    @Test
    fun `factory reset exige reconhecimento explicito`() {
        assertFalse(DuressPolicy().isExecutable)
        assertTrue(DuressPolicy(enabled = true, action = DuressAction.LOCK).isExecutable)
        assertFalse(DuressPolicy(enabled = true, action = DuressAction.FACTORY_RESET).isExecutable)
        assertTrue(DuressPolicy(enabled = true, action = DuressAction.FACTORY_RESET, ownerAcknowledgedFactoryReset = true).isExecutable)
        assertFalse(DuressPolicy().wipeEsim)
    }
}
