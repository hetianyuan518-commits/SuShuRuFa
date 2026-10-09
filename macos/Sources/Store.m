#import "Store.h"
#import <string.h>

char *SushengQuery(const char *payload);
void SushengFree(char *ptr);

static NSString *TrimSlash(NSString *value) {
    while ([value hasSuffix:@"/"]) value = [value substringToIndex:value.length - 1];
    return value ?: @"";
}

@implementation SushengStore {
    NSMutableArray *_items;
    NSInteger _epoch;
    NSURL *_root;
}

+ (instancetype)shared {
    static SushengStore *store;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ store = [SushengStore new]; });
    return store;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _chinese = YES;
        _mode = @"qwerty";
        _items = [NSMutableArray array];
        NSString *base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, YES).firstObject;
        _root = [NSURL fileURLWithPath:[base stringByAppendingPathComponent:@"苏晟输入法"] isDirectory:YES];
    }
    return self;
}

- (NSArray *)items { return [NSArray arrayWithArray:_items]; }

- (NSURL *)file:(NSString *)name { return [_root URLByAppendingPathComponent:name]; }

- (void)loadLocal {
    [[NSFileManager defaultManager] createDirectoryAtURL:_root withIntermediateDirectories:YES attributes:nil error:nil];
    NSData *data = [NSData dataWithContentsOfURL:[self file:@"content-pack.json"]];
    NSDictionary *pack = [NSJSONSerialization JSONObjectWithData:data ?: [NSData data] options:0 error:nil];
    if (![pack isKindOfClass:[NSDictionary class]]) return;
    _serverVersion = [pack[@"serverVersion"] integerValue];
    _epoch = [pack[@"epoch"] integerValue];
    [self replaceItems:pack[@"items"]];
}

- (void)replaceItems:(NSArray *)items {
    [_items removeAllObjects];
    // pack[@"items"] / json[@"items"] 来自服务端，可能被篡改或类型不符；
    // 非数组直接快速枚举会崩溃，先做类型校验。
    if (![items isKindOfClass:[NSArray class]]) return;
    for (NSDictionary *item in items) {
        if ([item isKindOfClass:[NSDictionary class]] && ![item[@"deleted"] boolValue]) [_items addObject:item];
    }
}

- (NSDictionary *)session {
    NSData *data = [NSData dataWithContentsOfURL:[self file:@"session.json"]];
    id json = [NSJSONSerialization JSONObjectWithData:data ?: [NSData data] options:0 error:nil];
    return [json isKindOfClass:[NSDictionary class]] ? json : nil;
}

- (NSString *)assetPath:(NSString *)assetId {
    // 安全校验：assetId 来自服务端，必须是裸文件名；拒绝路径分隔符与 ".."，防止路径穿越读写任意文件
    if (![assetId isKindOfClass:[NSString class]] || assetId.length == 0) return nil;
    if ([assetId rangeOfCharacterFromSet:[NSCharacterSet characterSetWithCharactersInString:@"/\\"]].location != NSNotFound) return nil;
    if ([assetId containsString:@".."]) return nil;
    NSURL *dir = [_root URLByAppendingPathComponent:@"assets" isDirectory:YES];
    NSString *path = [dir URLByAppendingPathComponent:assetId].path;
    // 双重保险：标准化后的路径必须仍在 assets 目录内
    if (![[path stringByStandardizingPath] hasPrefix:[[dir.path stringByStandardizingPath] stringByAppendingString:@"/"]]) return nil;
    return path;
}

- (void)loginServer:(NSString *)server username:(NSString *)username password:(NSString *)password done:(void (^)(NSString *))done {
    NSString *base = TrimSlash(server);
    [self request:base path:@"/api/auth/login" token:nil method:@"POST" body:@{@"username": username ?: @"", @"password": password ?: @""} done:^(NSDictionary *json, NSString *error) {
        if (error) { done(error); return; }
        NSString *token = json[@"token"];
        if (token.length == 0) { done(json[@"error"] ?: @"登录失败"); return; }
        // json[@"user"] 可能为 NSNull/数组/字符串；不先类型校验就对 displayName 发消息会因无法识别的选择子崩溃。
        id user = json[@"user"];
        NSString *name = ([user isKindOfClass:[NSDictionary class]] && [user[@"displayName"] isKindOfClass:[NSString class]]) ? user[@"displayName"] : username;
        NSDictionary *session = @{@"server": base, @"token": token, @"displayName": name ?: @""};
        [self writeJSON:session name:@"session.json"];
        done(@"已登录");
    }];
}

- (void)sync:(void (^)(NSString *))done {
    NSDictionary *session = [self session];
    if (![session[@"token"] length]) { done(@"请先登录"); return; }
    NSInteger since = _epoch == 0 ? 0 : _serverVersion;
    NSString *path = [NSString stringWithFormat:@"/api/sync?since=%ld&epoch=%ld", (long)since, (long)_epoch];
    [self request:session[@"server"] path:path token:session[@"token"] method:@"GET" body:nil done:^(NSDictionary *json, NSString *error) {
        if (error) { done(error); return; }
        NSArray *incoming = [json[@"items"] isKindOfClass:[NSArray class]] ? json[@"items"] : @[];
        NSInteger responseSince = [json[@"since"] integerValue];
        NSInteger responseEpoch = [json[@"epoch"] integerValue];
        if (responseSince == 0 || responseEpoch != _epoch) {
            [self replaceItems:incoming];
        } else {
            NSMutableDictionary *map = [NSMutableDictionary dictionary];
            for (NSDictionary *item in _items) map[item[@"id"]] = item;
            for (NSDictionary *item in incoming) {
                if ([item[@"deleted"] boolValue]) [map removeObjectForKey:item[@"id"]];
                else map[item[@"id"]] = item;
            }
            [self replaceItems:map.allValues];
        }
        _serverVersion = [json[@"serverVersion"] integerValue];
        _epoch = responseEpoch;
        [self writeJSON:@{@"serverVersion": @(_serverVersion), @"epoch": @(_epoch), @"items": _items} name:@"content-pack.json"];
        [self downloadAssets:session done:^{
            [self flushUsage:session done:^{
                done([NSString stringWithFormat:@"已同步到版本 %ld · %lu 条", (long)_serverVersion, (unsigned long)_items.count]);
            }];
        }];
    }];
}

- (void)reportUsage:(NSString *)itemId surface:(NSString *)surface {
    if (itemId.length == 0) return;
    NSMutableArray *queue = [[self readUsage] mutableCopy];
    [queue addObject:@{@"itemId": itemId, @"surface": surface ?: @"candidate"}];
    if (queue.count > 200) [queue removeObjectsInRange:NSMakeRange(0, queue.count - 200)];
    [self writeJSON:queue name:@"usage-queue.json"];
}

- (NSArray *)queryOp:(NSString *)op raw:(NSString *)raw last:(NSString *)last mode:(NSString *)mode {
    NSDictionary *body = @{
        @"op": op ?: @"candidates",
        @"raw": raw ?: @"",
        @"last": last ?: @"",
        @"mode": mode ?: @"qwerty",
        @"now": @((long long)([[NSDate date] timeIntervalSince1970] * 1000)),
        @"items": _items ?: @[]
    };
    NSData *data = [NSJSONSerialization dataWithJSONObject:body options:0 error:nil];
    if (!data) return @[];
    NSString *json = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
    char *result = SushengQuery(json.UTF8String);
    // C 契约（char *SushengQuery(const char *)）并不保证非 NULL；为 NULL 时 strlen 会解引用崩溃。
    if (result == NULL) return @[];
    NSData *out = [NSData dataWithBytes:result length:strlen(result)];
    SushengFree(result);
    id rows = [NSJSONSerialization JSONObjectWithData:out options:0 error:nil];
    return [rows isKindOfClass:[NSArray class]] ? rows : @[];
}

- (NSArray *)readUsage {
    NSData *data = [NSData dataWithContentsOfURL:[self file:@"usage-queue.json"]];
    id json = [NSJSONSerialization JSONObjectWithData:data ?: [NSData data] options:0 error:nil];
    return [json isKindOfClass:[NSArray class]] ? json : @[];
}

- (void)flushUsage:(NSDictionary *)session done:(void (^)(void))done {
    NSArray *snapshot = [self readUsage];
    if (snapshot.count == 0) { done(); return; }
    __block NSUInteger uploaded = 0;
    __block void (^step)(void);
    void (^finish)(void) = ^{
        // 仅移除已成功上传的事件；flush 期间 reportUsage 追加的事件保留，失败的事件留待下次重试
        NSMutableArray *remaining = [[self readUsage] mutableCopy];
        for (NSUInteger i = 0; i < uploaded; i++) [remaining removeObject:snapshot[i]];
        [self writeJSON:remaining name:@"usage-queue.json"];
    };
    step = ^{
        if (uploaded >= snapshot.count) {
            finish();
            step = nil;
            done();
            return;
        }
        NSDictionary *event = snapshot[uploaded];
        [self request:session[@"server"] path:@"/api/usage" token:session[@"token"] method:@"POST" body:event done:^(NSDictionary *json, NSString *error) {
            if (error) {
                // 上传失败：停止本次 flush，未上传事件保留在队列中
                finish();
                step = nil;
                done();
                return;
            }
            uploaded += 1;
            step();
        }];
    };
    step = [step copy];
    step();
}

- (void)downloadAssets:(NSDictionary *)session done:(void (^)(void))done {
    NSURL *dir = [_root URLByAppendingPathComponent:@"assets" isDirectory:YES];
    [[NSFileManager defaultManager] createDirectoryAtURL:dir withIntermediateDirectories:YES attributes:nil error:nil];
    NSMutableArray *pending = [NSMutableArray array];
    for (NSDictionary *item in _items) {
        NSString *assetId = item[@"assetId"];
        if (![assetId isKindOfClass:[NSString class]] || assetId.length == 0) continue;
        NSString *path = [self assetPath:assetId];
        if (!path) continue;  // 非法 assetId（含路径穿越）直接跳过
        if (![[NSFileManager defaultManager] fileExistsAtPath:path]) [pending addObject:assetId];
    }
    __block NSUInteger index = 0;
    __block void (^step)(void);
    step = ^{
        if (index >= pending.count) {
            step = nil;
            done();
            return;
        }
        NSString *assetId = pending[index++];
        // assetId 拼接到 URL 路径；需百分号编码，且 URLWithString: 可能因非法字符返回 nil，
        // nil 传给 requestWithURL: 会抛 NSInvalidArgumentException，故先判空。
        NSString *encodedId = [assetId stringByAddingPercentEncodingWithAllowedCharacters:[NSCharacterSet URLPathAllowedCharacterSet]];
        NSURL *url = [NSURL URLWithString:[NSString stringWithFormat:@"%@/api/assets/%@", session[@"server"], encodedId]];
        if (!url) { dispatch_async(dispatch_get_main_queue(), step); return; }
        NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:url];
        [req setValue:[@"Bearer " stringByAppendingString:session[@"token"]] forHTTPHeaderField:@"Authorization"];
        [[[NSURLSession sharedSession] dataTaskWithRequest:req completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
            NSHTTPURLResponse *http = (NSHTTPURLResponse *)response;
            NSString *dest = [self assetPath:assetId];
            if (data && http.statusCode < 400 && dest) [data writeToFile:dest atomically:YES];
            dispatch_async(dispatch_get_main_queue(), step);
        }] resume];
    };
    step = [step copy];
    step();
}

- (void)request:(NSString *)base path:(NSString *)path token:(NSString *)token method:(NSString *)method body:(NSDictionary *)body done:(void (^)(NSDictionary *, NSString *))done {
    NSURL *url = [NSURL URLWithString:[base stringByAppendingString:path]];
    if (!url) { done(nil, @"地址不正确"); return; }
    NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:url];
    req.HTTPMethod = method;
    if (token.length) [req setValue:[@"Bearer " stringByAppendingString:token] forHTTPHeaderField:@"Authorization"];
    if (body) {
        req.HTTPBody = [NSJSONSerialization dataWithJSONObject:body options:0 error:nil];
        [req setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
    }
    [[[NSURLSession sharedSession] dataTaskWithRequest:req completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
        dispatch_async(dispatch_get_main_queue(), ^{
            if (error) { done(nil, error.localizedDescription); return; }
            NSDictionary *json = [NSJSONSerialization JSONObjectWithData:data ?: [NSData data] options:0 error:nil];
            if (![json isKindOfClass:[NSDictionary class]]) json = @{};
            NSInteger status = [(NSHTTPURLResponse *)response statusCode];
            if (status >= 400) { done(json, json[@"error"] ?: @"请求失败"); return; }
            done(json, nil);
        });
    }] resume];
}

- (void)writeJSON:(id)value name:(NSString *)name {
    NSData *data = [NSJSONSerialization dataWithJSONObject:value options:0 error:nil];
    [data writeToURL:[self file:name] atomically:YES];
}

@end
