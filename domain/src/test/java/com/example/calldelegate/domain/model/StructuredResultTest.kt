package com.example.calldelegate.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StructuredResultTest {
    @Test
    fun ridePickupLocationIsExposedAsOneCanonicalLocation() {
        val result = StructuredResult().merge(
            SceneType.RIDE_HAILING,
            mapOf(
                "location" to "南门",
                "pickupLocation" to "南门",
                "vehicleColor" to "白色",
            ),
        )

        assertThat(result.asEntityMap(SceneType.RIDE_HAILING)).containsEntry("location", "南门")
        assertThat(result.asEntityMap(SceneType.RIDE_HAILING)).doesNotContainKey("pickupLocation")
        assertThat(result.asEntityMap(SceneType.RIDE_HAILING)).containsEntry("vehicleColor", "白色")
    }
}
