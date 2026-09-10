plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.pakredirect.remote"
    compileSdk = 35
    defaultConfig { applicationId = "com.pakredirect.remote"; minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "3.0.0-alpha1" }
    buildFeatures { buildConfig = true }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
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

