#import <Cocoa/Cocoa.h>
#import <InputMethodKit/InputMethodKit.h>
#import <Carbon/Carbon.h>
#import "Store.h"
#import "Home.h"

static NSRange NotFoundRange(void) { return NSMakeRange(NSNotFound, NSNotFound); }

static int DigitForKeyCode(unsigned short code) {
    switch (code) {
        case kVK_ANSI_1: return 1;
        case kVK_ANSI_2: return 2;
        case kVK_ANSI_3: return 3;
        case kVK_ANSI_4: return 4;
        case kVK_ANSI_5: return 5;
        case kVK_ANSI_6: return 6;
        case kVK_ANSI_7: return 7;
        case kVK_ANSI_8: return 8;
        case kVK_ANSI_9: return 9;
        default: return 0;
    }
}

@interface SushengPanel : NSObject
@property (nonatomic, copy) void (^onPick)(NSInteger index);
- (void)updateHeader:(NSString *)header lines:(NSArray *)lines near:(NSRect)rect;
- (void)hide;
@end

@implementation SushengPanel {
    NSPanel *_panel;
    NSView *_content;
}
- (instancetype)init {
    self = [super init];
    if (!self) return nil;
    _panel = [[NSPanel alloc] initWithContentRect:NSMakeRect(0, 0, 480, 88) styleMask:NSWindowStyleMaskBorderless | NSWindowStyleMaskNonactivatingPanel backing:NSBackingStoreBuffered defer:NO];
    _panel.level = NSPopUpMenuWindowLevel;
    _panel.opaque = NO;
    _panel.backgroundColor = SushengPanelColor();
    _panel.hasShadow = YES;
    _panel.hidesOnDeactivate = NO;
    _panel.collectionBehavior = NSWindowCollectionBehaviorCanJoinAllSpaces | NSWindowCollectionBehaviorFullScreenAuxiliary;
    _content = [NSView new];
    _panel.contentView = _content;
    return self;
}
- (void)updateHeader:(NSString *)header lines:(NSArray *)lines near:(NSRect)rect {
    [_content.subviews makeObjectsPerformSelector:@selector(removeFromSuperview)];
    CGFloat width = 480, row = 28;
    CGFloat height = 36 + row * lines.count + 30;
    if (height < 70) height = 70;
    [_panel setContentSize:NSMakeSize(width, height)];
    _panel.backgroundColor = SushengPanelColor();
    [self label:header.length ? header : @" " y:height - 30 width:width color:SushengTextColor() size:13 button:NO tag:0];
    for (NSUInteger i = 0; i < lines.count; i++) {
        [self label:[NSString stringWithFormat:@"%lu  %@", (unsigned long)(i + 1), lines[i]] y:height - 36 - row * (i + 1) width:width color:[NSColor labelColor] size:16 button:YES tag:i];
    }
    [self label:@"F2 话术   F3 推文   F4 宣发   F5 图片   Shift+Space 中/英" y:4 width:width color:SushengAccentColor() size:12 button:NO tag:0];
    NSPoint origin = NSMakePoint(rect.origin.x, rect.origin.y + rect.size.height + 8);
    if (rect.size.width == 0 && rect.size.height == 0) {
        NSPoint mouse = [NSEvent mouseLocation];
        origin = NSMakePoint(mouse.x, mouse.y + 16);
    }
    // 光标 rect 为全局屏幕坐标，可能落在非主显示器上；只用 mainScreen 高度会在多屏下把面板放到屏外。
    // 定位实际包含面板原点的屏幕，并针对其 visibleFrame 做翻转与夹制。
    NSScreen *target = nil;
    for (NSScreen *s in [NSScreen screens]) {
        if (NSPointInRect(origin, s.frame)) { target = s; break; }
    }
    if (!target) target = [NSScreen mainScreen];
    NSRect visible = target.visibleFrame;
    if (origin.y + height > NSMaxY(visible)) origin.y = MAX(NSMinY(visible), rect.origin.y - height - 8);
    [_panel setFrameOrigin:origin];
    [_panel orderFrontRegardless];
}
- (void)label:(NSString *)text y:(CGFloat)y width:(CGFloat)width color:(NSColor *)color size:(CGFloat)size button:(BOOL)button tag:(NSInteger)tag {
    if (button) {
        NSButton *view = [[NSButton alloc] initWithFrame:NSMakeRect(8, y, width - 16, 26)];
        view.title = text;
        view.bordered = NO;
        view.alignment = NSTextAlignmentLeft;
        view.font = [NSFont systemFontOfSize:size];
        view.tag = tag;
        view.target = self;
        view.action = @selector(picked:);
        [_content addSubview:view];
        return;
    }
    NSTextField *view = [[NSTextField alloc] initWithFrame:NSMakeRect(12, y, width - 24, 22)];
    view.stringValue = text;
    view.editable = NO;
    view.bordered = NO;
    view.drawsBackground = NO;
    view.textColor = color;
    view.font = [NSFont systemFontOfSize:size];
    [_content addSubview:view];
}
- (void)picked:(NSButton *)sender { if (self.onPick) self.onPick(sender.tag); }
- (void)hide { [_panel orderOut:nil]; }
@end

@interface SushengPreferences : NSObject
+ (void)show;
@end

@implementation SushengPreferences {
    NSWindow *_window;
    NSTextField *_server;
    NSTextField *_user;
    NSSecureTextField *_password;
    NSTextField *_status;
}
+ (void)show { [[self shared] open]; }
+ (instancetype)shared {
    static SushengPreferences *prefs;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ prefs = [SushengPreferences new]; });
    return prefs;
}
- (void)open {
    if (!_window) [self build];
    [NSApp activateIgnoringOtherApps:YES];
    [_window makeKeyAndOrderFront:nil];
}
- (void)build {
    _window = [[NSWindow alloc] initWithContentRect:NSMakeRect(0, 0, 480, 300) styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable backing:NSBackingStoreBuffered defer:NO];
    _window.title = @"苏晟输入法";
    _window.backgroundColor = [NSColor colorWithCalibratedRed:0.95 green:0.92 blue:0.86 alpha:1];
    NSView *content = _window.contentView;
    NSDictionary *session = [[SushengStore shared] session];
    [self label:@"登录后同步分配给你的话术。输入法不上传你敲下的字。" x:24 y:250 width:440 size:13 view:content];
    [self label:@"内容台地址" x:24 y:214 width:120 size:12 view:content];
    _server = [self field:session[@"server"] ?: @"http://127.0.0.1:8787" x:24 y:186 width:430 secure:NO view:content];
    [self label:@"账号" x:24 y:158 width:80 size:12 view:content];
    [self label:@"密码" x:250 y:158 width:80 size:12 view:content];
    _user = [self field:@"" x:24 y:130 width:200 secure:NO view:content];
    _password = (NSSecureTextField *)[self field:@"" x:250 y:130 width:190 secure:YES view:content];
    [content addSubview:[self button:@"登录" x:24 y:78 action:@selector(doLogin)]];
    [content addSubview:[self button:@"同步内容包" x:250 y:78 action:@selector(doSync)]];
    _status = [[NSTextField alloc] initWithFrame:NSMakeRect(24, 24, 430, 44)];
    _status.editable = NO;
    _status.bordered = NO;
    _status.drawsBackground = NO;
    _status.stringValue = session ? [NSString stringWithFormat:@"%@ · 本地版本 %ld", session[@"displayName"], (long)[SushengStore shared].serverVersion] : @"尚未同步";
    [content addSubview:_status];
    [_window center];
}
- (void)label:(NSString *)text x:(CGFloat)x y:(CGFloat)y width:(CGFloat)width size:(CGFloat)size view:(NSView *)view {
    NSTextField *label = [[NSTextField alloc] initWithFrame:NSMakeRect(x, y, width, 20)];
    label.stringValue = text;
    label.editable = NO;
    label.bordered = NO;
    label.drawsBackground = NO;
    label.font = [NSFont systemFontOfSize:size];
    [view addSubview:label];
}
- (NSTextField *)field:(NSString *)text x:(CGFloat)x y:(CGFloat)y width:(CGFloat)width secure:(BOOL)secure view:(NSView *)view {
    NSTextField *field = secure ? [[NSSecureTextField alloc] initWithFrame:NSMakeRect(x, y, width, 26)] : [[NSTextField alloc] initWithFrame:NSMakeRect(x, y, width, 26)];
    field.stringValue = text ?: @"";
    [view addSubview:field];
    return field;
}
- (NSButton *)button:(NSString *)title x:(CGFloat)x y:(CGFloat)y action:(SEL)action {
    NSButton *button = [[NSButton alloc] initWithFrame:NSMakeRect(x, y, 190, 32)];
    button.title = title;
    button.bezelStyle = NSBezelStyleRounded;
    button.target = self;
    button.action = action;
    return button;
}
- (void)doLogin {
    _status.stringValue = @"正在登录";
    [[SushengStore shared] loginServer:_server.stringValue username:_user.stringValue password:_password.stringValue done:^(NSString *message) {
        self->_status.stringValue = message;
    }];
}
- (void)doSync {
    _status.stringValue = @"正在同步";
    [[SushengStore shared] sync:^(NSString *message) { self->_status.stringValue = message; }];
}
@end

@interface SushengInputController : IMKInputController
@end

@implementation SushengInputController {
    NSString *_raw;
    BOOL _associating;
    NSString *_last;
    NSArray *_rows;
    NSString *_palette;
    NSArray *_paletteItems;
    SushengPanel *_panel;
}
- (instancetype)initWithServer:(IMKServer *)server delegate:(id)delegate client:(id)inputClient {
    self = [super initWithServer:server delegate:delegate client:inputClient];
    if (self) {
        _raw = @"";
        _last = @"";
        _rows = @[];
        _panel = [SushengPanel new];
        __weak SushengInputController *weakSelf = self;
        _panel.onPick = ^(NSInteger index) { [weakSelf choose:index client:nil]; };
    }
    return self;
}
- (BOOL)handleEvent:(NSEvent *)event client:(id)sender {
    if (event.type != NSEventTypeKeyDown) return NO;
    if (IsSecureEventInputEnabled()) return NO;
    NSEventModifierFlags flags = event.modifierFlags & NSEventModifierFlagDeviceIndependentFlagsMask;
    SushengStore *store = [SushengStore shared];
    if (flags & (NSEventModifierFlagCommand | NSEventModifierFlagControl)) return NO;
    if (event.keyCode == kVK_Space && (flags & NSEventModifierFlagShift) && _raw.length == 0 && !_associating && !_palette) {
        store.chinese = !store.chinese;
        [self showHint:store.chinese ? @"中文" : @"英文" client:sender];
        return YES;
    }
    if (flags & NSEventModifierFlagOption) {
        int digit = DigitForKeyCode(event.keyCode);
        if (digit && store.chinese && _rows.count) { [self choose:digit - 1 client:sender]; return YES; }
        return NO;
    }
    if (!store.chinese) return NO;
    switch (event.keyCode) {
        case kVK_F2: [self togglePalette:@"script" client:sender]; return YES;
        case kVK_F3: [self togglePalette:@"tweet" client:sender]; return YES;
        case kVK_F4: [self togglePalette:@"promo" client:sender]; return YES;
        case kVK_F5: [self togglePalette:@"image" client:sender]; return YES;
        case kVK_Delete:
            if (_raw.length) { _raw = [_raw substringToIndex:_raw.length - 1]; [self refresh:sender]; return YES; }
            if (_associating || _palette) { _associating = NO; _palette = nil; [self refresh:sender]; return YES; }
            return NO;
        case kVK_Escape:
            if (!_raw.length && !_associating && !_palette) return NO;
            _raw = @""; _associating = NO; _palette = nil; [self refresh:sender]; return YES;
        case kVK_Return: case kVK_ANSI_KeypadEnter:
            if (!_raw.length && !_associating && !_palette) return NO;
            if (!_rows.count && !_paletteItems.count) [self commitRaw:sender];
            else [self choose:0 client:sender];
            return YES;
        case kVK_Space:
            if ((_rows.count || _paletteItems.count) && (_raw.length || _associating || _palette)) { [self choose:0 client:sender]; return YES; }
            if (_raw.length) { [self commitRaw:sender]; return YES; }
            return NO;
        default: break;
    }
    NSString *chars = event.charactersIgnoringModifiers.lowercaseString ?: @"";
    if ([chars isEqualToString:@"'"] && ![store.mode isEqualToString:@"t9"] && _raw.length) {
        _raw = [_raw stringByAppendingString:@"'"];
        [self refresh:sender];
        return YES;
    }
    if (![store.mode isEqualToString:@"t9"] && chars.length == 1) {
        unichar ch = [chars characterAtIndex:0];
        if (ch >= 'a' && ch <= 'z') {
            if ((flags & NSEventModifierFlagShift) && !_raw.length && !_associating) return NO;
            _palette = nil; _associating = NO; _raw = [_raw stringByAppendingString:chars];
            [self refresh:sender];
            return YES;
        }
    }
    if ([store.mode isEqualToString:@"t9"] && chars.length == 1) {
        unichar ch = [chars characterAtIndex:0];
        if (ch >= '2' && ch <= '9') {
            _palette = nil; _associating = NO; _raw = [_raw stringByAppendingString:chars];
            [self refresh:sender];
            return YES;
        }
    }
    if (chars.length == 1) {
        unichar ch = [chars characterAtIndex:0];
        if (ch >= '1' && ch <= '9' && (_palette || (_rows.count && (![store.mode isEqualToString:@"t9"] || ch == '1')))) {
            [self choose:ch - '1' client:sender];
            return YES;
        }
    }
    return NO;
}
- (void)commitComposition:(id)sender {
    if (_rows.count) [self choose:0 client:sender];
    else if (_raw.length) [self commitRaw:sender];
}
- (id)composedString:(id)sender { return _raw ?: @""; }
- (NSMenu *)menu {
    NSMenu *menu = [[NSMenu alloc] initWithTitle:@"苏晟输入法"];
    [self add:menu title:@"登录与同步" action:@selector(showLogin:)];
    [self add:menu title:[[SushengStore shared].mode isEqualToString:@"t9"] ? @"切换到全拼" : @"切换到九键" action:@selector(toggleMode:)];
    [self add:menu title:[SushengStore shared].chinese ? @"切换到英文" : @"切换到中文" action:@selector(toggleLanguage:)];
    [menu addItem:[NSMenuItem separatorItem]];
    [self add:menu title:@"话术" action:@selector(showScripts:)];
    [self add:menu title:@"推文" action:@selector(showTweets:)];
    [self add:menu title:@"宣发" action:@selector(showPromos:)];
    [self add:menu title:@"图片" action:@selector(showImages:)];
    return menu;
}
- (void)add:(NSMenu *)menu title:(NSString *)title action:(SEL)action {
    NSMenuItem *item = [[NSMenuItem alloc] initWithTitle:title action:action keyEquivalent:@""];
    item.target = self;
    [menu addItem:item];
}
- (void)showLogin:(id)sender { [SushengPreferences show]; }
- (void)toggleMode:(id)sender {
    SushengStore *store = [SushengStore shared];
    store.mode = [store.mode isEqualToString:@"t9"] ? @"qwerty" : @"t9";
    _raw = @"";
    [self refresh:[self commandClient:sender]];
}
- (void)toggleLanguage:(id)sender {
    [SushengStore shared].chinese = ![SushengStore shared].chinese;
    _raw = @"";
    [self refresh:[self commandClient:sender]];
}
- (void)showScripts:(id)sender { [self togglePalette:@"script" client:[self commandClient:sender]]; }
- (void)showTweets:(id)sender { [self togglePalette:@"tweet" client:[self commandClient:sender]]; }
- (void)showPromos:(id)sender { [self togglePalette:@"promo" client:[self commandClient:sender]]; }
- (void)showImages:(id)sender { [self togglePalette:@"image" client:[self commandClient:sender]]; }
- (id)commandClient:(id)sender {
    if ([sender isKindOfClass:[NSDictionary class]]) return sender[(id)kIMKCommandClientName];
    return self.client;
}
- (id<IMKTextInput>)textClient:(id)sender {
    if ([sender conformsToProtocol:@protocol(IMKTextInput)]) return sender;
    return self.client;
}
- (void)refresh:(id)sender {
    SushengStore *store = [SushengStore shared];
    _paletteItems = @[];
    if (_palette) {
        NSMutableArray *found = [NSMutableArray array];
        for (NSDictionary *item in store.items) {
            if ([item[@"type"] isEqualToString:_palette] && ![item[@"deleted"] boolValue]) [found addObject:item];
        }
        if (found.count > 12) _paletteItems = [found subarrayWithRange:NSMakeRange(0, 12)];
        else _paletteItems = found;
        _rows = @[];
    } else if (_raw.length) {
        _rows = [store queryOp:@"candidates" raw:_raw last:@"" mode:store.mode];
    } else if (_associating) {
        _rows = [store queryOp:@"associate" raw:@"" last:_last mode:store.mode];
    } else {
        _rows = @[];
    }
    id<IMKTextInput> client = [self textClient:sender];
    if (_raw.length) {
        [client setMarkedText:_raw selectionRange:NSMakeRange(_raw.length, 0) replacementRange:NotFoundRange()];
    } else {
        [client setMarkedText:@"" selectionRange:NotFoundRange() replacementRange:NotFoundRange()];
    }
    NSMutableArray *lines = [NSMutableArray array];
    NSString *header = _raw;
    if (_palette) {
        header = [self paletteTitle:_palette];
        for (NSDictionary *item in _paletteItems) {
            NSString *title = [item[@"title"] length] ? item[@"title"] : item[@"body"];
            if (title.length > 28) title = [title substringToIndex:28];
            [lines addObject:title ?: @""];
        }
    } else {
        for (NSDictionary *row in _rows) [lines addObject:row[@"text"] ?: @""];
    }
    if (!header.length && !lines.count) { [_panel hide]; return; }
    [_panel updateHeader:header lines:lines near:[self cursor:client]];
}
- (void)showHint:(NSString *)hint client:(id)sender {
    _raw = @""; _rows = @[]; _palette = nil;
    [[self textClient:sender] setMarkedText:@"" selectionRange:NotFoundRange() replacementRange:NotFoundRange()];
    [_panel updateHeader:hint lines:@[] near:[self cursor:[self textClient:sender]]];
}
- (void)choose:(NSInteger)index client:(id)sender {
    id<IMKTextInput> client = [self textClient:sender];
    if (_palette) {
        if (index < 0 || index >= (NSInteger)_paletteItems.count) return;
        NSDictionary *picked = _paletteItems[index];
        BOOL isImage = [picked[@"type"] isEqualToString:@"image"];
        [self insertItem:picked client:client];
        _palette = nil;
        // 图片插入后 insertItem 已显示「已复制」提示；若再 refresh 会因候选为空立即隐藏面板，提示永远不可见
        if (!isImage) [self refresh:client];
        return;
    }
    if (index < 0 || index >= (NSInteger)_rows.count) return;
    NSDictionary *row = _rows[index];
    _raw = @"";
    if ([row[@"kind"] isEqualToString:@"image"] && [row[@"assetId"] length]) {
        [client setMarkedText:@"" selectionRange:NotFoundRange() replacementRange:NotFoundRange()];
        if ([self copyImage:row[@"assetId"]]) {
            [_panel updateHeader:@"已复制图片，请在当前应用粘贴" lines:@[] near:[self cursor:client]];
            [[SushengStore shared] reportUsage:row[@"itemId"] surface:@"image"];
        } else {
            [_panel updateHeader:@"图片还没同步下来" lines:@[] near:[self cursor:client]];
        }
        _associating = NO;
        return;
    }
    if ([row[@"commit"] length]) {
        [client insertText:row[@"commit"] replacementRange:NotFoundRange()];
        [[SushengStore shared] reportUsage:row[@"itemId"] surface:row[@"kind"]];
        _last = row[@"text"] ?: @"";
        _associating = YES;
    }
    [self refresh:client];
}
- (void)insertItem:(NSDictionary *)item client:(id<IMKTextInput>)client {
    if ([item[@"type"] isEqualToString:@"image"]) {
        // copyImage: 失败（图片未下载、assetId 缺失/为空、读取错误）时也要反馈，
        // 否则选图静默无反应，与 choose: 的「图片还没同步下来」提示不一致。
        NSString *assetId = [item[@"assetId"] isKindOfClass:[NSString class]] ? item[@"assetId"] : nil;
        if (assetId.length && [self copyImage:assetId]) {
            [_panel updateHeader:@"已复制图片，请在当前应用粘贴" lines:@[] near:[self cursor:client]];
            [[SushengStore shared] reportUsage:item[@"id"] surface:@"image"];
        } else {
            [_panel updateHeader:@"图片还没同步下来" lines:@[] near:[self cursor:client]];
        }
        return;
    }
    [client insertText:item[@"body"] ?: @"" replacementRange:NotFoundRange()];
    [[SushengStore shared] reportUsage:item[@"id"] surface:item[@"type"]];
    _last = item[@"title"] ?: @"";
    _associating = YES;
}
- (void)commitRaw:(id)sender {
    if (!_raw.length) return;
    [[self textClient:sender] insertText:_raw replacementRange:NotFoundRange()];
    _raw = @"";
    _associating = NO;
    [self refresh:sender];
}
- (void)togglePalette:(NSString *)kind client:(id)sender {
    _palette = [_palette isEqualToString:kind] ? nil : kind;
    if (_palette) { _raw = @""; _associating = NO; }
    [self refresh:sender];
}
- (BOOL)copyImage:(NSString *)assetId {
    NSString *path = [[SushengStore shared] assetPath:assetId];
    if (!path) return NO;
    NSData *data = [NSData dataWithContentsOfFile:path];
    if (!data) return NO;
    NSPasteboard *board = [NSPasteboard generalPasteboard];
    [board clearContents];
    // 用具名常量 NSPasteboardTypePNG（而非字面 @"public.png"），并以 setData: 返回值作为成败依据。
    return [board setData:data forType:NSPasteboardTypePNG];
}
- (NSRect)cursor:(id<IMKTextInput>)client {
    NSRect rect = NSZeroRect;
    // 应查询实际插入点而非固定索引 0，否则光标不在文首时面板会定位到错误位置（如文档开头）。
    NSRange selected = [client selectedRange];
    NSUInteger index = (selected.location == NSNotFound) ? 0 : selected.location;
    [client attributesForCharacterIndex:index lineHeightRectangle:&rect];
    return rect;
}
- (NSString *)paletteTitle:(NSString *)kind {
    if ([kind isEqualToString:@"script"]) return @"话术";
    if ([kind isEqualToString:@"tweet"]) return @"推文";
    if ([kind isEqualToString:@"promo"]) return @"宣发";
    if ([kind isEqualToString:@"image"]) return @"图片";
    return @"";
}
@end
