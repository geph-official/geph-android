package io.geph.android

import android.content.Context

@Suppress("UNUSED_PARAMETER")
fun createAccountEventSink(context: Context): AccountEventSink = NoOpAccountEventSink

private object NoOpAccountEventSink : AccountEventSink {
    override fun initialize() = Unit
    override fun onPlusTransition() = Unit
}
