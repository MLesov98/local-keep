package com.localkeep.feature.settings

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.ui.theme.LocalKeepTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The invisible-items gate only names the routes it actually offers.
 *
 * S1-F-30 (functional pass, 2026-10-15): after a panic lock the biometric button is withheld
 * ([HiddenItemsViewModel] reads `isBiometricSuspendedByPanic`) but the sentence above the password
 * well still said "biometric confirmation or your vault password", inviting the one route that
 * had just been taken away. The same sentence was wrong on a phone with no strong sensor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HiddenGateBodyMatchesTheRoutesOnOfferTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun text(res: Int) = app.getString(res)

    private fun show(biometricAvailable: Boolean) {
        compose.setContent {
            LocalKeepTheme {
                AuthGate(
                    biometricAvailable = biometricAvailable,
                    working = false,
                    onBiometric = {},
                    onPassword = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    @Test
    fun `without a biometric button the gate asks for the password alone`() {
        show(biometricAvailable = false)

        compose.onNodeWithText(text(R.string.settings_hidden_gate_biometric)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_hidden_gate_body_password_only)).assertExists()
        compose.onNodeWithText(text(R.string.settings_hidden_gate_body)).assertDoesNotExist()
    }

    @Test
    fun `control - with the biometric button the gate names both routes`() {
        show(biometricAvailable = true)

        compose.onNodeWithText(text(R.string.settings_hidden_gate_biometric)).assertExists()
        compose.onNodeWithText(text(R.string.settings_hidden_gate_body)).assertExists()
        compose.onNodeWithText(text(R.string.settings_hidden_gate_body_password_only)).assertDoesNotExist()
    }
}
