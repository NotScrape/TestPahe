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
        minSdk = 26
        targetSdk = 34
        versionCode = 39
        versionName = "16.39"
    }

    base {
        archivesName.set("aniyomi-en.animepahe-v${defaultConfig.versionName}")
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            vcsInfo.include = false
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

    buildFeatures {
        buildConfig = true
    }

    dependenciesInfo {
        includeInApk = false
    }

    packaging {
        resources.excludes.add("kotlin-tooling-metadata.json")
    }
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        freeCompilerArgs.addAll(
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