package dev.thris.pattalu.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelsTest {
    @Test fun formatsDurations() { assertEquals("0:00", formatDuration(-1)); assertEquals("3:07", formatDuration(187_000)) }
    @Test fun safeFilenameUsesOnlyId() { assertEquals("Ab_cd-123.m4a", safeAudioName("Ab_cd-123")); assertThrows(IllegalArgumentException::class.java) { safeAudioName("../../bad") } }
}
