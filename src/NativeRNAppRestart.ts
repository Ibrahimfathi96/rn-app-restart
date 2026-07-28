import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export interface Spec extends TurboModule {
  restart(): void;
}

// This uses `get` rather than `getEnforcing` so resolution stays nullable.
// That lets index.ts fail with an environment-aware error at call time instead
// of crashing the whole app at import time, which matters in Expo Go, when
// pod install has not run, and in Jest without a mock.
//
// On the New Architecture this resolves the codegen'd TurboModule. On the old
// architecture TurboModuleRegistry falls back to the NativeModules registry, so
// one entry point covers both.
export default TurboModuleRegistry.get<Spec>('RNAppRestart');
