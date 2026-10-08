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

- (NSArray *)items { return _items; }

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
    return [[_root URLByAppendingPathComponent:@"assets" isDirectory:YES] URLByAppendingPathComponent:assetId].path;
}

- (void)loginServer:(NSString *)server username:(NSString *)username password:(NSString *)password done:(void (^)(NSString *))done {
    NSString *base = TrimSlash(server);
    [self request:base path:@"/api/auth/login" token:nil method:@"POST" body:@{@"username": username ?: @"", @"password": password ?: @""} done:^(NSDictionary *json, NSString *error) {
        if (error) { done(error); return; }
        NSString *token = json[@"token"];
        if (token.length == 0) { done(json[@"error"] ?: @"登录失败"); return; }
        NSString *name = json[@"user"][@"displayName"] ?: username;
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
    NSMutableArray *queue = [[self readUsage] mutableCopy];
    if (queue.count == 0) { done(); return; }
    __block void (^step)(void);
    step = ^{
        if (queue.count == 0) {
            [self writeJSON:@[] name:@"usage-queue.json"];
            step = nil;
            done();
            return;
        }
        NSDictionary *event = queue.firstObject;
        [queue removeObjectAtIndex:0];
        [self request:session[@"server"] path:@"/api/usage" token:session[@"token"] method:@"POST" body:event done:^(NSDictionary *json, NSString *error) {
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
        if (![[NSFileManager defaultManager] fileExistsAtPath:[self assetPath:assetId]]) [pending addObject:assetId];
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
        NSURL *url = [NSURL URLWithString:[NSString stringWithFormat:@"%@/api/assets/%@", session[@"server"], assetId]];
        NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:url];
        [req setValue:[@"Bearer " stringByAppendingString:session[@"token"]] forHTTPHeaderField:@"Authorization"];
        [[[NSURLSession sharedSession] dataTaskWithRequest:req completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
            NSHTTPURLResponse *http = (NSHTTPURLResponse *)response;
            if (data && http.statusCode < 400) [data writeToFile:[self assetPath:assetId] atomically:YES];
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
