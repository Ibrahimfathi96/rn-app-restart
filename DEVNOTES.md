# DEVNOTES — everything in mind about rn-app-restart

> Internal working doc. Not shipped to npm (add to `.npmignore`/`files` already excludes it via allowlist). The README is the public face; this is the full context: why this exists, every design decision, current status, test plan, migration plan, publish plan, and known risks.

---

## 1. Why this exists

`react-native-restart` is being **removed from every project in the workspace** (the maintainers' politics are the reason — this is a deliberate replacement, not a fork). The replacement had to be a **real restart**, not a JS workaround (no remounting the root component with a changed key, no `DevSettings.reload` hacks).

The trigger bug: in **Podium** (RN 0.86, New Architecture/bridgeless), switching language AR→EN (which calls `I18nManager.forceRTL` + restart) intermittently produced a broken transient state — **every `<Text>` in the app rendered as `-`/`--` placeholders** (even plain literal strings like the "EN" button). A Metro `r` (dev-menu reload) always fixed it. Diagnosis: `react-native-restart` ships a **legacy (non-codegen) RCTBridgeModule** running through the New-Arch interop layer, and its reload raced the text/font subsystem on Fabric. The dev-menu reload path (`RCTTriggerReloadCommandListeners`) never glitched — which is exactly the path this package uses on iOS.

## 2. What it is

A tiny, dependency-free native module, dual-architecture:

- **Android — true cold restart.** `getLaunchIntentForPackage` + `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` → `startActivity` → `finishAffinity()` → `Runtime.getRuntime().exit(0)`. The process dies and the OS relaunches the app. This is the *only* restart on Android that guarantees a fully fresh native state (RTL flags, locale, half-initialized surfaces all impossible to leak). The ProcessPhoenix pattern.
- **iOS — reload + launch-screen overlay.** Apple forbids relaunching your own process, period. So the realest restart on iOS is `RCTTriggerReloadCommandListeners()` — the public, supported reload entry point, the same one the dev menu uses (and every restart library uses underneath). On top of that, this module instantiates the app's **own `LaunchScreen.storyboard`** into an overlay `UIWindow` (`UIWindowLevelAlert + 1`) *before* triggering the reload, and fades it out ~0.3 s after `RCTJavaScriptDidLoadNotification` fires, with a **hard 8 s failsafe** dismissal so a failed bundle load can never trap the user behind the overlay. Result: the reload reads as a genuine app relaunch, and the "restart loading screen" is automatically per-project (each app's own launch screen) with zero configuration.

### API

```ts
import { restart } from 'rn-app-restart';   // named
import RNRestart from 'rn-app-restart';     // default — react-native-restart compatible
RNRestart.restart(); RNRestart.Restart();   // both aliases work → migration = change the import line only
```

## 3. Design decisions & rationale

| Decision | Rationale |
| --- | --- |
| Codegen TurboModule on New Arch (not interop) | The interop layer was the suspected culprit in the Podium glitch; also future-proof (interop is a compatibility shim, not the destination). |
| Old-arch support via source-set split | Workspace still has RN 0.79 projects (riya, jeyad, niyak). `android/src/newarch` extends the generated `NativeRNAppRestartSpec`; `android/src/oldarch` extends `ReactContextBaseJavaModule` with the same abstract surface; one shared `RNAppRestartModule.kt` implements both. This is the create-react-native-library "backward compatible" pattern — battle-tested. |
| iOS single class, `#ifdef RCT_NEW_ARCH_ENABLED` | Same CRNL pattern: `RCT_EXPORT_MODULE` + `RCT_EXPORT_METHOD` serve old arch; `getTurboModule:` under the ifdef serves new arch. `install_modules_dependencies(s)` in the podspec wires React deps + codegen for whichever arch the host uses. |
| Module name `RNAppRestart` | Distinct from Podium's in-app `NativeAppRestart` so both can coexist during the transition without a registry clash. |
| Android cold restart (not `reactHost.reload`) | A JS-context reload on Android *can* leave native state behind; process death cannot. For the RTL use case, correctness beats the extra ~1 s of cold start. iOS can't have this, so it gets the overlay to compensate visually. |
| Android: make RN's RTL pref durable before `exit(0)` (synchronous `commit()` of the I18nUtil SharedPreferences) | RN's `I18nManager.forceRTL()` writes via `SharedPreferences.apply()` (async) — the value is only in memory when the cold restart's `exit(0)` fires, so the relaunched process reads the STALE direction and a language switch needs a SECOND restart to apply (measured on-device via pid trace: 3 pids = 2 restarts). Fix: read the in-memory value back (`apply()` sets it immediately) and re-write it with `commit()` (synchronous, blocking disk write) before `exit(0)` → durable → **one restart**. Uses RN's exact pref name/keys (verified against RN 0.86 `I18nUtil.kt`: file `com.facebook.react.modules.i18nmanager.I18nUtil`, keys `RCTI18nUtil_allowRTL`/`RCTI18nUtil_forceRTL`). **NOTE:** the earlier attempt — the generic `QueuedWork.waitToFinish()` via reflection — was measured BLOCKED on a modern device (threw, caught, no flush, still two restarts), so it was replaced by this targeted public-API commit. Coupling to RN's i18n pref is acceptable because an RTL switch IS the #1 reason to restart an RN app. This keeps the true cold restart (full freshness) AND one restart, instead of downgrading to a JS-context reload like react-native-restart. JS self-heal ([useBootstrap] language-keyed) stays as the safety net. |
| Overlay = the app's LaunchScreen storyboard | Per-project customization for free (change the project's launch screen → the restart look follows). No API surface to maintain, no assets shipped, always brand-consistent. If `UILaunchStoryboardName` is missing, reload happens with no overlay (graceful no-op). |
| Failsafe timeout on the overlay | The overlay is held in a static (`gRNAppRestartOverlay`) because the module instance itself is destroyed by the reload it triggers. Anything held statically and shown over the app MUST have an unconditional dismissal path — hence the 8 s `dispatch_after` + idempotent hide. |
| No options/params on `restart()` | YAGNI. A `reason?: string` param or overlay opt-out can be added later without breaking (spec change = codegen rerun). |
| Ship TS source (`main: src/index.ts`), no build step | Metro transpiles library TS in RN apps (gesture-handler etc. do the same). No bob/rollup until npm publish demands it. |
| Lazy native resolution (`TurboModuleRegistry.get`, not `getEnforcing`) | A missing native module (Expo Go, forgot pod install/rebuild) fails at CALL time with a descriptive error instead of crashing the app at import time. In `__DEV__` it falls back to `DevSettings.reload()` with a warning (keeps Expo Go / not-yet-rebuilt dev sessions usable); in production it throws — a JS reload is not a real restart, so prod fails loudly rather than pretending. |
| Shipped Jest mock (`jest/mock.js`) | Every consumer needs one (native resolution has no host under Jest); one `jest.mock('rn-app-restart', () => require('rn-app-restart/jest/mock'))` line replaces per-project hand-rolled mocks. |
| Expo: no config plugin | The module changes no manifests/Info.plist, so dev-client / prebuild / EAS / bare Expo work with plain autolinking. Expo Go can never run custom native modules (platform constraint) — covered by the dev fallback above and documented in the README. |

## 4. File map

```
package.json          name/version/files/codegenConfig (RNAppRestartSpec, jsSrcsDir: src,
                      android javaPackageName com.rnapprestart) + RN peer deps
RNAppRestart.podspec  s.name RNAppRestart; source_files ios/**; install_modules_dependencies
src/NativeRNAppRestart.ts  codegen spec; TurboModuleRegistry.getEnforcing('RNAppRestart') —
                      on old arch the registry falls back to NativeModules, so one entry point serves both
src/index.ts          restart() + default {restart, Restart}
ios/RNAppRestart.h/.mm     module + overlay logic (static fns, C-style, ~120 lines)
android/build.gradle       arch detection via rootProject.newArchEnabled; source-set switch;
                      buildConfigField IS_NEW_ARCHITECTURE_ENABLED; react-android dep (version via RNGP)
android/src/main/...       RNAppRestartModule.kt (cold restart) + RNAppRestartPackage.kt
                      (BaseReactPackage, isTurboModule = BuildConfig flag) — autolinking picks the package up
android/src/newarch/...    abstract RNAppRestartSpec : NativeRNAppRestartSpec (generated)
android/src/oldarch/...    abstract RNAppRestartSpec : ReactContextBaseJavaModule
```

## 5. Current status

| Item | Status |
| --- | --- |
| Package source complete | ✅ (all files above) |
| **Podium in-app twin — iOS** | ✅ **VERIFIED WORKING on device/sim** (2026-07-07): full app behaves as if the package exists; language switch restarts correctly |
| **Podium in-app twin — Android** | 🔜 testing next (2026-07-08) — cold-restart path is the thing to verify |
| Package compiled inside a host app | ❌ not yet — a library only compiles in a host; see test plan |
| Old-arch verification (0.79 project) | ❌ not yet |
| GitHub repo | ❌ not created; repo URL in package.json/podspec/README is a **placeholder** (`github.com/Ibrahimfathi96/rn-app-restart`) — confirm the username |
| npm publish | ❌ deliberately later, after all projects prove it; check the name `rn-app-restart` is free first |

**The Podium in-app twin** (same logic, different names) lives in the Podium repo: `specs/NativeAppRestart.ts`, `android/.../AppRestartModule.kt` + `AppRestartPackage.kt` + MainApplication registration, `ios/Podium/RCTNativeAppRestart.h/.mm` + pbxproj entries + `codegenConfig` (with `ios.modulesProvider`). It stays until the package is proven, then Podium migrates (see §7).

Differences between the twin and the package worth knowing:
- The twin registers on iOS via codegen's `RCTModuleProviders` map (`ios.modulesProvider` in app codegenConfig). The package instead uses `RCT_EXPORT_MODULE` (library-style discovery). Both are legitimate; libraries can't use the app's modulesProvider.
- The twin's iOS restart has **no launch-screen overlay** — the overlay is a package improvement. iOS verified fine without it; with it, it should only look better.
- The twin is new-arch only (Podium is bridgeless); the package adds the old-arch paths.

## 6. Test plan

Test locally BEFORE creating the GitHub repo (faster loop):

```sh
# in a host project
yarn add file:../rn-app-restart     # yarn classic projects (riya, jeyad, niyak)
yarn add portal:../rn-app-restart   # yarn 3+ projects (Podium — portal keeps it live-editable)
cd ios && pod install
```

Matrix (each row = install, build, tap a restart trigger, verify):

| Project | RN | Arch | Verifies |
| --- | --- | --- | --- |
| Podium | 0.86 | New (bridgeless) | TurboModule path, iOS overlay + dismissal timing, Android cold restart, coexistence with the in-app twin (different names — must not clash) |
| riya or niyak | 0.79 | Old | Old-arch fallback in the spec file, `oldarch` source set, `RCT_EXPORT_METHOD` path, BaseReactPackage on 0.79 |
| HM-tasks2 or Liana | 0.83 | check project | mid-version sanity |

Per-platform checks:
- **Android**: app fully relaunches (process id changes); RTL flip applies; back stack is clean (no old activity behind); works from a background→foreground state; release build too (not just debug).
- **iOS**: overlay appears instantly (no white flash), fades ~0.3 s after load; **watch for the overlay lingering the full 8 s** — that would mean `RCTJavaScriptDidLoadNotification` didn't fire on that RN version's bridgeless mode → switch the dismissal signal (candidates: `RCTInstanceDidLoadBundle`, surface-stage observation); the failsafe makes this a UX bug, not a trap. Verify the AR↔EN glitch (`-` placeholders) never reproduces.
- **Both**: react-query/MMKV persisted state intact after restart; no duplicate-module warnings in Metro.

## 7. Migration plan (per project, once proven)

1. `yarn remove react-native-restart`
2. `yarn add github:<user>/rn-app-restart` (or npm once published)
3. Change the import — API is compatible (`RNRestart.restart()` / `Restart()` both exist)
4. `pod install`, rebuild both platforms
5. Remove any jest mock of `react-native-restart` → mock `rn-app-restart` instead (its spec calls `getEnforcing` at import time, which throws under Jest: mock `'rn-app-restart'` with `{ restart: jest.fn(), default: { restart: jest.fn() } }`)

**Podium extra step** — delete the in-app twin: `specs/NativeAppRestart.ts`, the two Kotlin files + `add(AppRestartPackage())` line, `ios/Podium/RCTNativeAppRestart.h/.mm` + their four pbxproj entries, the `codegenConfig` block in package.json (the package brings its own), the `"specs"` tsconfig include, the jest setup mock path, and rewrite `src/shared/utils/restart.ts` to import from `rn-app-restart`.

## 8. Publish checklist (when the time comes)

- [ ] Confirm GitHub username → fix `repository.url`, `homepage`, README install commands, podspec `s.source`
- [ ] `npm view rn-app-restart` → confirm the name is unclaimed (fallbacks: `react-native-app-restart`, `@ibrahimfathi/rn-app-restart`)
- [ ] Create repo, push, tag `v0.1.0` (podspec expects tag format `v#{version}`)
- [ ] Verify the Stand With Palestine banner renders on the GitHub README (remote SVG from TheBSD/StandWithPalestine)
- [ ] Test `yarn add github:...` from a clean project (the `files` allowlist controls what npm packs; GitHub installs ship the whole repo — fine)
- [ ] `npm publish` (public), verify `npm install rn-app-restart` in a scratch app
- [ ] Optional later: CI (compile against an RN template matrix), `.npmignore` DEVNOTES, example app

## 9. Known risks / open questions

| Risk | Mitigation / plan |
| --- | --- |
| `RCTJavaScriptDidLoadNotification` on bridgeless in future RN versions | Failsafe timeout already bounds the damage; swap dismissal signal if it regresses (see §6 iOS checks) |
| `implementation "com.facebook.react:react-android"` (unversioned) needs the RN Gradle plugin's dependency substitution — true for RN ≥0.71 app templates | All workspace projects qualify (0.79+). If an exotic host fails, fall back to `com.facebook.react:react-native:+` |
| Kotlin Gradle plugin must be on the host's classpath | True for all bare RN templates 0.71+ (RN template root build.gradle ships Kotlin) |
| `BaseReactPackage` requires RN ≥ 0.74 | Oldest workspace project is 0.79 ✓. For hypothetical <0.74 hosts, swap to `TurboReactPackage` (same shape) |
| `RCTKeyWindow()` may return nil very early in app lifecycle → overlay falls back to `initWithFrame` | Already handled; worst case the overlay misses a windowScene on multi-scene apps (none in the workspace) |
| Overlay + `react-native-bootsplash` interaction on iOS | Bootsplash controls the *initial* launch; the overlay only exists during `restart()` reloads — they shouldn't overlap. Verify once in Podium |
| The `-`/`--` text glitch root cause is *suspected* (interop reload race), not proven | Acceptance test = it stops reproducing across many AR↔EN switches on the new module. iOS already looks good (in-app twin verified); keep watching |
| npm name squatting between now and publish | Check early (§8) |

## 10. Maintainer guide (for whoever develops or fixes bugs on this)

### Local development loop

There's no example app yet, so develop against a real host project:

```sh
# yarn classic hosts (riya, jeyad, niyak):
yarn add file:../rn-app-restart      # re-run after native changes (file: copies)

# yarn 3+ hosts (Podium):
yarn add portal:../rn-app-restart    # live-linked — edits show without reinstalling
```

- **JS change** (`src/`) → Metro reload is enough (portal:) or reinstall (file:).
- **Native change** (`ios/`, `android/`) → rebuild the host app. iOS also needs `pod install` if files were added/renamed.
- **Spec change** (`src/NativeRNAppRestart.ts`) → this is the codegen contract: `pod install` + full rebuild on iOS, gradle rebuild on Android, and the native implementations MUST be updated in the same commit (codegen makes mismatches compile errors — that's the point).

### Debugging map — symptom → where the bug lives

| Symptom | Look in |
| --- | --- |
| "Native module not found" in a real build | Autolinking: is the package in `node_modules`? iOS — does `Pods/` contain `RNAppRestart` after pod install? Android — does `PackageList` include `RNAppRestartPackage` (search `android/app/build/generated/.../PackageList.java` in the HOST app)? |
| iOS build error mentioning `NativeRNAppRestartSpecJSI` / missing `RNAppRestartSpec/RNAppRestartSpec.h` | Codegen didn't run or the config changed: check `codegenConfig` in package.json (name **RNAppRestartSpec** must match the `#import` in `RNAppRestart.mm`), then clean pod install (`rm -rf ios/Pods ios/build && pod install`). Old-arch hosts never hit this (`#ifdef RCT_NEW_ARCH_ENABLED`). |
| Android build error `Unresolved reference: NativeRNAppRestartSpec` | New-arch host but codegen output missing — the `com.facebook.react` plugin block in `android/build.gradle` didn't apply. Check `isNewArchitectureEnabled()` reads the host's `newArchEnabled` gradle property. |
| Android build error `Unresolved reference: RNAppRestartSpec` | Source-set switch failed — neither `src/newarch` nor `src/oldarch` on the compile path. Check the `sourceSets` block. |
| Restart does nothing on Android | `getLaunchIntentForPackage` returned null (no LAUNCHER activity — never true in RN templates) or `restart()` isn't reaching native: verify via `adb logcat` that the process exits. |
| iOS overlay never appears | Host has no `UILaunchStoryboardName` (by design → no overlay), or the storyboard failed to instantiate (`@try` swallows it → also no overlay, reload still fine). |
| iOS overlay lingers exactly 8 s | `RCTJavaScriptDidLoadNotification` didn't fire on that RN version's bridgeless mode. Fix in `RNAppRestartShowOverlay` — swap/add the dismissal signal (candidates: `RCTInstanceDidLoadBundle`, surface stage callbacks). The 8 s failsafe is the safety net, keep it whatever the signal becomes. |
| App crashes at JS import time in some environment | Someone reintroduced `getEnforcing` in the spec — resolution must stay nullable (`TurboModuleRegistry.get`) with the call-time error in `index.ts`. |

### Invariants — do not break these

1. **Android restart = process death.** Never "optimize" it to a JS-context reload — full freshness is the product.
2. **The iOS overlay must always have an unconditional dismissal path** (currently the 8 s failsafe). It outlives the module instance (statics), so nothing else can clean it up.
3. **Production never silently falls back to a JS reload.** Dev-only (`__DEV__`) fallback is fine; prod throws.
4. **`RNAppRestart` module name is public API** — JS resolves by that string on both archs. Renaming = major version.
5. **Default export stays react-native-restart-compatible** (`restart` + `Restart`) — migration-by-import-change is a core promise.

### Release process

1. Update `version` in package.json
2. `git tag v<version>` (podspec `s.source` expects the `v` prefix) and push with tags
3. GitHub installs pick up the branch/tag directly; for npm: `npm publish` (after the §8 checklist, first time)

## 11. Consolidated recommendations & honest flags (status board)

Recommendations — what I'd do, in order:

| # | Recommendation | Value | Status |
| --- | --- | --- | --- |
| 1 | Lazy native resolution + dev JS-reload fallback + prod throw | Crash-proof DX in Expo Go / missed rebuilds | ✅ done |
| 2 | Shipped Jest mock (`jest/mock.js`) | One-line test setup per consumer | ✅ done |
| 3 | README: requirements, full install steps, troubleshooting table | Users self-serve instead of filing issues | ✅ done |
| 4 | Prove the package by real installs (old-arch riya/niyak + new-arch Podium) | The only verification that counts | 🔜 next |
| 5 | Example app in-repo | Living docs + the CI host | ⏳ at publish time |
| 6 | GitHub Actions CI: build matrix (0.79 old arch, latest new arch) | Open-source credibility; catches RN upgrades breaking us | ⏳ at publish time |
| 7 | `restart(reason?: string)` | Nice for logging; codegen change → minor version | ⏳ if asked |
| 8 | Overlay options (opt-out / duration) | Only if a real project needs it | ⏳ if asked |

Honest flags — the things nobody should discover by surprise:

- **The package itself has never been compiled in a host app.** Podium verified the *in-app twin* (iOS ✅ working; Android tested next). The package is the same logic restructured into the standard dual-arch library pattern, but "same logic" ≠ "compiled" — recommendation #4 is the gate before GitHub/npm.
- **The dev-only `DevSettings.reload()` fallback path is untested** — exercising it requires an environment where the native module is genuinely missing (Expo Go). All workspace apps are bare RN, so it stays untested until someone tries Expo Go. Bounded risk: the path only exists under `__DEV__`.
- **The overlay dismissal signal (`RCTJavaScriptDidLoadNotification`) is a compat notification** on bridgeless — RN keeps posting it today, but it's the most likely thing a future RN release breaks. Symptom is cosmetic (8 s lingering overlay), mapped in the debugging table above.
- **The `-`/`--` text-glitch root cause is a strong hypothesis, not a proof** (legacy interop reload racing Fabric's text subsystem). Acceptance is empirical: many AR↔EN switches with zero reproductions. If it EVER reproduces on this module, the hypothesis is wrong — reopen the investigation, don't patch around it.
- **`react-android` unversioned gradle dep** relies on the RN Gradle plugin's dependency substitution (RN ≥ 0.71 templates — all workspace apps qualify). An exotic host without RNGP would need `com.facebook.react:react-native:+` instead.
- **npm name `rn-app-restart` is unverified** — check before publish (fallbacks in §8).

## 12. Future ideas (explicitly not now)

- `restart(reason?: string)` — forwarded to the reload reason / logged before process exit
- Overlay opt-out or custom duration via options object
- Android: optional "warm" mode (`reactHost.reload`) for hosts that prefer speed over full freshness
- Example app in-repo + CI build matrix (GitHub Actions compiling against an RN template matrix: 0.79 old arch + latest new arch) — do this at npm-publish time; it's the open-source credibility layer
- ~~Expo config plugin~~ not needed — module touches no native config; plain autolinking covers dev-client/prebuild/EAS/bare (see §3). Expo Go is impossible for any custom native module; handled by the dev JS-reload fallback + README.
