package com.gratilo.app

import androidx.test.platform.app.InstrumentationRegistry
import com.gratilo.app.data.SecureSessionStore
import org.junit.Assert.*
import org.junit.Test

class SecureSessionStoreTest {
    @Test fun tokenSurvivesStoreRecreationWithoutPlaintextStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = SecureSessionStore(context)
        try {
            first.write("test-token", "sensitive-jwt")
            val ciphertext = context.getSharedPreferences("gratilo.secure", 0).getString("test-token", null)
            assertNotNull(ciphertext); assertFalse(ciphertext!!.contains("sensitive-jwt"))
            assertEquals("sensitive-jwt", SecureSessionStore(context).read("test-token"))
            first.write("test-token", null); assertNull(first.read("test-token"))
        } finally { first.write("test-token", null) }
    }
}
