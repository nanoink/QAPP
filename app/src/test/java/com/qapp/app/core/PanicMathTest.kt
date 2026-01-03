package com.qapp.app.core

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PanicMathTest {

    @Test
    fun distanceKmReturnsZeroForSamePoint() {
        val distance = PanicMath.distanceKm(0.0, 0.0, 0.0, 0.0)
        assertThat(distance).isWithin(0.0001).of(0.0)
    }

    @Test
    fun distanceKmMatchesExpected() {
        val distance = PanicMath.distanceKm(0.0, 0.0, 0.0, 1.0)
        assertThat(distance).isWithin(0.5).of(111.19)
    }

    @Test
    fun priorityIsBasedOnDistance() {
        assertThat(PanicMath.priorityForDistance(0.3)).isEqualTo(PanicEventPriority.CRITICAL)
        assertThat(PanicMath.priorityForDistance(1.5)).isEqualTo(PanicEventPriority.HIGH)
        assertThat(PanicMath.priorityForDistance(4.0)).isEqualTo(PanicEventPriority.NORMAL)
        assertThat(PanicMath.priorityForDistance(6.0)).isEqualTo(PanicEventPriority.LOW)
    }
}
