package kz.pavlodar.transit.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

interface TransitProvider {
    val name: String
    fun routes(): List<Route>
    fun vehicles(): List<Vehicle>
}

/**
 * Источник по HTTP. Ожидаемый JSON (адаптер под go2bus / avtobys / 2GIS пишется на стороне сервера или здесь):
 * {"routes":[{"id":"1","type":"bus|tram","number":"5","stops":[{"id":"s1","name":"..","lat":52.28,"lon":76.97}]}],
 *  "vehicles":[{"id":"v1","routeId":"1","lat":52.28,"lon":76.97,"speed":25,"forward":true}]}
 */
class RemoteJsonProvider(private val url: String) : TransitProvider {
    override val name = "Онлайн: $url"
    private var cache: JSONObject? = null

    private fun fetch(): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        return c.inputStream.use { JSONObject(it.readBytes().decodeToString()) }.also { cache = it }
    }

    override fun routes(): List<Route> {
        val arr = fetch().getJSONArray("routes")
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i); val st = r.getJSONArray("stops")
            Route(r.getString("id"), if (r.optString("type") == "tram") VType.TRAM else VType.BUS, r.getString("number"),
                (0 until st.length()).map { j -> st.getJSONObject(j).let { Stop(it.getString("id"), it.getString("name"), it.getDouble("lat"), it.getDouble("lon")) } })
        }
    }

    override fun vehicles(): List<Vehicle> {
        val arr = fetch().getJSONArray("vehicles")
        return (0 until arr.length()).map { i ->
            val v = arr.getJSONObject(i)
            Vehicle(v.getString("id"), v.getString("routeId"), v.getDouble("lat"), v.getDouble("lon"),
                v.optDouble("speed", 20.0), if (v.has("forward")) v.getBoolean("forward") else null)
        }
    }
}

/** Демо: симулированный транспорт на приблизительных маршрутах по Павлодару. Не реальные данные! */
class DemoProvider : TransitProvider {
    override val name = "ДЕМО (симуляция, не реальные данные)"

    private fun s(id: String, n: String, la: Double, lo: Double) = Stop(id, n, la, lo)
    private val shared = listOf(
        s("a", "Автовокзал", 52.2930, 76.9440), s("b", "Площадь Победы", 52.2875, 76.9670),
        s("c", "Набережная", 52.2840, 76.9850), s("d", "Речной вокзал", 52.2810, 77.0010))
    private val routesList = listOf(
        Route("t1", VType.TRAM, "1", listOf(s("t0", "Тракторный", 52.3050, 76.9300), shared[0], shared[1],
            s("t4", "Университет", 52.2760, 76.9760), s("t5", "Алюминиевый", 52.2620, 76.9900))),
        Route("b5", VType.BUS, "5", listOf(shared[0], s("b1", "Рынок", 52.2905, 76.9560), shared[1], shared[2], shared[3])),
        Route("b12", VType.BUS, "12", listOf(s("c0", "Больница", 52.3000, 76.9720), s("c1", "ЦУМ", 52.2900, 76.9700), shared[1],
            s("c3", "Вокзал", 52.2700, 76.9650)))
    )

    override fun routes() = routesList

    override fun vehicles(): List<Vehicle> {
        val t = System.currentTimeMillis() / 1000.0
        val out = ArrayList<Vehicle>()
        for (r in routesList) {
            val len = r.length; val v = if (r.type == VType.TRAM) 7.0 else 6.0 // м/с
            for (k in 0 until 3) {
                val pos = ((t * v + k * 2 * len / 3) % (2 * len))
                val fwd = pos < len; val p = if (fwd) pos else 2 * len - pos
                var i = 0; while (i < r.stops.size - 2 && r.stopOffsets[i + 1] < p) i++
                val a = r.stops[i]; val b = r.stops[i + 1]
                val seg = r.stopOffsets[i + 1] - r.stopOffsets[i]
                val f = if (seg == 0.0) 0.0 else ((p - r.stopOffsets[i]) / seg).coerceIn(0.0, 1.0)
                out += Vehicle("${r.id}-$k", r.id, a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, v * 3.6, fwd)
            }
        }
        return out
    }
}
