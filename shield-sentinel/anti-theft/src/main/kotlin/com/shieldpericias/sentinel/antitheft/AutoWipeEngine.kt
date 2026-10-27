package com.shieldpericias.sentinel.antitheft

import com.shieldpericias.sentinel.audit.AuditLog
import com.shieldpericias.sentinel.audit.AuditTypes
import com.shieldpericias.sentinel.core.FeatureState
import com.shieldpericias.sentinel.core.PolicyCheck
import com.shieldpericias.sentinel.core.inactivity.AutoWipeConfig
import com.shieldpericias.sentinel.core.inactivity.EvidencePolicy
import com.shieldpericias.sentinel.core.inactivity.InactivityDecision
import com.shieldpericias.sentinel.core.inactivity.InactivityLedger
import com.shieldpericias.sentinel.core.inactivity.InactivityVerdict
import com.shieldpericias.sentinel.core.time.ClockSource
import com.shieldpericias.sentinel.securestorage.DeviceProtectedKv
import com.shieldpericias.sentinel.securestorage.InactivityLedgerStore

class AutoWipeConfigStore(private val kv: DeviceProtectedKv) {
    fun load() = AutoWipeConfig(
        enabled = kv.getBoolean("aw.enabled", false),
        thresholdMs = kv.getLong("aw.threshold") ?: AutoWipeConfig.DEFAULT_THRESHOLD_MS,
        evidencePolicy = kv.get("aw.evidence")?.let { runCatching { EvidencePolicy.valueOf(it) }.getOrNull() } ?: EvidencePolicy.STRICT,
        dryRun = kv.getBoolean("aw.dryrun", true),
    )

    fun save(c: AutoWipeConfig) {
        require(AutoWipeConfig.isAllowedThreshold(c.thresholdMs)) { "prazo não permitido" }
        kv.putBoolean("aw.enabled", c.enabled)
        kv.putLong("aw.threshold", c.thresholdMs)
        kv.put("aw.evidence", c.evidencePolicy.name)
        kv.putBoolean("aw.dryrun", c.dryRun)
    }
}

data class AutoWipeEvaluation(val config: AutoWipeConfig, val verdict: InactivityVerdict, val outcome: WipeOutcome?)

/**
 * Auto-Wipe por inatividade. Regras:
 *  - desativado por padrão; prazo padrão 7 dias (3/7/14/30);
 *  - o contador só zera com desbloqueio verificado (ledger compartilhado);
 *  - reinicializações automáticas NÃO zeram o contador;
 *  - sem prova suficiente (anomalia de relógio, sem baseline) → não apaga;
 *  - modo de ensaio (dryRun, padrão ligado) usa [FakeWipeController].
 */
class AutoWipeEngine(
    private val configStore: AutoWipeConfigStore,
    private val ledgerStore: InactivityLedgerStore,
    private val clock: ClockSource,
    private val realWipe: WipeController,
    private val audit: AuditLog,
    private val ledger: InactivityLedger = InactivityLedger(),
) {
    private val fake = FakeWipeController()

    fun evaluate(execute: Boolean): AutoWipeEvaluation {
        val cfg = configStore.load()
        val now = clock.snapshot()
        val state = ledgerStore.observe(now)
        val verdict = ledger.evaluate(state, now, cfg.thresholdMs, cfg.evidencePolicy)
        audit.append(AuditTypes.AUTO_WIPE_EVALUATED, "${verdict.decision} ${verdict.reasons.joinToString(",")}")

        if (!execute || !cfg.enabled || verdict.decision != InactivityDecision.THRESHOLD_REACHED) {
            return AutoWipeEvaluation(cfg, verdict, null)
        }
        val scope = realWipe.availableScope()
        if (scope == null) {
            audit.append(AuditTypes.POLICY_FAILED, "auto_wipe: sem escopo de apagamento disponível")
            return AutoWipeEvaluation(cfg, verdict, null)
        }
        val request = WipeRequest(scope = scope, reason = "Shield Sentinel: inatividade", origin = "auto_wipe", wipeEsim = false)
        val outcome = if (cfg.dryRun) {
            audit.append(AuditTypes.AUTO_WIPE_DRY_RUN, "escopo ${scope.name}")
            fake.wipe(request)
        } else {
            audit.append(AuditTypes.AUTO_WIPE_EXECUTED, "escopo ${scope.name}")
            realWipe.wipe(request)
        }
        return AutoWipeEvaluation(cfg, verdict, outcome)
    }

    fun check(): PolicyCheck {
        val cfg = configStore.load()
        val now = clock.snapshot()
        val verdict = ledger.evaluate(ledgerStore.load(), now, cfg.thresholdMs, cfg.evidencePolicy)
        val days = cfg.thresholdMs / 86_400_000L
        val state = when {
            realWipe.availableScope() == null -> FeatureState.Unavailable("Exige Device Owner (ou Profile Owner para o perfil)")
            !cfg.enabled -> FeatureState.Inactive("Desativado")
            verdict.decision == InactivityDecision.INCONCLUSIVE -> FeatureState.Active("Ativo; prova inconclusiva (${verdict.reasons.joinToString()}) — não apagará")
            verdict.decision == InactivityDecision.WAIT -> FeatureState.Active("Ativo; restam ${(verdict.remainingMs ?: 0) / 3_600_000} h" + if (cfg.dryRun) " (ensaio)" else "")
            else -> FeatureState.Active("Prazo atingido")
        }
        return PolicyCheck("auto_wipe", "Auto-Wipe por inatividade ($days dias)", state, weight = 2)
    }
}
