package kz.pavlodar.transit.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

data class Zone(val id: String, val name: String, val lat: Double, val lon: Double)
data class Vehicle(val id: String, val route: String, val vt: String, val letter: String, val lat: Double,
                   val lon: Double, val course: Int, val lowFloor: Boolean, val plate: String)
data class Coming(val route: String, val vt: String, val letter: String, val endName: String,
                  val waitSec: Int, val vehicleId: String, val plate: String)
data class RouteLine(val letter: String, val name: String, val points: List<Pair<Double, Double>>)

fun vtName(vt: String) = when (vt) { "tr" -> "Трамвай"; "tb" -> "Троллейбус"; else -> "Автобус" }

fun dist(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
    val dLa = Math.toRadians(la2 - la1); val dLo = Math.toRadians(lo2 - lo1)
    val h = sin(dLa / 2).pow(2) + cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLo / 2).pow(2)
    return 2 * 6371000.0 * asin(sqrt(h))
}

/** Не более [max] запросов за 60 с (у сайта лимит 30/мин — держим запас). */
class RateLimiter(private val max: Int) {
    private val times = ArrayDeque<Long>()
    fun acquire() {
        while (true) {
            val wait: Long
            synchronized(this) {
                val now = System.currentTimeMillis()
                while (times.isNotEmpty() && now - times.first() > 60_000) times.removeFirst()
                if (times.size < max) { times.addLast(now); return }
                wait = 60_000 - (now - times.first()) + 50
            }
            Thread.sleep(wait)
        }
    }
}

/** Клиент открытых данных сайта go2.bus-pvl.kz (город Павлодар, srv=pav). Все методы блокирующие — вызывать из IO. */
class Go2BusClient(private val cacheDir: File) {
    private val hosts = listOf("https://go2.bus-pvl.kz", "http://go2.bus-pvl.kz")
    @Volatile private var host = hosts[0]
    private val limiter = RateLimiter(24)
    private val lineCache = HashMap<String, List<RouteLine>>()

    private fun get(path: String, params: Map<String, String>): String {
        val q = (params + ("srv" to "pav") + ("lang" to "ru")).entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        var err: Exception? = null
        for (h in listOf(host) + hosts.filter { it != host }) {
            try {
                limiter.acquire()
                val c = URL("$h$path?$q").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 10000
                c.setRequestProperty("lang", "ru"); c.setRequestProperty("User-Agent", "PavlodarTransit/1.0 (Android)")
                if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
                host = h
                return c.inputStream.use { it.readBytes().decodeToString() }
            } catch (e: Exception) { err = e }
        }
        throw err!!
    }

    /** Все остановки города. Кэшируются на сутки. */
    fun zones(): List<Zone> {
        val f = File(cacheDir, "zones.json")
        val txt = if (f.exists() && System.currentTimeMillis() - f.lastModified() < 24 * 3600_000L) f.readText()
        else get("/inforoutezones", emptyMap()).also { f.writeText(it) }
        val a = JSONArray(txt)
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            val l = it.getJSONArray("location"); Zone(it.getString("id"), it.getString("name"), l.getDouble(0), l.getDouble(1))
        }
    }

    /** Текущие позиции всего транспорта города (один запрос). */
    fun vehicles(): List<Vehicle> {
        val a = JSONObject(get("/inforoutestates", mapOf("vt" to "b;rt;tb;tr;riv"))).getJSONArray("states")
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.isNull("lat") }.map {
            Vehicle(it.getString("id"), it.getString("route"), it.getString("vt"), it.optString("letter"), it.getDouble("lat"),
                it.getDouble("lon"), it.optInt("course"), it.optBoolean("lowFloor"), it.optString("stateNum"))
        }
    }

    /** Кто приедет на остановку и куда едет (считает сам сервис). */
    fun comings(zoneId: String): List<Coming> {
        val a = JSONArray(get("/infonextcomings", mapOf("zoneId" to zoneId)))
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            val r = it.getJSONObject("route"); val d = it.getJSONObject("direction"); val o = it.getJSONObject("object")
            Coming(r.getString("name"), r.getString("vt"), d.optString("letter"), d.optString("endName"),
                parseWait(it.getString("wait")), o.getString("id"), o.optString("stateNum"))
        }.sortedBy { it.waitSec }
    }

    /** Линии маршрута для показа на карте (загружаются один раз на маршрут). */
    fun routeLines(route: String, vt: String): List<RouteLine> = lineCache.getOrPut("$vt/$route") {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val d = JSONObject(get("/inforoutedetails", mapOf("route" to route, "vt" to vt, "date" to today))).getJSONArray("directions")
        (0 until d.length()).map { d.getJSONObject(it) }.map { dir ->
            val pts = ArrayList<Pair<Double, Double>>()
            val cs = dir.getJSONArray("corridors")
            for (i in 0 until cs.length()) { val c = cs.getJSONArray(i); for (j in 0 until c.length()) c.getJSONArray(j).let { pts += it.getDouble(0) to it.getDouble(1) } }
            RouteLine(dir.optString("letter"), dir.optString("name"), pts)
        }
    }

    private fun parseWait(s: String): Int { // "00:02:32.198"
        val p = s.split(":"); return (p[0].toInt() * 3600 + p[1].toInt() * 60 + p[2].toDouble().toInt())
    }
}
