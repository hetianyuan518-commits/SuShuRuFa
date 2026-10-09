#import <Cocoa/Cocoa.h>

// 线程契约：以下函数均读写仅主线程可用的 AppKit 状态（NSUserDefaults、构造
// NSColor/NSWindow、activateIgnoringOtherApps:），必须在主线程调用，否则为未定义行为。
// 所有权：返回的 NSColor 为自动释放对象（未持有，NS_RETURNS_NOT_RETAINED），调用方不得 release。
NS_ASSUME_NONNULL_BEGIN

NSColor *SushengPanelColor(void);
NSColor *SushengTextColor(void);
NSColor *SushengAccentColor(void);
void SushengPresentHome(void);

NS_ASSUME_NONNULL_END
