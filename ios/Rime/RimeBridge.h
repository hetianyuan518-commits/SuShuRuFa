#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// 链进 librime 之后，dlsym 才能找到 rime_get_api。没链上时键盘使用内置拼音。
FOUNDATION_EXPORT BOOL SushengRimeAvailable(void);
FOUNDATION_EXPORT NSString *SushengRimeContext(NSString *raw);

NS_ASSUME_NONNULL_END
