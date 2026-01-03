package com.qapp.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qapp.app.ui.home.HomeScreen
import com.qapp.app.ui.theme.QAPPTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun homeScreen_showsGreeting() {
        composeTestRule.setContent {
            QAPPTheme {
                HomeScreen(onOpenSettings = {})
            }
        }

        composeTestRule.onNodeWithText("Bem-vindo ao QAPP").assertIsDisplayed()
        composeTestRule.onNodeWithText("Substitua este texto pela descrição inicial do produto.").assertIsDisplayed()
    }
}
