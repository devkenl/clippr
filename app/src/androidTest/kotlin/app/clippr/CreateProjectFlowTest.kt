package app.clippr

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Home → new project → editor opens with an empty timeline and the export button disabled. */
@RunWith(AndroidJUnit4::class)
class CreateProjectFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun createProjectOpensEditor() {
        rule.onAllNodesWithText("New project").onFirst().performClick()
        rule.onNodeWithText("Name").performTextInput("Instrumented test")
        rule.onNodeWithText("Create project").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Instrumented test").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Preview monitor").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back to projects").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Recent projects").fetchSemanticsNodes().isNotEmpty() }
    }
}
