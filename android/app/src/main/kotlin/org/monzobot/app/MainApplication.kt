package org.monzobot.app

import android.app.Application
import androidx.work.WorkManager
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost

class MainApplication : Application(), ReactApplication {
  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(context = applicationContext, packageList = PackageList(this).packages.apply { add(MonzoDevicePackage()) })
  }
  override fun onCreate() {
    super.onCreate()
    // Legacy jobs must never run the replaced Java implementation after an upgrade.
    val work = WorkManager.getInstance(this)
    work.cancelUniqueWork("private-sheet-sync")
    work.cancelUniqueWork("companion-local-checks-v1")
    work.cancelUniqueWork("companion-local-checks-v1-now")
    work.cancelAllWorkByTag("monzo-sync")
    loadReactNative(this)
  }
}
