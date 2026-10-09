plugins {
    // 插件版本在此集中声明（单一来源）；:app 模块以无版本方式 apply，升级只需改这一处，不会跨模块漂移。
    // （可选改进：迁移到 gradle/libs.versions.toml 版本目录，需先在 settings.gradle.kts 声明。）
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
}
