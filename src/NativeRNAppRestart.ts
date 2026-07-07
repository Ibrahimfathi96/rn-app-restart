import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export interface Spec extends TurboModule {
  restart(): void;
}

// `get` (not `getEnforcing`): resolution is nullable so index.ts can fail with
// a helpful, environment-aware error at CALL time instead of crashing the whole
// app at import time (Expo Go, missing pod install, Jest without a mock…).
// On the New Architecture this resolves the codegen'd TurboModule; on the old
// architecture TurboModuleRegistry falls back to the NativeModules registry —
// one entry point, both archs.
export default TurboModuleRegistry.get<Spec>('RNAppRestart');
