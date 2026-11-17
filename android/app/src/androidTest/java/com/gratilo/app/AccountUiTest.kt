package com.gratilo.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gratilo.app.data.*
import com.gratilo.app.ui.*
import org.junit.Rule
import org.junit.Test

class AccountUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun emptyHistoryHasReadableEmptyState() {
        compose.setContent { GratiloTheme { Transactions(emptyList()) } }
        compose.onNodeWithText("Keine Transaktionen vorhanden.").assertIsDisplayed()
        compose.onNodeWithText("Referenz oder Beschreibung suchen").performTextInput("Alex")
        compose.onNodeWithText("Keine Transaktionen vorhanden.").assertIsDisplayed()
    }
    @Test fun passwordFieldCanBeRevealedAccessibly() {
        compose.setContent { GratiloTheme { Field("Passwort", "secret", {}, password = true) } }
        compose.onNodeWithContentDescription("Passwort anzeigen").performClick()
        compose.onNodeWithContentDescription("Passwort verbergen").assertExists()
    }
}
