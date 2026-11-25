package com.hamed.smartdelivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DestinationLinkParserTest {
    @Test fun parsesCoordinatePair() {
        val destination = DestinationLinkParser.parse("24.7136,46.6753")
        assertEquals(24.7136, destination?.latitude ?: 0.0, 0.00001)
        assertEquals(46.6753, destination?.longitude ?: 0.0, 0.00001)
    }

    @Test fun parsesGoogleMapsQueryCoordinates() {
        val destination = DestinationLinkParser.parse("https://maps.google.com/?q=24.7%2C46.6")
        assertEquals(24.7, destination?.latitude ?: 0.0, 0.00001)
        assertEquals(46.6, destination?.longitude ?: 0.0, 0.00001)
    }

    @Test fun rejectsOutOfRangeCoordinates() {
        assertNull(DestinationLinkParser.parse("120.0,220.0"))
    }
}
