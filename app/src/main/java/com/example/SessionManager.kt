package com.example

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Administrador de sesiones de WhatsApp (Baileys)
 */
object SessionManager {
    private const val TAG = "SessionManager"
    private const val SESSION_DIR_NAME = "auth_info_baileys"

    /**
     * Obtiene el directorio de sesión de Baileys
     */
    fun getSessionDir(context: Context): File {
        return File(context.filesDir, SESSION_DIR_NAME)
    }

    /**
     * Obtiene el número de archivos de sesión y el tamaño total en bytes
     * Retorna Pair(Contador_de_archivos, Tamaño_total_en_bytes)
     */
    fun getSessionInfo(context: Context): Pair<Int, Long> {
        val dir = getSessionDir(context)
        if (!dir.exists() || !dir.isDirectory) {
            return Pair(0, 0L)
        }
        var fileCount = 0
        var totalSize = 0L
        
        val files = dir.listFiles()
        if (files != null) {
            for (file in files) {
                if (file.isFile) {
                    fileCount++
                    totalSize += file.length()
                }
            }
        }
        return Pair(fileCount, totalSize)
    }

    /**
     * Borra la sesión completa de WhatsApp (elimina el directorio de sesión)
     */
    fun deleteSession(context: Context): Boolean {
        val dir = getSessionDir(context)
        Log.d(TAG, "Borrando directorio de sesión: ${dir.absolutePath}")
        return deleteDirRecursive(dir)
    }

    /**
     * Limpia únicamente las sesiones fantasma o corruptas
     * Se considera fantasma si:
     * - El archivo tiene 0 bytes
     * - Lleva más de 48h sin modificarse y el bot no está conectado
     * - Tiene extensión .json pero no es un JSON válido
     */
    fun cleanGhostSessions(context: Context, isBotConnected: Boolean): Int {
        val dir = getSessionDir(context)
        if (!dir.exists() || !dir.isDirectory) {
            return 0
        }
        
        var deletedCount = 0
        val files = dir.listFiles() ?: return 0
        val fortyEightHoursAgo = System.currentTimeMillis() - (48 * 60 * 60 * 1000L)

        for (file in files) {
            if (!file.isFile) continue

            var shouldDelete = false
            val fileName = file.name
            val fileLength = file.length()
            val lastModified = file.lastModified()

            // 1. Archivo tiene 0 bytes
            if (fileLength == 0L) {
                shouldDelete = true
                Log.d(TAG, "Detectado archivo fantasma (0 bytes): $fileName")
            }
            // 2. Más de 48 horas sin modificarse y bot desconectado
            else if (lastModified < fortyEightHoursAgo && !isBotConnected) {
                shouldDelete = true
                Log.d(TAG, "Detectado archivo obsoleto (>48h sin conexión): $fileName")
            }
            // 3. Extensión .json pero no es JSON válido
            else if (fileName.endsWith(".json", ignoreCase = true)) {
                if (!isValidJsonFile(file)) {
                    shouldDelete = true
                    Log.d(TAG, "Detectado archivo JSON corrupto: $fileName")
                }
            }

            if (shouldDelete) {
                if (file.delete()) {
                    deletedCount++
                } else {
                    Log.e(TAG, "No se pudo borrar el archivo: ${file.absolutePath}")
                }
            }
        }
        
        Log.d(TAG, "Limpieza de sesiones fantasma completada. Borrados: $deletedCount archivos.")
        return deletedCount
    }

    /**
     * Valida si un archivo contiene JSON estructurado de forma correcta
     */
    private fun isValidJsonFile(file: File): Boolean {
        return try {
            val content = file.readText().trim()
            if (content.startsWith("{") && content.endsWith("}")) {
                JSONObject(content)
                true
            } else if (content.startsWith("[") && content.endsWith("]")) {
                JSONArray(content)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Utilidad para borrar carpetas de manera recursiva
     */
    private fun deleteDirRecursive(fileOrDirectory: File): Boolean {
        if (fileOrDirectory.isDirectory) {
            val children = fileOrDirectory.listFiles()
            if (children != null) {
                for (child in children) {
                    deleteDirRecursive(child)
                }
            }
        }
        return fileOrDirectory.delete()
    }
}
