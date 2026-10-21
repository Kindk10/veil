plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "veil.core"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.sqlite:sqlite:2.4.0")
    implementation("org.whispersystems:signal-protocol-android:2.8.1")
    implementation("org.whispersystems:curve25519-android:0.5.0")
    implementation("com.google.crypto.tink:tink-android:1.16.0")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
