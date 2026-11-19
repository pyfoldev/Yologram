package ru.reader.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Card(
    val id: Int, val title: String, val year: Int, val type: String,
    val poster: String?, val thumb: String?, val ongoing: Boolean, val episodes: Int,
)

data class Episode(
    val ordinal: Int, val name: String?, val urls: Map<Int, String>,
    val duration: Int, val openStart: Int?, val openStop: Int?,
)

data class Detail(
    val card: Card, val english: String?, val description: String, val genres: List<String>,
    val age: String?, val rating: Double?, val season: String?, val publishDay: String?,
    val episodes: List<Episode>, val blocked: Boolean, val avgDuration: Int,
)

data class Genre(val id: Int, val name: String)
data class CatalogPage(val items: List<Card>, val hasMore: Boolean)

enum class Sort(val value: String, val label: String) {
    FRESH("FRESH_AT_DESC", "Обновлённые"), RATING("RATING_DESC", "По рейтингу"), YEAR("YEAR_DESC", "Новые")
}

private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }
private fun JSONObject.int(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)
private inline fun <T> JSONArray?.mapObj(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).map { f(getJSONObject(it)) }

object Api {
    private const val AL = "https://anilibria.top"
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    private class Cached(val at: Long, val body: String)
    private val cache = object : LinkedHashMap<String, Cached>(64, .75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Cached>) = size > 80
    }

    private fun url(path: String, vararg p: Pair<String, String>): HttpUrl =
        "$AL/api/v1/$path".toHttpUrl().newBuilder().apply { p.forEach { addQueryParameter(it.first, it.second) } }.build()

    /** GET с коротким кешем в памяти: повторные открытия экранов без задержки. */
    private suspend fun get(u: HttpUrl, ttlMs: Long = 5 * 60_000): String {
        val key = u.toString()
        synchronized(cache) { cache[key]?.takeIf { System.currentTimeMillis() - it.at < ttlMs } }?.let { return it.body }
        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(u).header("User-Agent", "AnimeApp/2.0").build()).execute().use {
                if (!it.isSuccessful) throw IOException("Сервер ответил ${it.code}")
                it.body!!.string()
            }
        }
        synchronized(cache) { cache[key] = Cached(System.currentTimeMillis(), body) }
        return body
    }

    private fun JSONObject.toCard(): Card {
        val p = optJSONObject("poster")
        val opt = p?.optJSONObject("optimized")
        fun abs(s: String?) = s?.let { if (it.startsWith("http")) it else AL + it }
        return Card(
            id = getInt("id"), title = getJSONObject("name").getString("main"),
            year = optInt("year"), type = optJSONObject("type")?.str("description") ?: "",
            poster = abs(opt?.str("src") ?: p?.str("src")),
            thumb = abs(opt?.str("preview") ?: p?.str("preview") ?: p?.str("src")),
            ongoing = optBoolean("is_ongoing"), episodes = optInt("episodes_total"),
        )
    }

    suspend fun catalog(page: Int, sort: Sort, ongoing: Boolean, genre: Int?): CatalogPage {
        val params = buildList {
            add("page" to page.toString()); add("limit" to "30"); add("f[sorting]" to sort.value)
            if (ongoing) add("f[publish_statuses]" to "IS_ONGOING")
            if (genre != null) add("f[genres]" to genre.toString())
        }
        val d = JSONObject(get(url("anime/catalog/releases", *params.toTypedArray())))
        val pg = d.getJSONObject("meta").getJSONObject("pagination")
        return CatalogPage(d.getJSONArray("data").mapObj { it.toCard() }, pg.optInt("current_page") < pg.optInt("total_pages"))
    }

    suspend fun search(q: String): List<Card> =
        JSONArray(get(url("app/search/releases", "query" to q), ttlMs = 60_000)).mapObj { it.toCard() }

    suspend fun genres(): List<Genre> =
        JSONArray(get(url("anime/catalog/references/genres"), ttlMs = 24 * 3600_000))
            .mapObj { Genre(it.getInt("id"), it.getString("name")) }

    suspend fun detail(id: Int): Detail {
        val r = JSONObject(get(url("anime/releases/$id")))
        val eps = r.optJSONArray("episodes").mapObj { e ->
            val urls = buildMap {
                e.str("hls_480")?.let { put(480, it) }
                e.str("hls_720")?.let { put(720, it) }
                e.str("hls_1080")?.let { put(1080, it) }
            }
            val op = e.optJSONObject("opening")
            Episode(e.optInt("ordinal"), e.str("name"), urls, e.optInt("duration"), op?.int("start"), op?.int("stop"))
        }.filter { it.urls.isNotEmpty() }.sortedBy { it.ordinal }
        val rating = r.optJSONObject("shikimori")?.optDouble("rating", 0.0)?.takeIf { it > 0 }
        return Detail(
            card = r.toCard(), english = r.getJSONObject("name").str("english"),
            description = (r.str("description") ?: "").replace("\r", "").trim(),
            genres = r.optJSONArray("genres").mapObj { it.getString("name") },
            age = r.optJSONObject("age_rating")?.str("label"), rating = rating,
            season = r.optJSONObject("season")?.str("description"),
            publishDay = r.optJSONObject("publish_day")?.str("description"),
            episodes = eps, blocked = r.optBoolean("is_blocked_by_geo") || r.optBoolean("is_blocked_by_copyrights"),
            avgDuration = r.optInt("average_duration_of_episode"),
        )
    }
}
