#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// 链进 librime 之后，dlsym 才能找到 rime_get_api。没链上时键盘使用内置拼音。
/// 可用性/线程契约：SushengRimeAvailable() 通过 dlsym(RTLD_DEFAULT, "rime_get_api") 惰性探测，
/// 并与 SushengRimeContext 的编译门控（SUSHENG_HAS_RIME）一致；未编进链接路径时固定返回 NO。
/// SushengRimeContext 内部会创建/销毁共享的 rime session 并操作全局 rime 状态，因此必须从
/// 单一线程/串行队列调用（当前调用方均在主线程），不得并发调用。
FOUNDATION_EXPORT BOOL SushengRimeAvailable(void);
/// 失败契约：本函数无 NSError**/哨兵通道，对所有失败/回退路径（rime 未链接、
/// SushengRimeAvailable() 为 NO、raw 为空、create_session 失败）与「转换结果为空」都返回 @""，
/// 调用方无法区分这几种情形，需自行配合 SushengRimeAvailable() 判断能力后再调用。
FOUNDATION_EXPORT NSString *SushengRimeContext(NSString *raw);

NS_ASSUME_NONNULL_END
