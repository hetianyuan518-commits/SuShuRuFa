#import "RimeBridge.h"
#import <dlfcn.h>

// 仅当构建显式定义 SUSHENG_HAS_RIME 且头文件可寻时才启用链接路径（与 Android 桥一致）。
// 否则若 rime_api.h 恰好进入 HEADER_SEARCH_PATHS 但 librime 未链接，rime_get_api() 会链接失败，
// 破坏「未链接时回退内置拼音」的降级语义。
#if defined(SUSHENG_HAS_RIME) && __has_include("rime_api.h")
#include "rime_api.h"
#define SUSHENG_RIME_LINKED 1
#endif

BOOL SushengRimeAvailable(void) {
    // 与 SushengRimeContext 的编译门控保持一致：未编译进链接路径时直接报 NO，
    // 避免「dlsym 能找到符号但 context 编译成 return @\"\"」两者能力上报不一致。
#ifdef SUSHENG_RIME_LINKED
    return dlsym(RTLD_DEFAULT, "rime_get_api") != NULL;
#else
    return NO;
#endif
}

NSString *SushengRimeContext(NSString *raw) {
#ifdef SUSHENG_RIME_LINKED
    if (!SushengRimeAvailable() || raw.length == 0) return @"";
    RimeApi *api = rime_get_api();
    if (api == NULL) return @"";
    // 注意：此处每次调用都新建/销毁 session 并整串重放按键，逐键调用时开销随输入长度呈二次增长。
    // 复用持久 session + 增量喂键可显著降本，但需 librime 头文件在位才能正确实现并验证
    // （会话复位/composition 清理的具体 API）；默认构建未定义 SUSHENG_HAS_RIME，本分支不参与编译，故暂维持现状。
    RimeSessionId session = api->create_session();
    if (!session) return @"";
    const char *bytes = raw.UTF8String;
    for (const char *cursor = bytes; cursor && *cursor; cursor++) {
        // char 在部分平台为有符号：>=0x80 的 UTF-8 字节会符号扩展为负键码，先经 unsigned char 转换。
        api->process_key(session, (int)(unsigned char)(*cursor), 0);
    }
    RimeContext context = {0};
    context.data_size = sizeof(RimeContext);
    NSMutableString *text = [NSMutableString string];
    if (api->get_context(session, &context)) {
        if (context.composition.preedit) [text appendString:@(context.composition.preedit)];
        if (context.menu.candidates) {
            for (int i = 0; i < context.menu.num_candidates; i++) {
                [text appendString:@"\n"];
                if (context.menu.candidates[i].text) [text appendString:@(context.menu.candidates[i].text)];
            }
        }
        api->free_context(&context);
    }
    api->destroy_session(session);
    return text;
#else
    (void) raw;
    return @"";
#endif
}
