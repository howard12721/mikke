package jp.xhw.mikke.services.guess

import jp.xhw.mikke.guess.v1.*
import jp.xhw.mikke.platform.time.toProtoTimestamp
import jp.xhw.mikke.common.v1.GeoPoint as CommonGeoPoint
import jp.xhw.mikke.services.guess.model.GeoPoint as DomainGeoPoint
import jp.xhw.mikke.services.guess.model.Guess as DomainGuess
import jp.xhw.mikke.services.guess.model.GuessRankingMetric as DomainGuessRankingMetric
import jp.xhw.mikke.services.guess.model.GuessResult as DomainGuessResult
import jp.xhw.mikke.services.guess.model.GuessUserRankingEntry as DomainGuessUserRankingEntry
import jp.xhw.mikke.services.guess.model.PostAuthorRankingEntry as DomainPostAuthorRankingEntry
import jp.xhw.mikke.services.guess.model.PostGuessStats as DomainPostGuessStats
import jp.xhw.mikke.services.guess.model.UserScoreSummary as DomainUserScoreSummary

fun DomainGuess.toProto(): Guess =
    Guess
        .newBuilder()
        .setId(id.value.toString())
        .setPostId(postId.value.toString())
        .setPostAuthorUserId(postAuthorUserId.value.toString())
        .setUserId(userId.value.toString())
        .setGuessedPoint(guessedPoint.toProto())
        .setDistanceMeters(distanceMeters)
        .setScore(score)
        .setCreatedAt(createdAt.toProtoTimestamp())
        .build()

fun DomainGuessResult.toProto(): GuessResult =
    GuessResult
        .newBuilder()
        .setGuess(guess.toProto())
        .setCorrectPoint(guess.correctPoint.toProto())
        .setDistanceMeters(guess.distanceMeters)
        .setScore(guess.score)
        .build()

fun DomainPostGuessStats.toProto(): PostGuessStats =
    PostGuessStats
        .newBuilder()
        .setPostId(postId.value.toString())
        .setGuessCount(guessCount)
        .setAverageDistanceMeters(averageDistanceMeters)
        .setAverageScore(averageScore)
        .also { builder ->
            bestDistanceMeters?.let(builder::setBestDistanceMeters)
        }.build()

fun DomainUserScoreSummary.toProto(): UserScoreSummary =
    UserScoreSummary
        .newBuilder()
        .setUserId(userId.value.toString())
        .setTotalScore(totalScore)
        .setAverageScore(averageScore)
        .setGuessCount(guessCount)
        .also { builder ->
            bestDistanceMeters?.let(builder::setBestDistanceMeters)
        }.build()

fun DomainPostAuthorRankingEntry.toProto(): PostUserRankingEntry =
    PostUserRankingEntry
        .newBuilder()
        .setUserId(userId.value.toString())
        .setRank(rank)
        .setPostCount(postCount)
        .build()

fun DomainGuessUserRankingEntry.toProto(): GuessUserRankingEntry =
    GuessUserRankingEntry
        .newBuilder()
        .setUserId(userId.value.toString())
        .setRank(rank)
        .setTotalScore(totalScore)
        .setAverageScore(averageScore)
        .setGuessCount(guessCount)
        .also { builder ->
            bestDistanceMeters?.let(builder::setBestDistanceMeters)
        }.build()

fun DomainGeoPoint.toProto(): CommonGeoPoint =
    CommonGeoPoint
        .newBuilder()
        .setLatitude(latitude)
        .setLongitude(longitude)
        .build()

fun DomainGuessRankingMetric.toProto(): GuessRankingMetric =
    when (this) {
        DomainGuessRankingMetric.GUESS_COUNT -> GuessRankingMetric.GUESS_RANKING_METRIC_GUESS_COUNT
        DomainGuessRankingMetric.TOTAL_SCORE -> GuessRankingMetric.GUESS_RANKING_METRIC_TOTAL_SCORE
        DomainGuessRankingMetric.AVERAGE_SCORE -> GuessRankingMetric.GUESS_RANKING_METRIC_AVERAGE_SCORE
    }

fun GuessRankingMetric.toDomain(): DomainGuessRankingMetric =
    when (this) {
        GuessRankingMetric.GUESS_RANKING_METRIC_GUESS_COUNT -> {
            DomainGuessRankingMetric.GUESS_COUNT
        }

        GuessRankingMetric.GUESS_RANKING_METRIC_TOTAL_SCORE -> {
            DomainGuessRankingMetric.TOTAL_SCORE
        }

        GuessRankingMetric.GUESS_RANKING_METRIC_AVERAGE_SCORE -> {
            DomainGuessRankingMetric.AVERAGE_SCORE
        }

        else -> {
            throw jp.xhw.mikke.platform.grpc
                .ValidationException("metric is required")
        }
    }

fun CommonGeoPoint.toDomain(): DomainGeoPoint =
    DomainGeoPoint(
        latitude = latitude,
        longitude = longitude,
    )
