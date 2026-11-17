package com.gratilo.app

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.gratilo.app.data.*
import com.gratilo.app.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class GratiloViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun reset() { Dispatchers.resetMain() }
    private class FakeRepository: Repository {
        override val authenticated = MutableStateFlow(false)
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<String>()
        val store = mutableMapOf<String, String>()
        override suspend fun request(path: String, body: JsonObject?, method: String): JsonElement {
            calls += path; gate?.await(); failure?.let { throw it }
            return when (path) {
                "/user-service/users/me" -> json("userId" to "user", "email" to "a@example.test")
                "/user-service/features" -> json("tipPoolsEnabled" to false)
                else -> json()
            }
        }
        override suspend fun login(email: String, password: String) { failure?.let { throw it }; authenticated.value = true }
        override fun logout() { authenticated.value = false }
        override fun saved(key: String) = store[key]
        override fun save(key: String, value: String?) { if(value == null) store.remove(key) else store[key] = value }
    }
    @Test fun loginLoadsProfileAndExposesError() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = GratiloViewModel(repo)
        runCurrent(); vm.login("a@example.test", "password"); assertTrue(vm.state.value.busy)
        advanceUntilIdle(); assertEquals("user", vm.state.value.profile?.string("userId")); assertFalse(vm.state.value.busy)
        repo.failure = ApiFailure(0, "NETWORK", "Offline")
        vm.load("transactions", "/payment-service/transactions"); advanceUntilIdle()
        assertEquals("Offline", vm.state.value.error); assertFalse(vm.state.value.busy)
    }
    @Test fun disabledPoolsNeverFetchProtectedPoolEndpoints() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = GratiloViewModel(repo)
        runCurrent(); vm.loadPools(); advanceUntilIdle()
        assertEquals(false, vm.state.value.poolsEnabled)
        assertEquals(listOf("/user-service/features"), repo.calls)
    }
    @Test fun invalidRegistrationDoesNotCallBackend() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = GratiloViewModel(repo)
        runCurrent(); vm.register(json("password" to "short", "confirmPassword" to "short")); advanceUntilIdle()
        assertTrue(repo.calls.isEmpty()); assertTrue(vm.state.value.error.isNotBlank())
    }
    @Test fun failedPayoutKeepsSameIdForRetry() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = GratiloViewModel(repo)
        runCurrent(); repo.authenticated.value = true; vm.refreshProfile(); advanceUntilIdle()
        repo.failure = ApiFailure(0, "NETWORK", "Offline")
        vm.payout(500); advanceUntilIdle(); val saved = repo.saved("payout:user")
        assertNotNull(saved)
        vm.payout(500); advanceUntilIdle(); assertEquals(saved, repo.saved("payout:user"))
        vm.payout(600); advanceUntilIdle(); assertEquals(saved, repo.saved("payout:user"))
        assertTrue(vm.state.value.error.contains("frühere Anfrage"))
    }
    @Test fun logoutDiscardsRequestsStillInFlight() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = GratiloViewModel(repo)
        runCurrent(); repo.authenticated.value = true; runCurrent()
        repo.gate = CompletableDeferred()
        vm.load("private", "/payment-service/transactions"); runCurrent()
        assertTrue(vm.state.value.busy)
        vm.logout(); repo.gate!!.complete(Unit); advanceUntilIdle()
        assertFalse(vm.authenticated.value); assertNull(vm.state.value.profile)
        assertFalse(vm.state.value.data.containsKey("private")); assertFalse(vm.state.value.busy)
    }
    @Test fun moneyAndPasswordValidationMatchesWebRules() {
        assertEquals(1234L, amountMinor("12,34")); assertNull(amountMinor("1.234")); assertNull(amountMinor("1e2"))
        assertNull(amountMinor("-1")); assertNull(amountMinor("1,001"))
        assertTrue(validPassword("Äbcdefg!")); assertFalse(validPassword("abcdefgh!")); assertFalse(validPassword("Abc def!"))
    }
}
