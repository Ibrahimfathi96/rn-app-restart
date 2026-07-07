package com.rnapprestart

import com.facebook.react.bridge.ReactApplicationContext

// New Architecture: bridge to the codegen-generated TurboModule spec.
abstract class RNAppRestartSpec(context: ReactApplicationContext) :
  NativeRNAppRestartSpec(context)
