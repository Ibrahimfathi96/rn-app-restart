<div align="center">

# rn-app-restart

**Restart your React Native app natively — for real.**

<p>
  <img src="https://res.cloudinary.com/doehu91ch/image/upload/v1783385542/uczjqseigongdph3ffd0.jpg" alt="I stand with Palestine" width="140" />
  <img src="https://res.cloudinary.com/doehu91ch/image/upload/v1783385542/ctppkd9anwhrxk7mrszf.jpg" alt="Free Palestine" width="140" />
</p>

![Platforms](https://img.shields.io/badge/platforms-android%20%7C%20ios-lightgrey.svg)
![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Architectures](https://img.shields.io/badge/RN-old%20%2B%20new%20architecture-blueviolet.svg)

</div>

A tiny, dependency-free native module that restarts your React Native app. Built as a drop-in replacement for `react-native-restart`, with first-class **New Architecture (TurboModule)** support and a nicer restart experience:

- **Android** — a **true cold restart**: the launch intent is relaunched with a cleared task and the process exits. Fully fresh native state — `I18nManager.forceRTL`, locale changes, and native config are guaranteed to apply. No half-reloaded surfaces, ever.
- **iOS** — Apple forbids an app relaunching its own process, so `restart()` triggers React Native's reload-command listeners (the *exact* mechanism the dev-menu reload uses) and covers the transition with **your app's own `LaunchScreen.storyboard`**, so the reload reads as a genuine relaunch instead of a flash of white.

The most common use case: applying an **RTL ⇄ LTR language switch**, which React Native can only apply after a restart.

## Requirements

- React Native **≥ 0.74** (needs `BaseReactPackage`; tested on 0.79 old arch and 0.86 New Architecture/bridgeless)
- iOS 13+, Android minSdk 24+ (follows your app's values when higher)
- Old and New Architecture both supported — nothing to configure, the module follows the host app
- Expo: dev client / prebuild / EAS / bare ✅ — Expo Go ❌ (see [Expo](#expo))

## Installation

**1. Install the package**

```sh
# yarn
yarn add github:Ibrahimfathi96/rn-app-restart

# npm
npm install github:Ibrahimfathi96/rn-app-restart
```

**2. iOS — install pods**

```sh
cd ios && pod install
```

**3. Rebuild both apps.** This is a native module — a Metro/JS reload is **not** enough. Run `yarn ios` / `yarn android` (or build from Xcode / Android Studio). Expo: `npx expo prebuild` (if using CNG) then `npx expo run:ios` / `run:android`, or an EAS build.

That's it — autolinking registers the module on both platforms. No manual `MainApplication` edits, no config plugin, no manifest changes.

> Published npm package coming once it's battle-tested — the GitHub install works today.

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `[rn-app-restart] Native module not found…` | The app binary predates the install — rerun `pod install` and **rebuild both apps**. If you're in **Expo Go**: custom native modules can't run there, use a dev build (in dev the call falls back to a JS reload so you can keep working). |
| Jest tests crash on import | Add the one-line mock — see [Testing](#testing-jest). |
| iOS: launch screen overlay stays ~8 s before fading | The reload-complete signal didn't fire on your RN version. The failsafe dismissed it (you're never trapped) — please [open an issue](https://github.com/Ibrahimfathi96/rn-app-restart/issues) with your RN version and architecture. |
| iOS: no overlay at all during restart | Your app has no `UILaunchStoryboardName` in Info.plist (no launch storyboard) — the reload still works, just uncovered. Add a launch screen if you want the covered transition. |
| Android: app closes but doesn't reopen | The launcher couldn't resolve your launch intent — check your `MainActivity` has the `MAIN`/`LAUNCHER` intent filter (standard in every RN template). |

## Usage

```ts
import { restart } from 'rn-app-restart';

restart();
```

Migrating from `react-native-restart`? The default export is API-compatible — change the import and you're done:

```ts
import RNRestart from 'rn-app-restart';

RNRestart.restart(); // or RNRestart.Restart()
```

### The classic use case — applying an RTL language switch

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

You don't configure one — **each app's own launch experience is the loading screen**, automatically per project:

| Platform | What the user sees during restart |
| --- | --- |
| **Android** | The process relaunches through your normal startup: your launch theme / splash (works great with `react-native-bootsplash`). |
| **iOS** | Your `LaunchScreen.storyboard` is shown as an overlay window the moment `restart()` is called, and fades out ~0.3s after the fresh JS bundle loads. Hard 8s timeout guarantees it can never trap the user if a load fails. If the app has no launch storyboard, the reload happens without an overlay. |

Want a different restart look for a specific project? Change that project's launch screen — the module always mirrors it.

## Expo

| Environment | Supported | Notes |
| --- | --- | --- |
| Dev client / `expo prebuild` (CNG) | ✅ | Works out of the box — no config plugin needed (no manifest or Info.plist changes). |
| EAS builds | ✅ | Same — just install and build. |
| Bare Expo | ✅ | Standard autolinking. |
| **Expo Go** | ❌ | Expo Go can never run custom native modules (any package, not just this one). In dev, `restart()` falls back to a JS reload with a warning so you can keep working; in production builds without the native module it throws with setup instructions. Use a [development build](https://docs.expo.dev/develop/development-builds/introduction/). |

## Testing (Jest)

The package ships a mock — add one line to your Jest setup:

```js
jest.mock('rn-app-restart', () => require('rn-app-restart/jest/mock'));
```

## Architecture support

| | Old architecture (Paper) | New Architecture (Fabric / bridgeless) |
| --- | --- | --- |
| **JS** | Same import — `TurboModuleRegistry` falls back to the classic registry | Codegen'd TurboModule spec |
| **Android** | Classic `ReactContextBaseJavaModule` | Codegen spec (`NativeRNAppRestartSpec`) |
| **iOS** | `RCT_EXPORT_MODULE` / `RCT_EXPORT_METHOD` | Same class + `getTurboModule` under `RCT_NEW_ARCH_ENABLED` |

No `newArchEnabled` flags to set — the module follows whatever the host app uses. Tested on RN 0.79 (old arch) and RN 0.86 (New Architecture, bridgeless).

## Why not react-native-restart?

- This module ships a **real codegen TurboModule** on the New Architecture instead of relying on the legacy interop layer.
- Android does a **true cold restart** (fresh process), not a JS-context reload — the only restart that guarantees native flags like RTL direction apply cleanly.
- iOS covers the reload with your launch screen instead of flashing the teardown.
- Zero dependencies, ~200 lines of native code you can read in one sitting.

## How it works

- **Android**: `packageManager.getLaunchIntentForPackage()` + `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` → `startActivity` → `finishAffinity()` → process exit. The OS relaunches the app fresh.
- **iOS**: `RCTTriggerReloadCommandListeners()` — the supported, public reload entry point that tears down and recreates the React host and all surfaces. The launch-screen overlay window sits at `UIWindowLevelAlert + 1` and dismisses on `RCTJavaScriptDidLoadNotification` (posted on both architectures) with a timeout fallback.

## License

[MIT](LICENSE) © [Ibrahim Fathi](https://ibrahimfathi.dev)
