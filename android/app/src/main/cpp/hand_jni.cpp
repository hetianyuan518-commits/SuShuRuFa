#include <jni.h>
#include <cstring>
#include <cstdlib>
#include <mutex>
#include <memory>
#include <sched.h>
#include <unistd.h>
#include <ncnn/cpu.h>
#include <ncnn/net.h>
#include <string>

// net 由 unique_ptr 持有，所有权与销毁自动化，避免裸 new/delete 漏释放。
static std::unique_ptr<ncnn::Net> net;
// 保护 net 的所有访问：nativeLoad 的重置与 nativeLogits 的读取可能并发，
// 无同步会导致 use-after-free 或读到半更新指针
static std::mutex netMutex;

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
    if (root == nullptr) return JNI_FALSE;
    std::string dir(root);
    env->ReleaseStringUTFChars(path, root);
    quietOpenMp();
    // 先加载到局部临时对象，成功后才发布到全局；避免加载失败时 net 非空但未初始化，
    // 导致后续 nativeLogits 越过 nullptr 守卫调用未加载的 net 而崩溃
    std::unique_ptr<ncnn::Net> fresh(new ncnn::Net());
    fresh->opt.use_vulkan_compute = false;
    fresh->opt.num_threads = 1;
    fresh->opt.openmp_blocktime = 0;
    if (fresh->load_param((dir + "/hand-zh.param").c_str()) != 0) {
        return JNI_FALSE;
    }
    if (fresh->load_model((dir + "/hand-zh.bin").c_str()) != 0) {
        return JNI_FALSE;
    }
    {
        std::lock_guard<std::mutex> lock(netMutex);
        net = std::move(fresh);
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_susheng_ime_engine_HandModel_nativeLogits(JNIEnv* env, jobject, jfloatArray input) {
    if (input == nullptr) return nullptr;
    jsize length = env->GetArrayLength(input);
    if (length < 64 * 64) return nullptr;
    jfloat* data = env->GetFloatArrayElements(input, nullptr);
    if (data == nullptr) return nullptr;  // OOM：避免 memcpy 解引用空指针
    ncnn::Mat in(64, 64, 1, (size_t)4);
    memcpy(in.data, data, 64 * 64 * sizeof(float));
    env->ReleaseFloatArrayElements(input, data, JNI_ABORT);
    quietOpenMp();
    std::lock_guard<std::mutex> lock(netMutex);
    if (net == nullptr) return nullptr;
    ncnn::Extractor ex = net->create_extractor();
    if (ex.input("in0", in) != 0) return nullptr;
    ncnn::Mat out;
    if (ex.extract("out0", out) != 0) return nullptr;
    // 仅处理单精度、未打包的浮点输出；fp16/bf16/packed 或非 float 输出会造成错误结果或越界拷贝。
    if (out.elemsize != 4 || out.elempack != 1) return nullptr;
    int count = out.w * out.h * out.c;
    if (count <= 0) return nullptr;
    jfloatArray result = env->NewFloatArray(count);
    if (result == nullptr) return nullptr;  // OOM：避免向 SetFloatArrayRegion 传入空数组
    env->SetFloatArrayRegion(result, 0, count, static_cast<const jfloat*>(out.data));
    return result;
}
