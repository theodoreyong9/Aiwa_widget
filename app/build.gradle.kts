plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.aiwa.widget"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.aiwa.widget"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    // Without a pinned debug keystore, AGP auto-generates one per
    // machine the first time it's needed (~/.android/debug.keystore) —
    // on GitHub Actions' ephemeral runners that means a genuinely
    // DIFFERENT random signing key every single build. Android refuses
    // to install an APK over an existing one when the signatures don't
    // match, forcing an uninstall before every update. debug.keystore
    // here is committed on purpose: it's the well-known, publicly
    // documented Android debug convention (alias/password "android"),
    // never a real secret — the point is every build uses the SAME one.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    // See bridge/build.gradle.kts for why both of these are needed —
    // without them, javac and kotlinc target different JVM versions and
    // compileDebugKotlin fails outright.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":bridge"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.9.0")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
