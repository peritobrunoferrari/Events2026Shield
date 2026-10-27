package com.shieldpericias.sentinel

import android.content.Context
import com.shieldpericias.sentinel.antitheft.AntiTheftEntryPoint
import com.shieldpericias.sentinel.antitheft.AntiTheftScheduler
import com.shieldpericias.sentinel.antitheft.AutoWipeConfigStore
import com.shieldpericias.sentinel.antitheft.AutoWipeEngine
import com.shieldpericias.sentinel.antitheft.FakeWipeController
import com.shieldpericias.sentinel.antitheft.RealWipeController
import com.shieldpericias.sentinel.antitheft.WipeController
import com.shieldpericias.sentinel.audit.AuditLog
import com.shieldpericias.sentinel.audit.AuditTypes
import com.shieldpericias.sentinel.autoreboot.AutoRebootConfigStore
import com.shieldpericias.sentinel.autoreboot.AutoRebootEntryPoint
import com.shieldpericias.sentinel.autoreboot.RealRebootExecutor
import com.shieldpericias.sentinel.autoreboot.RebootExecutor
import com.shieldpericias.sentinel.autoreboot.RebootScheduler
import com.shieldpericias.sentinel.core.emergency.EmergencyActionId
import com.shieldpericias.sentinel.core.remote.CommandVerifier
import com.shieldpericias.sentinel.coresecurity.AndroidClockSource
import com.shieldpericias.sentinel.coresecurity.CapabilityDetector
import com.shieldpericias.sentinel.deviceowner.DeviceAdminEvents
import com.shieldpericias.sentinel.deviceowner.DevicePolicyFacade
import com.shieldpericias.sentinel.deviceowner.ProvisioningHelper
import com.shieldpericias.sentinel.deviceowner.SentinelDeviceAdminReceiver
import com.shieldpericias.sentinel.duress.DuressExecutor
import com.shieldpericias.sentinel.duress.DuressPinManager
import com.shieldpericias.sentinel.emergency.EmergencyAction
import com.shieldpericias.sentinel.emergency.EmergencyConfigStore
import com.shieldpericias.sentinel.emergency.EmergencyEngine
import com.shieldpericias.sentinel.emergency.EmergencyEntryPoint
import com.shieldpericias.sentinel.emergency.LockNowAction
import com.shieldpericias.sentinel.emergency.RebootAction
import com.shieldpericias.sentinel.emergency.SecurityStatusAction
import com.shieldpericias.sentinel.emergency.UsbProtectionAction
import com.shieldpericias.sentinel.hardening.HardeningController
import com.shieldpericias.sentinel.remotewipe.DeviceIdentity
import com.shieldpericias.sentinel.remotewipe.ManualTransport
import com.shieldpericias.sentinel.remotewipe.PersistentReplayStore
import com.shieldpericias.sentinel.remotewipe.RemoteCommandProcessor
import com.shieldpericias.sentinel.remotewipe.RemoteWipeConfigStore
import com.shieldpericias.sentinel.remotewipe.TrustAnchorStore
import com.shieldpericias.sentinel.securestorage.DeviceProtectedKv
import com.shieldpericias.sentinel.securestorage.InactivityLedgerStore
import com.shieldpericias.sentinel.securestorage.KeystoreCrypto
import com.shieldpericias.sentinel.securestorage.SecureKv
import com.shieldpericias.sentinel.securestorage.VerifiedUnlockProbe
import com.shieldpericias.sentinel.usbsecurity.AdvancedProtectionProbe
import com.shieldpericias.sentinel.usbsecurity.UsbPolicyController
import com.shieldpericias.sentinel.watchdog.LocalAlerts
import com.shieldpericias.sentinel.watchdog.SentinelGuardService
import com.shieldpericias.sentinel.watchdog.UnlockVerifier
import com.shieldpericias.sentinel.watchdog.WatchdogChecker
import com.shieldpericias.sentinel.watchdog.WatchdogEntryPoint
import com.shieldpericias.sentinel.watchdog.WatchdogGraph
import com.shieldpericias.sentinel.watchdog.WatchdogScheduler

/**
 * Grafo de dependências manual (sem framework de DI). Único por processo.
 * Componentes do sistema (receivers/serviços/tile) chegam aqui pelos EntryPoints,
 * inclusive quando o processo é iniciado em BFU (Direct Boot).
 */
class SentinelGraph private constructor(private val context: Context) : WatchdogGraph {

    val clock = AndroidClockSource(context)
    val crypto = KeystoreCrypto()
    val secureKv = SecureKv(context, crypto)
    val dpKv = DeviceProtectedKv(context, crypto)
    override val audit = AuditLog(context)
    val ledgerStore = InactivityLedgerStore(dpKv)
    val unlockProbe = VerifiedUnlockProbe()

    val facade = DevicePolicyFacade(context)
    val adminComponent = SentinelDeviceAdminReceiver.component(context)
    val capabilities = CapabilityDetector(context, adminComponent)
    val provisioning = ProvisioningHelper(context)

    val advancedProtection = AdvancedProtectionProbe(context)
    val usb = UsbPolicyController(facade, advancedProtection, dpKv, audit)
    val hardening = HardeningController(context, facade, audit)

    val autoRebootConfig = AutoRebootConfigStore(dpKv)
    val rebootExecutor: RebootExecutor = RealRebootExecutor(facade)
    val rebootScheduler = RebootScheduler(context, autoRebootConfig, ledgerStore, clock, facade, rebootExecutor, audit)

    val autoWipeConfig = AutoWipeConfigStore(dpKv)
    val realWipe: WipeController = RealWipeController(facade)
    val autoWipe = AutoWipeEngine(autoWipeConfig, ledgerStore, clock, realWipe, audit)
    val antiTheft = AntiTheftScheduler(context, autoWipe)

    val alerts = LocalAlerts(context)
    val watchdog = WatchdogChecker(context, facade, usb, hardening, rebootScheduler, autoWipe, ledgerStore, dpKv, audit, alerts)
    override val watchdogScheduler = WatchdogScheduler(context, watchdog, ledgerStore, clock)
    override val unlockVerifier = UnlockVerifier(context, unlockProbe, ledgerStore, clock, audit)

    val emergencyConfig = EmergencyConfigStore(dpKv)
    val emergencyActions: Map<EmergencyActionId, EmergencyAction> = mapOf(
        EmergencyActionId.LOCK_NOW to LockNowAction(facade),
        EmergencyActionId.USB_PROTECTION to UsbProtectionAction(usb),
        EmergencyActionId.REBOOT to RebootAction(rebootExecutor),
        EmergencyActionId.SECURITY_STATUS to SecurityStatusAction(watchdog, alerts),
    )
    val emergency = EmergencyEngine(emergencyConfig, emergencyActions, audit)

    val remoteWipeConfig = RemoteWipeConfigStore(secureKv)
    val deviceIdentity = DeviceIdentity(secureKv)
    val trustAnchors = TrustAnchorStore(secureKv)
    val replayStore = PersistentReplayStore(secureKv)
    val manualTransport = ManualTransport()
    val remoteProcessor: RemoteCommandProcessor by lazy {
        RemoteCommandProcessor(
            remoteWipeConfig,
            CommandVerifier(deviceIdentity.id(), trustAnchors, replayStore, { System.currentTimeMillis() }, enabled = { remoteWipeConfig.load().enabled }),
            facade,
            { wipeControllerForPolicy() },
            rebootExecutor,
            audit,
            onStatusReport = { watchdog.run(notify = true) },
        )
    }

    val duressPins = DuressPinManager(secureKv, audit)
    val duressExecutor = DuressExecutor(facade, rebootExecutor, { wipeControllerForPolicy() }, audit)

    /** Em modo de ensaio (padrão enquanto não homologado) o apagamento é simulado. */
    fun wipeControllerForPolicy(): WipeController = if (autoWipeConfig.load().dryRun) FakeWipeController(realWipe.availableScope()) else realWipe

    // ------------------------------------------------------------ ciclo de vida

    fun onAppStart() {
        audit.append(AuditTypes.APP_START)
        unlockVerifier.addListener {
            rebootScheduler.rearm()
            antiTheft.schedule()
        }
        DeviceAdminEvents.register(object : DeviceAdminEvents.Listener {
            override fun onAdminEnabled(context: Context) { audit.append(AuditTypes.ADMIN_ENABLED); watchdogScheduler.schedule() }
            override fun onAdminDisabled(context: Context) { audit.append(AuditTypes.ADMIN_DISABLED); alerts.protectionLost(listOf("Administrador do Sentinel desativado")) }
            override fun onProvisioningComplete(context: Context) { audit.append(AuditTypes.PROVISIONING_COMPLETE); watchdog.run(notify = false) }
            override fun onCredentialSucceeded(context: Context) { unlockVerifier.onUnlockSignal("onPasswordSucceeded") }
        })
        SentinelGuardService.start(context)
        watchdogScheduler.schedule()
        antiTheft.schedule()
    }

    override fun onBoot(action: String) {
        WatchdogEntryPoint.logBoot(this, action)
        ledgerStore.observe(clock.snapshot())
        antiTheft.onBoot()
        watchdogScheduler.schedule()
        SentinelGuardService.start(context)
    }

    override fun onScreenOff() { ledgerStore.observe(clock.snapshot()) }

    override fun onUserUnlocked() { unlockVerifier.onUnlockSignal("USER_UNLOCKED") }

    companion object {
        @Volatile private var instance: SentinelGraph? = null

        fun get(context: Context): SentinelGraph = instance ?: synchronized(this) {
            instance ?: SentinelGraph(context.applicationContext).also { g ->
                instance = g
                WatchdogEntryPoint.provider = { g }
                AutoRebootEntryPoint.provider = { g.rebootScheduler }
                AntiTheftEntryPoint.provider = { g.antiTheft }
                EmergencyEntryPoint.provider = { g.emergency }
                EmergencyEntryPoint.configStoreProvider = { g.emergencyConfig }
                DeviceAdminEvents.bootstrap = { ctx -> get(ctx) }
            }
        }
    }
}
