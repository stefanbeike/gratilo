package com.gratilo.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.gratilo.app.data.*
import com.gratilo.app.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class AuthenticationUiTest {
    @get:Rule val compose = createComposeRule()
    private class Offline: Repository {
        override val authenticated = MutableStateFlow(false)
        override suspend fun request(path: String, body: JsonObject?, method: String): JsonElement = throw ApiFailure(0, "NETWORK", "Der Server ist nicht erreichbar.")
        override suspend fun login(email: String, password: String) { throw ApiFailure(0, "NETWORK", "Der Server ist nicht erreichbar.") }
        override fun logout() { authenticated.value = false }
        override fun saved(key: String): String? = null
        override fun save(key: String, value: String?) { }
    }
    @Test fun loginDisplaysNetworkFailureAndAllowsRetry() {
        val model = GratiloViewModel(Offline())
        compose.setContent { GratiloTheme { GratiloApp(model, MutableStateFlow(null)) {} } }
        compose.onNodeWithText("E-Mail-Adresse").performTextInput("alex@example.test")
        compose.onNodeWithText("Passwort").performTextInput("Abcdefg!")
        compose.onNodeWithText("Anmelden").performScrollTo().performClick()
        compose.onNodeWithText("Der Server ist nicht erreichbar.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Anmelden").assertIsEnabled()
    }
}
