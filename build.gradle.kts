plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "eu.kanade.tachiyomi.animeextension.en.animepahe"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.animeextension.en.animepahe"
        minSdk = 21
        targetSdk = 34
        versionCode = 39
        versionName = "14.39"
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

dependencies {
    compileOnly(libs.bundles.common)

    implementation(libs.jsunpacker) {
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation(libs.nanohttpd)
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name == "kotlin-stdlib-jdk8" && requested.version == "1.7.0") {
            useVersion(libs.versions.kotlin.version.get())
            because("Fix problems with dev.datlag JsUnpacker")
        }
    }
}
