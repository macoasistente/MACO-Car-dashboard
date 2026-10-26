plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.macoasistente.sophie"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.macoasistente.sophie"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Fixed signing key so a new build installs over the old one without losing saved data.
    signingConfigs {
        create("sophie") {
            storeFile = file("sophie.keystore")
            storePassword = "sophie-android"
            keyAlias = "sophie"
            keyPassword = "sophie-android"
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sophie")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/webassets"))
}

// The phone screen is the same dashboard as the website: copy it in at build time.
val copyWeb by tasks.registering(Copy::class) {
    from(rootProject.file("../index.html"))
    from(rootProject.file("../icons")) { into("icons") }
    into(layout.buildDirectory.dir("generated/webassets/web"))
}
tasks.named("preBuild") { dependsOn(copyWeb) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
