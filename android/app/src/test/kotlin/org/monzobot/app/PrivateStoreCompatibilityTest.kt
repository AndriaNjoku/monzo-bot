package org.monzobot.app

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec

/** Synthetic keys only. Does not claim real Android Keystore/filesystem/device upgrade coverage. */
class PrivateStoreCompatibilityTest {
  private val key=SecretKeySpec(ByteArray(16){it.toByte()},"AES")
  @Test fun originalFinancialEnvelopeDecryptsAndRoundTrips() {
    val iv=ByteArray(12){(11-it).toByte()}
    val original="{\"version\":1,\"workspaceId\":\"synthetic-only\",\"transactions\":[],\"conversationBudgets\":{\"records\":[]}}"
    val legacy=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key,GCMParameterSpec(128,iv)) }
    val envelope=iv+legacy.doFinal(original.toByteArray())
    assertEquals(original,PrivateEnvelope.decrypt(key,envelope,null,PrivateStore.MAX_STATE))
    assertEquals(original,PrivateEnvelope.decrypt(key,PrivateEnvelope.encrypt(key,original,null,PrivateStore.MAX_STATE),null,PrivateStore.MAX_STATE))
    assertEquals("budget-state.enc",PrivateStore.STATE_FILE);assertEquals("monzobot-state-v1",PrivateStore.STATE_ALIAS)
  }
  @Test fun originalProviderSlotsStayIsolated() {
    assertEquals("companion-provider.enc" to "monzobot-companion-provider-v1",PrivateStore.slot("openai"))
    assertEquals("companion-provider-anthropic.enc" to "monzobot-companion-anthropic-v1",PrivateStore.slot("anthropic"))
    assertNull(PrivateStore.aad("openai"))
    val expected="monzobot-credential-slot-v1\u0000anthropic\u0000companion-provider-anthropic.enc\u0000monzobot-companion-anthropic-v1".toByteArray()
    assertArrayEquals(expected,PrivateStore.aad("anthropic"))
    val envelope=PrivateEnvelope.encrypt(key,"synthetic-placeholder",PrivateStore.aad("anthropic"),4124)
    assertEquals("synthetic-placeholder",PrivateEnvelope.decrypt(key,envelope,expected,4124))
    try { PrivateEnvelope.decrypt(key,envelope,null,4124);fail("Cross-provider decryption must fail") } catch(_:javax.crypto.AEADBadTagException) {}
    try { PrivateStore.slot("other");fail("Unknown providers must fail") } catch(_:IllegalArgumentException) {}
  }
  @Test fun corruptedOversizeAndWrongKeyAreRejected() {
    val envelope=PrivateEnvelope.encrypt(key,"synthetic-only",null,100)
    val wrong=SecretKeySpec(ByteArray(16){(it+1).toByte()},"AES")
    try { PrivateEnvelope.decrypt(wrong,envelope,null,100);fail("Wrong key accepted") } catch(_:javax.crypto.AEADBadTagException) {}
    envelope[envelope.lastIndex]=(envelope.last().toInt() xor 1).toByte()
    try { PrivateEnvelope.decrypt(key,envelope,null,100);fail("Corrupt data accepted") } catch(_:javax.crypto.AEADBadTagException) {}
    try { PrivateEnvelope.decrypt(key,ByteArray(28),null,100);fail("Short envelope accepted") } catch(_:java.io.IOException) {}
    try { PrivateEnvelope.encrypt(key,"x".repeat(73),null,100);fail("Oversize accepted") } catch(_:java.io.IOException) {}
  }
}
