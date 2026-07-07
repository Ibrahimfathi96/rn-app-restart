import { DevSettings } from 'react-native';
import NativeRNAppRestart from './NativeRNAppRestart';

const MISSING_NATIVE_MODULE =
  '[rn-app-restart] Native module not found. ' +
  'Rebuild the app after installing (cd ios && pod install, then rebuild both platforms). ' +
  'Note: custom native modules can never run in Expo Go — use a development build ' +
  '(npx expo prebuild / EAS build), where rn-app-restart works with no extra config.';

/**
 * Restart the app natively.
 *
 * - **Android** — a true cold restart: the launch intent is relaunched with a
 *   cleared task and the process exits. Fully fresh native state (guaranteed to
 *   apply `I18nManager.forceRTL`, locale changes, etc.). The relaunch goes
 *   through your app's normal launch theme / splash screen.
 * - **iOS** — iOS forbids relaunching your own process, so this triggers the
 *   React Native reload-command listeners (the same mechanism as the dev-menu
 *   reload) and covers the transition with your app's own
 *   `LaunchScreen.storyboard` so it looks like a real relaunch.
 *
 * If the native module is missing (Expo Go, forgot to rebuild): in dev this
 * falls back to a JS reload with a warning so you can keep working; in
 * production it throws with setup instructions.
 */
export const restart = (): void => {
  if (NativeRNAppRestart != null) {
    NativeRNAppRestart.restart();
    return;
  }
  // ponytail: dev-only JS-reload fallback keeps Expo Go / not-yet-rebuilt dev
  // sessions usable. Deliberately NOT a production path — a JS reload is not a
  // real restart, so production fails loudly instead of pretending.
  if (__DEV__) {
    console.warn(MISSING_NATIVE_MODULE + ' Falling back to a dev JS reload.');
    DevSettings.reload();
    return;
  }
  throw new Error(MISSING_NATIVE_MODULE);
};

// Drop-in compatibility with react-native-restart's API surface:
//   import RNRestart from 'rn-app-restart';
//   RNRestart.restart(); / RNRestart.Restart();
export default { restart, Restart: restart };
