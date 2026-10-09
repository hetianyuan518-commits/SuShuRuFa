#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface SushengStore : NSObject
@property (nonatomic) BOOL chinese;
@property (nonatomic, copy) NSString *mode;
@property (nonatomic, readonly) NSArray *items;
@property (nonatomic, readonly) NSInteger serverVersion;
+ (instancetype)shared;
- (void)loadLocal;
- (nullable NSDictionary *)session;
/// 返回 assetId 对应的本地文件路径。
/// 安全契约：assetId 必须是裸文件名；实现会拒绝包含路径分隔符或 ".." 的值，
/// 非法时返回 nil，调用方必须判空。
- (nullable NSString *)assetPath:(NSString *)assetId;
/// server 必须是 https:// 基础地址；password 以明文字段发送，调用方不得记录或持久化。
/// done 回调在主队列执行；可变状态（chinese/mode/items/serverVersion）仅在主队列内部改写，
/// 调用方不得改写，且应只在主线程读取（属性为 nonatomic，跨线程读取不安全）。
- (void)loginServer:(NSString *)server username:(NSString *)username password:(NSString *)password done:(void (^)(NSString *message))done;
/// done 回调在主队列执行；线程契约同 loginServer:。
- (void)sync:(void (^)(NSString *message))done;
/// 即发即忘；在主队列追加到上传队列。
- (void)reportUsage:(NSString *)itemId surface:(NSString *)surface;
/// op 必须是受支持的操作名（如 "candidates"、"associate"）；raw/last 为不可信输入，
/// 实现应在转发前校验并限长。raw/last/mode 可为 nil（分别按空串、默认模式处理）。
- (NSArray *)queryOp:(NSString *)op raw:(nullable NSString *)raw last:(nullable NSString *)last mode:(nullable NSString *)mode;
@end

NS_ASSUME_NONNULL_END
