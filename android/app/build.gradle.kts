plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Public OAuth identifier supplied by the release maintainer, never an API key or client secret.
val googleWebClientId = providers.gradleProperty("GREENTUBE_GOOGLE_WEB_CLIENT_ID")
    .orElse(providers.environmentVariable("GREENTUBE_GOOGLE_WEB_CLIENT_ID")).orElse("").get().trim()
require(googleWebClientId.isEmpty() || googleWebClientId.matches(Regex("[0-9]+-[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com"))) {
    "GREENTUBE_GOOGLE_WEB_CLIENT_ID must be a Web OAuth client ID, not a secret."
}
val validateReleaseOAuth = tasks.register("validateReleaseOAuth") {
    doLast {check(googleWebClientId.isNotEmpty()) {"Release maintainers must configure GREENTUBE_GOOGLE_WEB_CLIENT_ID before building an APK."}}
}
tasks.matching {it.name=="preReleaseBuild"}.configureEach {dependsOn(validateReleaseOAuth)}

android {
    namespace = "app.verdant.search"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.verdant.search"
        minSdk = 26
        targetSdk = 36
        versionCode = 28
        versionName = "1.0"
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("privateRelease") {
            val store = System.getenv("GREENTUBE_KEYSTORE") ?: System.getenv("VERDANT_KEYSTORE")
            if (!store.isNullOrBlank()) {
                storeFile = file(store)
                storePassword = System.getenv("GREENTUBE_STORE_PASSWORD") ?: System.getenv("VERDANT_STORE_PASSWORD")
                keyAlias = System.getenv("GREENTUBE_KEY_ALIAS") ?: "verdant"
                keyPassword = System.getenv("GREENTUBE_KEY_PASSWORD") ?: System.getenv("VERDANT_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("privateRelease")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.noties.markwon:core:4.6.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}


