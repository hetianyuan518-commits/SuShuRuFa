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
    const char *shared = env->GetStringUTFChars(shared_dir, nullptr);
    const char *user = env->GetStringUTFChars(user_dir, nullptr);
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
        api->setup(&traits);
        api->initialize(&traits);
        api->start_maintenance(True);
        api->join_maintenance_thread();
        ok = JNI_TRUE;
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
    if (api == nullptr || session == 0) return JNI_FALSE;
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
    if (api == nullptr || session == 0) return env->NewStringUTF("");
    RimeContext context = {0};
    context.data_size = sizeof(RimeContext);
    if (!api->get_context((RimeSessionId) session, &context)) return env->NewStringUTF("");
    std::string text = context.composition.preedit ? context.composition.preedit : "";
    if (context.menu.candidates) {
        for (int i = 0; i < context.menu.num_candidates; i++) {
            text.push_back('\n');
            if (context.menu.candidates[i].text) text += context.menu.candidates[i].text;
        }
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
    if (api != nullptr && session != 0) api->destroy_session((RimeSessionId) session);
#else
    (void) session;
#endif
}
