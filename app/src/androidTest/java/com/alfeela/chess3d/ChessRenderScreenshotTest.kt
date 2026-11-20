package com.alfeela.chess3d

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChessRenderScreenshotTest {
    @Test
    fun launchGameAndCaptureRenderedBoard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("Main activity launch intent missing", intent)
        intent!!.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        // Match the current home-screen label; the former "ابدأ اللعب" label no longer exists.
        val startButton = device.wait(Until.findObject(By.textContains("العب ضد الكمبيوتر")), 15_000)
        assertNotNull("Current computer-play button did not appear", startButton)
        startButton!!.click()
        device.waitForIdle(2_000)
        Thread.sleep(4_000)

        val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        if (!outputDir.exists()) outputDir.mkdirs()
        val screenshot = File(outputDir, "chess-3d-render.png")
        assertTrue("Screenshot capture failed", device.takeScreenshot(screenshot))
        assertTrue("Screenshot file is empty", screenshot.exists() && screenshot.length() > 10_000)
    }
}
