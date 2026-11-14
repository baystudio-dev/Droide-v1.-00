package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.FileIconRegistry
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
class FileTypeBrandCoverageUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyRegisteredTypeHasAnAssetThatComposeCanRender() {
        val icons = FileIconRegistry.registeredIconNames.sorted().map { name ->
            val resource = fileTypeResourceForIcon(name)
            assertNotNull("Missing artwork for $name", resource)
            name to requireNotNull(resource)
        }
        compose.setContent { DroideTheme { Column {
            for (row in icons.chunked(10)) Row {
                for ((name, resource) in row) Image(painterResource(resource), name, Modifier.size(24.dp))
            }
        } } }
        for ((name, _) in icons) compose.onNodeWithContentDescription(name).assertIsDisplayed()
    }

    @Test fun commonFileFamiliesRenderThroughTheSharedComponentAtTheExistingSize() {
        val examples = mapOf("main.fs" to "fsharp", "style.scss" to "sass", "CMakeLists.txt" to "cmake",
            "LICENSE" to "license", "notes.txt" to "text", "photo.png" to "image", "song.mp3" to "audio",
            "clip.mp4" to "video", "library.wasm" to "webassembly", "archive.tar.gz" to "zip",
            "rows.csv" to "table", "deck.pptx" to "powerpoint", "analysis.ipynb" to "jupyter",
            "font.woff2" to "font", "server.crt" to "certificate", "app.nix" to "nix", "script.jl" to "julia")
        compose.setContent { DroideTheme { Column {
            for (path in examples.keys) FileTypeBrandIcon(path)
        } } }
        for (icon in examples.values) compose.onNodeWithContentDescription("$icon file")
            .assertIsDisplayed().assertWidthIsEqualTo(17.dp).assertHeightIsEqualTo(17.dp)
    }

    @Test fun unknownFilesUseAFileGlyphWithoutTakingMoreSpace() {
        compose.setContent { DroideTheme { FileIdentityLabel("custom.unregistered") } }
        compose.onNodeWithContentDescription("File", useUnmergedTree = true)
            .assertIsDisplayed().assertWidthIsEqualTo(15.dp).assertHeightIsEqualTo(15.dp)
        compose.onNodeWithText("custom.unregistered").assertIsDisplayed()
        compose.onNodeWithText("UNR").assertDoesNotExist()
    }
}
