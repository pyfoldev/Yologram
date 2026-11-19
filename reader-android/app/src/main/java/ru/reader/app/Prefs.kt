package ru.reader.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)

    fun saved(): List<Item> = runCatching {
        val a = JSONArray(sp.getString("saved", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Item(o.getString("key"), o.getString("title"), o.getString("sub"),
                if (o.isNull("cover")) null else o.getString("cover"), Target.decode(o.getString("target")))
        }
    }.getOrDefault(emptyList())

    fun toggle(item: Item): List<Item> {
        val list = saved().toMutableList()
        if (!list.removeAll { it.key == item.key }) list.add(item)
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("key", it.key).put("title", it.title).put("sub", it.sub)
                .put("cover", it.cover ?: JSONObject.NULL).put("target", it.target.encode()))
        }
        sp.edit().putString("saved", a.toString()).apply()
        return list
    }

    fun episode(id: Int) = sp.getInt("ep$id", -1)
    fun setEpisode(id: Int, ordinal: Int) = sp.edit().putInt("ep$id", ordinal).apply()
    var fontSize: Int
        get() = sp.getInt("fs", 18)
        set(v) = sp.edit().putInt("fs", v.coerceIn(12, 32)).apply()
}
