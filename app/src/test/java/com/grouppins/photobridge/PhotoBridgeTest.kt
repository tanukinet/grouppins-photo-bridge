package com.grouppins.photobridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class PhotoBridgeTest {

    private fun coordinates(latRef: String?, lngRef: String?): DoubleArray? =
        PhotoBridge.coordinatesOf("35/1,30/1,0/1", latRef, "139/1,45/1,0/1", lngRef)

    @Test
    fun uppercaseRefsAreRead() {
        val gps = coordinates("N", "E")!!
        assertEquals(35.5, gps[0], 0.0)
        assertEquals(139.75, gps[1], 0.0)
    }

    @Test
    fun lowercaseRefsAreRead() {
        val gps = coordinates("n", "e")!!
        assertEquals(35.5, gps[0], 0.0)
        assertEquals(139.75, gps[1], 0.0)
    }

    @Test
    fun missingRefsCountAsNorthEast() {
        val gps = coordinates(null, null)!!
        assertEquals(35.5, gps[0], 0.0)
        assertEquals(139.75, gps[1], 0.0)
    }

    @Test
    fun southAndWestAreNegated() {
        val gps = coordinates("s", "w")!!
        assertEquals(-35.5, gps[0], 0.0)
        assertEquals(-139.75, gps[1], 0.0)
    }

    @Test
    fun zeroDenominatorIsRejected() {
        assertNull(PhotoBridge.coordinatesOf("0/0,0/0,0/0", "N", "0/0,0/0,0/0", "E"))
    }

    @Test
    fun outOfRangeIsRejected() {
        assertNull(PhotoBridge.coordinatesOf("200/1,0/1,0/1", "N", "139/1,0/1,0/1", "E"))
        assertNull(PhotoBridge.coordinatesOf("35/1,0/1,0/1", "N", "200/1,0/1,0/1", "E"))
    }

    @Test
    fun nullIslandIsRejected() {
        assertNull(PhotoBridge.coordinatesOf("0/1,0/1,0/1", "N", "0/1,0/1,0/1", "E"))
    }

    @Test
    fun missingOrMalformedValuesAreRejected() {
        assertNull(PhotoBridge.coordinatesOf(null, "N", "139/1,45/1,0/1", "E"))
        assertNull(PhotoBridge.coordinatesOf("35/1,30/1,0/1", "N", null, "E"))
        assertNull(PhotoBridge.coordinatesOf("35,30,0", "N", "139/1,45/1,0/1", "E"))
        assertNull(PhotoBridge.coordinatesOf("35/1,30/1,0/1,0/1", "N", "139/1,45/1,0/1", "E"))
    }

    @Test
    fun exifDateTimeKeepsTheWallClockValue() {
        assertEquals("2024-01-02T03:04:05", PhotoBridge.isoTimeOf("2024:01:02 03:04:05"))
    }

    @Test
    fun daylightSavingGapIsNotShifted() {
        val deviceZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertEquals("2024-03-10T02:30:00", PhotoBridge.isoTimeOf("2024:03:10 02:30:00"))
        } finally {
            TimeZone.setDefault(deviceZone)
        }
    }

    @Test
    fun blankAndInvalidExifDateTimesAreRejected() {
        assertNull(PhotoBridge.isoTimeOf(null))
        assertNull(PhotoBridge.isoTimeOf("    :  :     :  :  "))
        assertNull(PhotoBridge.isoTimeOf("2024:13:01 00:00:00"))
        assertNull(PhotoBridge.isoTimeOf("2024:01:32 00:00:00"))
        assertNull(PhotoBridge.isoTimeOf("2024:01:02 24:00:00"))
        assertNull(PhotoBridge.isoTimeOf("2024:01:02 00:60:00"))
    }
}
