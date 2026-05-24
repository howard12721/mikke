package jp.xhw.mikke.services.guess.application

import jp.xhw.mikke.services.guess.model.GeoPoint
import kotlin.math.*

private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val PERFECT_DISTANCE_METERS = 25.0
private const val ZERO_SCORE_DISTANCE_METERS = 50_000.0
private const val MAX_SCORE = 1000
private const val MIN_SCORE = 0

object GeoDistanceCalculator {
    fun haversineMeters(
        from: GeoPoint,
        to: GeoPoint,
    ): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLat = Math.toRadians(to.latitude - from.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)

        val a =
            sin(deltaLat / 2).pow(2) +
                cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }
}

object GuessScoreCalculator {
    private val logDenominator = ln(ZERO_SCORE_DISTANCE_METERS / PERFECT_DISTANCE_METERS)

    fun calculateScore(distanceMeters: Double): Int {
        val effectiveDistance = max(distanceMeters, PERFECT_DISTANCE_METERS)
        val rawScore = MAX_SCORE * (1 - ln(effectiveDistance / PERFECT_DISTANCE_METERS) / logDenominator)
        return round(rawScore.coerceIn(MIN_SCORE.toDouble(), MAX_SCORE.toDouble())).toInt()
    }
}
