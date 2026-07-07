package com.rnapprestart

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule

// Old architecture: a classic native module with the same surface as the
// codegen spec, so the module implementation is shared between both archs.
abstract class RNAppRestartSpec(context: ReactApplicationContext) :
  ReactContextBaseJavaModule(context) {
  abstract fun restart()
}
