package io.geph.android

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory

fun createReviewPrompter(context: Context): ReviewPrompter =
        PlayReviewPrompter(context.applicationContext)

private class PlayReviewPrompter(private val context: Context) : ReviewPrompter {
    private var requestInFlight = false

    override fun maybePrompt(activity: Activity) {
        if (requestInFlight || !isEligible()) {
            return
        }

        requestInFlight = true
        val manager = ReviewManagerFactory.create(activity)
        val request = manager.requestReviewFlow()
        request.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                requestInFlight = false
                Log.w(TAG, "Failed to request Play in-app review", task.exception)
                return@addOnCompleteListener
            }

            if (activity.isFinishing || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) {
                requestInFlight = false
                return@addOnCompleteListener
            }

            ReviewPromptState.markReviewPromptAttempted(context)
            manager.launchReviewFlow(activity, task.result).addOnCompleteListener { launchTask ->
                requestInFlight = false
                if (!launchTask.isSuccessful) {
                    Log.w(TAG, "Failed to launch Play in-app review", launchTask.exception)
                }
            }
        }
    }

    private fun isEligible(): Boolean {
        if (ReviewPromptState.hasReviewPromptBeenAttempted(context)) {
            return false
        }

        return ReviewPromptState.hasQualifiedContinuousVpnSession(context)
    }

    companion object {
        private const val TAG = "PlayReviewPrompter"
    }
}
