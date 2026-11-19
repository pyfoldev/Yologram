package ru.reader.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class Hist(val card: Card, val ordinal: Int, val pos: Long, val dur: Long)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("anime", Context.MODE_PRIVATE)

    // ---- настройки (реактивные) ----
    var theme by mutableIntStateOf(sp.getInt("theme", 0)); private set          // 0 система, 1 светлая, 2 тёмная
    var amoled by mutableStateOf(sp.getBoolean("amoled", true)); private set
    var quality by mutableIntStateOf(sp.getInt("quality", 1080)); private set
    var speed by mutableFloatStateOf(sp.getFloat("speed", 1f)); private set
    var resize by mutableIntStateOf(sp.getInt("resize", 0)); private set        // 0 вписать, 1 заполнить кадр, 2 растянуть
    var seekStep by mutableIntStateOf(sp.getInt("step", 10)); private set
    var autoNext by mutableStateOf(sp.getBoolean("autonext", true)); private set
    var autoSkip by mutableStateOf(sp.getBoolean("autoskip", false)); private set

    fun putTheme(v: Int) { theme = v; sp.edit().putInt("theme", v).apply() }
    fun putAmoled(v: Boolean) { amoled = v; sp.edit().putBoolean("amoled", v).apply() }
    fun putQuality(v: Int) { quality = v; sp.edit().putInt("quality", v).apply() }
    fun putSpeed(v: Float) { speed = v; sp.edit().putFloat("speed", v).apply() }
    fun putResize(v: Int) { resize = v; sp.edit().putInt("resize", v).apply() }
    fun putSeekStep(v: Int) { seekStep = v; sp.edit().putInt("step", v).apply() }
    fun putAutoNext(v: Boolean) { autoNext = v; sp.edit().putBoolean("autonext", v).apply() }
    fun putAutoSkip(v: Boolean) { autoSkip = v; sp.edit().putBoolean("autoskip", v).apply() }

    // ---- избранное ----
    var favorites by mutableStateOf(readCards("fav")); private set

    fun isFavorite(id: Int) = favorites.any { it.id == id }
    fun toggleFavorite(c: Card) {
        favorites = if (isFavorite(c.id)) favorites.filter { it.id != c.id } else listOf(c) + favorites
        sp.edit().putString("fav", cardsJson(favorites)).apply()
    }

    // ---- история и позиции ----
    var history by mutableStateOf(readHistory()); private set

    fun progress(id: Int, ordinal: Int): Float {
        val d = sp.getLong("d_${id}_$ordinal", 0)
        return if (d <= 0) 0f else (sp.getLong("p_${id}_$ordinal", 0).toFloat() / d).coerceIn(0f, 1f)
    }
    fun position(id: Int, ordinal: Int): Long = sp.getLong("p_${id}_$ordinal", 0)
    fun lastOrdinal(id: Int): Int? = history.firstOrNull { it.card.id == id }?.ordinal

    fun saveProgress(card: Card, ordinal: Int, pos: Long, dur: Long) {
        if (dur <= 0) return
        val done = pos > dur - 20_000
        sp.edit().putLong("p_${card.id}_$ordinal", if (done) 0 else pos).putLong("d_${card.id}_$ordinal", dur).apply()
        history = (listOf(Hist(card, ordinal, if (done) 0 else pos, dur)) + history.filter { it.card.id != card.id }).take(20)
        sp.edit().putString("hist", JSONArray().apply {
            history.forEach { put(JSONObject(cardJson(it.card)).put("ord", it.ordinal).put("pos", it.pos).put("dur", it.dur)) }
        }.toString()).apply()
    }

    fun clearHistory() {
        history = emptyList()
        sp.edit().apply { sp.all.keys.filter { it.startsWith("p_") || it.startsWith("d_") }.forEach { remove(it) }; remove("hist") }.apply()
    }

    private fun cardJson(c: Card) = JSONObject().put("id", c.id).put("title", c.title).put("year", c.year).put("type", c.type)
        .put("poster", c.poster ?: JSONObject.NULL).put("thumb", c.thumb ?: JSONObject.NULL)
        .put("ongoing", c.ongoing).put("eps", c.episodes).toString()
    private fun cardsJson(l: List<Card>) = JSONArray().apply { l.forEach { put(JSONObject(cardJson(it))) } }.toString()
    private fun jsonCard(o: JSONObject) = Card(o.getInt("id"), o.getString("title"), o.optInt("year"), o.optString("type"),
        if (o.isNull("poster")) null else o.getString("poster"), if (o.isNull("thumb")) null else o.getString("thumb"),
        o.optBoolean("ongoing"), o.optInt("eps"))
    private fun readCards(k: String): List<Card> = runCatching {
        val a = JSONArray(sp.getString(k, "[]")); (0 until a.length()).map { jsonCard(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    private fun readHistory(): List<Hist> = runCatching {
        val a = JSONArray(sp.getString("hist", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Hist(jsonCard(o), o.getInt("ord"), o.getLong("pos"), o.getLong("dur")) }
    }.getOrDefault(emptyList())
}
