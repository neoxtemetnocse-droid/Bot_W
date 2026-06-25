package com.example

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// Data class representing structured console lines
data class LogLine(val id: Long, val text: String, val type: String)

class MainActivity : ComponentActivity() {

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Permiso de notificaciones concedido ✅", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this, 
                "Las notificaciones están desactivadas. El bot podría suspenderse en segundo plano.", 
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Solicitar permisos de notificación si es Android 13+ (Tiramisu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Arrancar el servicio por defecto en segundo plano al iniciar
        startBotService()

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    BotControlPanel(
                        modifier = Modifier.padding(innerPadding),
                        onStartService = { startBotService() }
                    )
                }
            }
        }
    }

    private fun startBotService() {
        val intent = Intent(this, BotService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error iniciando BotService", e)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BotControlPanel(
    modifier: Modifier = Modifier,
    onStartService: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    // ----------------------------------------------------
    // STATE MANAGERS
    // ----------------------------------------------------
    var nodeState by remember { mutableStateOf("DETENIDO") }
    var waState by remember { mutableStateOf("DESCONECTADO") }
    var uptimeMs by remember { mutableStateOf(0L) }
    var pairingCode by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }

    val logsList = remember { mutableStateListOf<LogLine>() }
    var logIdCounter by remember { mutableStateOf(0L) }

    // Session directories stats
    var sessionFileCount by remember { mutableStateOf(0) }
    var sessionSizeInBytes by remember { mutableStateOf(0L) }

    // Dialogs state
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Helper functions
    fun refreshSessionInfo() {
        val info = SessionManager.getSessionInfo(context)
        sessionFileCount = info.first
        sessionSizeInBytes = info.second
    }

    fun broadcastControl(command: String, extraKey: String? = null, extraVal: String? = null) {
        val intent = Intent(BotService.ACTION_CONTROL).apply {
            putExtra(BotService.EXTRA_COMMAND, command)
            if (extraKey != null && extraVal != null) {
                putExtra(extraKey, extraVal)
            }
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    // ----------------------------------------------------
    // INITIALIZATION & LOG LOADING
    // ----------------------------------------------------
    LaunchedEffect(Unit) {
        refreshSessionInfo()
        // Cargar logs almacenados anteriormente en bot_logs.txt
        withContext(Dispatchers.IO) {
            val logFile = File(context.filesDir, "bot_logs.txt")
            if (logFile.exists()) {
                try {
                    val lines = logFile.readLines().mapIndexed { index, line ->
                        val type = line.substringBefore("|", "normal")
                        val text = line.substringAfter("|", line)
                        LogLine(index.toLong(), text, type)
                    }
                    withContext(Dispatchers.Main) {
                        logsList.clear()
                        logsList.addAll(lines)
                        logIdCounter = lines.size.toLong()
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error cargando logs guardados", e)
                }
            }
        }
    }

    // Ticker para actualizar el Uptime por segundo localmente de forma fluida
    var tickerTimeSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(uptimeMs, nodeState) {
        tickerTimeSeconds = uptimeMs / 1000
        if (nodeState == "CORRIENDO") {
            while (true) {
                delay(1000)
                tickerTimeSeconds++
            }
        }
    }

    // ----------------------------------------------------
    // BROADCAST RECEIVER REGISTRATION
    // ----------------------------------------------------
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BotService.ACTION_LOG -> {
                        val text = intent.getStringExtra(BotService.EXTRA_LOG_LINE) ?: ""
                        val type = intent.getStringExtra(BotService.EXTRA_LOG_TYPE) ?: "normal"
                        logsList.add(LogLine(logIdCounter++, text, type))
                        
                        // Limitar logs en memoria a un máximo razonable (400)
                        if (logsList.size > 400) {
                            logsList.removeRange(0, logsList.size - 400)
                        }
                    }
                    BotService.ACTION_STATUS_UPDATE -> {
                        nodeState = intent.getStringExtra(BotService.EXTRA_NODE_STATE) ?: "DETENIDO"
                        waState = intent.getStringExtra(BotService.EXTRA_WA_STATE) ?: "DESCONECTADO"
                        uptimeMs = intent.getLongExtra(BotService.EXTRA_UPTIME_MS, 0L)
                        pairingCode = intent.getStringExtra(BotService.EXTRA_PAIRING_CODE_VALUE) ?: ""
                        refreshSessionInfo()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BotService.ACTION_LOG)
            addAction(BotService.ACTION_STATUS_UPDATE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // Auto scroll list state
    val listState = rememberLazyListState()
    LaunchedEffect(logsList.size) {
        if (logsList.isNotEmpty()) {
            listState.animateScrollToItem(logsList.size - 1)
        }
    }

    // Format uptime helper
    fun formatUptime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
    }

    // Format size helper
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024
        if (kb < 1024) return "$kb KB"
        val mb = kb / 1024f
        return String.format(Locale.getDefault(), "%.2f MB", mb)
    }

    // ----------------------------------------------------
    // LAYOUT DESIGN
    // ----------------------------------------------------
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070A13)) // Deep Space dark slate background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "NEO PANEL 🤖",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Consola de Control del Bot de WhatsApp",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Pulso de estado en vivo
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (nodeState == "CORRIENDO") Color(0xFF10B981) else Color(0xFFEF4444)
                        )
                )
            }

            // ----------------------------------------------------
            // CARDS METRICS SECTION
            // ----------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Engine State Card
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                    border = BorderStroke(1.dp, Color(0xFF1F2937))
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "Motor Node.js", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        val color = when (nodeState) {
                            "CORRIENDO" -> Color(0xFF10B981)
                            "COLGADO" -> Color(0xFFF59E0B)
                            else -> Color(0xFFEF4444)
                        }
                        Text(
                            text = nodeState,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = color,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // WhatsApp State Card
                Card(
                    modifier = Modifier.weight(1.2f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                    border = BorderStroke(1.dp, Color(0xFF1F2937))
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "WhatsApp Link", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        val color = when (waState) {
                            "CONECTADO" -> Color(0xFF10B981)
                            "REQUIERE_CODIGO" -> Color(0xFF3B82F6)
                            else -> Color(0xFFEF4444)
                        }
                        Text(
                            text = if (waState == "REQUIERE_CODIGO") "VINCULAR" else waState,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Uptime State Card
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                    border = BorderStroke(1.dp, Color(0xFF1F2937))
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "Tiempo Activo", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatUptime(tickerTimeSeconds),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // ----------------------------------------------------
            // PAIRING CODE GENERATION CARD (Triggered when Link required or always available)
            // ----------------------------------------------------
            if (waState == "REQUIERE_CODIGO" || pairingCode.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .testTag("pairing_code_card"),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B4B)), // Dark Indigo alert card
                    border = BorderStroke(1.dp, Color(0xFF4338CA))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Código requerido",
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "🔑 Se requiere Vinculación de WhatsApp",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        if (pairingCode.isNotEmpty()) {
                            // Código generado disponible
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(0xFF3B82F6), RoundedCornerShape(8.dp))
                                    .clickable {
                                        clipboardManager.setText(AnnotatedString(pairingCode))
                                        Toast.makeText(context, "Código copiado: $pairingCode 📋", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "CÓDIGO DE VINCULACIÓN",
                                    fontSize = 11.sp,
                                    color = Color(0xFF93C5FD),
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = pairingCode,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF3B82F6),
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 3.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Toca para copiar código 📋",
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Instrucciones:\n1. Abre WhatsApp -> Configuración -> Dispositivos vinculados.\n2. Selecciona 'Vincular con número de teléfono'.\n3. Ingresa el código azul de arriba en tu dispositivo.",
                                fontSize = 11.sp,
                                color = Color.LightGray,
                                lineHeight = 14.sp
                            )
                        } else {
                            // No hay código generado aún, solicitar número
                            Text(
                                text = "Genera un código ingresando el número telefónico del bot (con código internacional, ej: 5491166778899):",
                                fontSize = 11.sp,
                                color = Color.LightGray,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = phoneNumber,
                                    onValueChange = { phoneNumber = it },
                                    placeholder = { Text("Ej. 5491133445566", color = Color.Gray) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp)
                                        .testTag("phone_number_input"),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedContainerColor = Color(0xFF0B0F19),
                                        unfocusedContainerColor = Color(0xFF0B0F19),
                                        focusedBorderColor = Color(0xFF4338CA),
                                        unfocusedBorderColor = Color(0xFF374151)
                                    ),
                                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Phone,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            if (phoneNumber.isNotBlank()) {
                                                broadcastControl(BotService.COMMAND_SEND_INPUT, BotService.EXTRA_INPUT_TEXT, phoneNumber)
                                                phoneNumber = ""
                                            }
                                        }
                                    ),
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Button(
                                    onClick = {
                                        if (phoneNumber.isNotBlank()) {
                                            broadcastControl(BotService.COMMAND_SEND_INPUT, BotService.EXTRA_INPUT_TEXT, phoneNumber)
                                            phoneNumber = ""
                                        } else {
                                            Toast.makeText(context, "Por favor ingresa un número", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier
                                        .height(52.dp)
                                        .testTag("vincular_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F51B5)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Vincular", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // ----------------------------------------------------
            // LOG TERMINAL CONTAINER
            // ----------------------------------------------------
            Card(
                modifier = Modifier
                    .weight(1.8f)
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF090D16)),
                border = BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Log Terminal Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0F172A))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Logs",
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "CONSOLA DE SALIDA",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )

                        // Copiar logs button
                        IconButton(
                            onClick = {
                                val logsText = logsList.joinToString("\n") { it.text }
                                clipboardManager.setText(AnnotatedString(logsText))
                                Toast.makeText(context, "Logs copiados al portapapeles 📋", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Copiar logs",
                                tint = Color.LightGray,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Limpiar pantalla logs button
                        IconButton(
                            onClick = {
                                logsList.clear()
                                broadcastControl("COMMAND_CLEAR_LOGS")
                                Toast.makeText(context, "Consola limpiada 🧹", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Limpiar consola",
                                tint = Color.LightGray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Log list terminal screen
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF050811))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        if (logsList.isEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "No hay registros disponibles en este momento.",
                                    color = Color.DarkGray,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Inicia el bot para recibir eventos.",
                                    color = Color.DarkGray,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 10.dp)
                            ) {
                                items(logsList, key = { it.id }) { logItem ->
                                    val logColor = when (logItem.type) {
                                        "error" -> Color(0xFFF87171)   // Soft Coral Red
                                        "warn" -> Color(0xFFFBBF24)    // Warm Amber
                                        "success" -> Color(0xFF34D399) // Emerald Green
                                        "pairing" -> Color(0xFF60A5FA) // Electric Blue
                                        else -> Color(0xFFE2E8F0)      // Sleek Slate grey/white
                                    }
                                    Text(
                                        text = logItem.text,
                                        color = logColor,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ----------------------------------------------------
            // CONTROL PANEL ACTIONS SECTION
            // ----------------------------------------------------
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                border = BorderStroke(1.dp, Color(0xFF1F2937))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "ACCIONES DE CONTROL",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 8.dp),
                        fontFamily = FontFamily.Monospace
                    )

                    // Row 1: Start and Stop service
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                onStartService()
                                broadcastControl(BotService.COMMAND_START)
                                Toast.makeText(context, "Iniciando servicio...", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("start_bot_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Iniciar", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Iniciar Bot", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                broadcastControl(BotService.COMMAND_STOP)
                                Toast.makeText(context, "Enviando comando detener...", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("stop_bot_button"),
                            border = BorderStroke(1.dp, Color(0xFFEF4444)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Detener", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Detener Bot", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Row 2: Delete session files & Prune junk
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier
                                .weight(1.2f)
                                .height(38.dp)
                                .testTag("delete_credentials_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)), // Amber Orange
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Borrar", modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Borrar Credenciales", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                val deleted = SessionManager.cleanGhostSessions(context, nodeState == "CORRIENDO")
                                refreshSessionInfo()
                                logsList.add(LogLine(logIdCounter++, "[Watchdog] 🧹 Limpieza manual ejecutada. Se eliminaron $deleted archivos corruptos/fantasma.", "success"))
                                Toast.makeText(context, "Limpieza completada ($deleted borrados)", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .testTag("cleanup_junk_button"),
                            border = BorderStroke(1.dp, Color(0xFF4B5563)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Limpiar", modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Limpiar Basura", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Row 3: Display session credentials status
                    if (sessionFileCount > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Sesión guardada en local:",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                            Text(
                                text = "$sessionFileCount archivos (${formatSize(sessionSizeInBytes)})",
                                fontSize = 11.sp,
                                color = Color(0xFF10B981),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Delete Credentials Confirmation Dialog
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("⚠️ Borrar Credenciales", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "¿Estás seguro de que deseas borrar por completo las credenciales locales de WhatsApp?\n\nEsto desconectará el bot permanentemente y deberás volver a escanear un código QR o generar un código de vinculación para usarlo.",
                        color = Color.LightGray
                    )
                },
                containerColor = Color(0xFF111827),
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteConfirm = false
                            // Detener bot primero para liberar descriptores de archivos
                            broadcastControl(BotService.COMMAND_STOP)
                            
                            coroutineScope.launch {
                                delay(800) // Tiempo para detener el bot de manera segura
                                val success = SessionManager.deleteSession(context)
                                refreshSessionInfo()
                                
                                if (success) {
                                    logsList.add(LogLine(logIdCounter++, "[Consola] 🗑️ Todas las credenciales borradas. Por favor inicia el Bot e ingresa tu número para vincular de nuevo.", "success"))
                                    Toast.makeText(context, "Sesión eliminada con éxito ✅", Toast.LENGTH_SHORT).show()
                                } else {
                                    logsList.add(LogLine(logIdCounter++, "[Consola] ⚠️ No se pudieron borrar algunos archivos. Por favor detén el bot por completo e inténtalo de nuevo.", "error"))
                                    Toast.makeText(context, "Error eliminando sesión ❌", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                    ) {
                        Text("Borrar permanentemente")
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { showDeleteConfirm = false },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray)
                    ) {
                        Text("Cancelar")
                    }
                }
            )
        }
    }
}
