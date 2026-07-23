package com.ahmed.spenpointer

import android.content.Context

/** Persists the PC's WebSocket host/port so you don't have to retype it every launch. */
object PrefsHelper {
    private const val PREFS_NAME = "spen_pointer_prefs"
    private const val KEY_HOST = "pc_host"
    private const val KEY_PORT = "pc_port"
    // v2: slider now stores a 1–100 position (multiplied by a gain in the app),
    // not the old raw multiplier — new key so old saved values are ignored.
    private const val KEY_SENSITIVITY = "sensitivity_slider_v2"
    private const val KEY_SMOOTHNESS = "smoothness_slider"
    private const val DEFAULT_PORT = 8765
    private const val DEFAULT_SENSITIVITY = 25f
    // 1..100, higher = smoother. 68 ≈ the previous 0.42 alpha.
    private const val DEFAULT_SMOOTHNESS = 68f

    fun saveTarget(context: Context, host: String, port: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .apply()
    }

    fun getHost(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HOST, "") ?: ""

    fun getPort(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PORT, DEFAULT_PORT)

    fun saveSensitivity(context: Context, value: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SENSITIVITY, value)
            .apply()
    }

    fun getSensitivity(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY)

    fun saveSmoothness(context: Context, value: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SMOOTHNESS, value)
            .apply()
    }

    fun getSmoothness(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_SMOOTHNESS, DEFAULT_SMOOTHNESS)
}
