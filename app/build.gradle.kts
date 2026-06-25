import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.net.URL
import java.net.URI
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.aistudio.wapanel.hjklm"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    
    ndk {
      abiFilters.addAll(setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
    }

    externalNativeBuild {
      cmake {
        arguments("-DANDROID_STL=c++_shared")
      }
    }
  }

  externalNativeBuild {
    cmake {
      path = file("CMakeLists.txt")
    }
  }

  sourceSets {
    getByName("main") {
      jniLibs.setSrcDirs(listOf("libnode/bin"))
    }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

googleServices {
  missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN
}


// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation("androidx.appcompat:appcompat:1.6.1")
  implementation("com.google.android.material:material:1.11.0")
  implementation("androidx.constraintlayout:constraintlayout:2.1.4")

  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  // implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

val installNodeModules = tasks.register("installNodeModules") {
    val nodeModulesDir = file("src/main/assets/nodejs-project/node_modules")
    inputs.file(file("src/main/assets/nodejs-project/package.json"))
    outputs.dir(nodeModulesDir)
    doFirst {
        println("Running npm install in assets/nodejs-project...")
        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("windows")
            val cmd = if (isWindows) listOf("cmd", "/c", "npm", "install", "--omit=dev") else listOf("npm", "install", "--omit=dev")
            val process = ProcessBuilder(cmd)
                .directory(file("src/main/assets/nodejs-project"))
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw GradleException("npm install exited with code $exitCode")
            }
        } catch (e: Exception) {
            println("Error: Could not run npm install: ${e.message}")
            throw GradleException("Failed to install node modules", e)
        }
    }
}

val bundleNodeProject = tasks.register("bundleNodeProject") {
    dependsOn(installNodeModules)
    
    val sourceFile = file("src/main/assets/nodejs-project/index.js")
    val destFile = file("src/main/assets/nodejs-project/index_bundled.js")
    
    inputs.file(sourceFile)
    outputs.file(destFile)
    
    doFirst {
        println("Bundling node project with esbuild...")
        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("windows")
            val cmd = if (isWindows) {
                listOf("cmd", "/c", "npx", "esbuild", sourceFile.absolutePath, "--bundle", "--platform=node", "--target=es2016", "--outfile=" + destFile.absolutePath)
            } else {
                listOf("npx", "esbuild", sourceFile.absolutePath, "--bundle", "--platform=node", "--target=es2016", "--outfile=" + destFile.absolutePath)
            }
            val process = ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw GradleException("esbuild exited with code $exitCode")
            }
            println("Node project bundled successfully: ${destFile.length()} bytes")
        } catch (e: Exception) {
            throw GradleException("Failed to bundle node project", e)
        }
    }
}

val generateAssetLists = tasks.register("generateAssetLists") {
    dependsOn(bundleNodeProject)
    
    val assetsDir = file("src/main/assets")
    val nodejsProjectDir = file("src/main/assets/nodejs-project")
    val dirListFile = file("src/main/assets/dir.list")
    val fileListFile = file("src/main/assets/file.list")
    
    inputs.dir(nodejsProjectDir)
    outputs.file(dirListFile)
    outputs.file(fileListFile)
    
    doLast {
        if (!nodejsProjectDir.exists()) return@doLast
        
        val dirsList = mutableListOf<String>()
        val filesList = mutableListOf<String>()
        
        nodejsProjectDir.walkTopDown().forEach { f ->
            val relPath = f.relativeTo(assetsDir).path.replace('\\', '/')
            if (f.isDirectory) {
                dirsList.add(relPath)
            } else {
                filesList.add(relPath)
            }
        }
        
        dirListFile.writeText(dirsList.joinToString("\n") + "\n")
        fileListFile.writeText(filesList.joinToString("\n") + "\n")
        println("Generated dir.list (${dirsList.size} dirs) and file.list (${filesList.size} files)")
    }
}

val prepareNodeJSNative = tasks.register("prepareNodeJSNative") {
    val libnodeDir = file("libnode")
    val cppDir = file("src/main/cpp")
    val cmakeFile = file("CMakeLists.txt")
    
    dependsOn(generateAssetLists)
    
    outputs.dir(libnodeDir)
    outputs.dir(cppDir)
    outputs.file(cmakeFile)
    
    doFirst {
        val libsDir = file("libs")
        val packageDir = file("libs/package")
        if (!packageDir.exists()) {
            val tgzFile = file("libs/nodejs-mobile-react-native.tgz")
            if (tgzFile.exists()) {
                println("Extracting nodejs-mobile-react-native tgz...")
                copy {
                    from(tarTree(resources.gzip(tgzFile)))
                    into(libsDir)
                }
            }
        }
        
        // Copy C++ files to src/main/cpp
        if (!cppDir.exists()) cppDir.mkdirs()
        copy {
            from("libs/package/android/src/main/cpp")
            into(cppDir)
        }
        
        // Copy CMakeLists.txt to app root
        copy {
            from("libs/package/android/CMakeLists.txt")
            into(projectDir)
        }
        
        // Copy headers to libnode/include
        val includeDest = file("libnode/include")
        if (!includeDest.exists()) includeDest.mkdirs()
        copy {
            from("libs/package/libs/android/libnode/include")
            into(includeDest)
        }
        
        // Decompress JNI .so.gz files to .so
        val binSrc = file("libs/package/libs/android/libnode/bin")
        val binDest = file("libnode/bin")
        if (binSrc.exists()) {
            binSrc.walkTopDown().forEach { file ->
                if (file.name.endsWith(".so.gz")) {
                    val relativePath = file.relativeTo(binSrc).parent
                    val abiDir = File(binDest, relativePath)
                    if (!abiDir.exists()) abiDir.mkdirs()
                    
                    val decompressedName = file.name.removeSuffix(".gz")
                    val decompressedFile = File(abiDir, decompressedName)
                    
                    if (!decompressedFile.exists()) {
                        println("Decompressing ${file.name} to ${decompressedFile.absolutePath}...")
                        GZIPInputStream(file.inputStream()).use { gzis ->
                            decompressedFile.outputStream().use { output ->
                                gzis.copyTo(output)
                            }
                        }
                    }
                }
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(prepareNodeJSNative)
}

