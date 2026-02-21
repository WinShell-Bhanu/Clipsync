package com.bunty.clipsync

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*


@RunWith(AndroidJUnit4::class)


// Purpose: Class that models example instrumented test behavior in this module.
// Responsibilities: Encapsulates example instrumented test behavior for this feature area.
// Usage: Read this type first to understand the high-level control flow in this file.
class ExampleInstrumentedTest {
    @Test


    // Purpose: Implements the use app context operation for this feature.
    // Parameters: No parameters.
    // Returns: Unit unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    fun useAppContext() {

        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.bunty.clipsync", appContext.packageName)
    }
}