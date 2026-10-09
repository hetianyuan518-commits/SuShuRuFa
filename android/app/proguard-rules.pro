# R8/ProGuard 规则：release 变体开启 minify 后保留 JNI 与反射依赖，避免原生方法无法解析。

# 保留所有声明了 native 方法的类及其原生方法名，JNI 通过名字解析这些方法，
# 一旦被 R8 重命名或移除，System.loadLibrary 后的 native 调用会抛 UnsatisfiedLinkError。
-keepclasseswithmembernames class * {
    native <methods>;
}

# LibrimeBridge 以 external 声明原生方法（nativeSetup/nativeProcessKey/nativeContext 等），
# 且作为单例被 JNI 侧引用，整体保留。
-keep class com.susheng.ime.ime.LibrimeBridge { *; }

# 说明：
# - com.huawei.system.BuildEx / android.os.SystemProperties 是框架类，由系统提供，无需 keep；
#   反射调用点已用 runCatching 兜底，类缺失时安全降级。
# - IME Service / Activity 等 Manifest 组件由 Android Gradle Plugin 依据 AndroidManifest.xml 自动保留。
# - 数据类（ContentItem 等）经 org.json 手工读写，不依赖反射字段名，可被 R8 正常优化。
