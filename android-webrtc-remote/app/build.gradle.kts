plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.pakredirect.remote"
    compileSdk = 35
    defaultConfig { applicationId = "com.pakredirect.remote"; minSdk = 29; targetSdk = 35; versionCode = 2; versionName = "3.0.0-alpha2" }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("RYLUX_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RYLUX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RYLUX_KEY_ALIAS")
                keyPassword = System.getenv("RYLUX_KEY_PASSWORD")
            }
        }
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("release") } }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
}

