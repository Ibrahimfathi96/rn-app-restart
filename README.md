<div align="center">

# rn-app-restart

**Restart your React Native app natively.**

<p>
  <img src="https://res.cloudinary.com/doehu91ch/image/upload/v1783385542/uczjqseigongdph3ffd0.jpg" alt="I stand with Palestine" width="140" />
  <img src="https://res.cloudinary.com/doehu91ch/image/upload/v1783385542/ctppkd9anwhrxk7mrszf.jpg" alt="Free Palestine" width="140" />
</p>

![Platforms](https://img.shields.io/badge/platforms-android%20%7C%20ios-lightgrey.svg)
![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Architectures](https://img.shields.io/badge/RN-old%20%2B%20new%20architecture-blueviolet.svg)

</div>

A small, dependency-free native module that restarts your React Native app. It is a drop-in replacement for `react-native-restart` and ships a real codegen TurboModule on the New Architecture.

On Android you get a true cold restart. The launch intent is relaunched with a cleared task and the process exits, so the app comes back with completely fresh native state. That means `I18nManager.forceRTL`, locale changes, and native config always take effect, and you never end up with half-reloaded surfaces.

On iOS, Apple does not allow an app to relaunch its own process. So `restart()` triggers React Native's reload-command listeners, the same mechanism the dev-menu reload uses, and covers the transition with your app's own `LaunchScreen.storyboard`. You see your launch screen instead of a flash of white.

The usual reason to need any of this is an RTL/LTR language switch, which React Native only applies after a restart.

## Requirements

- React Native 0.74 or newer (it needs `BaseReactPackage`). Verified end to end on RN 0.86 with the New Architecture and bridgeless, on both iOS and Android. Both architectures compile on RN 0.79 and 0.86.
- iOS 13+, Android minSdk 24+. If your app sets higher values, the module follows them.
- Both architectures work with nothing to configure. The module follows whatever the host app uses.
- Expo dev client, prebuild, EAS, and bare all work. Expo Go does not, for reasons covered in [Expo](#expo).

## Installation

1. Install the package:

```sh
yarn add rn-app-restart
# or
npm install rn-app-restart
```

2. On iOS, install the pods:

```sh
cd ios && pod install
```

3. Rebuild both apps. This is a native module, so a Metro or JS reload will not pick it up. Run `yarn ios` and `yarn android`, or build from Xcode and Android Studio. On Expo, run `npx expo prebuild` if you use CNG, then `npx expo run:ios` or `npx expo run:android`, or make an EAS build.

Autolinking handles registration on both platforms. There is nothing to add to `MainApplication`, no config plugin, and no manifest changes.

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `[rn-app-restart] Native module not found...` | The installed binary predates the package. Rerun `pod install` and rebuild both apps. If you are in Expo Go, custom native modules cannot run there at all, so use a dev build. In development the call falls back to a JS reload so you can keep working. |
| Jest tests crash on import | Add the one-line mock. See [Testing](#testing-jest). |
| iOS: launch screen overlay stays about 8 seconds before fading | Neither bundle-loaded signal fired on your RN version, so the failsafe dismissed it. You are never trapped, but expected dismissal is about 0.5 seconds. Please [open an issue](https://github.com/Ibrahimfathi96/rn-app-restart/issues) with your RN version and architecture. |
| iOS: no overlay at all during restart | Your app has no `UILaunchStoryboardName` in Info.plist, so there is no launch storyboard to show. The reload still works, it just is not covered. Add a launch screen if you want the covered transition. |
| Android: app closes but does not reopen | The launcher could not resolve your launch intent. Check that `MainActivity` has the `MAIN` and `LAUNCHER` intent filter, which every RN template includes by default. |

## Usage

```ts
import { restart } from 'rn-app-restart';

restart();
```

If you are migrating from `react-native-restart`, the default export is API-compatible. Change the import and you are done:

```ts
import RNRestart from 'rn-app-restart';

RNRestart.restart(); // or RNRestart.Restart()
```

### Applying an RTL language switch

```ts
import { I18nManager } from 'react-native';
import { restart } from 'rn-app-restart';

export const setLanguage = (lang: 'ar' | 'en') => {
  persistLang(lang); // your storage
  const rtl = lang === 'ar';
  if (I18nManager.isRTL !== rtl) {
    I18nManager.allowRTL(rtl);
    I18nManager.forceRTL(rtl);
    restart(); // direction only applies after a restart
  }
};
```

## The restart "loading screen"

There is nothing to configure. Each app's own launch experience becomes the loading screen.

| Platform | What the user sees during restart |
| --- | --- |
| Android | The process relaunches through your normal startup, so you get your launch theme or splash. This works well with `react-native-bootsplash`. |
| iOS | Your `LaunchScreen.storyboard` appears as an overlay window the moment `restart()` is called, then fades out about 0.3 seconds after the fresh JS bundle loads. A hard 8 second timeout means a failed load can never trap the user. If the app has no launch storyboard, the reload happens without an overlay. |

To change how a restart looks in a given project, change that project's launch screen. The module always mirrors it.

## Expo

| Environment | Supported | Notes |
| --- | --- | --- |
| Dev client, `expo prebuild` (CNG) | Yes | Works out of the box. No config plugin is needed because there are no manifest or Info.plist changes. |
| EAS builds | Yes | Same. Install and build. |
| Bare Expo | Yes | Standard autolinking. |
| Expo Go | No | Expo Go cannot run custom native modules at all, for any package. In development, `restart()` falls back to a JS reload with a warning so you can keep working. In production builds without the native module it throws with setup instructions. Use a [development build](https://docs.expo.dev/develop/development-builds/introduction/). |

## Testing (Jest)

The package ships a mock. Add one line to your Jest setup:

```js
jest.mock('rn-app-restart', () => require('rn-app-restart/jest/mock'));
```

## Architecture support

| | Old architecture (Paper) | New Architecture (Fabric, bridgeless) |
| --- | --- | --- |
| JS | Same import. `TurboModuleRegistry` falls back to the classic registry | Codegen'd TurboModule spec |
| Android | Classic `ReactContextBaseJavaModule` | Codegen spec (`NativeRNAppRestartSpec`) |
| iOS | `RCT_EXPORT_MODULE` and `RCT_EXPORT_METHOD` | Same class plus `getTurboModule` under `RCT_NEW_ARCH_ENABLED` |

There are no `newArchEnabled` flags to set. The module follows whatever the host app uses. Runtime-verified on RN 0.86 with the New Architecture and bridgeless on both platforms. The old-architecture paths compile on RN 0.79 and 0.86.

## Why not react-native-restart?

It ships a real codegen TurboModule on the New Architecture rather than going through the legacy interop layer. On Android it does a true cold restart in a fresh process instead of a JS-context reload, which is what makes native flags like RTL direction apply cleanly. On iOS it covers the reload with your launch screen rather than showing the teardown. It has no dependencies and about 200 lines of native code.

## How it works

On Android: `packageManager.getLaunchIntentForPackage()` with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`, then `startActivity`, `finishAffinity()`, and a process exit. The OS relaunches the app fresh.

On iOS: `RCTTriggerReloadCommandListeners()`, the supported public reload entry point, tears down and recreates the React host and all its surfaces. The launch-screen overlay window sits at `UIWindowLevelAlert + 1` and dismisses on whichever bundle-loaded signal the host runtime posts: `RCTJavaScriptDidLoadNotification` on the bridge, or `RCTInstanceDidLoadBundle` on bridgeless. An 8 second timeout is the fallback. Both signals are needed, because the bridge notification is not posted in bridgeless mode, which is the default on modern React Native.

## License

[MIT](LICENSE) © [Ibrahim Fathi](https://ibrahimfathi.dev)
