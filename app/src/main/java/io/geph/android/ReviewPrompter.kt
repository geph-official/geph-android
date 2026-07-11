package io.geph.android

import android.app.Activity

interface ReviewPrompter {
    fun maybePrompt(activity: Activity)
}
