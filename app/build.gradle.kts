plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// OAuth client IDs are public identifiers you register yourself; read from env or local.properties (never committed).
val localProps = java.util.Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun cfg(name: String): String = System.getenv(name) ?: localProps.getProperty(name) ?: ""

android {
    namespace = "ch.imail.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ch.imail.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${cfg("GOOGLE_CLIENT_ID")}\"")
        buildConfigField("String", "MICROSOFT_CLIENT_ID", "\"${cfg("MICROSOFT_CLIENT_ID")}\"")
        manifestPlaceholders["appAuthRedirectScheme"] = "ch.imail.app"
        manifestPlaceholders["googleRedirectScheme"] = cfg("GOOGLE_CLIENT_ID").removeSuffix(".apps.googleusercontent.com")
            .let { if (it.isBlank()) "ch.imail.app.unconfigured" else "com.googleusercontent.apps.$it" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST", "META-INF/*.kotlin_module")
    }
    lint { abortOnError = true; checkReleaseBuilds = true }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("net.openid:appauth:0.11.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
