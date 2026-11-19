package kz.pavlodar.transit.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kz.pavlodar.transit.R
import kz.pavlodar.transit.data.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MainActivity : AppCompatActivity(), LocationListener {
    private lateinit var map: MapView
    private lateinit var provider: TransitProvider
    private val calc = ArrivalCalculator()
    private var routes: List<Route> = emptyList()
    private var vehicles: List<Vehicle> = emptyList()
    private var me: Location? = null
    private var selected: Stop? = null
    private var manual = false            // true — пользователь сам выбрал остановку
    private var routeFilter: String? = null
    private val center = GeoPoint(52.2873, 76.9674) // Павлодар
    private val dyn = ArrayList<org.osmdroid.views.overlay.Overlay>()
    private val iconCache = HashMap<String, BitmapDrawable>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Configuration.getInstance().apply { load(this@MainActivity, getSharedPreferences("osm", MODE_PRIVATE)); userAgentValue = packageName }
        setContentView(R.layout.activity_main)
        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK) // OpenStreetMap
        map.setMultiTouchControls(true)
        map.controller.setZoom(14.0); map.controller.setCenter(center)
        findViewById<View>(R.id.fabLocate).setOnClickListener { manual = false; me?.let { map.controller.animateTo(GeoPoint(it)) }; refreshUi() }
        findViewById<View>(R.id.sourceBadge).setOnClickListener { chooseSource() }
        useProvider()
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        startLocation()
        lifecycleScope.launch { loop() }
    }

    private fun useProvider() {
        val url = getSharedPreferences("app", MODE_PRIVATE).getString("url", "").orEmpty()
        provider = if (url.isBlank()) DemoProvider() else RemoteJsonProvider(url)
        findViewById<TextView>(R.id.sourceBadge).text = provider.name + " ⚙"
        routes = emptyList(); selected = null
    }

    private fun chooseSource() {
        val et = EditText(this).apply { hint = "https://…/transit.json (пусто = демо)"; setText(getSharedPreferences("app", MODE_PRIVATE).getString("url", "")) }
        AlertDialog.Builder(this).setTitle("Источник данных").setView(et)
            .setPositiveButton("OK") { _, _ -> getSharedPreferences("app", MODE_PRIVATE).edit().putString("url", et.text.toString().trim()).apply(); useProvider() }
            .setNegativeButton("Отмена", null).show()
    }

    private suspend fun loop() {
        var tick = 0
        while (true) {
            try {
                val p = provider
                if (routes.isEmpty() || tick % 12 == 0) routes = withContext(Dispatchers.IO) { p.routes() }
                vehicles = withContext(Dispatchers.IO) { p.vehicles() }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { findViewById<TextView>(R.id.sourceBadge).text = "Ошибка источника: ${e.message} ⚙" }
            tick++; refreshUi(); delay(5000)
        }
    }

    private fun allStops() = routes.flatMap { it.stops }.distinctBy { it.id }

    private fun nearest(n: Int): List<Pair<Stop, Double>> {
        val l = me ?: return allStops().take(n).map { it to -1.0 }
        return allStops().map { it to dist(l.latitude, l.longitude, it.lat, it.lon) }.sortedBy { it.second }.take(n)
    }

    private fun refreshUi() {
        if (routes.isEmpty()) return
        val near = nearest(5)
        if (!manual || selected == null) selected = near.first().first
        val stop = selected!!
        val arrivals = calc.compute(stop, routes, vehicles)
        val shown = arrivals.filter { routeFilter == null || it.route.id == routeFilter }

        findViewById<TextView>(R.id.stopTitle).text =
            (if (manual) "Остановка: " else if (me != null) "Вы у остановки: " else "Остановка (геолокация не получена): ") + stop.name

        val nearbyRow = findViewById<LinearLayout>(R.id.nearbyRow); nearbyRow.removeAllViews()
        near.forEach { (s, d) ->
            nearbyRow.addView(chip(s.name + if (d >= 0) " · ${d.toInt()} м" else "", s.id == stop.id) { manual = true; selected = s; routeFilter = null; refreshUi() })
        }
        val filterRow = findViewById<LinearLayout>(R.id.filterRow); filterRow.removeAllViews()
        filterRow.addView(chip("Все", routeFilter == null) { routeFilter = null; refreshUi() })
        routes.filter { r -> r.stops.any { it.id == stop.id } }.forEach { r ->
            filterRow.addView(chip("${if (r.type == VType.TRAM) "🚋" else "🚌"} ${r.number}", routeFilter == r.id) { routeFilter = if (routeFilter == r.id) null else r.id; refreshUi() })
        }
        val list = findViewById<ListView>(R.id.arrivals)
        val rows = if (shown.isEmpty()) listOf("Нет транспорта, едущего к остановке") else shown.map {
            "${it.route.type.label} №${it.route.number}  →  ${it.toward}   ·   ${if (it.etaMin <= 0) "прибывает" else "${it.etaMin} мин"}"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        drawMap(stop, shown)
    }

    private fun chip(text: String, on: Boolean, click: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 12f
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (on) 0xFF1565C0.toInt() else 0xFFE0E0E0.toInt())
        setTextColor(if (on) Color.WHITE else Color.BLACK); setOnClickListener { click() }
    }

    private fun drawMap(stop: Stop, shown: List<Arrival>) {
        map.overlays.removeAll(dyn.toSet()); dyn.clear()
        val visibleRoutes = routes.filter { routeFilter == null || it.id == routeFilter }
        routeFilter?.let { id -> routes.find { it.id == id }?.let { r ->
            dyn += Polyline(map).apply { setPoints(r.stops.map { GeoPoint(it.lat, it.lon) }); outlinePaint.color = 0xFF1565C0.toInt(); outlinePaint.strokeWidth = 8f } } }
        val stops = if (routeFilter == null) allStops() else visibleRoutes.flatMap { it.stops }.distinctBy { it.id }
        for (s in stops) dyn += Marker(map).apply {
            position = GeoPoint(s.lat, s.lon); title = s.name; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = icon(if (s.id == stop.id) "S!" else "S", if (s.id == stop.id) 0xFF2E7D32.toInt() else 0xFF757575.toInt(), if (s.id == stop.id) 18 else 10)
            setOnMarkupClick { manual = true; selected = s; routeFilter = null; refreshUi() }
        }
        val etaByVehicle = shown.associateBy { it.vehicle.id }
        for (v in vehicles) {
            val r = routes.find { it.id == v.routeId } ?: continue
            if (routeFilter != null && r.id != routeFilter) continue
            val a = etaByVehicle[v.id]
            dyn += Marker(map).apply {
                position = GeoPoint(v.lat, v.lon); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = icon(r.number, if (r.type == VType.TRAM) 0xFFC62828.toInt() else 0xFF1565C0.toInt(), 16)
                title = "${r.type.label} №${r.number}" + (a?.let { " → ${it.toward}, ${it.etaMin} мин" } ?: "")
            }
        }
        me?.let { l -> dyn += Marker(map).apply { position = GeoPoint(l); icon = icon("", 0xFFFF6F00.toInt(), 8); title = "Вы здесь"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER) } }
        map.overlays.addAll(dyn); map.invalidate()
    }

    private fun Marker.setOnMarkupClick(f: () -> Unit) = setOnMarkerClickListener { _, _ -> f(); true }

    private fun icon(text: String, color: Int, radiusDp: Int): BitmapDrawable = iconCache.getOrPut("$text/$color/$radiusDp") {
        val d = resources.displayMetrics.density; val r = radiusDp * d
        val bmp = Bitmap.createBitmap((r * 2).toInt() + 4, (r * 2).toInt() + 4, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color; c.drawCircle(r + 2, r + 2, r, p)
        p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; c.drawCircle(r + 2, r + 2, r - d, p)
        if (text.isNotEmpty() && !text.startsWith("S")) {
            p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; p.textSize = r * 0.95f; p.isFakeBoldText = true
            c.drawText(text, r + 2, r + 2 - (p.ascent() + p.descent()) / 2, p)
        }
        BitmapDrawable(resources, bmp)
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (lm.isProviderEnabled(p)) {
                lm.requestLocationUpdates(p, 4000L, 5f, this)
                lm.getLastKnownLocation(p)?.let { if (me == null) onLocationChanged(it) }
            }
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res); startLocation()
    }

    override fun onLocationChanged(l: Location) {
        val first = me == null; me = l
        if (first) map.controller.animateTo(GeoPoint(l))
        refreshUi()
    }

    override fun onDestroy() { super.onDestroy(); (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this) }
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
}
