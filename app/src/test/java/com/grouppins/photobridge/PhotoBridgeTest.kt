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
    fun aSingleZeroDenominatorRejectsTheWholeCoordinate() {
        // PWA の readGpsCoord は分母 0 の成分を 0 として計算を続けるが、それは最大 30 分角
        // (約 55km) ずれた座標を地図に置くことになるため、橋渡し側は棄却する側で揃える
        assertNull(PhotoBridge.coordinatesOf("35/1,30/0,0/1", "N", "139/1,45/1,0/1", "E"))
        assertNull(PhotoBridge.coordinatesOf("35/1,30/1,0/1", "N", "139/1,45/0,0/1", "E"))
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
    fun aSinglePhotoIsSentAsAOneEntryBatch() {
        // 親 (map.tsx) は photo_batch を ";" と "," で分割して読む。1 枚でも同じ形で渡す
        assertEquals(
            "35.5,139.75,2024-01-02T03:04:05",
            PhotoBridge.batchParameterOf(
                listOf(PhotoBridge.LocatedPhoto(35.5, 139.75, "2024-01-02T03:04:05")),
            ),
        )
    }

    @Test
    fun aPhotoWithoutATimeKeepsTheTrailingSeparator() {
        // "35.5,139.75," → 親の split(",") が timeS = "" を得て takenAt を null にする。
        // 区切りを落とすと 2 要素になり、時刻の位置がずれる
        assertEquals(
            "35.5,139.75,",
            PhotoBridge.batchParameterOf(listOf(PhotoBridge.LocatedPhoto(35.5, 139.75, null))),
        )
    }

    @Test
    fun multiplePhotosAreJoinedWithSemicolons() {
        assertEquals(
            "35.5,139.75,2024-01-02T03:04:05;-33.9,-70.6,",
            PhotoBridge.batchParameterOf(
                listOf(
                    PhotoBridge.LocatedPhoto(35.5, 139.75, "2024-01-02T03:04:05"),
                    PhotoBridge.LocatedPhoto(-33.9, -70.6, null),
                ),
            ),
        )
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

    @Test
    fun datesThatDoNotExistOnTheCalendarAreRejected() {
        // 親は new Date("2024-02-31T00:00:00") を 3/2 として有効値にしてしまうため、
        // 存在しない日付を photo_time / photo_batch に載せてはいけない
        assertNull(PhotoBridge.isoTimeOf("2024:02:31 00:30:00"))
        assertNull(PhotoBridge.isoTimeOf("2024:04:31 00:30:00"))
        assertNull(PhotoBridge.isoTimeOf("2023:02:29 00:30:00"))
        assertNull(PhotoBridge.isoTimeOf("2024:01:00 00:30:00"))
    }

    @Test
    fun leapDayIsAccepted() {
        assertEquals("2024-02-29T12:00:00", PhotoBridge.isoTimeOf("2024:02:29 12:00:00"))
    }
}
