package io.geph.android

import android.app.Activity
import android.content.Context

@Suppress("UNUSED_PARAMETER")
fun createReviewPrompter(context: Context): ReviewPrompter = NoOpReviewPrompter

private object NoOpReviewPrompter : ReviewPrompter {
    override fun maybePrompt(activity: Activity) = Unit
}
