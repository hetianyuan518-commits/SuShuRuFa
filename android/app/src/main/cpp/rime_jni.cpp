#include <jni.h>
#include <string>

#if defined(SUSHENG_HAS_RIME) && __has_include("rime_api.h")
#include "rime_api.h"
#define SUSHENG_RIME_LINKED 1
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_com_susheng_ime_ime_LibrimeBridge_nativeAvailable(JNIEnv *, jclass) {
#ifdef SUSHENG_RIME_LINKED
    return rime_get_api() != nullptr ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_susheng_ime_ime_LibrimeBridge_nativeSetup(JNIEnv *env, jclass, jstring shared_dir, jstring user_dir) {
#ifdef SUSHENG_RIME_LINKED
    // 防止重复初始化：多次 setup/initialize/start_maintenance 而无 finalize 会泄漏资源或损坏引擎状态。
    static bool g_initialized = false;
    if (g_initialized) return JNI_TRUE;
    const char *shared = env->GetStringUTFChars(shared_dir, nullptr);
    const char *user = env->GetStringUTFChars(user_dir, nullptr);
    if (shared == nullptr || user == nullptr) {
        // GetStringUTFChars 失败（OOM 或无效 jstring）：仅释放非空指针，避免 JNI 违约的未定义行为
        if (shared != nullptr) env->ReleaseStringUTFChars(shared_dir, shared);
        if (user != nullptr) env->ReleaseStringUTFChars(user_dir, user);
        return JNI_FALSE;
    }
    RimeTraits traits = {0};
    traits.data_size = sizeof(RimeTraits);
    traits.shared_data_dir = shared;
    traits.user_data_dir = user;
    traits.distribution_name = "Susheng";
    traits.distribution_code_name = "susheng";
    traits.distribution_version = "1.0";
    traits.app_name = "com.susheng.ime";
    RimeApi *api = rime_get_api();
    jboolean ok = JNI_FALSE;
    if (api != nullptr) {
        // 聚合各阶段返回值：任一失败都应让 Kotlin 调用方知道初始化未成功
        bool setup_ok = api->setup(&traits);
        bool init_ok = api->initialize(&traits);
        bool maint_ok = api->start_maintenance(True);
        api->join_maintenance_thread();
        ok = (setup_ok && init_ok && maint_ok) ? JNI_TRUE : JNI_FALSE;
        if (ok == JNI_TRUE) g_initialized = true;
    }
    env->ReleaseStringUTFChars(shared_dir, shared);
    env->ReleaseStringUTFChars(user_dir, user);
    return ok;
#else
    (void) env;
    (void) shared_dir;
    (void) user_dir;
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_susheng_ime_ime_LibrimeBridge_nativeCreateSession(JNIEnv *, jclass) {
#ifdef SUSHENG_RIME_LINKED
    RimeApi *api = rime_get_api();
    if (api == nullptr) return 0;
    return (jlong) api->create_session();
#else
    return 0;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_susheng_ime_ime_LibrimeBridge_nativeProcessKey(JNIEnv *, jclass, jlong session, jint keycode) {
#ifdef SUSHENG_RIME_LINKED
    RimeApi *api = rime_get_api();
    if (api == nullptr || session <= 0) return JNI_FALSE;
    return api->process_key((RimeSessionId) session, keycode, 0) ? JNI_TRUE : JNI_FALSE;
#else
    (void) session;
    (void) keycode;
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_susheng_ime_ime_LibrimeBridge_nativeContext(JNIEnv *env, jclass, jlong session) {
#ifdef SUSHENG_RIME_LINKED
    RimeApi *api = rime_get_api();
    if (api == nullptr || session <= 0) return env->NewStringUTF("");
    RimeContext context = {0};
    context.data_size = sizeof(RimeContext);
    if (!api->get_context((RimeSessionId) session, &context)) return env->NewStringUTF("");
    std::string text;
    try {
        text = context.composition.preedit ? context.composition.preedit : "";
        if (context.menu.candidates) {
            for (int i = 0; i < context.menu.num_candidates; i++) {
                text.push_back('\n');
                if (context.menu.candidates[i].text) text += context.menu.candidates[i].text;
            }
        }
    } catch (...) {
        // std::string 分配失败时也要释放 context，避免会话拥有的 context 泄漏，
        // 并阻止异常穿越 JNI 边界（未定义行为）。
        api->free_context(&context);
        return env->NewStringUTF("");
    }
    api->free_context(&context);
    return env->NewStringUTF(text.c_str());
#else
    (void) session;
    return env->NewStringUTF("");
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_susheng_ime_ime_LibrimeBridge_nativeDestroySession(JNIEnv *, jclass, jlong session) {
#ifdef SUSHENG_RIME_LINKED
    RimeApi *api = rime_get_api();
    if (api != nullptr && session > 0) api->destroy_session((RimeSessionId) session);
#else
    (void) session;
#endif
}
