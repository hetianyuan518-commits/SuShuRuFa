#include <jni.h>
#include <cstring>
#include <cstdlib>
#include <sched.h>
#include <unistd.h>
#include <ncnn/cpu.h>
#include <ncnn/net.h>
#include <string>

static ncnn::Net* net = nullptr;

// 预编译的 libncnn 把 LLVM OpenMP 编了进去。识别跑在新建的后台线程上时，
// 这个线程的 CPU 亲和性掩码是空的，OpenMP 在 __kmp_affinity_initialize 里
// 断言失败并 abort 整个进程。关掉 KMP_AFFINITY 挡不住这次断言，
// 所以 jniLibs 里的 libncnn 已把 __kmp_debug_assert 改成直接返回。
// 这里仍把识别限制成单线程，并尽量给当前线程一个非空掩码。
static void quietOpenMp() {
    setenv("KMP_AFFINITY", "disabled", 1);
    setenv("KMP_WARNINGS", "0", 1);
    setenv("OMP_PROC_BIND", "false", 1);
    setenv("OMP_NUM_THREADS", "1", 1);
    setenv("OMP_WAIT_POLICY", "PASSIVE", 1);
    setenv("KMP_BLOCKTIME", "0", 1);
    long cpus = sysconf(_SC_NPROCESSORS_ONLN);
    if (cpus < 1) cpus = 1;
    cpu_set_t set;
    CPU_ZERO(&set);
    for (long i = 0; i < cpus && i < CPU_SETSIZE; ++i) CPU_SET(i, &set);
    sched_setaffinity(0, sizeof(set), &set);
    ncnn::set_omp_dynamic(0);
    ncnn::set_omp_num_threads(1);
    ncnn::set_kmp_blocktime(0);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    quietOpenMp();
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_susheng_ime_engine_HandModel_nativeLoad(JNIEnv* env, jobject, jstring path) {
    const char* root = env->GetStringUTFChars(path, nullptr);
    std::string dir(root == nullptr ? "" : root);
    env->ReleaseStringUTFChars(path, root);
    delete net;
    quietOpenMp();
    net = new ncnn::Net();
    net->opt.use_vulkan_compute = false;
    net->opt.num_threads = 1;
    net->opt.openmp_blocktime = 0;
    if (net->load_param((dir + "/hand-zh.param").c_str()) != 0) return JNI_FALSE;
    if (net->load_model((dir + "/hand-zh.bin").c_str()) != 0) return JNI_FALSE;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_susheng_ime_engine_HandModel_nativeLogits(JNIEnv* env, jobject, jfloatArray input) {
    if (net == nullptr || input == nullptr) return nullptr;
    quietOpenMp();
    jsize length = env->GetArrayLength(input);
    if (length < 64 * 64) return nullptr;
    jfloat* data = env->GetFloatArrayElements(input, nullptr);
    ncnn::Mat in(64, 64, 1, (size_t)4);
    memcpy(in.data, data, 64 * 64 * sizeof(float));
    env->ReleaseFloatArrayElements(input, data, JNI_ABORT);
    ncnn::Extractor ex = net->create_extractor();
    if (ex.input("in0", in) != 0) return nullptr;
    ncnn::Mat out;
    if (ex.extract("out0", out) != 0) return nullptr;
    int count = out.w * out.h * out.c;
    if (count <= 0) return nullptr;
    jfloatArray result = env->NewFloatArray(count);
    env->SetFloatArrayRegion(result, 0, count, static_cast<const jfloat*>(out.data));
    return result;
}
