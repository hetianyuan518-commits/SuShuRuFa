#import "Home.h"

static NSColor *Color(int hex) {
    return [NSColor colorWithCalibratedRed:((hex >> 16) & 255) / 255.0 green:((hex >> 8) & 255) / 255.0 blue:(hex & 255) / 255.0 alpha:1];
}

static int SkinHex(int id, int slot) {
    int table[6][3] = {
        {0xF3F4F6, 0x222222, 0xFF6A00},
        {0xF3EADB, 0x1B140F, 0xC4372C},
        {0xE7F6E4, 0x1C3A24, 0x2E9B4A},
        {0x1C1E27, 0xF2F4F8, 0xFF8A3D},
        {0xE7F2FA, 0x16324A, 0x2F7FE0},
        {0xFDEFE3, 0x4A2E22, 0xE36A2E}
    };
    if (id < 0 || id > 5) id = 0;
    return table[id][slot];
}

static int CurrentSkin(void) {
    return (int)[[NSUserDefaults standardUserDefaults] integerForKey:@"susheng.skin"];
}

NSColor *SushengPanelColor(void) { return Color(SkinHex(CurrentSkin(), 0)); }
NSColor *SushengTextColor(void) { return Color(SkinHex(CurrentSkin(), 1)); }
NSColor *SushengAccentColor(void) { return Color(SkinHex(CurrentSkin(), 2)); }

static NSString * const names[] = {@"浅灰日常", @"宣纸朱红", @"新叶", @"夜航", @"海盐", @"暖杏"};

@interface SushengHome : NSObject
- (void)show;
- (void)pick:(id)sender;
@end

@implementation SushengHome {
    NSWindow *_window;
}
- (void)show {
    // 窗口已存在时直接重新前置，避免重复创建导致多窗口漂浮与旧窗口泄漏
    if (_window) {
        [_window makeKeyAndOrderFront:nil];
        [NSApp activateIgnoringOtherApps:YES];
        return;
    }
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    if (![defaults boolForKey:@"susheng.privacy"]) {
        NSAlert *alert = [NSAlert new];
        alert.messageText = @"苏晟输入法";
        alert.informativeText = @"请阅读并同意。苏晟输入法不上传你敲下的文字。登录只同步公司发布的话术、词条和图片。皮肤选择保存在本机。";
        [alert addButtonWithTitle:@"同意"];
        [alert addButtonWithTitle:@"拒绝"];
        if ([alert runModal] != NSAlertFirstButtonReturn) {
            [NSApp terminate:nil];
            return;
        }
        [defaults setBool:YES forKey:@"susheng.privacy"];
    }
    _window = [[NSWindow alloc] initWithContentRect:NSMakeRect(0, 0, 420, 520) styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable backing:NSBackingStoreBuffered defer:NO];
    // ARC 下关闭窗口时 AppKit 默认会额外 release，与 strong ivar 叠加导致悬空指针
    _window.releasedWhenClosed = NO;
    _window.title = @"苏晟输入法";
    NSView *content = [NSView new];
    _window.contentView = content;
    NSImage *logo = [[NSImage alloc] initWithContentsOfFile:[[NSBundle mainBundle] pathForResource:@"susheng_logo" ofType:@"png"]];
    if (logo) {
        NSImageView *mark = [[NSImageView alloc] initWithFrame:NSMakeRect(24, 440, 56, 56)];
        mark.image = logo;
        [content addSubview:mark];
    }
    NSTextField *title = [NSTextField labelWithString:@"装扮与说明"];
    title.frame = NSMakeRect(92, 456, 280, 28);
    title.font = [NSFont boldSystemFontOfSize:18];
    [content addSubview:title];
    NSTextField *note = [NSTextField wrappingLabelWithString:@"点按皮肤后，候选条会换成对应颜色。词库已包含热词和人工智能相关词句。"];
    note.frame = NSMakeRect(24, 400, 372, 40);
    [content addSubview:note];
    for (int i = 0; i < 6; i++) {
        NSButton *button = [[NSButton alloc] initWithFrame:NSMakeRect(24, 340 - i * 44, 372, 36)];
        NSString *name = names[i];
        if (i == CurrentSkin()) name = [name stringByAppendingString:@" · 使用中"];
        button.title = name;
        button.tag = i;
        button.target = self;
        button.action = @selector(pick:);
        [content addSubview:button];
    }
    NSTextField *legal = [NSTextField wrappingLabelWithString:@"用户服务协议、隐私政策：本输入法面向苏晟集团员工和合作达人。不上传键入原文，不向儿童提供账号。"];
    legal.frame = NSMakeRect(24, 24, 372, 48);
    [content addSubview:legal];
    [_window center];
    [_window makeKeyAndOrderFront:nil];
    [NSApp activateIgnoringOtherApps:YES];
}
- (void)pick:(id)sender {
    NSInteger idValue = [sender tag];
    // sender.tag 直接索引 6 元素 names 数组；越界会读到垃圾值并被持久化为皮肤索引，先限定 0–5。
    if (idValue < 0 || idValue > 5) return;
    [[NSUserDefaults standardUserDefaults] setInteger:idValue forKey:@"susheng.skin"];
    // 刷新所有皮肤按钮，确保只有当前选中项带「· 使用中」后缀（只改被点按钮会遗留旧选中项的标记）。
    for (NSView *sub in [(NSView *)sender superview].subviews) {
        if (![sub isKindOfClass:[NSButton class]]) continue;
        NSInteger tag = sub.tag;
        if (tag < 0 || tag > 5) continue;
        NSString *n = names[tag];
        ((NSButton *)sub).title = (tag == idValue) ? [n stringByAppendingString:@" · 使用中"] : n;
    }
}
@end

void SushengPresentHome(void) {
    static SushengHome *home;
    if (!home) home = [SushengHome new];
    [home show];
}
