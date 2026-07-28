# DEVNOTES: everything in mind about rn-app-restart

> Internal working doc, not shipped to npm (the `files` allowlist in package.json already excludes it). The README is the public face. This is the full context: why this exists, every design decision, current status, test plan, migration plan, publish plan, and known risks.

---

## 1. Why this exists

`react-native-restart` is being removed from every project in the workspace. The maintainers' politics are the reason, so this is a deliberate replacement rather than a fork. The replacement had to be a real restart, not a JS workaround. No remounting the root component with a changed key, no `DevSettings.reload` hacks.

The trigger bug was in Podium (RN 0.86, New Architecture and bridgeless). Switching language from AR to EN calls `I18nManager.forceRTL` and then restarts, and that intermittently produced a broken transient state where every `<Text>` in the app rendered as `-` or `--` placeholders. Even plain literal strings like the "EN" button did it. A Metro `r` (dev-menu reload) always fixed it.

The diagnosis: `react-native-restart` ships a legacy non-codegen RCTBridgeModule that runs through the New Architecture interop layer, and its reload raced the text and font subsystem on Fabric. The dev-menu reload path (`RCTTriggerReloadCommandListeners`) never glitched once, and that is exactly the path this package uses on iOS.

## 2. What it is

A tiny, dependency-free native module that supports both architectures.

On Android it is a true cold restart: `getLaunchIntentForPackage` with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`, then `startActivity`, `finishAffinity()`, and `Runtime.getRuntime().exit(0)`. The process dies and the OS relaunches the app. This is the only restart on Android that guarantees fully fresh native state, so RTL flags, locale, and half-initialized surfaces are all impossible to leak. It is the ProcessPhoenix pattern.

On iOS it is a reload plus a launch-screen overlay. Apple forbids relaunching your own process, full stop. So the closest thing to a real restart is `RCTTriggerReloadCommandListeners()`, the public supported reload entry point, the same one the dev menu uses and the same one every restart library uses underneath. On top of that, this module instantiates the app's own `LaunchScreen.storyboard` into an overlay `UIWindow` at `UIWindowLevelAlert + 1` before triggering the reload, then fades it out about 0.3 s after the bundle-loaded signal fires, with a hard 8 s failsafe dismissal so a failed bundle load can never trap the user behind the overlay. The result is that the reload reads as a genuine app relaunch, and the restart loading screen is automatically per-project (each app's own launch screen) with zero configuration.

### API

```ts
import { restart } from 'rn-app-restart';   // named
import RNRestart from 'rn-app-restart';     // default, react-native-restart compatible
RNRestart.restart(); RNRestart.Restart();   // both aliases work, so migration is one import line
```

## 3. Design decisions and rationale

| Decision | Rationale |
| --- | --- |
| Codegen TurboModule on New Arch, not interop | The interop layer was the suspected culprit in the Podium glitch. It is also future-proof, since interop is a compatibility shim rather than the destination. |
| Old-arch support via source-set split | The workspace still has RN 0.79 projects (riya, jeyad, niyak). `android/src/newarch` extends the generated `NativeRNAppRestartSpec`, `android/src/oldarch` extends `ReactContextBaseJavaModule` with the same abstract surface, and one shared `RNAppRestartModule.kt` implements both. This is the create-react-native-library "backward compatible" pattern, which is well tested in the wild. |
| iOS single class with `#ifdef RCT_NEW_ARCH_ENABLED` | Same CRNL pattern. `RCT_EXPORT_MODULE` and `RCT_EXPORT_METHOD` serve old arch, `getTurboModule:` under the ifdef serves new arch. `install_modules_dependencies(s)` in the podspec wires React deps and codegen for whichever arch the host uses. Under the ifdef the class also conforms to `<NativeRNAppRestartSpec>`, so the compiler verifies the implementation matches the JS spec. |
| Module name `RNAppRestart` | Distinct from Podium's in-app `NativeAppRestart` so both can coexist during the transition without a registry clash. |
| Android cold restart rather than `reactHost.reload` | A JS-context reload on Android can leave native state behind. Process death cannot. For the RTL use case, correctness beats the extra second or so of cold start. iOS cannot have this, so it gets the overlay to compensate visually. |
| Android: make RN's RTL pref durable before `exit(0)` using a synchronous `commit()` of the I18nUtil SharedPreferences | RN's `I18nManager.forceRTL()` writes through `SharedPreferences.apply()`, which is async, so the value is still only in memory when the cold restart's `exit(0)` fires. The relaunched process then reads the stale direction and a language switch needs a second restart to apply. This was measured on device via pid trace: 3 pids meant 2 restarts. The fix is to read the in-memory value back (`apply()` sets it immediately) and rewrite it with `commit()`, a synchronous blocking disk write, before `exit(0)`. That makes it durable, so one restart is enough. It uses RN's exact pref name and keys, verified against RN 0.86 `I18nUtil.kt`: file `com.facebook.react.modules.i18nmanager.I18nUtil`, keys `RCTI18nUtil_allowRTL` and `RCTI18nUtil_forceRTL`. Note that the earlier attempt, a generic `QueuedWork.waitToFinish()` via reflection, was measured as blocked on a modern device. It threw, got caught, flushed nothing, and still produced two restarts, so it was replaced by this targeted public-API commit. Coupling to RN's i18n pref is acceptable because an RTL switch is the single most common reason to restart an RN app. This keeps both the true cold restart and the one-restart behaviour, instead of downgrading to a JS-context reload the way react-native-restart does. The JS self-heal (language-keyed `useBootstrap`) stays as the safety net. |
| Overlay is the app's LaunchScreen storyboard | Per-project customization for free, since changing the project's launch screen changes the restart look. No API surface to maintain, no assets shipped, and it is always brand-consistent. If `UILaunchStoryboardName` is missing, the reload happens with no overlay, which is a graceful no-op. |
| Failsafe timeout on the overlay | The overlay is held in a static (`gRNAppRestartOverlay`) because the module instance itself is destroyed by the reload it triggers. Anything held statically and shown over the app must have an unconditional dismissal path, hence the 8 s `dispatch_after` plus an idempotent hide. |
| Observe both bundle-loaded signals | `RCTJavaScriptDidLoadNotification` is bridge-era and is never posted in bridgeless mode. Bridgeless posts `"RCTInstanceDidLoadBundle"` instead. Watching only one means the overlay always waits out the full failsafe on whichever runtime does not post it. See §5 for how this was found. |
| No options or params on `restart()` | YAGNI. A `reason?: string` param or an overlay opt-out can be added later without breaking anything, since a spec change just means rerunning codegen. |
| Ship TS source (`main: src/index.ts`) with no build step | Metro transpiles library TS in RN apps, the same way gesture-handler and others do. No bob or rollup until something actually demands it. |
| Lazy native resolution using `TurboModuleRegistry.get` rather than `getEnforcing` | A missing native module, whether from Expo Go or a skipped pod install or rebuild, then fails at call time with a descriptive error instead of crashing the app at import time. In `__DEV__` it falls back to `DevSettings.reload()` with a warning, which keeps Expo Go and not-yet-rebuilt dev sessions usable. In production it throws, because a JS reload is not a real restart and prod should fail loudly rather than pretend. |
| Shipped Jest mock (`jest/mock.js`) | Every consumer needs one, since native resolution has no host under Jest. One `jest.mock('rn-app-restart', () => require('rn-app-restart/jest/mock'))` line replaces per-project hand-rolled mocks. |
| No Expo config plugin | The module changes no manifests or Info.plist, so dev client, prebuild, EAS, and bare Expo all work with plain autolinking. Expo Go can never run custom native modules, which is a platform constraint, and that case is covered by the dev fallback above and documented in the README. |

## 4. File map

```
package.json          name/version/files/codegenConfig (RNAppRestartSpec, jsSrcsDir: src,
                      android javaPackageName com.rnapprestart) + RN peer deps
RNAppRestart.podspec  s.name RNAppRestart; source_files ios/**; install_modules_dependencies
src/NativeRNAppRestart.ts  codegen spec; TurboModuleRegistry.get('RNAppRestart'), and on old
                      arch the registry falls back to NativeModules, so one entry point serves both
src/index.ts          restart() + default {restart, Restart}
ios/RNAppRestart.h/.mm     module + overlay logic (static fns, C-style, ~120 lines)
android/build.gradle       arch detection via rootProject.newArchEnabled; source-set switch;
                      buildConfigField IS_NEW_ARCHITECTURE_ENABLED; react-android dep (version via RNGP)
android/src/main/...       RNAppRestartModule.kt (cold restart) + RNAppRestartPackage.kt
                      (BaseReactPackage, isTurboModule = BuildConfig flag), picked up by autolinking
android/src/newarch/...    abstract RNAppRestartSpec : NativeRNAppRestartSpec (generated)
android/src/oldarch/...    abstract RNAppRestartSpec : ReactContextBaseJavaModule
```

## 5. Current status

| Item | Status |
| --- | --- |
| Package source complete | Done |
| Podium in-app twin, iOS | Verified working on device and sim (2026-07-07). The full app behaves as if the package exists and the language switch restarts correctly |
| Podium in-app twin, Android | Not tested. Superseded by the harness runtime verification below |
| Compiled: Android, new arch | Done 2026-07-28 in riya (RN 0.79, `newArchEnabled=true`). `:rn-app-restart:assembleDebug` succeeded, codegen ran and generated and compiled `NativeRNAppRestartSpec.java`, `IS_NEW_ARCHITECTURE_ENABLED=true` |
| Compiled: Android, old arch | Done 2026-07-28 on the same host via `-PnewArchEnabled=false`. Build succeeded, `IS_NEW_ARCHITECTURE_ENABLED=false`, and the `oldarch` `RNAppRestartSpec.class` compiled. No host mutation was needed, because the gradle property override drives the source-set split directly. This is the cheap repeatable old-arch check |
| Compiled: iOS, new arch | Done 2026-07-28 on the riya Pods target with `-DRCT_NEW_ARCH_ENABLED=1`. Build succeeded and produced `RNAppRestart.o` with 16 TurboModule symbols, compiled with `<NativeRNAppRestartSpec>` conformance so the compiler verified the impl matches the JS spec |
| Compiled: iOS, old arch | Done 2026-07-28 by recompiling the same TU with `-URCT_NEW_ARCH_ENABLED`. The object drops from 494 KB to 93 KB, has zero TurboModule symbols, and keeps the legacy `+[RNAppRestart __rct_export__]` export |
| TS strict typecheck | Done 2026-07-28 against RN 0.86.2 types, `tsc --strict` clean |
| Runtime: Android, RN 0.86 new arch | Done 2026-07-28 on a throwaway harness on the Pixel 9 Pro AVD. True cold restart (pid 6359 to 6516 to 6639), RTL applies in one restart in both directions, and `RCTI18nUtil_forceRTL` is durable on disk immediately after. The `-`/`--` text glitch did not reproduce |
| Runtime: iOS, RN 0.86 bridgeless | Done 2026-07-28 after fixing the overlay bug below. The overlay appears instantly, dismisses in about 0.5 s, and the JS context is fresh every time |
| Bug found and fixed: iOS overlay hung 8 s on every restart | `RCTJavaScriptDidLoadNotification` is a bridge-era notification and is never posted in bridgeless mode, so the overlay was dismissed by the 8 s failsafe on every restart on modern RN. Measured across 3 consecutive runs at 8.04, 8.02, and 8.01 s, with the JS-load observer never firing once. The §6 prediction was exactly right. The fix is to also observe `"RCTInstanceDidLoadBundle"`, posted by `RCTInstance.mm` as a bare string with no exported constant, which is the same pair RN's own `RCTDevLoadingView` and `RCTDevSettings` watch. After the fix the signal fires at 0.197, 0.189, and 0.191 s, giving roughly 0.5 s dismissal including the grace period. That is a 16x improvement and it means the headline iOS feature actually does what the README says. Worth remembering how it was caught: only `NSLog` plus `log show` was precise enough. Screenshot timing was too coarse to distinguish the 8 s failsafe from a slow bundle load, and it gave the wrong answer twice before the logs settled it |
| GitHub repo | Created and pushed. Username `Ibrahimfathi96` confirmed correct |
| npm name `rn-app-restart` | Claimed. Registry 404 on 2026-07-28, published same day |
| npm publish | Published. 0.1.0 on 2026-07-28, then 1.0.0 |

The Podium in-app twin (same logic, different names) lives in the Podium repo: `specs/NativeAppRestart.ts`, `android/.../AppRestartModule.kt` and `AppRestartPackage.kt` plus MainApplication registration, `ios/Podium/RCTNativeAppRestart.h/.mm` plus pbxproj entries, and `codegenConfig` with `ios.modulesProvider`. It stays until Podium migrates (see §7).

Differences between the twin and the package worth knowing:

- The twin registers on iOS via codegen's `RCTModuleProviders` map (`ios.modulesProvider` in the app codegenConfig). The package uses `RCT_EXPORT_MODULE` instead, which is library-style discovery. Both are legitimate, and libraries cannot use the app's modulesProvider.
- The twin's iOS restart has no launch-screen overlay, since the overlay is a package improvement. iOS was verified fine without it, and with it it should only look better.
- The twin is new-arch only, because Podium is bridgeless. The package adds the old-arch paths.

## 6. Test plan

Develop against a real host project:

```sh
# in a host project
yarn add file:../rn-app-restart     # yarn classic projects (riya, jeyad, niyak)
yarn add portal:../rn-app-restart   # yarn 3+ projects (Podium, where portal keeps it live-editable)
cd ios && pod install
```

Each row below means install, build, tap a restart trigger, verify.

> Corrected 2026-07-28: the old "riya/niyak = old arch" assumption was wrong. Every project in the workspace has `newArchEnabled=true` (riya, niyak, Jeyad at 0.79; HM-tasks2, Liana at 0.83; Sahwa and the template at 0.85; Podium at 0.86), because RN's template has defaulted to new arch since 0.76. There is no naturally old-arch host anywhere. Old arch is covered by the property override below instead, which needs no host changes at all.

| Project | RN | Arch | Verifies |
| --- | --- | --- | --- |
| Throwaway harness | 0.86 | New (bridgeless) | Full runtime path on both platforms. Done 2026-07-28 |
| Podium | 0.86 | New (bridgeless) | Real-app runtime, plus coexistence with the in-app twin (different names, must not clash). Still to do |
| riya | 0.79 | New | Compile floor, the oldest supported RN. Done 2026-07-28 for Android and the iOS pod target |
| riya | 0.79 | Old (forced) | `cd android && ./gradlew :rn-app-restart:assembleDebug -PnewArchEnabled=false` exercises the `oldarch` source set, `ReactContextBaseJavaModule`, and BaseReactPackage. Done 2026-07-28. The iOS equivalent is the same TU recompiled with `-URCT_NEW_ARCH_ENABLED` |
| HM-tasks2 or Liana | 0.83 | New | Mid-version sanity. Still to do |

Per-platform checks:

- Android: the app fully relaunches (process id changes), the RTL flip applies, the back stack is clean with no old activity behind, it works from a background-to-foreground state, and it works in a release build and not just debug.
- iOS: the overlay appears instantly with no white flash and fades about 0.3 s after load. Watch for the overlay lingering the full 8 s, which means neither bundle-loaded signal fired and the failsafe did the work. The failsafe keeps that a UX bug rather than a trap. Also verify the AR to EN glitch (`-` placeholders) never reproduces.
- Both: react-query and MMKV persisted state survive the restart, and Metro shows no duplicate-module warnings.

The lesson from the overlay bug: to time the overlay, instrument it and read `xcrun simctl spawn booted log show`. Screenshots are not precise enough, because each `simctl io screenshot` takes most of a second and that is the same order as what you are trying to measure.

## 7. Migration plan (per project)

1. `yarn remove react-native-restart`
2. `yarn add rn-app-restart`
3. Change the import. The API is compatible, since both `RNRestart.restart()` and `Restart()` exist
4. `pod install`, then rebuild both platforms
5. Replace any jest mock of `react-native-restart` with a mock of `rn-app-restart`, using the shipped one: `jest.mock('rn-app-restart', () => require('rn-app-restart/jest/mock'))`

Podium has one extra step, which is deleting the in-app twin: `specs/NativeAppRestart.ts`, the two Kotlin files and the `add(AppRestartPackage())` line, `ios/Podium/RCTNativeAppRestart.h/.mm` and their four pbxproj entries, the `codegenConfig` block in package.json (the package brings its own), the `"specs"` tsconfig include, the jest setup mock path, and then rewrite `src/shared/utils/restart.ts` to import from `rn-app-restart`.

## 8. Publish checklist

- [x] Confirm GitHub username, then fix `repository.url`, `homepage`, README install commands, and podspec `s.source`
- [x] `npm view rn-app-restart` to confirm the name is unclaimed (fallbacks were `react-native-app-restart` and `@ibrahimfathi/rn-app-restart`)
- [x] Create the repo, push, and tag (the podspec expects the `v#{version}` tag format)
- [ ] Verify the Stand With Palestine banner renders on the GitHub README
- [x] `npm publish --access public`, then verify `npm install rn-app-restart` in a scratch app
- [ ] Later: CI compiling against an RN template matrix, and an example app

Note on 2FA: the npm account uses a passkey (Touch ID), which cannot produce a TOTP code, so `--otp=` is not usable. Publishing needs either a granular access token with the 2FA bypass enabled, or the account's two-factor mode set to authorization only rather than authorization and writes.

Note on `npm pkg fix`: do not run it. It rewrites `repository.url` to `git+https://...`, and the podspec feeds that same string to `s.source[:git]`, where the `git+` prefix is not something git can clone. If it ever needs fixing, change both halves together and strip the prefix in the podspec with `.sub(/^git\+/, "")`.

## 9. Known risks and open questions

| Risk | Mitigation or plan |
| --- | --- |
| Bundle-loaded signal names change in a future RN version | Both known signals are observed now, and the failsafe timeout bounds the damage either way. `"RCTInstanceDidLoadBundle"` is a bare string with no exported constant, so it is the more fragile of the two. If it disappears, the symptom is an 8 s overlay and the fix is in `RNAppRestartShowOverlay` |
| `implementation "com.facebook.react:react-android"` unversioned needs the RN Gradle plugin's dependency substitution, which holds for RN 0.71+ app templates | All workspace projects qualify at 0.79+. If an exotic host fails, fall back to `com.facebook.react:react-native:+` |
| Kotlin Gradle plugin must be on the host's classpath | True for all bare RN templates 0.71+, since the RN template root build.gradle ships Kotlin |
| `BaseReactPackage` requires RN 0.74 or newer | The oldest workspace project is 0.79, so this is fine. For a hypothetical host below 0.74, swap to `TurboReactPackage`, which has the same shape |
| `RCTKeyWindow()` may return nil very early in the app lifecycle, so the overlay falls back to `initWithFrame` | Already handled. Worst case the overlay misses a windowScene on multi-scene apps, and there are none in the workspace |
| Overlay interaction with `react-native-bootsplash` on iOS | Bootsplash controls the initial launch, and the overlay only exists during `restart()` reloads, so they should not overlap. Verify once in Podium |
| The `-`/`--` text glitch root cause is suspected rather than proven | The acceptance test is that it stops reproducing across many AR to EN switches on the new module. It did not reproduce in harness runtime testing, and the in-app twin looked good on iOS. Keep watching |

## 10. Maintainer guide

### Local development loop

There is no example app yet, so develop against a real host project:

```sh
# yarn classic hosts (riya, jeyad, niyak):
yarn add file:../rn-app-restart      # re-run after native changes, since file: copies

# yarn 3+ hosts (Podium):
yarn add portal:../rn-app-restart    # live-linked, so edits show without reinstalling
```

- A JS change in `src/` only needs a Metro reload with `portal:`, or a reinstall with `file:`.
- A native change in `ios/` or `android/` needs a host app rebuild. iOS also needs `pod install` if files were added or renamed.
- A spec change in `src/NativeRNAppRestart.ts` is the codegen contract. That needs `pod install` and a full rebuild on iOS, a gradle rebuild on Android, and the native implementations must be updated in the same commit. Codegen turns mismatches into compile errors, which is the whole point.

### Fast verification commands

These need no host-project changes and are the cheapest way to check all four compile combinations:

```sh
# Android, both architectures
cd <host>/android
./gradlew :rn-app-restart:assembleDebug                        # new arch
./gradlew :rn-app-restart:assembleDebug -PnewArchEnabled=false # old arch

# iOS new arch
xcodebuild -project Pods/Pods.xcodeproj -target RNAppRestart \
  -sdk iphonesimulator -configuration Debug -arch arm64 build

# iOS old arch: take the CompileC clang line from that build's log,
# append -URCT_NEW_ARCH_ENABLED, and redirect -o to a temp path.
# Sanity check: nm on the result should show zero turbomodule symbols.
```

### Debugging map

| Symptom | Look in |
| --- | --- |
| "Native module not found" in a real build | Autolinking. Is the package in `node_modules`? On iOS, does `Pods/` contain `RNAppRestart` after pod install? On Android, does `PackageList` include `RNAppRestartPackage`? Search `android/app/build/generated/.../PackageList.java` in the host app |
| iOS build error mentioning `NativeRNAppRestartSpecJSI` or a missing `RNAppRestartSpec/RNAppRestartSpec.h` | Codegen did not run, or the config changed. Check `codegenConfig` in package.json, where the name `RNAppRestartSpec` must match the `#import` in `RNAppRestart.mm`, then do a clean pod install with `rm -rf ios/Pods ios/build && pod install`. Old-arch hosts never hit this because of the `#ifdef RCT_NEW_ARCH_ENABLED` |
| Android build error `Unresolved reference: NativeRNAppRestartSpec` | New-arch host with missing codegen output, meaning the `com.facebook.react` plugin block in `android/build.gradle` did not apply. Check that `isNewArchitectureEnabled()` reads the host's `newArchEnabled` gradle property |
| Android build error `Unresolved reference: RNAppRestartSpec` | The source-set switch failed, so neither `src/newarch` nor `src/oldarch` is on the compile path. Check the `sourceSets` block |
| Restart does nothing on Android | Either `getLaunchIntentForPackage` returned null because there is no LAUNCHER activity, which is never true in RN templates, or `restart()` is not reaching native at all. Verify via `adb logcat` that the process exits, and confirm the pid changes |
| iOS overlay never appears | The host has no `UILaunchStoryboardName`, which by design means no overlay, or the storyboard failed to instantiate and the `@try` swallowed it, which also means no overlay while the reload still works |
| iOS overlay lingers exactly 8 s | Neither bundle-loaded signal fired. Fix in `RNAppRestartShowOverlay` by adding whatever signal that RN version posts. Keep the 8 s failsafe whatever the signal becomes. Instrument with `NSLog` and read `xcrun simctl spawn booted log show` rather than trusting screenshots |
| App crashes at JS import time in some environment | Someone reintroduced `getEnforcing` in the spec. Resolution must stay nullable with `TurboModuleRegistry.get`, with the call-time error in `index.ts` |

### Invariants, do not break these

1. Android restart means process death. Never optimize it into a JS-context reload, because full freshness is the product.
2. The iOS overlay must always have an unconditional dismissal path, currently the 8 s failsafe. It outlives the module instance because it is held in statics, so nothing else can clean it up.
3. Production never silently falls back to a JS reload. A `__DEV__`-only fallback is fine, prod throws.
4. `RNAppRestart` is public API, because JS resolves by that exact string on both architectures. Renaming it is a major version.
5. The default export stays react-native-restart compatible with both `restart` and `Restart`, because migration by changing one import line is a core promise.

### Release process

1. Update `version` in package.json
2. `git tag v<version>` (the podspec `s.source` expects the `v` prefix) and push with tags
3. `npm publish --access public`, keeping the 2FA notes in §8 in mind

## 11. Recommendations and honest flags

What I would do, in order:

| # | Recommendation | Value | Status |
| --- | --- | --- | --- |
| 1 | Lazy native resolution, dev JS-reload fallback, prod throw | Crash-proof DX in Expo Go and after missed rebuilds | Done |
| 2 | Shipped Jest mock (`jest/mock.js`) | One-line test setup per consumer | Done |
| 3 | README with requirements, full install steps, troubleshooting table | Users self-serve instead of filing issues | Done |
| 4 | Prove the package by compiling and running it | The only verification that counts | Done 2026-07-28, and it caught the overlay bug |
| 5 | Migrate Podium off the twin | Closes the original glitch investigation on the real app | Next |
| 6 | GitHub Actions CI with a build matrix | Open-source credibility, and it catches RN upgrades breaking us | Next, and the §10 commands are already CI-ready |
| 7 | Example app in-repo | Living docs plus a CI host | Later |
| 8 | `restart(reason?: string)` | Useful for logging. A codegen change, so a minor version | If asked |
| 9 | Overlay options such as opt-out or duration | Only if a real project needs it | If asked |

Honest flags, the things nobody should discover by surprise:

- The dev-only `DevSettings.reload()` fallback path is still untested. Exercising it needs an environment where the native module is genuinely missing, which means Expo Go, and every workspace app is bare RN. The risk is bounded because the path only exists under `__DEV__`.
- `"RCTInstanceDidLoadBundle"` is a bare string literal in RN's source with no exported constant, so nothing stops a future RN release from renaming it without it looking like a breaking change. The symptom would be cosmetic, an 8 s lingering overlay, and it is mapped in the debugging table.
- The `-`/`--` text-glitch root cause is a strong hypothesis rather than a proof: legacy interop reload racing Fabric's text subsystem. Acceptance is empirical, meaning many AR to EN switches with zero reproductions. If it ever reproduces on this module, the hypothesis is wrong, so reopen the investigation instead of patching around it.
- The `react-android` unversioned gradle dep relies on the RN Gradle plugin's dependency substitution, which holds for RN 0.71+ templates and therefore all workspace apps. An exotic host without RNGP would need `com.facebook.react:react-native:+` instead.
- Runtime verification was done in a throwaway harness rather than a production app. That is enough to prove the mechanism on both platforms, but it does not prove behaviour under a real app's native module set, which is what the Podium migration will show.

## 12. Future ideas, explicitly not now

- `restart(reason?: string)`, forwarded to the reload reason and logged before process exit
- Overlay opt-out or custom duration via an options object
- Android: an optional warm mode using `reactHost.reload` for hosts that prefer speed over full freshness
- Example app in-repo plus a CI build matrix compiling against an RN template matrix, at 0.79 old arch and latest new arch
- Expo config plugin: not needed, since the module touches no native config and plain autolinking covers dev client, prebuild, EAS, and bare (see §3). Expo Go is impossible for any custom native module, and that is handled by the dev JS-reload fallback and documented in the README
