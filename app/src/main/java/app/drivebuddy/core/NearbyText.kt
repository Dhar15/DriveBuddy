package app.drivebuddy.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One result from a places search. */
data class NearbyPlace(val name: String, val latitude: Double, val longitude: Double)

/** A place plus how far it is from the phone. */
data class RankedPlace(val place: NearbyPlace, val meters: Double)

/** Distance maths and the spoken answer for "find a petrol pump near me". Pure logic, no Android. */
object NearbyText {

    /** Straight-line distance in metres (haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    fun sortByDistance(places: List<NearbyPlace>, lat: Double, lon: Double): List<NearbyPlace> =
        places.sortedBy { distanceMeters(lat, lon, it.latitude, it.longitude) }

    /** "about 400 meters", "about 1.2 kilometers", "about 14 kilometers". */
    fun spokenDistance(meters: Double): String = when {
        meters < 975 -> "about ${((meters / 50).roundToInt() * 50).coerceAtLeast(50)} meters"
        meters < 10_000 -> {
            val km = (meters / 100).roundToInt() / 10.0
            if (km == km.toInt().toDouble()) {
                val n = km.toInt()
                "about $n ${if (n == 1) "kilometer" else "kilometers"}"
            } else {
                "about $km kilometers"
            }
        }
        else -> "about ${(meters / 1000).roundToInt()} kilometers"
    }

    /** The nearest [limit] places with their straight-line distance, closest first. */
    fun rank(places: List<NearbyPlace>, lat: Double, lon: Double, limit: Int = 5): List<RankedPlace> =
        places.map { RankedPlace(it, distanceMeters(lat, lon, it.latitude, it.longitude)) }
            .sortedBy { it.meters }
            .take(limit)

    /** Places read out and shown at a time. */
    const val BATCH = 3

    private val cutAt = Regex("\\s+[-\u2013|]\\s+|,")
    private val legalSuffix = Regex("(?:\\s+(?:corporation|corp|limited|ltd|pvt|private|inc|llp)\\.?)+$", RegexOption.IGNORE_CASE)

    /** "Bharat Petroleum Corporation Limited" -> "Bharat Petroleum"; "Indian Oil - Petrol Pump" -> "Indian Oil". */
    fun shortName(name: String): String {
        val short = name.split(cutAt).first().replace(legalSuffix, "").trim()
        return short.ifEmpty { name.trim() }
    }

    /** Short form for the screen: "850 m", "1.2 km", "14 km". */
    fun shortDistance(meters: Double): String = when {
        meters < 975 -> "${((meters / 50).roundToInt() * 50).coerceAtLeast(50)} m"
        meters < 10_000 -> "${(meters / 100).roundToInt() / 10.0} km".replace(".0 km", " km")
        else -> "${(meters / 1000).roundToInt()} km"
    }

    private fun numberWords(count: Int, digits: Boolean): String {
        val n = if (digits) listOf("1", "2", "3") else listOf("one", "two", "three")
        return when (count) {
            1 -> n[0]
            2 -> "${n[0]} or ${n[1]}"
            else -> "${n[0]}, ${n[1]} or ${n[2]}"
        }
    }

    private fun tail(count: Int, more: Boolean, digits: Boolean): String =
        "Say ${numberWords(count, digits)}, ${if (more) "more for the next ones, " else ""}or cancel."

    /** Everything DriveBuddy says for one batch, ending in the question. */
    fun offerBatch(batch: List<RankedPlace>, first: Boolean, more: Boolean): String {
        val count = batch.size
        val lead = when {
            first && count == 1 -> "Here is the closest."
            first -> "Here are the ${listOf("", "one", "two", "three")[count.coerceIn(1, 3)]} closest."
            count == 1 -> "Here is one more."
            else -> "Here are the next ${listOf("", "one", "two", "three")[count.coerceIn(1, 3)]}."
        }
        val items = batch.mapIndexed { i, r ->
            "${listOf("One", "Two", "Three")[i]}, ${shortName(r.place.name)}, ${spokenDistance(r.meters)} away."
        }
        return "$lead ${items.joinToString(" ")} Which one? ${tail(count, more, digits = false)}"
    }

    /** Short re-ask after an answer we could not understand. */
    fun retryPrompt(count: Int, more: Boolean): String = "Sorry. ${tail(count, more, digits = false)}"

    /** The line under the title on screen. */
    fun hint(count: Int): String = "Tap one to navigate, or say ${numberWords(count, digits = true)}."
}