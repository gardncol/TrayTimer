package com.traytimer.core.logic

import android.util.Log

/**
 * Analytics facade — STUB.
 *
 * This stub logs to logcat so instrumentation points exist throughout the codebase.
 *
 * TODO(analytics): forward to a real analytics backend.
 */
object Analytics {
    private const val TAG = "TrayTimerAnalytics"

    fun log(event: String, params: Map<String, Any?> = emptyMap()) {
        Log.i(TAG, "$event ${params.entries.joinToString(" ") { "${it.key}=${it.value}" }}")
    }

    fun logScreen(screen: String) = log("screen_view", mapOf("screen" to screen))
}