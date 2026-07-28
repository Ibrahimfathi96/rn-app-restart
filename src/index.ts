import { DevSettings } from 'react-native';
import NativeRNAppRestart from './NativeRNAppRestart';

const MISSING_NATIVE_MODULE =
  '[rn-app-restart] Native module not found. ' +
  'Rebuild the app after installing (cd ios && pod install, then rebuild both platforms). ' +
  'Note that custom native modules can never run in Expo Go. Use a development build ' +
  '(npx expo prebuild / EAS build), where rn-app-restart works with no extra config.';

/**
 * Restart the app natively.
 *
 * On Android this is a true cold restart. The launch intent is relaunched with
 * a cleared task and the process exits, so native state comes back completely
 * fresh and `I18nManager.forceRTL`, locale changes and similar always apply.
 * The relaunch goes through your app's normal launch theme or splash screen.
 *
 * On iOS the OS does not let an app relaunch its own process, so this triggers
 * the React Native reload-command listeners (the same mechanism as the dev-menu
 * reload) and covers the transition with your app's own
 * `LaunchScreen.storyboard` so it reads as a real relaunch.
 *
 * If the native module is missing, either because you are in Expo Go or because
 * you have not rebuilt yet, development falls back to a JS reload with a warning
 * so you can keep working. Production throws with setup instructions.
 */
export const restart = (): void => {
  if (NativeRNAppRestart != null) {
    NativeRNAppRestart.restart();
    return;
  }
  // The dev-only JS-reload fallback keeps Expo Go and not-yet-rebuilt dev
  // sessions usable. It is deliberately not a production path, because a JS
  // reload is not a real restart. Production fails loudly instead of pretending.
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
