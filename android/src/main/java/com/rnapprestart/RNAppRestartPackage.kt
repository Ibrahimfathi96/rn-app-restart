package com.rnapprestart

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class RNAppRestartPackage : BaseReactPackage() {

  override fun getModule(
    name: String,
    reactContext: ReactApplicationContext,
  ): NativeModule? =
    if (name == RNAppRestartModule.NAME) RNAppRestartModule(reactContext)
    else null

  override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
    mapOf(
      RNAppRestartModule.NAME to
        ReactModuleInfo(
          RNAppRestartModule.NAME,
          RNAppRestartModule.NAME,
          false, // canOverrideExistingModule
          false, // needsEagerInit
          false, // isCxxModule
          BuildConfig.IS_NEW_ARCHITECTURE_ENABLED, // isTurboModule
        ),
    )
  }
}
