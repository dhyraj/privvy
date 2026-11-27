package com.privvy.app.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileSpaceRepositoryTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetMetadata() {
        File(context.filesDir, "keystore_meta/spaces.json").delete()
    }

    @Test
    fun pinlessSpaceStaysPinlessAfterReload() {
        val first = FileSpaceRepository(context).listSpaces()
        assertNull(first.single().pinHashHex)
        val reloaded = FileSpaceRepository(context).listSpaces().single()
        assertNull(reloaded.pinHashHex)
        assertNull(reloaded.pinSaltHex)
    }

    @Test
    fun pinSurvivesReload() {
        FileSpaceRepository(context).listSpaces()
        FileSpaceRepository(context).setPin(0, "1234")
        val reloaded = FileSpaceRepository(context)
        assertTrue(reloaded.verifyPin(0, "1234"))
        assertEquals(0, reloaded.findSpaceByPin("1234")?.id)
    }

    @Test
    fun resetReplacesAllSpacesWithPinlessPersonalSpace() {
        val repository = FileSpaceRepository(context)
        repository.listSpaces()
        repository.setPin(0, "1234")
        repository.createSpace("Work", "5678".toCharArray())

        val personal = repository.resetToDefault()

        assertEquals(0, personal.id)
        assertEquals("Personal", personal.name)
        assertNull(personal.pinHashHex)
        assertEquals(listOf(personal), FileSpaceRepository(context).listSpaces())
    }
}
