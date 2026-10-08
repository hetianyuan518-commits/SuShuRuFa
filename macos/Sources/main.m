#import <Cocoa/Cocoa.h>
#import <InputMethodKit/InputMethodKit.h>
#import "Store.h"
#import "Home.h"

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        NSString *identifier = [[NSBundle mainBundle] bundleIdentifier] ?: @"com.susheng.ime.macos";
        IMKServer *server = [[IMKServer alloc] initWithName:@"SushengIM_Connection" bundleIdentifier:identifier];
        (void)server;
        [[SushengStore shared] loadLocal];
        NSApplication *app = [NSApplication sharedApplication];
        [app setActivationPolicy:NSApplicationActivationPolicyRegular];
        SushengPresentHome();
        [app run];
    }
    return 0;
}
