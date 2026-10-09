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
        // versionCode 为整数递增的构建号；versionName 为面向用户的四段版本号，两者需同步手动更新。
        // （可选改进：接入 gradle/libs.versions.toml 或 version.properties 作为单一来源。）
        versionCode = 1015
        versionName = "1.0.1.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 开启 R8 压缩/混淆与资源收缩；保留规则见 proguard-rules.pro（JNI/反射）。
            // 注意：启用 minify 后需执行 `./gradlew assembleRelease` 验证原生方法与键盘功能正常。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 发布签名在 CI 中外部完成；如需本地签名，配置 signingConfigs.release 后取消下行注释。
            // signingConfig = signingConfigs.getByName("release")
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
        // ../../rime 仅应包含需要打包的 susheng*.yaml 词表/schema（当前已验证只有 5 个 .yaml）。
        // 依赖仓库目录布局（monorepo）；aapt 默认 ignoreAssetsPattern 已排除 .git 等 VCS 元数据。
        assets.srcDir("../../rime")
    }

    ndkVersion = "25.1.8937393"
    // x86_64 已附带预编译的 jniLibs/x86_64/libncnn.so，且 CMake 按 ABI 从源码构建 JNI，
    // 因此纳入 x86_64 可让现代模拟器/CI 直接安装运行（此前仅 ARM 会使其无法安装）。
    defaultConfig.ndk.abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
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
