import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.vowed"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.vowed"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.0-rc1.1"
        // Backend base URL. 10.0.2.2 is the emulator's alias for the development machine. No secrets live here.
        // The hosted devnet backend (a public URL, not a secret). Pass -PbackendUrl=http://10.0.2.2:8787 for a local backend; debug builds can also switch in You (settings).
        buildConfigField("String", "BACKEND_URL", "\"${providers.gradleProperty("backendUrl").getOrElse("https://vowed-backend.onrender.com")}\"")
    }
    // Release signing: the keystore and its passwords live OUTSIDE the repository (default ~/.vowed-signing/signing.properties, or the file named by
    // the VOWED_SIGNING_PROPERTIES environment variable). Without that file the release APK is built unsigned.
    val signingProps = Properties().also { props ->
        val f = file(System.getenv("VOWED_SIGNING_PROPERTIES") ?: (System.getProperty("user.home") + "/.vowed-signing/signing.properties"))
        if (f.exists()) f.inputStream().use { props.load(it) }
    }
    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.2.0")
    // camera rep counting: CameraX for frames, ML Kit for body landmarks (model bundled in the app, runs on the phone, no network)
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
}
