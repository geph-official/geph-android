package io.geph.android

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.analytics.FirebaseAnalytics

fun createAccountEventSink(context: Context): AccountEventSink =
        FirebaseAccountEventSink(context.applicationContext)

private class FirebaseAccountEventSink(private val context: Context) : AccountEventSink {
    private val analytics: FirebaseAnalytics by lazy {
        ensureFirebaseApp()
        FirebaseAnalytics.getInstance(context)
    }

    override fun onPlusTransition() {
        analytics.logEvent("buyPlus", null)
    }

    private fun ensureFirebaseApp() {
        val hasDefaultApp =
                FirebaseApp.getApps(context).any { app ->
                    app.name == FirebaseApp.DEFAULT_APP_NAME
                }
        if (hasDefaultApp) {
            return
        }

        val options =
                FirebaseOptions.Builder()
                        .setProjectId("geph-android")
                        .setApplicationId("1:203643111221:android:f8e7f23b00b66f2b2b6e7c")
                        .setApiKey("AIzaSyA7ZvHeS-Gikmw58XarHdZqCujYD3NmwRE")
                        .setStorageBucket("geph-android.appspot.com")
                        .build()
        FirebaseApp.initializeApp(context, options)
    }
}
