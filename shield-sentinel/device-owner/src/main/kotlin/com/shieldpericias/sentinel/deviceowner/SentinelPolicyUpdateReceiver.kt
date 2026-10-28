package com.shieldpericias.sentinel.deviceowner

import android.app.admin.PolicyUpdateReceiver
import android.app.admin.PolicyUpdateResult
import android.app.admin.TargetUser
import android.content.Context
import android.os.Bundle

/**
 * Android 14+ (API 34): o sistema informa de forma assíncrona se uma política
 * foi efetivamente aplicada ou se perdeu para outro administrador. O resultado
 * é encaminhado a [DeviceAdminEvents] para auditoria e reavaliação do Watchdog.
 * Em Android 12/13 o receiver nunca é invocado.
 */
class SentinelPolicyUpdateReceiver : PolicyUpdateReceiver() {

    override fun onPolicySetResult(context: Context, policyIdentifier: String, additionalPolicyParams: Bundle, targetUser: TargetUser, policyUpdateResult: PolicyUpdateResult) {
        DeviceAdminEvents.dispatch(context) { it.onPolicyResult(context, policyIdentifier, describe(policyUpdateResult), changed = false) }
    }

    override fun onPolicyChanged(context: Context, policyIdentifier: String, additionalPolicyParams: Bundle, targetUser: TargetUser, policyUpdateResult: PolicyUpdateResult) {
        DeviceAdminEvents.dispatch(context) { it.onPolicyResult(context, policyIdentifier, describe(policyUpdateResult), changed = true) }
    }

    private fun describe(r: PolicyUpdateResult): String = when (r.resultCode) {
        PolicyUpdateResult.RESULT_POLICY_SET -> "RESULT_POLICY_SET"
        PolicyUpdateResult.RESULT_POLICY_CLEARED -> "RESULT_POLICY_CLEARED"
        PolicyUpdateResult.RESULT_FAILURE_CONFLICTING_ADMIN_POLICY -> "RESULT_FAILURE_CONFLICTING_ADMIN_POLICY"
        PolicyUpdateResult.RESULT_FAILURE_HARDWARE_LIMITATION -> "RESULT_FAILURE_HARDWARE_LIMITATION"
        PolicyUpdateResult.RESULT_FAILURE_STORAGE_LIMIT_REACHED -> "RESULT_FAILURE_STORAGE_LIMIT_REACHED"
        PolicyUpdateResult.RESULT_FAILURE_UNKNOWN -> "RESULT_FAILURE_UNKNOWN"
        else -> "RESULT_${r.resultCode}"
    }
}
