plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.23"

}

android {
    namespace = "com.example.decentracam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.decentracam"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // CameraX
    val cameraxVersion = "1.4.2"      // check for the latest
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.compose.material:material-icons-extended")
    // Coroutines / Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    //Solana-Wallet
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.3")
    implementation("com.solanamobile:rpc-core:0.2.7")
    implementation("com.solanamobile:web3-solana:0.2.5")

    //implementation ("com.solana:rpc-core-okiodriver:0.2.8") // easiest
    implementation ("io.ktor:ktor-client-core:2.3.4")
    implementation ("io.ktor:ktor-client-cio:2.3.4") // for Android/JVM engine
    implementation ("io.ktor:ktor-client-content-negotiation:2.3.4")
    implementation ("io.ktor:ktor-serialization-kotlinx-json:2.3.4")
    implementation("com.solanamobile:rpc-core:0.2.6")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")





}