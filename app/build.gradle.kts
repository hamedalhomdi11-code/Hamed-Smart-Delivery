plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val hamedKeystoreFile = System.getenv("HAMED_KEYSTORE_FILE")
val hamedKeystorePassword = System.getenv("HAMED_KEYSTORE_PASSWORD")
val hamedKeyAlias = System.getenv("HAMED_KEY_ALIAS")
val hamedKeyPassword = System.getenv("HAMED_KEY_PASSWORD")
val hasHamedReleaseKey = !hamedKeystoreFile.isNullOrBlank() &&
    !hamedKeystorePassword.isNullOrBlank() &&
    !hamedKeyAlias.isNullOrBlank() &&
    !hamedKeyPassword.isNullOrBlank()

android {
    namespace = "com.hamed.smartdelivery"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.hamed.smartdelivery"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.4.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (hasHamedReleaseKey) {
            create("hamedRelease") {
                storeFile = file(hamedKeystoreFile!!)
                storePassword = hamedKeystorePassword!!
                keyAlias = hamedKeyAlias!!
                keyPassword = hamedKeyPassword!!
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasHamedReleaseKey) {
                signingConfig = signingConfigs.getByName("hamedRelease")
            }
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    androidTestImplementation(composeBom)
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.maplibre.gl:android-sdk-opengl:13.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
