package org.monzobot.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import java.util.concurrent.Executors
import java.nio.charset.StandardCharsets.UTF_8

/** Small Android-only boundary. UI, financial calculations and AI live in TypeScript. */
class MonzoDeviceModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context), LifecycleEventListener {
  companion object { private const val AUTH=7401; private const val IMPORT=7402; private const val EXPORT=7403; private const val MIC=7404; private const val NOTIFY=7405 }
  private val io=Executors.newSingleThreadExecutor()
  private val network=Executors.newFixedThreadPool(2)
  private val main=Handler(Looper.getMainLooper())
  private val store=PrivateStore(context)
  private var googlePromise: Promise?=null
  @Volatile private var storageEpoch=0L
  @Volatile private var googleEpoch=0L
  private var pendingEpoch=0L
  private var token: String?=null
  private var documentPromise: Promise?=null
  private var documentEpoch=0L
  private var exportText: String?=null
  private var speech: SpeechRecognizer?=null
  private var speechPromise: Promise?=null
  private val stopSpeech=Runnable { finishSpeech(null,"On-device recognition timed out. Type or edit your message instead.") }
  private val activities=object : BaseActivityEventListener() {
    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
      if(requestCode==AUTH) {
        val pending=googlePromise ?: return
        if(pendingEpoch!=googleEpoch) { googlePromise=null;pending.reject("CANCELLED","Google authorization was cancelled");return }
        if(resultCode!=Activity.RESULT_OK||data==null) { googlePromise=null;pending.reject("CANCELLED","Google authorization was not granted");return }
        try { acceptGoogle(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)) }
        catch(_:Exception) { googlePromise=null;pending.reject("GOOGLE_AUTH","Google authorization could not be completed. Check the app registration and granted read-only permissions.") }
      } else if(requestCode==IMPORT||requestCode==EXPORT) {
        val promise=documentPromise ?: return
        val epoch=documentEpoch
        documentPromise=null
        if(epoch!=storageEpoch) { exportText=null;promise.reject("CANCELLED","Workspace changed; document operation cancelled");return }
        if(resultCode!=Activity.RESULT_OK||data?.data==null) { exportText=null;promise.reject("CANCELLED","No file was selected");return }
        val uri=data.data!!
        val text=exportText;exportText=null
        io.execute {
          try {
            if(epoch!=storageEpoch) { promise.reject("CANCELLED","Workspace changed; no document operation completed");return@execute }
            if(requestCode==IMPORT) {
              val input=context.contentResolver.openInputStream(uri) ?: throw IllegalStateException()
              val bytes=input.use { stream ->
                val output=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(8192)
                while(true) { val count=stream.read(buffer);if(count<0) break;require(output.size()+count<=PrivateStore.MAX_STATE);output.write(buffer,0,count) }
                output.toByteArray()
              }
              require(bytes.size<=PrivateStore.MAX_STATE)
              if(epoch!=storageEpoch) promise.reject("CANCELLED","Workspace changed; imported text was discarded") else promise.resolve(String(bytes,UTF_8))
            } else {
              if(epoch!=storageEpoch) { promise.reject("CANCELLED","Workspace changed; export cancelled");return@execute }
              val output=context.contentResolver.openOutputStream(uri,"wt") ?: throw IllegalStateException()
              output.use { it.write((text ?: throw IllegalStateException()).toByteArray(UTF_8)) }
              if(epoch!=storageEpoch) promise.reject("CANCELLED","Workspace changed during the already-approved document write; inspect the chosen destination") else promise.resolve(null)
            }
          } catch(_:Exception) { promise.reject("DOCUMENT","The selected document could not be read or written safely. Existing app data was preserved.") }
        }
      }
    }
  }
  init { context.addActivityEventListener(activities);context.addLifecycleEventListener(this) }
  override fun getName()="MonzoDevice"
  private fun storage(promise:Promise, action:()->Any?) {
    val epoch=storageEpoch
    io.execute { try {
      if(epoch!=storageEpoch) { promise.reject("CANCELLED","The workspace changed; reopen it before continuing");return@execute }
      val result=action()
      if(epoch!=storageEpoch) promise.reject("CANCELLED","The workspace changed; reopen it before continuing") else promise.resolve(result)
    } catch(_:Exception) { promise.reject("PRIVATE_STORAGE","Private data could not be opened or saved safely. Existing data was preserved; do not clear it to retry.") } }
  }
  @ReactMethod fun providerRequest(provider:String,key:String,bodyJson:String,promise:Promise) {
    network.execute { try { promise.resolve(PrivateHttp.provider(provider,key,bodyJson)) } catch(_:Exception) { promise.reject("PROVIDER_REQUEST","The selected provider request failed or exceeded a supported limit. No automatic retry was made.") } }
  }
  @ReactMethod fun googleRequest(url:String,token:String,promise:Promise) {
    network.execute { try { promise.resolve(PrivateHttp.google(url,token)) } catch(_:Exception) { promise.reject("GOOGLE_REQUEST","The read-only Google request failed or exceeded a supported limit. Reconnect or refresh after reviewing permissions.") } }
  }
  @ReactMethod fun loadWorkspace(promise:Promise)=storage(promise) { store.loadWorkspace() }
  @ReactMethod fun saveWorkspace(json:String,promise:Promise)=storage(promise) { store.saveWorkspace(json);null }
  @ReactMethod fun readProviderKey(provider:String,promise:Promise)=storage(promise) { store.readProvider(provider) }
  @ReactMethod fun saveProviderKey(provider:String,key:String,promise:Promise)=storage(promise) { store.saveProvider(provider,key);null }
  @ReactMethod fun forgetProviderKey(provider:String,promise:Promise)=storage(promise) {
    if(provider=="all") store.forgetAllProviders() else store.forgetProvider(provider)
    null
  }
  @ReactMethod fun deleteWorkspace(promise:Promise) {
    storageEpoch++;googleEpoch++
    main.post {
      token=null;googlePromise?.reject("CANCELLED","Workspace deleted");googlePromise=null
      finishSpeech(null,"Workspace deleted")
      documentPromise?.reject("CANCELLED","Workspace deleted");documentPromise=null;exportText=null
      PrivateCheckIns.cancel(context)
    }
    storage(promise) { store.clear();null }
  }
  @ReactMethod fun authorizeGoogle(promise:Promise) {
    main.post {
      val activity=context.currentActivity
      if(activity==null) { promise.reject("NO_ACTIVITY","Open the app before connecting Google");return@post }
      if(googlePromise!=null) { promise.reject("BUSY","A Google authorization is already open");return@post }
      googlePromise=promise;pendingEpoch=googleEpoch
      val epoch=pendingEpoch
      val request=AuthorizationRequest.builder().setRequestedScopes(listOf(
        Scope("https://www.googleapis.com/auth/spreadsheets.readonly"),
        Scope("https://www.googleapis.com/auth/drive.metadata.readonly"))).build()
      Identity.getAuthorizationClient(activity).authorize(request).addOnSuccessListener { result ->
        if(epoch!=googleEpoch||googlePromise!==promise) return@addOnSuccessListener
        if(result.hasResolution()) {
          try { activity.startIntentSenderForResult(result.pendingIntent!!.intentSender,AUTH,null,0,0,0) }
          catch(_:Exception) { googlePromise=null;promise.reject("GOOGLE_AUTH","Google consent could not open. Check this package and signing certificate's Google configuration.") }
        } else acceptGoogle(result)
      }.addOnFailureListener {
        if(epoch==googleEpoch&&googlePromise===promise) { googlePromise=null;promise.reject("GOOGLE_AUTH","Google authorization failed. The existing signing certificate and package must be registered in Google Cloud, with Drive and Sheets APIs enabled.") }
      }
    }
  }
  private fun acceptGoogle(result:AuthorizationResult) {
    val promise=googlePromise ?: return
    googlePromise=null
    if(pendingEpoch!=googleEpoch) { promise.reject("CANCELLED","Google authorization cancelled");return }
    val value=result.accessToken
    if(value.isNullOrEmpty()) { promise.reject("GOOGLE_AUTH","Google did not grant the requested read-only access");return }
    token=value // Memory only; never financial JSON, logs, Intent extras or WorkManager input.
    promise.resolve(Arguments.createMap().apply { putString("accessToken",value) })
  }
  @ReactMethod fun disconnectGoogle(promise:Promise) {
    main.post {
      googleEpoch++;token=null;googlePromise?.reject("CANCELLED","Google authorization cancelled");googlePromise=null
      PrivateCheckIns.cancel(context)
      promise.resolve(null) // Google account grant revocation remains in Google Account controls.
    }
  }
  @ReactMethod fun notificationStatus(promise:Promise) {
    main.post { promise.resolve(PrivateCheckIns.status(context)) }
  }
  @ReactMethod fun configureNotifications(options:ReadableMap,promise:Promise) {
    val epoch=storageEpoch
    main.post {
      if(epoch!=storageEpoch) { promise.reject("CANCELLED","Workspace changed; notification changes cancelled");return@post }
      try {
        val enabled=options.hasKey("enabled")&&options.getBoolean("enabled")
        val persistent=options.hasKey("persistent")&&options.getBoolean("persistent")
        val start=if(options.hasKey("quietStart")) options.getInt("quietStart") else 21
        val end=if(options.hasKey("quietEnd")) options.getInt("quietEnd") else 8
        require(start in 0..23&&end in 0..23)
        if(enabled&&Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
          val host=context.currentActivity as? PermissionAwareActivity
          if(host==null) { promise.reject("NO_ACTIVITY","Open the app to allow notifications");return@post }
          host.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),NOTIFY,PermissionListener { code,_,_ ->
            if(code!=NOTIFY) false else {
              if(epoch!=storageEpoch) promise.reject("CANCELLED","Workspace changed; notification changes cancelled") else {
                try { PrivateCheckIns.configure(context,enabled,persistent,start,end);promise.resolve(null) }
                catch(_:Exception) { promise.reject("NOTIFICATIONS","Notification preferences could not be changed") }
              }
              true
            }
          })
        } else { PrivateCheckIns.configure(context,enabled,persistent,start,end);promise.resolve(null) }
      } catch(_:Exception) { promise.reject("NOTIFICATIONS","Notification preferences could not be changed") }
    }
  }
  @ReactMethod fun recognizeSpeech(promise:Promise) {
    main.post {
      if(speechPromise!=null) { promise.reject("BUSY","Voice input is already active");return@post }
      if(Build.VERSION.SDK_INT<31||!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) { promise.reject("VOICE_UNAVAILABLE","On-device recognition is unavailable. Type or edit your message; no cloud fallback is used.");return@post }
      speechPromise=promise
      if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
        val activity=context.currentActivity as? PermissionAwareActivity
        if(activity==null) { finishSpeech(null,"Open the app before using voice input");return@post }
        activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),MIC,PermissionListener { code,_,results ->
          if(code!=MIC) false else {
            if(results.firstOrNull()==PackageManager.PERMISSION_GRANTED) beginSpeech() else finishSpeech(null,"Microphone permission was not granted. Type your message instead.")
            true
          }
        })
      } else beginSpeech()
    }
  }
  private fun beginSpeech() {
    if(speechPromise==null) return
    if(Build.VERSION.SDK_INT<31) { finishSpeech(null,"On-device recognition is unavailable. Type your message instead.");return }
    try {
      speech=SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
        setRecognitionListener(object:RecognitionListener {
          override fun onReadyForSpeech(params:Bundle?) {}
          override fun onBeginningOfSpeech() {}
          override fun onRmsChanged(rmsdB:Float) {}
          override fun onBufferReceived(buffer:ByteArray?) {}
          override fun onEndOfSpeech() {}
          override fun onError(error:Int) { finishSpeech(null,"On-device recognition did not complete. Type or edit the message; no cloud fallback was used.") }
          override fun onResults(results:Bundle?) {
            val text=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if(text.isNullOrBlank()||text.length>4000) finishSpeech(null,"No supported transcript was returned. Type your message instead.") else finishSpeech(text,null)
          }
          override fun onPartialResults(partial:Bundle?) {}
          override fun onEvent(type:Int,params:Bundle?) {}
        })
        startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true).putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1))
      }
      main.postDelayed(stopSpeech,30000)
    } catch(_:Exception) { finishSpeech(null,"On-device voice input is unavailable. Type or edit your message instead.") }
  }
  private fun finishSpeech(text:String?,error:String?) {
    main.removeCallbacks(stopSpeech)
    val pending=speechPromise;speechPromise=null
    speech?.cancel();speech?.destroy();speech=null
    if(text!=null) pending?.resolve(text) else pending?.reject("VOICE_UNAVAILABLE",error ?: "Voice input cancelled")
  }
  @ReactMethod fun cancelSpeech(promise:Promise) { main.post { finishSpeech(null,"Voice input cancelled");promise.resolve(null) } }
  @ReactMethod fun openCalendarDraft(title:String,day:String,description:String,promise:Promise) {
    main.post {
      try {
        require(title.isNotBlank()&&title.length<=200&&description.length<=2000)
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(day))
        val date=java.time.LocalDate.parse(day)
        val begin=date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val end=date.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val activity=context.currentActivity ?: throw IllegalStateException()
        val draft=Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
          .putExtra(android.provider.CalendarContract.Events.TITLE,title)
          .putExtra(android.provider.CalendarContract.Events.DESCRIPTION,description)
          .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME,begin)
          .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME,end)
          .putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY,true)
          .putExtra(android.provider.CalendarContract.Events.EVENT_TIMEZONE,"UTC")
          .putExtra(android.provider.CalendarContract.Events.AVAILABILITY,android.provider.CalendarContract.Events.AVAILABILITY_FREE)
          .putExtra(android.provider.CalendarContract.Events.STATUS,android.provider.CalendarContract.Events.STATUS_TENTATIVE)
        activity.startActivity(draft)
        promise.resolve(null) // Opens an editor. The user chooses a calendar and saves there.
      } catch(_:Exception) { promise.reject("CALENDAR","No calendar draft was saved. Check the reviewed date and whether a calendar editor is installed.") }
    }
  }
  @ReactMethod fun importWorkspace(promise:Promise) {
    main.post {
      val activity=context.currentActivity
      if(activity==null||documentPromise!=null) { promise.reject("BUSY","Open the app and finish the current document operation");return@post }
      documentPromise=promise;documentEpoch=storageEpoch
      try { activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("application/json","text/csv","text/comma-separated-values","text/plain")).addCategory(Intent.CATEGORY_OPENABLE),IMPORT) }
      catch(_:Exception) { documentPromise=null;promise.reject("DOCUMENT","No document picker is available") }
    }
  }
  @ReactMethod fun exportWorkspace(promise:Promise) {
    storageExport(promise)
  }
  private fun storageExport(promise:Promise) {
    val epoch=storageEpoch
    io.execute {
      if(epoch!=storageEpoch) { promise.reject("CANCELLED","Workspace changed; export cancelled");return@execute }
      val text=try { store.loadWorkspace() ?: throw IllegalStateException() } catch(_:Exception) { promise.reject("PRIVATE_STORAGE","The private workspace could not be opened for export");return@execute }
      main.post {
        if(epoch!=storageEpoch) { promise.reject("CANCELLED","Workspace changed; export cancelled");return@post }
        val activity=context.currentActivity
        if(activity==null||documentPromise!=null) { promise.reject("BUSY","Open the app and finish the current document operation");return@post }
        documentPromise=promise;documentEpoch=epoch;exportText=text
        try { activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"private-budget-export.json"),EXPORT) }
        catch(_:Exception) { documentPromise=null;exportText=null;promise.reject("DOCUMENT","No document destination picker is available") }
      }
    }
  }
  override fun onHostResume() {}
  override fun onHostPause() { if(speech!=null) main.post { finishSpeech(null,"Voice input stopped when the app left the foreground") } }
  override fun onHostDestroy() { main.post { googleEpoch++;token=null;googlePromise?.reject("CANCELLED","App closed");googlePromise=null;finishSpeech(null,"App closed");documentPromise?.reject("CANCELLED","App closed");documentPromise=null;exportText=null } }
  override fun invalidate() { onHostDestroy();context.removeActivityEventListener(activities);context.removeLifecycleEventListener(this);io.shutdown();network.shutdownNow();super.invalidate() }
}
