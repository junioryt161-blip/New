plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.friday.assistant"
    compileSdk = 34
    defaultConfig { applicationId = "com.friday.assistant"; minSdk = 26; targetSdk = 34; versionCode = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 3; versionName = "2.3" }
    signingConfigs { create("friday") { storeFile = file("friday.keystore"); storePassword = "friday123"; keyAlias = "friday"; keyPassword = "friday123" } }
    buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("friday") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
