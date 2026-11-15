package org.monzobot.app

import org.junit.Assert.*
import org.junit.Test

/** Synthetic target validation; no network requests or real credentials. */
class PrivateHttpTest {
  @Test fun credentialsHaveFixedProviderOrigins() {
    val openai=PrivateHttp.providerTarget("openai","synthetic-only")
    val anthropic=PrivateHttp.providerTarget("anthropic","synthetic-other")
    assertEquals("https://api.openai.com/v1/chat/completions",openai.endpoint)
    assertEquals(mapOf("Authorization" to "Bearer synthetic-only"),openai.headers)
    assertEquals("https://api.anthropic.com/v1/messages",anthropic.endpoint)
    assertEquals("synthetic-other",anthropic.headers["x-api-key"])
    assertEquals("2026-10-21",anthropic.headers["anthropic-version"])
    for((provider,key) in listOf("other" to "synthetic", "openai" to "bad\nheader", "anthropic" to "")) {
      try { PrivateHttp.providerTarget(provider,key);fail("Invalid provider/credential accepted") } catch(_:IllegalArgumentException) {}
    }
  }
  @Test fun onlyGoogleReadOnlyMetadataAndSheetTargetsAreAllowed() {
    val good=listOf("https://www.googleapis.com/drive/v3/files?q=synthetic", "https://sheets.googleapis.com/v4/spreadsheets/syntheticSheetId12345?fields=properties,sheets", "https://sheets.googleapis.com/v4/spreadsheets/syntheticSheetId12345/values/Transactions%21A1%3AZ50000")
    for(url in good) assertEquals(url,PrivateHttp.googleTarget(url))
    val bad=listOf("http://www.googleapis.com/drive/v3/files", "https://evil.example/drive/v3/files", "https://www.googleapis.com/upload/drive/v3/files", "https://www.googleapis.com/drive/v3/files/one", "https://username@www.googleapis.com/drive/v3/files", "https://www.googleapis.com:444/drive/v3/files", "https://www.googleapis.com/drive/v3/files#secret", "https://sheets.googleapis.com/v4/spreadsheets/short", "https://sheets.googleapis.com/v4/spreadsheets/syntheticSheetId12345/../other")
    for(url in bad) { try { PrivateHttp.googleTarget(url);fail("Unsupported endpoint accepted") } catch(_:IllegalArgumentException) {} }
  }
  private class FakeConnection(private val code:Int=200,private val response:ByteArray="{}".toByteArray(),private val declared:Long=-1):javax.net.ssl.HttpsURLConnection(java.net.URL("https://synthetic.invalid")) {
    val output=java.io.ByteArrayOutputStream()
    var reads=0;var disconnected=false
    override fun getResponseCode()=code
    override fun getContentLengthLong()=declared
    override fun getInputStream():java.io.InputStream { reads++;return java.io.ByteArrayInputStream(response) }
    override fun getOutputStream():java.io.OutputStream=output
    override fun connect() {}
    override fun disconnect() { disconnected=true }
    override fun usingProxy()=false
    override fun getCipherSuite()="synthetic-only"
    override fun getLocalCertificates():Array<java.security.cert.Certificate>?=null
    override fun getServerCertificates():Array<java.security.cert.Certificate> = emptyArray()
  }
  @Test fun realTransportSetsFixedAuthTimeoutsAndDisablesRedirects() {
    val connection=FakeConnection();var calls=0;var endpoint=""
    assertEquals("{}",PrivateHttp.provider("anthropic","synthetic-key","{}") { url -> calls++;endpoint=url;connection })
    assertEquals(1,calls);assertEquals("https://api.anthropic.com/v1/messages",endpoint)
    assertFalse(connection.instanceFollowRedirects);assertEquals("POST",connection.requestMethod)
    assertEquals(15000,connection.connectTimeout);assertEquals(30000,connection.readTimeout)
    assertEquals("synthetic-key",connection.getRequestProperty("x-api-key"))
    assertNull(connection.getRequestProperty("Authorization"));assertEquals("{}",connection.output.toString("UTF-8"));assertTrue(connection.disconnected)
  }
  @Test fun redirectsErrorsAndBoundedBodiesNeverRetryOrReadErrorContent() {
    for(code in arrayOf(301,302,303,307,308,401,429,500)) {
      val connection=FakeConnection(code);var calls=0
      try { PrivateHttp.provider("openai","synthetic-key","{}") { calls++;connection };fail("Service status accepted") } catch(_:java.io.IOException) {}
      assertEquals(1,calls);assertEquals(0,connection.reads);assertFalse(connection.instanceFollowRedirects);assertTrue(connection.disconnected)
    }
    var opened=0
    try { PrivateHttp.provider("openai","synthetic-key","x".repeat(256001)) { opened++;FakeConnection() };fail("Large request accepted") } catch(_:IllegalArgumentException) {}
    assertEquals(0,opened)
    val tooLarge=FakeConnection(response=ByteArray(128001){120})
    try { PrivateHttp.provider("openai","synthetic-key","{}") { tooLarge };fail("Large response accepted") } catch(_:java.io.IOException) {}
    assertTrue(tooLarge.disconnected)
    val declaredLarge=FakeConnection(declared=128001)
    try { PrivateHttp.provider("openai","synthetic-key","{}") { declaredLarge };fail("Large declared response accepted") } catch(_:java.io.IOException) {}
    assertEquals(0,declaredLarge.reads)
  }
  @Test fun googleTransportIsGetOnlyAndBoundedBeforeAllocatingResponse() {
    val connection=FakeConnection();var calls=0
    assertEquals("{}",PrivateHttp.google("https://www.googleapis.com/drive/v3/files","synthetic-token") { calls++;connection })
    assertEquals(1,calls);assertEquals("GET",connection.requestMethod);assertEquals(0,connection.output.size())
    assertEquals("Bearer synthetic-token",connection.getRequestProperty("Authorization"));assertFalse(connection.instanceFollowRedirects)
    val declared=FakeConnection(declared=10L*1024*1024+1)
    try { PrivateHttp.google("https://www.googleapis.com/drive/v3/files","synthetic-token") { declared };fail("Large Google response accepted") } catch(_:java.io.IOException) {}
    assertEquals(0,declared.reads)
  }

}
