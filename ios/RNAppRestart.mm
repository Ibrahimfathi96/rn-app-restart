#import "RNAppRestart.h"

#import <React/RCTBridge.h>
#import <React/RCTReloadCommand.h>
#import <React/RCTUtils.h>
#import <UIKit/UIKit.h>

#ifdef RCT_NEW_ARCH_ENABLED
#import <RNAppRestartSpec/RNAppRestartSpec.h>
#endif

// Overlay window that covers the reload with the app's own launch screen so a
// restart reads as a genuine relaunch. Held statically: the module instance is
// torn down by the reload itself, but the overlay must outlive it.
static UIWindow *gRNAppRestartOverlay = nil;
static id gRNAppRestartLoadObserver = nil;

// The overlay must never be able to permanently cover the app: it is dismissed
// when the new JS bundle finishes loading, with a hard timeout fallback.
static const NSTimeInterval kRNAppRestartOverlayMaxSeconds = 8.0;
// Small grace period after JS load so the first frame paints under the overlay.
static const NSTimeInterval kRNAppRestartOverlayGraceSeconds = 0.3;

static void RNAppRestartHideOverlay(void)
{
  RCTExecuteOnMainQueue(^{
    if (gRNAppRestartLoadObserver != nil) {
      [[NSNotificationCenter defaultCenter] removeObserver:gRNAppRestartLoadObserver];
      gRNAppRestartLoadObserver = nil;
    }
    UIWindow *overlay = gRNAppRestartOverlay;
    if (overlay == nil) {
      return;
    }
    gRNAppRestartOverlay = nil;
    [UIView animateWithDuration:0.25
        animations:^{
          overlay.alpha = 0;
        }
        completion:^(__unused BOOL finished) {
          overlay.hidden = YES;
        }];
  });
}

static void RNAppRestartShowOverlay(void)
{
  NSString *storyboardName =
      [[NSBundle mainBundle] objectForInfoDictionaryKey:@"UILaunchStoryboardName"];
  if (storyboardName.length == 0) {
    return; // No launch screen configured — reload without an overlay.
  }

  UIViewController *launchVC = nil;
  @try {
    UIStoryboard *storyboard = [UIStoryboard storyboardWithName:storyboardName bundle:nil];
    launchVC = [storyboard instantiateInitialViewController];
  } @catch (__unused NSException *e) {
    return;
  }
  if (launchVC == nil) {
    return;
  }

  UIWindowScene *scene = RCTKeyWindow().windowScene;
  UIWindow *overlay = scene != nil
      ? [[UIWindow alloc] initWithWindowScene:scene]
      : [[UIWindow alloc] initWithFrame:UIScreen.mainScreen.bounds];
  overlay.rootViewController = launchVC;
  overlay.windowLevel = UIWindowLevelAlert + 1;
  overlay.hidden = NO;
  gRNAppRestartOverlay = overlay;

  // Dismiss when the fresh JS bundle has loaded (posted on both architectures)…
  gRNAppRestartLoadObserver = [[NSNotificationCenter defaultCenter]
      addObserverForName:RCTJavaScriptDidLoadNotification
                  object:nil
                   queue:[NSOperationQueue mainQueue]
              usingBlock:^(__unused NSNotification *note) {
                dispatch_after(
                    dispatch_time(DISPATCH_TIME_NOW,
                                  (int64_t)(kRNAppRestartOverlayGraceSeconds * NSEC_PER_SEC)),
                    dispatch_get_main_queue(), ^{
                      RNAppRestartHideOverlay();
                    });
              }];

  // …and unconditionally after the timeout, so a failed load can't trap the user.
  dispatch_after(
      dispatch_time(DISPATCH_TIME_NOW,
                    (int64_t)(kRNAppRestartOverlayMaxSeconds * NSEC_PER_SEC)),
      dispatch_get_main_queue(), ^{
        RNAppRestartHideOverlay();
      });
}

@implementation RNAppRestart

RCT_EXPORT_MODULE(RNAppRestart)

+ (BOOL)requiresMainQueueSetup
{
  return NO;
}

RCT_EXPORT_METHOD(restart)
{
  RCTExecuteOnMainQueue(^{
    RNAppRestartShowOverlay();
    RCTTriggerReloadCommandListeners(@"rn-app-restart: restart()");
  });
}

#ifdef RCT_NEW_ARCH_ENABLED
- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeRNAppRestartSpecJSI>(params);
}
#endif

@end
