package com.janeasystems.rn_nodejs_mobile

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.Semaphore

object RNNodeJsMobileModule {
    private const val TAG = "NODEJS-NATIVE"
    private const val NODEJS_PROJECT_DIR = "nodejs-project"
    private const val TRASH_DIR = "nodejs-project-trash"
    
    private var filesDirPath: String = ""
    private var nodeJsProjectPath: String = ""
    private var trashDirPath: String = ""
    
    private var initCompleted = false
    private val initSemaphore = Semaphore(1)
    
    // Callback to listen to messages from Node.js
    var messageListener: ((channel: String, message: String) -> Unit)? = null

    init {
        System.loadLibrary("nodejs-mobile-react-native-native-lib")
        System.loadLibrary("node")
    }

    fun init(context: Context) {
        if (initCompleted) return
        filesDirPath = context.filesDir.absolutePath
        nodeJsProjectPath = "$filesDirPath/$NODEJS_PROJECT_DIR"
        trashDirPath = "$filesDirPath/$TRASH_DIR"
        
        try {
            // Set TMPDIR
            val cacheDir = context.cacheDir.absolutePath
            android.system.Os.setenv("TMPDIR", cacheDir, true)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting TMPDIR", e)
        }

        registerNodeDataDirPath(filesDirPath)
        
        // Extract assets in a background thread
        Thread {
            try {
                initSemaphore.acquire()
                emptyTrash()
                copyNodeJsAssets(context)
                initCompleted = true
            } catch (e: Exception) {
                Log.e(TAG, "Node assets copy failed", e)
            } finally {
                initSemaphore.release()
                emptyTrash()
            }
        }.start()
    }

    private fun emptyTrash() {
        val trash = File(trashDirPath)
        if (trash.exists()) {
            trash.deleteRecursively()
        }
    }

    private fun copyNodeJsAssets(context: Context) {
        val nodeDir = File(nodeJsProjectPath)
        if (nodeDir.exists()) {
            val trash = File(trashDirPath)
            nodeDir.renameTo(trash)
        }
        nodeDir.mkdirs()

        // List assets and copy
        try {
            // Read dir.list and file.list if they exist, or list assets recursively
            val dirs = readFileFromAssets(context, "dir.list")
            val files = readFileFromAssets(context, "file.list")

            if (dirs.isNotEmpty() && files.isNotEmpty()) {
                for (dir in dirs) {
                    File("$filesDirPath/$dir").mkdirs()
                }
                for (file in files) {
                    copyAsset(context, file, "$filesDirPath/$file")
                }
            } else {
                copyAssetFolder(context, NODEJS_PROJECT_DIR, nodeJsProjectPath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copying assets", e)
        }
    }

    private fun readFileFromAssets(context: Context, filename: String): List<String> {
        val list = mutableListOf<String>()
        try {
            context.assets.open(filename).bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (line.isNotBlank()) list.add(line.trim())
                    line = reader.readLine()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "File not found in assets: $filename")
        }
        return list
    }

    private fun copyAssetFolder(context: Context, fromAssetPath: String, toPath: String) {
        val files = context.assets.list(fromAssetPath) ?: emptyArray()
        if (files.isEmpty()) {
            copyAsset(context, fromAssetPath, toPath)
        } else {
            File(toPath).mkdirs()
            for (file in files) {
                copyAssetFolder(context, "$fromAssetPath/$file", "$toPath/$file")
            }
        }
    }

    private fun copyAsset(context: Context, fromAssetPath: String, toPath: String) {
        try {
            val file = File(toPath)
            file.parentFile?.mkdirs()
            context.assets.open(fromAssetPath).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset: $fromAssetPath", e)
        }
    }

    fun startNodeProject(mainFileName: String, redirectOutputToLogcat: Boolean = true) {
        Thread {
            startNodeProjectSynchronous(mainFileName, redirectOutputToLogcat)
        }.start()
    }

    fun startNodeProjectSynchronous(mainFileName: String, redirectOutputToLogcat: Boolean = true): Int {
        waitForInit()
        val scriptPath = "$nodeJsProjectPath/$mainFileName"
        Log.i(TAG, "Starting Node.js project (Synchronous): $scriptPath")
        return startNodeWithArguments(
            arrayOf("node", scriptPath),
            nodeJsProjectPath,
            redirectOutputToLogcat
        )
    }

    fun sendMessage(channel: String, msg: String) {
        sendMessageToNodeChannel(channel, msg)
    }

    private fun waitForInit() {
        if (!initCompleted) {
            try {
                initSemaphore.acquire()
                initSemaphore.release()
            } catch (e: InterruptedException) {
                initSemaphore.release()
            }
        }
    }

    // JNI Static callback called from native-lib.cpp
    @JvmStatic
    fun sendMessageToApplication(channelName: String, msg: String) {
        Log.d(TAG, "Received message from Node channel $channelName: $msg")
        messageListener?.invoke(channelName, msg)
    }

    // JNI Native methods
    external fun registerNodeDataDirPath(dataDir: String)
    external fun getCurrentABIName(): String
    external fun startNodeWithArguments(arguments: Array<String>, modulesPath: String, redirectOutputToLogcat: Boolean): Int
    external fun sendMessageToNodeChannel(channelName: String, msg: String)
}
