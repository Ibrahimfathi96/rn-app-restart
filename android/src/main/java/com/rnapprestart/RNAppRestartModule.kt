package com.rnapprestart

import android.content.Context
import android.content.Intent
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil

/**
 * True cold restart: relaunch the app's launch intent with a cleared task and
 * exit the current process. Unlike a JS-only reload this gives a completely
 * fresh native state, so I18nManager direction changes always apply and there
 * are no half-reloaded surfaces. The relaunch goes through the app's normal
 * launch theme or splash screen, so the "loading screen" is the app's own.
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
      flushRtlPref(context)
      Runtime.getRuntime().exit(0)
    }
  }

  companion object {
    const val NAME = "RNAppRestart"

    // RN's I18nUtil SharedPreferences (stable across RN versions).
    private const val I18N_PREFS = "com.facebook.react.modules.i18nmanager.I18nUtil"
    private const val KEY_ALLOW_RTL = "RCTI18nUtil_allowRTL"
    private const val KEY_FORCE_RTL = "RCTI18nUtil_forceRTL"

    /**
     * Make RN's RTL direction durable before the process is killed.
     *
     * The most common reason to restart an RN app is an RTL/LTR language
     * switch. `I18nManager.forceRTL()` writes through
     * `SharedPreferences.apply()`, which is async, so the value is still only in
     * memory when `exit(0)` fires and the relaunched process reads the stale
     * direction. That makes the switch need a second restart to take effect.
     *
     * `apply()` does update the in-memory value immediately, so we read it back
     * and rewrite it with `commit()`, a synchronous blocking disk write. The
     * value is then durable before the kill and one restart is enough.
     *
     * A generic flush via `QueuedWork.waitToFinish()` is greylisted and blocked
     * on modern Android, so this targeted public-API commit is the reliable
     * path. It is best effort and wrapped so a failure never blocks the restart.
     */
    private fun flushRtlPref(context: Context) {
      try {
        val prefs = context.getSharedPreferences(I18N_PREFS, Context.MODE_PRIVATE)
        prefs
          .edit()
          .putBoolean(KEY_ALLOW_RTL, prefs.getBoolean(KEY_ALLOW_RTL, true))
          .putBoolean(KEY_FORCE_RTL, prefs.getBoolean(KEY_FORCE_RTL, false))
          .commit()
      } catch (_: Throwable) {
        // Best effort. Never block the restart on this.
      }
    }
  }
}
