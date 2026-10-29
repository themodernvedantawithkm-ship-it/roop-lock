package com.deepfaceswap.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/**
 * The people you have saved, so a source face does not have to be re-picked
 * every time.
 *
 * A saved person is not a folder of photos - it is an **identity centroid**: the
 * recogniser's vector, averaged across every photo saved for that person. The
 * swapper consumes exactly that vector, so averaging is not a convenience hack,
 * it is a better estimate of who someone is than any single photo.
 *
 * Alongside the centroid each person keeps an averaged aligned face, written by
 * the engine. That image is what the swap is actually given, which means the
 * whole existing swap path is used unchanged - nothing new to break.
 *
 * Storage is small by design: the centroid is 512 floats and the averaged face is
 * a 128-pixel image. A hundred people cost a few hundred kilobytes, and no full
 * photographs are retained.
 */
class PeopleStore(private val context: Context) {

    data class Person(
        val id: String,
        val name: String,
        /** Averaged identity vector, or empty when it could not be computed. */
        val centroid: FloatArray,
        /** Averaged aligned face, used as the swap's source. */
        val meanFacePath: String,
        /** How many photos went into it. */
        val photoCount: Int,
        /**
         * Mean pairwise similarity between the photos, 0..1.
         *
         * High means a coherent set. Low means either a mislabel or one bad
         * photo - which is exactly the mistake that would otherwise degrade the
         * centroid silently and forever.
         */
        val consistency: Float,
        val createdAt: Long,
    ) {
        val hasMeanFace: Boolean get() = meanFacePath.isNotEmpty()

        /** A plain-language read on how much to trust this person's set. */
        val quality: String
            get() = when {
                photoCount <= 0 -> "no photos"
                consistency >= 0.62f -> "strong"
                consistency >= 0.45f -> "fair"
                else -> "mixed - check the photos"
            }
    }

    private val dir = File(context.filesDir, "people").apply { mkdirs() }
    private val indexFile = File(dir, "people.json")

    fun all(): List<Person> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val vec = o.optJSONArray("centroid") ?: JSONArray()
                val centroid = FloatArray(vec.length()) { k -> vec.optDouble(k).toFloat() }
                Person(
                    id = o.optString("id"),
                    name = o.optString("name", "Unnamed"),
                    centroid = centroid,
                    meanFacePath = o.optString("meanFace", ""),
                    photoCount = o.optInt("photoCount", 0),
                    consistency = o.optDouble("consistency", 0.0).toFloat(),
                    createdAt = o.optLong("createdAt", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(people: List<Person>) {
        val arr = JSONArray()
        people.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("centroid", JSONArray().apply { p.centroid.forEach { put(it.toDouble()) } })
                put("meanFace", p.meanFacePath)
                put("photoCount", p.photoCount)
                put("consistency", p.consistency.toDouble())
                put("createdAt", p.createdAt)
            })
        }
        runCatching { indexFile.writeText(arr.toString()) }
    }

    fun add(person: Person) = save(all() + person)

    fun remove(id: String) {
        runCatching { File(dir, "mean_$id.png").delete() }
        save(all().filterNot { it.id == id })
    }

    fun rename(id: String, name: String) =
        save(all().map { if (it.id == id) it.copy(name = name) else it })

    /** Where a person's averaged face will be written. */
    fun meanFaceFile(id: String): File = File(dir, "mean_$id.png")

    /**
     * The closest saved person to an embedding, when it is close enough to call.
     *
     * Used to name a face rather than to change a swap: nothing is applied on the
     * strength of this, it only labels what was found.
     */
    fun bestMatch(embedding: FloatArray, minimum: Float = 0.42f): Pair<Person, Float>? {
        if (embedding.isEmpty()) return null
        var best: Person? = null
        var bestScore = -1f
        all().forEach { p ->
            if (p.centroid.isEmpty()) return@forEach
            val s = cosine(embedding, p.centroid)
            if (s > bestScore) {
                bestScore = s
                best = p
            }
        }
        val found = best ?: return null
        return if (bestScore >= minimum) found to bestScore else null
    }

    companion object {
        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var dot = 0f
            var na = 0f
            var nb = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            if (na <= 0f || nb <= 0f) return 0f
            return (dot / (sqrt(na) * sqrt(nb))).coerceIn(-1f, 1f)
        }

        /** Mean of the vectors, re-normalised so cosine stays meaningful. */
        fun average(vectors: List<FloatArray>): FloatArray {
            val usable = vectors.filter { it.isNotEmpty() }
            if (usable.isEmpty()) return FloatArray(0)
            val size = usable.minOf { it.size }
            val sum = FloatArray(size)
            usable.forEach { v -> for (i in 0 until size) sum[i] += v[i] }
            var norm = 0f
            for (i in 0 until size) {
                sum[i] /= usable.size
                norm += sum[i] * sum[i]
            }
            norm = sqrt(norm)
            if (norm > 0f) for (i in 0 until size) sum[i] /= norm
            return sum
        }

        /** Mean pairwise cosine similarity across a set, 0..1. */
        fun consistency(vectors: List<FloatArray>): Float {
            val usable = vectors.filter { it.isNotEmpty() }
            if (usable.size < 2) return if (usable.size == 1) 1f else 0f
            var total = 0f
            var pairs = 0
            for (i in usable.indices) {
                for (j in i + 1 until usable.size) {
                    total += cosine(usable[i], usable[j])
                    pairs++
                }
            }
            return if (pairs == 0) 0f else (total / pairs).coerceIn(0f, 1f)
        }
    }
}
