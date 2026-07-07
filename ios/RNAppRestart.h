#import <Foundation/Foundation.h>
#import <React/RCTBridgeModule.h>

NS_ASSUME_NONNULL_BEGIN

/// Restart the React Native app.
/// iOS cannot relaunch its own process (Apple restriction), so restart here
/// triggers the reload-command listeners — the exact mechanism the dev-menu
/// reload uses — while covering the transition with the app's own
/// LaunchScreen.storyboard so it reads as a real relaunch.
@interface RNAppRestart : NSObject <RCTBridgeModule>
@end

NS_ASSUME_NONNULL_END
