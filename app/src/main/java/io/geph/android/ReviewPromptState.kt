package io.geph.android

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.frybits.harmony.getHarmonySharedPreferences

object ReviewPromptState {
    const val MIN_CONTINUOUS_VPN_RUNTIME_MS = 3L * 60L * 60L * 1000L

    private const val PREFS_NAME = "review-prompt"
    private const val KEY_ACTIVE_VPN_SESSION_START_MS = "active_vpn_session_start_ms"
    private const val KEY_COMPLETED_CONTINUOUS_VPN_SESSION = "completed_continuous_vpn_session"
    private const val KEY_REVIEW_PROMPT_ATTEMPTED = "review_prompt_attempted"

    fun recordVpnSessionStarted(context: Context, nowMs: Long = SystemClock.elapsedRealtime()) {
        val prefs = prefs(context)
        prefs.edit().putLong(KEY_ACTIVE_VPN_SESSION_START_MS, nowMs).commit()
    }

    fun recordVpnSessionEnded(context: Context) {
        val prefs = prefs(context)
        val hasReachedThreshold = hasReachedContinuousVpnRuntime(context)
        prefs.edit()
                .remove(KEY_ACTIVE_VPN_SESSION_START_MS)
                .putBoolean(
                        KEY_COMPLETED_CONTINUOUS_VPN_SESSION,
                        prefs.getBoolean(KEY_COMPLETED_CONTINUOUS_VPN_SESSION, false) ||
                                hasReachedThreshold
                )
                .commit()
    }

    fun activeVpnSessionElapsedMs(
            context: Context,
            nowMs: Long = SystemClock.elapsedRealtime()
    ): Long {
        val prefs = prefs(context)
        val startMs = prefs.getLong(KEY_ACTIVE_VPN_SESSION_START_MS, 0L)
        return if (startMs > 0L && nowMs >= startMs) {
            nowMs - startMs
        } else {
            0L
        }
    }

    fun remainingContinuousVpnRuntimeMs(
            context: Context,
            nowMs: Long = SystemClock.elapsedRealtime()
    ): Long {
        val elapsedMs = activeVpnSessionElapsedMs(context, nowMs)
        return (MIN_CONTINUOUS_VPN_RUNTIME_MS - elapsedMs).coerceAtLeast(0L)
    }

    fun hasActiveVpnSession(context: Context): Boolean =
            prefs(context).getLong(KEY_ACTIVE_VPN_SESSION_START_MS, 0L) > 0L

    fun hasReachedContinuousVpnRuntime(
            context: Context,
            nowMs: Long = SystemClock.elapsedRealtime()
    ): Boolean =
            activeVpnSessionElapsedMs(context, nowMs) >= MIN_CONTINUOUS_VPN_RUNTIME_MS

    fun hasReviewPromptBeenAttempted(context: Context): Boolean =
            prefs(context).getBoolean(KEY_REVIEW_PROMPT_ATTEMPTED, false)

    fun shouldScheduleReviewPrompt(context: Context): Boolean =
            hasActiveVpnSession(context) &&
                    !hasQualifiedContinuousVpnSession(context) &&
                    !hasReviewPromptBeenAttempted(context)

    fun hasQualifiedContinuousVpnSession(context: Context): Boolean {
        val prefs = prefs(context)
        return prefs.getBoolean(KEY_COMPLETED_CONTINUOUS_VPN_SESSION, false) ||
                hasReachedContinuousVpnRuntime(context)
    }

    fun markReviewPromptAttempted(context: Context) {
        prefs(context).edit().putBoolean(KEY_REVIEW_PROMPT_ATTEMPTED, true).commit()
    }

    private fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getHarmonySharedPreferences(PREFS_NAME)
}
