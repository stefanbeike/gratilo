package com.gratilo.app

import com.gratilo.app.data.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*

class MemoryStore: SessionStore {
    val values = mutableMapOf<String, String>()
    override fun read(key: String) = values[key]
    override fun write(key: String, value: String?) { if(value == null) values.remove(key) else values[key] = value }
}
class GratiloRepositoryTest {
    private lateinit var users: MockWebServer
    private lateinit var payments: MockWebServer
    private lateinit var store: MemoryStore
    private lateinit var repository: GratiloRepository
    @Before fun setup() {
        users = MockWebServer().apply { start() }; payments = MockWebServer().apply { start() }
        store = MemoryStore()
        repository = GratiloRepository(store, users.url("/").toString(), payments.url("/").toString())
    }
    @After fun close() { users.shutdown(); payments.shutdown() }
    @Test fun loginVerifiesProfileAndRoutesPaymentWithBearer() = runTest {
        users.enqueue(MockResponse().setBody("""{"accessToken":"jwt","email":"a@example.test","userId":"user"}"""))
        users.enqueue(MockResponse().setBody("""{"userId":"user"}"""))
        payments.enqueue(MockResponse().setBody("[]"))
        repository.login(" a@example.test ", "secret")
        assertTrue(repository.authenticated.value)
        assertEquals("jwt", store.read("token"))
        val login = users.takeRequest()
        assertEquals("/user-service/auth/login", login.path)
        assertFalse(login.body.readUtf8().contains(" a@example.test "))
        assertEquals("Bearer jwt", users.takeRequest().getHeader("Authorization"))
        repository.request("/payment-service/transactions")
        assertEquals("Bearer jwt", payments.takeRequest().getHeader("Authorization"))
    }
    @Test fun unauthorizedClearsStoredSession() = runTest {
        store.write("token", "expired")
        val restored = GratiloRepository(store, users.url("/").toString(), payments.url("/").toString())
        users.enqueue(MockResponse().setResponseCode(401))
        try { restored.request("/user-service/users/me"); fail("401 expected") } catch (failure: ApiFailure) { assertEquals(401, failure.status) }
        assertFalse(restored.authenticated.value); assertNull(store.read("token"))
    }
    @Test fun businessErrorKeepsSessionAndDiagnostic() = runTest {
        store.write("token", "jwt")
        repository = GratiloRepository(store, users.url("/").toString(), payments.url("/").toString())
        payments.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"POOL_NOT_READY","message":"Pool gesperrt"}"""))
        try { repository.request("/payment-service/tips/pools/pool/checkout", json(), "POST"); fail() }
        catch (failure: ApiFailure) { assertEquals("POOL_NOT_READY", failure.code); assertEquals("Pool gesperrt", failure.message); assertTrue(repository.authenticated.value) }
    }
    @Test fun registrationAcceptsEmptyCreatedResponse() = runTest {
        users.enqueue(MockResponse().setResponseCode(201))
        assertTrue(repository.request("/user-service/users/register", json(), "POST").isJsonObject)
    }
    @Test fun invalidCredentialsNeverPersistAToken() = runTest {
        users.enqueue(MockResponse().setResponseCode(401))
        try { repository.login("a@example.test", "wrong"); fail() } catch (failure: ApiFailure) { assertEquals(401, failure.status) }
        assertFalse(repository.authenticated.value); assertNull(store.read("token"))
    }
    @Test fun successfulLoginWithoutAccessibleProfileIsNotPersisted() = runTest {
        users.enqueue(MockResponse().setBody("""{"accessToken":"jwt"}"""))
        users.enqueue(MockResponse().setResponseCode(503).setBody("""{"message":"Offline"}"""))
        try { repository.login("a@example.test", "password"); fail() } catch (_: ApiFailure) { }
        assertFalse(repository.authenticated.value); assertNull(store.read("token"))
    }
    @Test fun malformedJsonBecomesAReadableFailure() = runTest {
        users.enqueue(MockResponse().setBody("{"))
        try { repository.request("/user-service/users/me"); fail() }
        catch (failure: ApiFailure) { assertEquals("INVALID_RESPONSE", failure.code) }
    }
    @Test fun rejectsExternalUrlBeforeSendingCredentials() = runTest {
        try { repository.request("https://example.test/steal"); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(0, users.requestCount); assertEquals(0, payments.requestCount)
    }
    @Test fun redirectsAreNotFollowed() = runTest {
        users.enqueue(MockResponse().setResponseCode(302).addHeader("Location", payments.url("/steal")))
        try { repository.request("/user-service/users/me"); fail() } catch (failure: ApiFailure) { assertEquals(302, failure.status) }
        assertEquals(0, payments.requestCount)
    }
}
