package org.monzobot.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets.UTF_8
import javax.net.ssl.HttpsURLConnection

/** Credentials go only to fixed supported origins. Redirects and retries are forbidden. */
internal object PrivateHttp {
  const val PROVIDER_BODY_LIMIT=256000
  const val PROVIDER_RESPONSE_LIMIT=128000
  const val GOOGLE_RESPONSE_LIMIT=10*1024*1024
  data class ProviderRequest(val endpoint:String,val headers:Map<String,String>)
  fun providerTarget(provider:String,key:String):ProviderRequest {
    require(validCredential(key)) { "Invalid selected-provider credential" }
    return when(provider) {
      "openai" -> ProviderRequest("https://api.openai.com/v1/chat/completions",mapOf("Authorization" to "Bearer $key"))
      "anthropic" -> ProviderRequest("https://api.anthropic.com/v1/messages",mapOf("x-api-key" to key,"anthropic-version" to "2026-10-21"))
      else -> throw IllegalArgumentException("Unsupported provider")
    }
  }
  fun googleTarget(url:String):String {
    require(url.length<=8192)
    val uri=URI(url)
    require(uri.scheme=="https"&&uri.rawUserInfo==null&&uri.port in listOf(-1,443)&&uri.rawFragment==null&&uri.normalize().rawPath==uri.rawPath)
    val allowed=when(uri.host) {
      "www.googleapis.com" -> uri.rawPath=="/drive/v3/files"
      "sheets.googleapis.com" -> Regex("/v4/spreadsheets/[A-Za-z0-9_-]{15,200}(?:/values/[^?#]{1,2000})?").matches(uri.rawPath ?: "")
      else -> false
    }
    require(allowed) { "Unsupported Google read-only endpoint" }
    return uri.toASCIIString()
  }
  fun provider(provider:String,key:String,body:String,connect:(String)->HttpsURLConnection={ URL(it).openConnection() as HttpsURLConnection }):String {
    val request=providerTarget(provider,key)
    val bytes=body.toByteArray(UTF_8)
    require(bytes.size in 1..PROVIDER_BODY_LIMIT)
    return exchange(request.endpoint,"POST",request.headers,bytes,PROVIDER_RESPONSE_LIMIT,connect)
  }
  fun google(url:String,token:String,connect:(String)->HttpsURLConnection={ URL(it).openConnection() as HttpsURLConnection }):String {
    require(validCredential(token)) { "Invalid Google credential" }
    return exchange(googleTarget(url),"GET",mapOf("Authorization" to "Bearer $token"),null,GOOGLE_RESPONSE_LIMIT,connect)
  }
  private fun validCredential(value:String)=value.length in 1..4000&&value.all { it.code in 33..126 }
  private fun exchange(url:String,method:String,headers:Map<String,String>,body:ByteArray?,limit:Int,connect:(String)->HttpsURLConnection):String {
    val connection=connect(url)
    try {
      connection.instanceFollowRedirects=false
      connection.requestMethod=method;connection.connectTimeout=15000;connection.readTimeout=30000
      connection.setRequestProperty("Accept","application/json")
      for((key,value) in headers) connection.setRequestProperty(key,value)
      if(body!=null) {
        connection.setRequestProperty("Content-Type","application/json; charset=utf-8")
        connection.doOutput=true;connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
      }
      if(connection.responseCode !in 200..299) throw IOException("The service request was rejected; no automatic retry was made")
      val declared=connection.contentLengthLong
      if(declared>limit) throw IOException("The service response exceeded the supported limit")
      val output=ByteArrayOutputStream()
      connection.inputStream.use { input ->
        val buffer=ByteArray(8192)
        while(true) {
          val count=input.read(buffer);if(count<0) break
          if(output.size()+count>limit) throw IOException("The service response exceeded the supported limit")
          output.write(buffer,0,count)
        }
      }
      return String(output.toByteArray(),UTF_8)
    } finally { connection.disconnect() }
  }
}
