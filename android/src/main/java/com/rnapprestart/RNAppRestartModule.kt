package com.rnapprestart

import android.content.Intent
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil

/**
 * True cold restart: relaunch the app's launch intent with a cleared task and
 * exit the current process. Unlike a JS-only reload this guarantees a fully
 * fresh native state — I18nManager direction changes always apply, no
 * half-reloaded surfaces. The relaunch goes through the app's normal launch
 * theme / splash screen, so the "loading screen" is the app's own.
 */
class RNAppRestartModule(context: ReactApplicationContext) :
  RNAppRestartSpec(context) {

  override fun getName() = NAME

  @ReactMethod
  override fun restart() {
    val context = reactApplicationContext
    UiThreadUtil.runOnUiThread {
      val intent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
          ?: return@runOnUiThread
      intent.addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
      )
      context.startActivity(intent)
      currentActivity?.finishAffinity()
      Runtime.getRuntime().exit(0)
    }
  }

  companion object {
    const val NAME = "RNAppRestart"
  }
}
