#import <Foundation/Foundation.h>

@interface SushengStore : NSObject
@property (nonatomic) BOOL chinese;
@property (nonatomic, copy) NSString *mode;
@property (nonatomic, readonly) NSArray *items;
@property (nonatomic, readonly) NSInteger serverVersion;
+ (instancetype)shared;
- (void)loadLocal;
- (NSDictionary *)session;
- (NSString *)assetPath:(NSString *)assetId;
- (void)loginServer:(NSString *)server username:(NSString *)username password:(NSString *)password done:(void (^)(NSString *message))done;
- (void)sync:(void (^)(NSString *message))done;
- (void)reportUsage:(NSString *)itemId surface:(NSString *)surface;
- (NSArray *)queryOp:(NSString *)op raw:(NSString *)raw last:(NSString *)last mode:(NSString *)mode;
@end
