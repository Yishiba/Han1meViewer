package com.yenaly.han1meviewer.logic.commerce

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MembershipCredentialStoreDeviceTest {
    @Test fun encryptedSessionSurvivesStoreRecreationWithoutPlaintext() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = File(target.cacheDir, "membership-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = isolated
        }
        try {
            val first = MembershipCredentialStore(context)
            val deviceId = first.deviceId()
            val ticket = "t".repeat(43)
            val token = "s".repeat(43)
            first.saveLoginTicket(ticket)
            assertEquals(ticket, MembershipCredentialStore(context).load().loginTicket)
            first.saveSessionToken(token)
            val reloaded = MembershipCredentialStore(context)
            assertEquals(deviceId, reloaded.deviceId())
            assertEquals(token, reloaded.load().sessionToken)
            assertNull(reloaded.load().loginTicket)
            val ciphertext = File(isolated, "membership-auth-state.bin").readBytes()
            assertFalse(ciphertext.toString(Charsets.UTF_8).contains(token))
            assertTrue(ciphertext.size > token.length)
            reloaded.clear()
            assertNull(reloaded.load().sessionToken)
        } finally {
            isolated.deleteRecursively()
        }
    }
}
