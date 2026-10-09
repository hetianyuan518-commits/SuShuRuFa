#import <Cocoa/Cocoa.h>
#import <InputMethodKit/InputMethodKit.h>
#import "Store.h"
#import "Home.h"

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        NSString *identifier = [[NSBundle mainBundle] bundleIdentifier] ?: @"com.susheng.ime.macos";
        // IMKServer 必须在整个进程生命周期存活（释放它会关闭输入法连接）；
        // ARC 局部变量可能在最后一次使用后被提前释放，故用 static 持有。
        static IMKServer *server = nil;
        server = [[IMKServer alloc] initWithName:@"SushengIM_Connection" bundleIdentifier:identifier];
        // 创建失败（连接名被占、bundle 未正确安装等）会返回 nil，不能当作成功静默略过。
        if (!server) NSLog(@"IMKServer 创建失败，输入法未注册：%@", identifier);
        [[SushengStore shared] loadLocal];
        NSApplication *app = [NSApplication sharedApplication];
        [app setActivationPolicy:NSApplicationActivationPolicyRegular];
        SushengPresentHome();
        [app run];
    }
    return 0;
}
