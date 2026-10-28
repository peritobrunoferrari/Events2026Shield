package com.shieldpericias.sentinel

import android.app.KeyguardManager
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shieldpericias.sentinel.antitheft.FakeWipeController
import com.shieldpericias.sentinel.autoreboot.AutoRebootConfig
import com.shieldpericias.sentinel.autoreboot.FakeRebootExecutor
import com.shieldpericias.sentinel.autoreboot.RebootScheduler
import com.shieldpericias.sentinel.core.AdminMode
import com.shieldpericias.sentinel.core.PolicyResult
import com.shieldpericias.sentinel.core.duress.DuressAction
import com.shieldpericias.sentinel.core.duress.DuressPolicy
import com.shieldpericias.sentinel.core.emergency.EmergencyActionId
import com.shieldpericias.sentinel.core.emergency.EmergencyArming
import com.shieldpericias.sentinel.core.inactivity.AutoWipeConfig
import com.shieldpericias.sentinel.core.inactivity.InactivityDecision
import com.shieldpericias.sentinel.core.inactivity.RebootPlanner
import com.shieldpericias.sentinel.core.remote.CommandCodec
import com.shieldpericias.sentinel.core.remote.CommandSigner
import com.shieldpericias.sentinel.core.remote.RemoteAction
import com.shieldpericias.sentinel.core.remote.RemoteCommand
import com.shieldpericias.sentinel.core.remote.VerificationResult
import com.shieldpericias.sentinel.core.time.Durations
import com.shieldpericias.sentinel.core.usb.UsbProfile
import com.shieldpericias.sentinel.emergency.EngineResult
import com.shieldpericias.sentinel.emergency.TriggerSource
import com.shieldpericias.sentinel.hardening.HardeningPolicy
import com.shieldpericias.sentinel.remotewipe.RemoteOutcome
import com.shieldpericias.sentinel.remotewipe.RemoteWipeConfig
import com.shieldpericias.sentinel.securestorage.VerifiedUnlockProbe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.Base64
import java.util.UUID

/**
 * Testes de integração executados no emulador do CI com o Sentinel provisionado
 * como Device Owner (`dpm set-device-owner`). Exercitam as APIs REAIS do
 * DevicePolicyManager e revertem cada política ao final.
 *
 * Regras: nenhum reboot real (FakeRebootExecutor), nenhum wipe real
 * (modo de ensaio + FakeWipeController), nada que derrube o ADB
 * (DISALLOW_DEBUGGING_FEATURES e sinalização USB real são evitados).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DeviceOwnerPolicyTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = SentinelApp.graph(context)

    @Before
    fun requireDeviceOwner() {
        assumeTrue("Exige Device Owner (dpm set-device-owner)", graph.facade.mode() == AdminMode.DEVICE_OWNER)
    }

    @After
    fun revertEverything() {
        if (graph.facade.mode() != AdminMode.DEVICE_OWNER) return
        graph.usb.revertAll()
        for (p in SAFE_HARDENING) graph.hardening.apply(p, false)
        graph.autoWipeConfig.save(AutoWipeConfig())
        graph.autoRebootConfig.save(AutoRebootConfig())
        graph.emergencyConfig.save(EmergencyArming())
        graph.remoteWipeConfig.save(RemoteWipeConfig())
    }

    @Test
    fun t01_capabilitiesReportDeviceOwner() {
        val status = graph.facade.status()
        assertTrue(status.isDeviceOwner)
        val caps = graph.capabilities.detect(status)
        assertTrue(caps.first { it.id == "admin_mode" }.state.isActive)
        assertTrue(caps.first { it.id == "reboot" }.state.let { !it.isUnavailable })
        assertTrue(caps.first { it.id == "wipe" }.state.let { !it.isUnavailable })
    }

    @Test
    fun t02_usbElevatedProfileAppliesAndReverts() {
        val results = graph.usb.applyProfile(UsbProfile.ELEVATED)
        assertEquals(PolicyResult.Applied, results.first { it.first == "usb.file_transfer" }.second)
        assertEquals(PolicyResult.Applied, results.first { it.first == "usb.physical_media" }.second)
        assertTrue(graph.facade.restrictionState(UserManager.DISALLOW_USB_FILE_TRANSFER).isActive)
        assertTrue(context.getSystemService(UserManager::class.java).hasUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER))
        assertEquals(UsbProfile.ELEVATED, graph.usb.selectedProfile())

        graph.usb.revertAll()
        assertFalse(graph.facade.restrictionState(UserManager.DISALLOW_USB_FILE_TRANSFER).isActive)
        assertNull(graph.usb.selectedProfile())
    }

    @Test
    fun t03_usbMaximumProfileHonoursDeviceSupport() {
        // Em aparelhos que suportam desabilitar a sinalização USB o teste não aplica
        // o perfil máximo (poderia derrubar a conexão de teste); só cobre o caminho Unsupported.
        assumeTrue("Aparelho suporta desabilitar sinalização USB; teste pulado por segurança", !graph.facade.canUsbDataSignalingBeDisabled())
        val results = graph.usb.applyProfile(UsbProfile.MAXIMUM)
        val signaling = results.first { it.first == "usb.signaling" }.second
        assertTrue("esperado Unsupported, obtido $signaling", signaling is PolicyResult.Unsupported)
        assertTrue(graph.usb.snapshot().dataSignalingDisabled.isUnavailable)
        assertTrue(graph.facade.restrictionState(UserManager.DISALLOW_USB_FILE_TRANSFER).isActive)
        graph.usb.revertAll()
    }

    @Test
    fun t04_hardeningPoliciesToggle() {
        for (p in SAFE_HARDENING) {
            val on = graph.hardening.apply(p, true)
            assertEquals("aplicar $p", PolicyResult.Applied, on)
            assertTrue("estado após aplicar $p: ${graph.hardening.state(p)}", graph.hardening.state(p).isActive)
            val off = graph.hardening.apply(p, false)
            assertEquals("reverter $p", PolicyResult.Applied, off)
            assertFalse("estado após reverter $p: ${graph.hardening.state(p)}", graph.hardening.state(p).isActive)
        }
    }

    @Test
    fun t05_emergencyEngineRequiresArmingAndConfirmation() {
        graph.emergencyConfig.save(EmergencyArming(armed = false))
        assertEquals(EngineResult.NotArmed, graph.emergency.trigger(TriggerSource.APP_BUTTON, confirmed = true))

        graph.emergencyConfig.save(EmergencyArming(armed = true, requireConfirmation = true, actions = setOf(EmergencyActionId.SECURITY_STATUS)))
        assertEquals(EngineResult.NeedsConfirmation, graph.emergency.trigger(TriggerSource.APP_BUTTON, confirmed = false))

        val r = graph.emergency.trigger(TriggerSource.APP_BUTTON, confirmed = true)
        assertTrue(r is EngineResult.Executed)
        val results = (r as EngineResult.Executed).results
        assertEquals(1, results.size)
        assertEquals(EmergencyActionId.SECURITY_STATUS, results[0].id)
        assertEquals(PolicyResult.Applied, results[0].result)
    }

    @Test
    fun t06_emergencyUsbProtectionActionAppliesRestrictions() {
        graph.emergencyConfig.save(EmergencyArming(armed = true, requireConfirmation = false, actions = setOf(EmergencyActionId.USB_PROTECTION)))
        val r = graph.emergency.trigger(TriggerSource.SHORTCUT, confirmed = false)
        assertTrue(r is EngineResult.Executed)
        assertTrue(graph.facade.restrictionState(UserManager.DISALLOW_USB_FILE_TRANSFER).isActive)
        graph.usb.revertAll()
    }

    @Test
    fun t07_autoWipeDryRunNeverWipes() {
        graph.autoWipeConfig.save(AutoWipeConfig(enabled = true, thresholdMs = 3 * Durations.DAY_MS, dryRun = true))
        val eval = graph.autoWipe.evaluate(execute = true)
        // Sem baseline ou dentro do prazo → nenhuma ação.
        assertTrue(eval.verdict.decision == InactivityDecision.INCONCLUSIVE || eval.verdict.decision == InactivityDecision.WAIT)
        assertNull(eval.outcome)
        assertTrue(graph.facade.mode() == AdminMode.DEVICE_OWNER)
        // Em modo de ensaio o controlador de apagamento é o fake.
        assertTrue(graph.wipeControllerForPolicy() is FakeWipeController)
    }

    @Test
    fun t08_autoRebootSchedulerWithFakeExecutor() {
        val fake = FakeRebootExecutor()
        val scheduler = RebootScheduler(context, graph.autoRebootConfig, graph.ledgerStore, graph.clock, graph.facade, fake, graph.audit)
        graph.autoRebootConfig.save(AutoRebootConfig(enabled = true, intervalMs = 30 * Durations.MINUTE_MS, dryRun = false))

        // Desbloqueio "agora" → timer armado, nada vencido.
        graph.ledgerStore.recordVerifiedUnlock(graph.clock.snapshot())
        val plan = scheduler.rearm()
        assertTrue("plano: $plan", plan is RebootPlanner.Plan.Armed)
        assertTrue(scheduler.onAlarm() is PolicyResult.Unsupported)
        assertEquals(0, fake.calls.size)

        // Desbloqueio há 31 min neste boot → vencido → executor (fake) chamado uma vez.
        val now = graph.clock.snapshot()
        val s = graph.ledgerStore.load()
        graph.ledgerStore.save(s.copy(lastVerifiedUnlockElapsedMs = now.elapsedRealtimeMs - 31 * Durations.MINUTE_MS, lastVerifiedUnlockBootCount = now.bootCount))
        assertEquals(PolicyResult.Applied, scheduler.onAlarm())
        assertEquals(listOf("auto-reboot"), fake.calls)
        scheduler.cancel()
    }

    @Test
    fun t09_remoteCommandRoundTripReplayAndPendingWipe() {
        val admin = CommandSigner.generateAdminKeyPair()
        graph.remoteWipeConfig.save(RemoteWipeConfig(enabled = true, requireAdminConfirmation = true))
        assertTrue(graph.trustAnchors.add("test-admin", Base64.getEncoder().encodeToString(admin.public.encoded)))
        val now = System.currentTimeMillis()
        fun cmd(action: RemoteAction, seq: Long) = CommandCodec.encodeTransport(CommandSigner.sign(
            RemoteCommand(deviceId = graph.deviceIdentity.id(), commandId = UUID.randomUUID().toString(), sequence = seq,
                issuedAtEpochMs = now, expiresAtEpochMs = now + 60_000, action = action, keyId = "test-admin"), admin.private))

        val status = cmd(RemoteAction.STATUS_REPORT, System.nanoTime())
        val r1 = graph.remoteProcessor.submit(status)
        assertTrue("$r1", r1 is RemoteOutcome.Executed)
        val replay = graph.remoteProcessor.submit(status)
        assertTrue("$replay", replay is RemoteOutcome.Rejected && (replay.reason == VerificationResult.Reason.REPLAY_SEQUENCE || replay.reason == VerificationResult.Reason.REPLAY_COMMAND_ID))

        val wipe = graph.remoteProcessor.submit(cmd(RemoteAction.WIPE_DEVICE, System.nanoTime()))
        assertTrue("$wipe", wipe is RemoteOutcome.PendingConfirmation)
        graph.remoteProcessor.discard((wipe as RemoteOutcome.PendingConfirmation).command.commandId)
        assertTrue(graph.remoteProcessor.pendingCommands().isEmpty())

        graph.trustAnchors.revoke("test-admin")
        val revoked = graph.remoteProcessor.submit(cmd(RemoteAction.LOCK, System.nanoTime()))
        assertTrue("$revoked", revoked is RemoteOutcome.Rejected && revoked.reason == VerificationResult.Reason.REVOKED_KEY)
        graph.trustAnchors.remove("test-admin")
    }

    @Test
    fun t10_duressFactoryResetRequiresAcknowledgement() {
        val none = graph.duressExecutor.execute(DuressPolicy(enabled = true, action = DuressAction.FACTORY_RESET, ownerAcknowledgedFactoryReset = false))
        assertNull(none)
        // Com reconhecimento e modo de ensaio ligado, o apagamento é simulado.
        graph.autoWipeConfig.save(AutoWipeConfig(dryRun = true))
        val simulated = graph.duressExecutor.execute(DuressPolicy(enabled = true, action = DuressAction.FACTORY_RESET, ownerAcknowledgedFactoryReset = true))
        assertNotNull(simulated)
        assertTrue(simulated!!.simulated)
        assertEquals(PolicyResult.Applied, simulated.results.single())
        assertTrue(graph.facade.mode() == AdminMode.DEVICE_OWNER)
    }

    @Test
    fun t11_verifiedUnlockProbeAvailableWithSecureLock() {
        val km = context.getSystemService(KeyguardManager::class.java)
        assumeTrue("exige bloqueio seguro (locksettings set-pin)", km.isDeviceSecure)
        val r = graph.unlockProbe.probe()
        assertTrue("resultado: $r", r is VerifiedUnlockProbe.Result.Authenticated || r is VerifiedUnlockProbe.Result.NotAuthenticated)
    }

    @Test
    fun t12_auditChainIntactAndProvisioningPayload() {
        graph.audit.append("test_event", "instrumented")
        assertEquals(-1, graph.audit.verifyChain())
        val qr = graph.provisioning.qrPayload("https://exemplo.invalido/app.apk")
        assertTrue(qr.contains("SentinelDeviceAdminReceiver"))
        assertTrue(qr.contains("PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION"))
    }

    @Test
    fun t13_lockNowLocksDevice() {
        // Último teste: bloqueia a tela (não interfere nos demais).
        assertEquals(PolicyResult.Applied, graph.facade.lockNow())
    }

    companion object {
        /** Políticas seguras para alternar no emulador (sem derrubar ADB nem interferir no harness). */
        val SAFE_HARDENING = listOf(
            HardeningPolicy.UNKNOWN_SOURCES,
            HardeningPolicy.SAFE_BOOT,
            HardeningPolicy.FACTORY_RESET_UI,
            HardeningPolicy.ADD_USER,
            HardeningPolicy.CONFIG_DATE_TIME,
            HardeningPolicy.SCREEN_CAPTURE_DISABLED,
            HardeningPolicy.PASSWORD_COMPLEXITY_HIGH,
            HardeningPolicy.MAX_TIME_TO_LOCK,
            HardeningPolicy.DISABLE_TRUST_AGENTS,
            HardeningPolicy.UNINSTALL_BLOCKED_SELF,
        )
    }
}
