import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.hana.reader"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.hana.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.4.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        getByName("debug") {
            val debugStore = rootProject.file("debug.keystore")
            if (debugStore.exists()) {
                storeFile = debugStore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        getByName("debug") {
            val debugStore = rootProject.file("debug.keystore")
            if (debugStore.exists()) {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }
    buildFeatures { compose = true }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
            pickFirsts += "**/libonnxruntime.so"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}

val sherpaAar = file("libs/sherpa-onnx-1.13.8.aar")
tasks.register("downloadSherpaAar") {
    outputs.file(sherpaAar)
    doLast {
        if (sherpaAar.exists() && sherpaAar.length() > 1_000_000L) return@doLast
        sherpaAar.parentFile.mkdirs()
        val tmp = file("${sherpaAar.path}.part")
        val url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar").toURL()
        val conn = url.openConnection()
        conn.setRequestProperty("User-Agent", "HanaReader/1.1")
        conn.getInputStream().use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        if (sherpaAar.exists()) sherpaAar.delete()
        if (!tmp.renameTo(sherpaAar)) {
            tmp.copyTo(sherpaAar, overwrite = true)
            tmp.delete()
        }
        require(sherpaAar.length() > 1_000_000L) { "sherpa-onnx AAR download failed" }
    }
}

afterEvaluate {
    tasks.matching { it.name.startsWith("pre") && it.name.endsWith("Build") }.configureEach {
        dependsOn("downloadSherpaAar")
    }
    tasks.matching { it.name.contains("compile", ignoreCase = true) && it.name.contains("Kotlin") }.configureEach {
        dependsOn("downloadSherpaAar")
    }
}
