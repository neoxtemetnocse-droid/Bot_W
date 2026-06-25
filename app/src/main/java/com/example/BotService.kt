package com.example

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.system.Os
import android.util.Log
import androidx.core.app.NotificationCompat
import com.janeasystems.rn_nodejs_mobile.RNNodeJsMobileModule
import java.io.BufferedReader
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Servicio de primer plano (Foreground Service) que ejecuta el motor de Node.js
 * Corre en un proceso separado (:bot_process) para permitir reinicios limpios del motor nativo.
 */
class BotService : Service() {

    companion object {
        private const val TAG = "BotService"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "BotNeoChannel"

        // Acciones recibidas desde la UI (MainActivity)
        const val ACTION_CONTROL = "com.example.ACTION_CONTROL"
        const val EXTRA_COMMAND = "EXTRA_COMMAND"
        const val COMMAND_START = "COMMAND_START"
        const val COMMAND_STOP = "COMMAND_STOP"
        const val COMMAND_SEND_INPUT = "COMMAND_SEND_INPUT"
        const val EXTRA_INPUT_TEXT = "EXTRA_INPUT_TEXT"

        // Acciones enviadas hacia la UI (MainActivity)
        const val ACTION_LOG = "com.example.ACTION_LOG"
        const val EXTRA_LOG_LINE = "EXTRA_LOG_LINE"
        const val EXTRA_LOG_TYPE = "EXTRA_LOG_TYPE" // "normal", "error", "warn", "success", "pairing"

        const val ACTION_STATUS_UPDATE = "com.example.ACTION_STATUS_UPDATE"
        const val EXTRA_NODE_STATE = "EXTRA_NODE_STATE" // "CORRIENDO", "DETENIDO", "COLGADO"
        const val EXTRA_WA_STATE = "EXTRA_WA_STATE" // "CONECTADO", "DESCONECTADO", "REQUIERE_CODIGO"
        const val EXTRA_UPTIME_MS = "EXTRA_UPTIME_MS"
        const val EXTRA_PAIRING_CODE_VALUE = "EXTRA_PAIRING_CODE_VALUE"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var nodeStarted = false
    private var startTimeMillis: Long = 0
    private var currentWaStatus = "DESCONECTADO"
    private var currentPairingCode = ""
    
    // Watchdog local
    private lateinit var watchdog: WatchdogManager
    private val statusUpdateHandler = Handler(Looper.getMainLooper())
    private val watchdogCheckRunnable = object : Runnable {
        override fun run() {
            checkWatchdogStatus()
            statusUpdateHandler.postDelayed(this, 5000) // Verificar cada 5 segundos
        }
    }

    // Descriptores de archivo para redirigir stdin/stdout
    private var stdoutReadFd: FileDescriptor? = null
    private var stdoutWriteFd: FileDescriptor? = null
    private var stdinReadFd: FileDescriptor? = null
    private var stdinWriteFd: FileDescriptor? = null

    // Receptor de comandos desde MainActivity
    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CONTROL) {
                when (intent.getStringExtra(EXTRA_COMMAND)) {
                    COMMAND_START -> {
                        startNodeEngine()
                    }
                    COMMAND_STOP -> {
                        stopServiceCleanly()
                    }
                    COMMAND_SEND_INPUT -> {
                        val text = intent.getStringExtra(EXTRA_INPUT_TEXT) ?: ""
                        writeToStdin(text)
                    }
                    "COMMAND_CLEAR_LOGS" -> {
                        try {
                            File(filesDir, "bot_logs.txt").delete()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error clearing log file", e)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BotService creado en proceso separado")
        watchdog = WatchdogManager(this)
        
        // Registrar receptor de control de la app
        val filter = IntentFilter(ACTION_CONTROL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(controlReceiver, filter)
        }

        // Crear canal de notificación e iniciar en primer plano
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Iniciando Bot NEO..."))

        // Configurar WakeLock para mantener encendida la CPU
        acquireWakeLock()

        // Configurar pipes nativos de stdin / stdout
        setupNativePipes()

        // Iniciar bucle de verificación de estado
        statusUpdateHandler.post(watchdogCheckRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "BotService onStartCommand recibido")
        
        // Si viene acción de detener desde la notificación
        if (intent?.action == "STOP_SERVICE") {
            logAction("⏹ Servicio detenido desde la notificación por el usuario")
            stopServiceCleanly()
            return START_NOT_STICKY
        }

        // Iniciar el motor de Node.js por defecto si no ha iniciado
        startNodeEngine()

        // Enviar estado inmediato a la UI al iniciarse o reconectarse
        broadcastStatus(if (nodeStarted) "CORRIENDO" else "DETENIDO", currentWaStatus)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BotService destruido")
        unregisterReceiver(controlReceiver)
        statusUpdateHandler.removeCallbacks(watchdogCheckRunnable)
        releaseWakeLock()
        // Asegurar que el proceso muera para liberar libnode y permitir una inicialización limpia al siguiente inicio
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /**
     * Inicia el motor de Node.js en un hilo nativo de background
     */
    private fun startNodeEngine() {
        if (nodeStarted) {
            Log.d(TAG, "El motor de Node.js ya está corriendo.")
            return
        }

        startTimeMillis = System.currentTimeMillis()
        nodeStarted = true
        watchdog.resetHangTimer()
        logAction("▶ Iniciando motor nativo de Node.js...")

        // Ejecutar Node en un hilo de fondo
        Thread {
            try {
                RNNodeJsMobileModule.init(applicationContext)
                
                Log.d(TAG, "Lanzando script de carga en Node.js mediante loader.js")
                val exitCode = RNNodeJsMobileModule.startNodeProjectSynchronous("loader.js")
                Log.d(TAG, "El motor de Node.js ha salido con código de salida: $exitCode")
                
                // Si el motor sale de forma normal
                nodeStarted = false
                Log.d(TAG, "El motor de Node.js ha finalizado la ejecución.")
                broadcastStatus("DETENIDO", "DESCONECTADO")
                
                // Activar Watchdog si el proceso muere inesperadamente
                handleUnexpectedProcessDeath()

            } catch (e: Exception) {
                Log.e(TAG, "Error iniciando el motor de Node.js", e)
                nodeStarted = false
                broadcastStatus("DETENIDO", "DESCONECTADO")
                handleUnexpectedProcessDeath()
            }
        }.start()
    }

    /**
     * Procesa la muerte inesperada de Node.js según las políticas del Watchdog
     */
    private fun handleUnexpectedProcessDeath() {
        if (watchdog.isEnabled) {
            if (watchdog.canRestart()) {
                val currentRestart = watchdog.incrementRestartCount()
                logAction("🐕 [Watchdog] El proceso Node murió. Reiniciando automáticamente en 5s (Intento $currentRestart / ${WatchdogManager.MAX_RESTARTS})")
                
                // Programar el reinicio usando AlarmManager para que funcione incluso si el sistema intenta dormir
                scheduleRestart(5000)
                stopSelf()
            } else {
                logAction("🐕 [Watchdog] Límite de reinicios automáticos alcanzado (${WatchdogManager.MAX_RESTARTS}/5). Bot detenido.")
                showMaxRestartsNotification()
                stopServiceCleanly()
            }
        } else {
            logAction("❌ El proceso Node se ha detenido. Watchdog desactivado.")
            stopSelf()
        }
    }

    /**
     * Programa el reinicio automático del servicio usando AlarmManager
     */
    private fun scheduleRestart(delayMs: Long) {
        val intent = Intent(applicationContext, BotService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext, 
            0, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + delayMs
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    /**
     * Detiene el servicio de forma limpia
     */
    private fun stopServiceCleanly() {
        logAction("⏹ Deteniendo Bot NEO de forma limpia por el usuario...")
        nodeStarted = false
        broadcastStatus("DETENIDO", "DESCONECTADO")
        stopSelf()
    }

    /**
     * Configura pipes para redireccionar stdout, stderr y stdin nativos de Node.js
     */
    private fun setupNativePipes() {
        try {
            // 1. Pipe para stdout / stderr (Node.js salida -> Android entrada)
            val outPipe = Os.pipe()
            stdoutReadFd = outPipe[0]
            stdoutWriteFd = outPipe[1]
            
            // Duplicar el descriptor de escritura al descriptor estándar stdout (1) y stderr (2)
            Os.dup2(stdoutWriteFd!!, 1)
            Os.dup2(stdoutWriteFd!!, 2)

            // 2. Pipe para stdin (Android salida -> Node.js entrada)
            val inPipe = Os.pipe()
            stdinReadFd = inPipe[0]
            stdinWriteFd = inPipe[1]
            
            // Duplicar el descriptor de lectura al descriptor estándar stdin (0)
            Os.dup2(stdinReadFd!!, 0)

            // Iniciar hilo lector de logs
            startReadingStdoutPipe()

        } catch (e: Exception) {
            Log.e(TAG, "Fallo al configurar redirección de pipes nativos", e)
        }
    }

    /**
     * Lee continuamente del pipe redireccionado de stdout/stderr de Node.js
     */
    private fun startReadingStdoutPipe() {
        Thread {
            val reader = BufferedReader(InputStreamReader(FileInputStream(stdoutReadFd!!)))
            try {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { parseAndBroadcastLog(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error leyendo del pipe stdout", e)
            }
        }.start()
    }

    /**
     * Escribe texto en el descriptor stdin (0) de Node.js
     */
    private fun writeToStdin(text: String) {
        Thread {
            try {
                stdinWriteFd?.let { fd ->
                    val fos = FileOutputStream(fd)
                    fos.write((text + "\n").toByteArray())
                    fos.flush()
                    Log.d(TAG, "Escrito a stdin: $text")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error escribiendo en el pipe stdin", e)
            }
        }.start()
    }

    /**
     * Parsea la línea de log de Node.js, detecta comandos de estado y la envía a la UI
     */
    private fun parseAndBroadcastLog(rawLine: String) {
        watchdog.feed() // Hubo actividad de log, alimentar watchdog

        var type = "normal"
        var lineText = rawLine

        // Detectar estados de WhatsApp emitidos por Node.js
        if (lineText.contains("WA_STATUS:CONECTADO")) {
            currentWaStatus = "CONECTADO"
            type = "success"
            broadcastStatus("CORRIENDO", currentWaStatus)
            updateNotification("Bot NEO activo 🤖 - Conectado ✅")
        } else if (lineText.contains("WA_STATUS:DESCONECTADO")) {
            currentWaStatus = "DESCONECTADO"
            type = "error"
            broadcastStatus("CORRIENDO", currentWaStatus)
            updateNotification("Bot NEO activo 🤖 - Desconectado ❌")
        } else if (lineText.contains("WA_STATUS:REQUIERE_CODIGO")) {
            currentWaStatus = "REQUIERE_CODIGO"
            type = "warn"
            broadcastStatus("CORRIENDO", currentWaStatus)
            updateNotification("Bot NEO activo 🤖 - Requiere código de vinculación 🔑")
        } else if (lineText.contains("PAIRING_CODE:")) {
            val code = lineText.substringAfter("PAIRING_CODE:").trim()
            currentPairingCode = code
            type = "pairing"
            broadcastStatus("CORRIENDO", currentWaStatus, code)
            updateNotification("Bot NEO activo - Código: $code 🔑")
        }

        // Determinar colores y estilos por contenido del log
        val lowerText = lineText.lowercase()
        if (lowerText.contains("error") || lowerText.contains("exception")) {
            type = "error"
        } else if (lowerText.contains("warn") || lineText.contains("⚠️")) {
            type = "warn"
        } else if (lineText.contains("✅") || lineText.contains("exitoso") || lineText.contains("success")) {
            type = "success"
        }

        // Formatear log agregando timestamp [HH:MM:SS]
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val formattedLine = "[${sdf.format(Date())}] $lineText"

        saveLogToFile(formattedLine, type)

        // Enviar broadcast a la MainActivity
        val logIntent = Intent(ACTION_LOG).apply {
            putExtra(EXTRA_LOG_LINE, formattedLine)
            putExtra(EXTRA_LOG_TYPE, type)
            setPackage(packageName)
        }
        sendBroadcast(logIntent)
    }

    /**
     * Registra eventos iniciados por el usuario o acciones internas con formato de consola
     */
    private fun logAction(text: String) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val formattedLine = "[${sdf.format(Date())}] $text"
        
        saveLogToFile(formattedLine, "normal")
        
        val logIntent = Intent(ACTION_LOG).apply {
            putExtra(EXTRA_LOG_LINE, formattedLine)
            putExtra(EXTRA_LOG_TYPE, "normal")
            setPackage(packageName)
        }
        sendBroadcast(logIntent)
    }

    /**
     * Envía una actualización de estado general a la MainActivity
     */
    private fun broadcastStatus(nodeState: String, waState: String, pairingCode: String = currentPairingCode) {
        val uptime = if (nodeStarted) System.currentTimeMillis() - startTimeMillis else 0L
        val statusIntent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_NODE_STATE, nodeState)
            putExtra(EXTRA_WA_STATE, waState)
            putExtra(EXTRA_UPTIME_MS, uptime)
            putExtra(EXTRA_PAIRING_CODE_VALUE, pairingCode)
            setPackage(packageName)
        }
        sendBroadcast(statusIntent)
    }

    /**
     * Verifica periódicamente el watchdog para detectar cuelgues (10 mins sin logs)
     */
    private fun checkWatchdogStatus() {
        val isRunning = nodeStarted
        
        // Broadcast de estado para actualización periódica (Uptime, etc.)
        broadcastStatus(if (isRunning) "CORRIENDO" else "DETENIDO", currentWaStatus)

        if (isRunning && watchdog.isEnabled) {
            // Detección de colgados (10 min sin logs)
            if (watchdog.isBotHung()) {
                logAction("🐕 [Watchdog] ¡PROCESO COLGADO! Se detectaron 10 minutos de inactividad absoluta en consola.")
                logAction("🐕 [Watchdog] Ejecutando: MATAR TODO -> REINICIAR LIMPIO")
                
                // Programar el reinicio limpio automático
                scheduleRestart(2000)
                stopSelf()
            }
        }
    }

    /**
     * Manejo del WakeLock de CPU para evitar que se duerma
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BotNeo:WakeLockTag").apply {
            acquire(30 * 60 * 1000L /*30 minutos*/)
        }
        Log.d(TAG, "WakeLock adquirido")
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "WakeLock liberado")
        }
    }

    /**
     * Notificaciones de Android
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bot NEO Canal de Control",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificación persistente para monitorear el estado del bot WhatsApp"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        // Intent para abrir MainActivity al hacer clic en "Ver logs" o la notificación
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent para detener el bot mediante botón "Detener"
        val stopIntent = Intent(this, BotService::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Bot NEO activo 🤖")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopPendingIntent)
            .addAction(android.R.drawable.ic_menu_view, "Ver logs", mainPendingIntent)
            .setColor(0x1DB954) // Verde Spotify
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    /**
     * Muestra una notificación si se supera el límite de reinicios del Watchdog
     */
    private fun showMaxRestartsNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this, 2, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Bot NEO Detenido 🐕")
            .setContentText("Demasiados reinicios consecutivos. Revisa los logs.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(mainPendingIntent)
            .setAutoCancel(true)
            .setColor(0xFF3333) // Rojo
            .build()

        manager.notify(9002, notification)
    }

    private fun saveLogToFile(lineText: String, type: String) {
        try {
            val logFile = File(filesDir, "bot_logs.txt")
            logFile.appendText("$type|$lineText\n")
            if (Math.random() < 0.02) {
                pruneLogFile(logFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando log en archivo", e)
        }
    }

    private fun pruneLogFile(file: File) {
        try {
            if (!file.exists()) return
            val lines = file.readLines()
            if (lines.size > 1000) {
                val pruned = lines.takeLast(500)
                file.writeText(pruned.joinToString("\n") + "\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recortando archivo de logs", e)
        }
    }
}
