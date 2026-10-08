plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.susheng.ime"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.susheng.ime"
        minSdk = 28
        targetSdk = 34
        versionCode = 1015
        versionName = "1.0.1.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets.getByName("main") {
        assets.srcDir("../../rime")
    }

    ndkVersion = "25.1.8937393"
    defaultConfig.ndk.abiFilters += listOf("armeabi-v7a", "arm64-v8a")
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

base {
    archivesName.set("su-ime-${android.defaultConfig.versionCode}")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
