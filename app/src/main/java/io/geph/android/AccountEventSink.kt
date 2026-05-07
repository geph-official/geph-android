package io.geph.android

interface AccountEventSink {
    fun initialize()
    fun onPlusTransition()
}
