#import "RimeBridge.h"
#import <dlfcn.h>

#if __has_include("rime_api.h")
#include "rime_api.h"
#define SUSHENG_RIME_LINKED 1
#endif

BOOL SushengRimeAvailable(void) {
    return dlsym(RTLD_DEFAULT, "rime_get_api") != NULL;
}

NSString *SushengRimeContext(NSString *raw) {
#ifdef SUSHENG_RIME_LINKED
    if (!SushengRimeAvailable() || raw.length == 0) return @"";
    RimeApi *api = rime_get_api();
    if (api == NULL) return @"";
    RimeSessionId session = api->create_session();
    if (!session) return @"";
    const char *bytes = raw.UTF8String;
    for (const char *cursor = bytes; cursor && *cursor; cursor++) {
        api->process_key(session, (int)(*cursor), 0);
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
