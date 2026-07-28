#import <Foundation/Foundation.h>
#import <React/RCTBridgeModule.h>

#ifdef RCT_NEW_ARCH_ENABLED
#import <RNAppRestartSpec/RNAppRestartSpec.h>
#endif

NS_ASSUME_NONNULL_BEGIN

/// Restart the React Native app.
/// iOS cannot relaunch its own process (Apple restriction), so restart here
/// triggers the reload-command listeners — the exact mechanism the dev-menu
/// reload uses — while covering the transition with the app's own
/// LaunchScreen.storyboard so it reads as a real relaunch.
#ifdef RCT_NEW_ARCH_ENABLED
// Conform to the codegen'd spec so the compiler verifies this class actually
// implements the JS surface. `NativeRNAppRestartSpec` already inherits
// RCTBridgeModule + RCTTurboModule.
@interface RNAppRestart : NSObject <NativeRNAppRestartSpec>
#else
@interface RNAppRestart : NSObject <RCTBridgeModule>
#endif
@end

NS_ASSUME_NONNULL_END
