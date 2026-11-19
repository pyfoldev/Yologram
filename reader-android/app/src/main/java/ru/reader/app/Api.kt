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

/** Что открывать по нажатию на карточку. */
sealed interface Target {
    data class Anime(val id: Int) : Target
    data class Wiki(val title: String) : Target
    data class Web(val url: String) : Target   // встроенный просмотрщик источника
    data class Link(val url: String) : Target  // только внешняя ссылка

    fun encode() = when (this) {
        is Anime -> "anime:$id"
        is Wiki -> "wiki:$title"
        is Web -> "web:$url"
        is Link -> "link:$url"
    }

    companion object {
        fun decode(s: String): Target {
            val v = s.substringAfter(':')
            return when (s.substringBefore(':')) {
                "anime" -> Anime(v.toInt())
                "wiki" -> Wiki(v)
                "web" -> Web(v)
                else -> Link(v)
            }
        }
    }
}

data class Item(val key: String, val title: String, val sub: String, val cover: String?, val target: Target)
data class Episode(val ordinal: Int, val url: String?)
data class AnimeData(val title: String, val description: String, val episodes: List<Episode>)

private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }
private inline fun <T> JSONArray?.mapObj(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).map { f(getJSONObject(it)) }

object Api {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    private fun url(base: String, vararg p: Pair<String, String>): HttpUrl =
        base.toHttpUrl().newBuilder().apply { p.forEach { addQueryParameter(it.first, it.second) } }.build()

    private suspend fun get(u: HttpUrl): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(u).header("User-Agent", "ReaderApp/1.0").build()
        http.newCall(req).execute().use {
            if (!it.isSuccessful) throw IOException("${u.host}: ${it.code}")
            it.body!!.string()
        }
    }

    // ---------- AniLibria (v1; v3 закрыт) ----------
    private const val AL = "https://anilibria.top"

    suspend fun aniList(q: String): List<Item> {
        val arr = if (q.isEmpty())
            JSONObject(get(url("$AL/api/v1/anime/catalog/releases", "page" to "1", "limit" to "30"))).getJSONArray("data")
        else JSONArray(get(url("$AL/api/v1/app/search/releases", "query" to q)))
        return arr.mapObj { r ->
            val p = r.optJSONObject("poster")
            val path = p?.optJSONObject("optimized")?.str("preview") ?: p?.str("preview") ?: p?.str("src")
            Item(
                "al${r.getInt("id")}", r.getJSONObject("name").getString("main"),
                "${r.optInt("year")} · ${r.optJSONObject("type")?.str("description") ?: ""}",
                path?.let { AL + it }, Target.Anime(r.getInt("id")),
            )
        }
    }

    suspend fun anime(id: Int): AnimeData {
        val r = JSONObject(get(url("$AL/api/v1/anime/releases/$id")))
        val eps = r.optJSONArray("episodes").mapObj { e ->
            Episode(e.optInt("ordinal"), e.str("hls_1080") ?: e.str("hls_720") ?: e.str("hls_480"))
        }.sortedBy { it.ordinal }
        return AnimeData(r.getJSONObject("name").getString("main"), r.str("description") ?: "", eps)
    }

    // ---------- Книги ----------
    suspend fun gutenberg(q: String): List<Item> {
        val params = buildList { add("languages" to "ru"); if (q.isNotEmpty()) add("search" to q) }
        val d = JSONObject(get(url("https://gutendex.com/books", *params.toTypedArray())))
        return d.getJSONArray("results").mapObj { b ->
            val f = b.getJSONObject("formats")
            val html = f.keys().asSequence().firstOrNull { it.startsWith("text/html") }?.let { f.getString(it) }
            val authors = b.optJSONArray("authors").mapObj { it.getString("name") }.joinToString()
            Item(
                "gt${b.getInt("id")}", b.getString("title"), authors, f.str("image/jpeg"),
                if (html != null) Target.Web(html) else Target.Link("https://www.gutenberg.org/ebooks/${b.getInt("id")}"),
            )
        }
    }

    suspend fun wikisource(q: String): List<Item> {
        val d = JSONObject(get(url("https://ru.wikisource.org/w/api.php",
            "action" to "query", "list" to "search", "srsearch" to q.ifEmpty { "Пушкин" },
            "srlimit" to "30", "format" to "json")))
        return d.getJSONObject("query").getJSONArray("search").mapObj { s ->
            Item("ws${s.getInt("pageid")}", s.getString("title"),
                s.getString("snippet").replace(Regex("<[^>]+>"), "").take(80), null, Target.Wiki(s.getString("title")))
        }
    }

    suspend fun openLibrary(q: String): List<Item> {
        val d = JSONObject(get(url("https://openlibrary.org/search.json",
            "q" to q.ifEmpty { "классика" }, "language" to "rus", "limit" to "30",
            "fields" to "key,title,author_name,cover_i,ia")))
        return d.getJSONArray("docs").mapObj { b ->
            val ia = b.optJSONArray("ia")?.optString(0)?.ifEmpty { null }
            val cover = if (b.has("cover_i")) "https://covers.openlibrary.org/b/id/${b.getInt("cover_i")}-M.jpg" else null
            Item("ol" + b.getString("key"), b.getString("title"),
                b.optJSONArray("author_name")?.let { a -> (0 until a.length()).joinToString { a.getString(it) } } ?: "",
                cover,
                if (ia != null) Target.Web("https://archive.org/embed/$ia") else Target.Link("https://openlibrary.org" + b.getString("key")))
        }
    }

    suspend fun googleBooks(q: String): List<Item> {
        val d = JSONObject(get(url("https://www.googleapis.com/books/v1/volumes",
            "q" to q.ifEmpty { "классика" }, "langRestrict" to "ru", "maxResults" to "30", "filter" to "partial")))
        return d.optJSONArray("items").mapObj { b ->
            val v = b.getJSONObject("volumeInfo")
            val emb = b.optJSONObject("accessInfo")?.optBoolean("embeddable") == true
            Item("gb" + b.getString("id"), v.optString("title"),
                v.optJSONArray("authors")?.let { a -> (0 until a.length()).joinToString { a.getString(it) } } ?: "",
                v.optJSONObject("imageLinks")?.str("thumbnail")?.replace("http:", "https:"),
                if (emb) Target.Web("https://books.google.com/books?id=${b.getString("id")}&output=embed")
                else Target.Link(v.optString("infoLink")))
        }
    }

    suspend fun archive(q: String, comics: Boolean): List<Item> {
        val subj = if (comics) " AND (subject:comics OR subject:\"комиксы\" OR subject:manga)" else ""
        val lang = if (comics) "" else " AND language:(rus OR russian)"
        val query = "${if (q.isEmpty()) "*" else "($q)"}$lang$subj AND mediatype:texts"
        val u = "https://archive.org/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("fl[]", "identifier").addQueryParameter("fl[]", "title")
            .addQueryParameter("fl[]", "creator").addQueryParameter("rows", "30")
            .addQueryParameter("sort[]", "downloads desc").addQueryParameter("output", "json").build()
        val docs = JSONObject(get(u)).getJSONObject("response").getJSONArray("docs")
        return docs.mapObj { b ->
            val id = b.getString("identifier")
            val c = b.opt("creator")
            val author = when (c) {
                is JSONArray -> (0 until c.length()).joinToString { c.getString(it) }
                is String -> c
                else -> ""
            }
            Item("ia$id", b.optString("title", id), author, "https://archive.org/services/img/$id",
                Target.Web("https://archive.org/embed/$id"))
        }
    }

    suspend fun wikiText(title: String): String {
        val d = JSONObject(get(url("https://ru.wikisource.org/w/api.php",
            "action" to "parse", "page" to title, "prop" to "text", "format" to "json",
            "disableeditsection" to "1", "redirects" to "1")))
        return d.getJSONObject("parse").getJSONObject("text").getString("*")
    }
}

enum class Source(val label: String) {
    ANILIBRIA("AniLibria"), GUTENBERG("Gutenberg"), WIKISOURCE("Викитека"),
    OPENLIB("Open Library"), GOOGLE("Google Books"), ARCHIVE("Internet Archive");

    suspend fun list(q: String, comics: Boolean): List<Item> = when (this) {
        ANILIBRIA -> Api.aniList(q)
        GUTENBERG -> Api.gutenberg(q)
        WIKISOURCE -> Api.wikisource(q)
        OPENLIB -> Api.openLibrary(q)
        GOOGLE -> Api.googleBooks(q)
        ARCHIVE -> Api.archive(q, comics)
    }
}

enum class Tab(val label: String, val sources: List<Source>) {
    ANIME("Аниме", listOf(Source.ANILIBRIA)),
    BOOKS("Книги", listOf(Source.GUTENBERG, Source.WIKISOURCE, Source.OPENLIB, Source.GOOGLE, Source.ARCHIVE)),
    COMICS("Комиксы", listOf(Source.ARCHIVE)),
    SAVED("Закладки", emptyList()),
}
