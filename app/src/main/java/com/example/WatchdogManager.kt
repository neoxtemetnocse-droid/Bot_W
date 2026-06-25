package com.example

import android.content.Context
import android.content.SharedPreferences

/**
 * Gestor del Watchdog automático para monitorear el estado del bot
 */
class WatchdogManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("bot_panel_prefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_WATCHDOG_ENABLED = "watchdog_enabled"
        const val KEY_RESTART_COUNT = "watchdog_restart_count"
        const val MAX_RESTARTS = 5
        const val HANG_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutos
    }

    // Indica si el Watchdog está activado o desactivado
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_WATCHDOG_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WATCHDOG_ENABLED, value).apply()
        }

    // Contador de reinicios automáticos
    var restartCount: Int
        get() = prefs.getInt(KEY_RESTART_COUNT, 0)
        set(value) {
            prefs.edit().putInt(KEY_RESTART_COUNT, value).apply()
        }

    // Timestamp del último log recibido para detección de colgados
    private var lastLogTimestamp: Long = System.currentTimeMillis()

    /**
     * Actualiza el timestamp para indicar actividad
     */
    fun feed() {
        lastLogTimestamp = System.currentTimeMillis()
    }

    /**
     * Resetea el contador del último log (por ejemplo, al iniciar el bot)
     */
    fun resetHangTimer() {
        lastLogTimestamp = System.currentTimeMillis()
    }

    /**
     * Retorna cuántos milisegundos han pasado desde el último log
     */
    fun getTimeSinceLastLog(): Long {
        return System.currentTimeMillis() - lastLogTimestamp
    }

    /**
     * Verifica si el bot se encuentra colgado (más de 10 minutos sin logs)
     */
    fun isBotHung(): Boolean {
        if (!isEnabled) return false
        val timeElapsed = System.currentTimeMillis() - lastLogTimestamp
        return timeElapsed > HANG_TIMEOUT_MS
    }

    /**
     * Valida si el watchdog puede proceder con un reinicio automático
     */
    fun canRestart(): Boolean {
        return isEnabled && restartCount < MAX_RESTARTS
    }

    /**
     * Incrementa el contador de reinicios automáticos
     */
    fun incrementRestartCount(): Int {
        val nextCount = restartCount + 1
        restartCount = nextCount
        return nextCount
    }

    /**
     * Resetea manualmente el contador de reinicios
     */
    fun resetRestartCount() {
        restartCount = 0
    }
}
