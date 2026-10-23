package com.mgba.bingbaihanji.emulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mgba.bingbaihanji.core.emulation.EmulatorError
import com.mgba.bingbaihanji.core.emulation.EmulatorException
import com.mgba.bingbaihanji.core.emulation.EmulatorState
import com.mgba.bingbaihanji.platform.android.emulation.JniEmulatorCore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeEmulatorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val coreLibraryPath: String
        get() {
            val extracted =
                File(context.applicationInfo.nativeLibraryDir, "libmgba_libretro.so")
            // With legacy packaging disabled the library is loaded from the APK
            // and the linker resolves it by soname.
            return if (extracted.exists()) extracted.absolutePath else "libmgba_libretro.so"
        }

    @Test
    fun initializesCoreAndReadsVersion() {
        JniEmulatorCore(coreLibraryPath, context.filesDir.absolutePath).use { session ->
            session.initialize().getOrThrow()

            assertEquals(EmulatorState.INITIALIZED, session.state)
            assertNotNull(session.coreName)
            assertNotNull(session.coreVersion)
            assertTrue(session.coreVersion!!.isNotEmpty())
            assertEquals(2, session.pixelFormat) // RETRO_PIXEL_FORMAT_RGB565

            session.shutdown().getOrThrow()
            assertEquals(EmulatorState.UNINITIALIZED, session.state)
        }
    }

    @Test
    fun shutdownWithoutInitializeIsIdempotent() {
        JniEmulatorCore(coreLibraryPath, context.filesDir.absolutePath).use { session ->
            session.shutdown().getOrThrow()
            session.shutdown().getOrThrow()
            assertEquals(EmulatorState.UNINITIALIZED, session.state)
        }
    }

    @Test
    fun initializeTwiceIsRejectedWithoutCorruptingSession() {
        JniEmulatorCore(coreLibraryPath, context.filesDir.absolutePath).use { session ->
            session.initialize().getOrThrow()

            val second = session.initialize()
            assertTrue(second.isFailure)
            assertEquals(
                EmulatorError.INVALID_STATE,
                (second.exceptionOrNull() as EmulatorException).error,
            )
            assertEquals(EmulatorState.INITIALIZED, session.state)

            session.shutdown().getOrThrow()
        }
    }

    @Test
    fun missingRomFailsWithoutCorruptingSession() {
        JniEmulatorCore(coreLibraryPath, context.filesDir.absolutePath).use { session ->
            session.initialize().getOrThrow()
            val missing = File(context.filesDir, "missing-${System.nanoTime()}.gba")

            val result = session.loadGame(missing.absolutePath)
            assertTrue(result.isFailure)
            assertEquals(
                EmulatorError.CORE_LOAD_GAME,
                (result.exceptionOrNull() as EmulatorException).error,
            )
            assertEquals(EmulatorState.INITIALIZED, session.state)

            session.shutdown().getOrThrow()
        }
    }
}
