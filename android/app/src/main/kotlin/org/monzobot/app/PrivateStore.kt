package org.monzobot.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Exact IV(12) + AES-GCM ciphertext/tag envelopes used by the original app. */
internal class PrivateStore(private val context: Context) {
  companion object {
    val LOCK = Any()
    const val STATE_ALIAS = "monzobot-state-v1"
    const val STATE_FILE = "budget-state.enc"
    const val MAX_STATE = 30 * 1024 * 1024
    fun slot(provider: String): Pair<String, String> = when(provider) {
      "openai" -> "companion-provider.enc" to "monzobot-companion-provider-v1"
      "anthropic" -> "companion-provider-anthropic.enc" to "monzobot-companion-anthropic-v1"
      else -> throw IllegalArgumentException("Unsupported provider")
    }
    fun aad(provider: String): ByteArray? {
      val (file, alias) = slot(provider)
      return if(provider == "openai") null else "monzobot-credential-slot-v1\u0000$provider\u0000$file\u0000$alias".toByteArray(UTF_8)
    }
  }
  private fun file(name: String) = AtomicFile(File(context.noBackupFilesDir, name))
  private fun key(alias: String, create: Boolean): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    if(!store.containsAlias(alias)) {
      if(!create) throw IOException("Existing encryption key unavailable. Saved data was preserved.")
      KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        generateKey()
      }
    }
    return store.getKey(alias, null) as? SecretKey ?: throw IOException("Existing encryption key unavailable")
  }
  private fun read(name: String, alias: String, limit: Int, aad: ByteArray? = null): String? {
    val bytes = try { file(name).readFully() } catch (_: FileNotFoundException) { return null }
    if(bytes.size < 29 || bytes.size > limit) throw IOException("Saved data could not be read safely. It was preserved.")
    return PrivateEnvelope.decrypt(key(alias, false), bytes, aad, limit)
  }
  private fun write(name: String, alias: String, clear: String, limit: Int, aad: ByteArray? = null) {
    val bytes = clear.toByteArray(UTF_8)
    if(bytes.size > limit - 28) throw IOException("Private data exceeds the supported limit. Previous data was preserved.")
    // Never replace unreadable existing data with a newly generated key.
    read(name, alias, limit, aad) // AtomicFile also restores a surviving .bak before deciding the file is absent.
    val envelope = PrivateEnvelope.encrypt(key(alias, true), clear, aad, limit)
    val atomic = file(name)
    val output = atomic.startWrite()
    try { output.write(envelope); atomic.finishWrite(output) }
    catch(error: Exception) { atomic.failWrite(output); throw error }
  }
  fun loadWorkspace(): String? = synchronized(LOCK) {
    read(STATE_FILE, STATE_ALIAS, MAX_STATE)?.also { JSONObject(it) }
  }
  fun saveWorkspace(json: String) = synchronized(LOCK) {
    val parsed = JSONObject(json)
    for(name in arrayOf("apiKey", "companionApiKey", "accessToken", "refreshToken", "providerKeys"))
      require(!parsed.has(name)) { "Credentials cannot enter the financial workspace" }
    write(STATE_FILE, STATE_ALIAS, parsed.toString(), MAX_STATE)
  }
  fun readProvider(provider: String): String = synchronized(LOCK) {
    val (name, alias) = slot(provider)
    (read(name, alias, 4124, aad(provider)) ?: "").also { require(it.isEmpty() || valid(it)) }
  }
  fun saveProvider(provider: String, value: String) = synchronized(LOCK) {
    require(valid(value)) { "Invalid provider key" }
    val (name, alias) = slot(provider)
    write(name, alias, value, 4124, aad(provider))
  }
  fun forgetProvider(provider: String) = synchronized(LOCK) {
    val (name, alias) = slot(provider)
    file(name).delete()
    KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
  }
  fun forgetAllProviders() = synchronized(LOCK) {
    var first:Exception?=null
    for(provider in arrayOf("openai","anthropic")) {
      try { forgetProvider(provider) } catch(error:Exception) { if(first==null) first=error else first.addSuppressed(error) }
    }
    if(first!=null) throw first
  }
  fun clear() = synchronized(LOCK) {
    var first:Exception?=null
    try { file(STATE_FILE).delete();KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(STATE_ALIAS) } } catch(error:Exception) { first=error }
    try { forgetAllProviders() } catch(error:Exception) { if(first==null) first=error else first.addSuppressed(error) }
    if(first!=null) throw first
  }
  private fun valid(value: String) = value.length in 1..4000 && value.all { it.code in 33..126 }
}

/** Pure envelope codec: compatibility can be tested without Android Keystore or real data. */
internal object PrivateEnvelope {
  fun decrypt(key:SecretKey,bytes:ByteArray,aad:ByteArray?,limit:Int):String {
    if(bytes.size<29||bytes.size>limit) throw IOException("Invalid encrypted envelope")
    val cipher=Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(0,12)))
    if(aad!=null) cipher.updateAAD(aad)
    return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),UTF_8)
  }
  fun encrypt(key:SecretKey,value:String,aad:ByteArray?,limit:Int):ByteArray {
    val clear=value.toByteArray(UTF_8)
    if(clear.isEmpty()||clear.size>limit-28) throw IOException("Invalid private payload size")
    val cipher=Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE,key)
    if(aad!=null) cipher.updateAAD(aad)
    return cipher.iv+cipher.doFinal(clear)
  }
}
