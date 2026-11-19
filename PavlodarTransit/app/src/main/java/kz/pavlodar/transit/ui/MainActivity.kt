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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kz.pavlodar.transit.R
import kz.pavlodar.transit.data.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

class MainActivity : AppCompatActivity(), LocationListener {
    private lateinit var map: MapView
    private lateinit var api: Go2BusClient
    private var zones: List<Zone> = emptyList()
    private var vehicles: List<Vehicle> = emptyList()
    private var comings: List<Coming> = emptyList()
    private var lines: List<RouteLine> = emptyList()
    private var me: Location? = null
    private var selected: Zone? = null
    private var manual = false              // true — остановку выбрал пользователь, а не GPS
    private var filter: String? = null      // "vt/route"
    private var status = ""
    private var lastLinesKey: String? = null
    private val dyn = ArrayList<Overlay>()
    private val icons = HashMap<String, BitmapDrawable>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Configuration.getInstance().apply { load(this@MainActivity, getSharedPreferences("osm", MODE_PRIVATE)); userAgentValue = packageName }
        setContentView(R.layout.activity_main)
        api = Go2BusClient(cacheDir)
        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK) // OpenStreetMap
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0); map.controller.setCenter(GeoPoint(52.2858, 76.9675)) // Павлодар
        findViewById<View>(R.id.fabLocate).setOnClickListener {
            manual = false; filter = null; me?.let { map.controller.animateTo(GeoPoint(it)) }; onSelectionChanged()
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        startLocation()
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { launch { vehicleLoop() }; launch { comingLoop() } } }
    }

    // --- загрузка данных (запросы ограничены RateLimiter: ≤24/мин) ---

    private suspend fun vehicleLoop() {
        while (true) {
            try {
                if (zones.isEmpty()) { zones = withContext(Dispatchers.IO) { api.zones() }; pickNearest() }
                vehicles = withContext(Dispatchers.IO) { api.vehicles() }
                status = "go2.bus-pvl.kz · онлайн · ${vehicles.size} ТС"
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { status = "Нет связи с go2.bus-pvl.kz: ${e.message}" }
            refreshUi(); delay(6000)
        }
    }

    private suspend fun comingLoop() {
        while (true) {
            val z = selected
            if (z != null) try {
                comings = withContext(Dispatchers.IO) { api.comings(z.id) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { }
            refreshUi(); delay(10000)
        }
    }

    private fun onSelectionChanged() {
        comings = emptyList(); refreshUi()
        val z = selected ?: return
        lifecycleScope.launch { // быстро обновить прибытие для новой остановки
            try { comings = withContext(Dispatchers.IO) { api.comings(z.id) }; refreshUi() } catch (e: CancellationException) { throw e } catch (e: Exception) { }
        }
    }

    private fun loadLines() {
        val f = filter
        if (f == null) { lines = emptyList(); lastLinesKey = null; return }
        if (f == lastLinesKey) return
        lastLinesKey = f
        val (vt, route) = f.split("/", limit = 2)
        lifecycleScope.launch {
            try { lines = withContext(Dispatchers.IO) { api.routeLines(route, vt) }; refreshUi() } catch (e: CancellationException) { throw e } catch (e: Exception) { }
        }
    }

    // --- выбор остановки ---

    private fun nearest(n: Int): List<Pair<Zone, Double>> {
        val l = me ?: return emptyList()
        return zones.map { it to dist(l.latitude, l.longitude, it.lat, it.lon) }.sortedBy { it.second }.take(n)
    }

    private fun pickNearest() {
        if (manual) return
        val n = nearest(1).firstOrNull()?.first ?: return
        if (n.id != selected?.id) { selected = n; filter = null; onSelectionChanged() }
    }

    // --- UI ---

    private fun refreshUi() {
        findViewById<TextView>(R.id.sourceBadge).apply { text = status; setTextColor(if (status.startsWith("Нет")) 0xFFB71C1C.toInt() else 0xFF2E7D32.toInt()) }
        val stop = selected
        val title = findViewById<TextView>(R.id.stopTitle)
        if (stop == null) { title.text = if (me == null) "Определяю остановку… (включите геолокацию)" else "Загрузка остановок…"; drawMap(null); return }
        title.text = (if (manual) "Остановка: " else "Вы у остановки: ") + stop.name

        val near = nearest(5)
        val nearbyRow = findViewById<LinearLayout>(R.id.nearbyRow); nearbyRow.removeAllViews()
        near.forEach { (z, d) -> nearbyRow.addView(chip("${z.name} · ${d.toInt()} м", z.id == stop.id) { manual = true; selected = z; filter = null; onSelectionChanged() }) }

        val keys = comings.map { "${it.vt}/${it.route}" }.distinct()
        val filterRow = findViewById<LinearLayout>(R.id.filterRow); filterRow.removeAllViews()
        filterRow.addView(chip("Все", filter == null) { filter = null; refreshUi() })
        keys.forEach { k -> val (vt, r) = k.split("/", limit = 2)
            filterRow.addView(chip("${if (vt == "tr") "🚋" else "🚌"} $r", filter == k) { filter = if (filter == k) null else k; loadLines(); refreshUi() }) }

        val shown = comings.filter { filter == null || "${it.vt}/${it.route}" == filter }
        val rows = if (shown.isEmpty()) listOf("Нет ближайших рейсов к этой остановке") else shown.map {
            "${vtName(it.vt)} №${it.route}  →  ${it.endName}   ·   ${etaText(it.waitSec)}"
        }
        findViewById<ListView>(R.id.arrivals).adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        drawMap(stop, shown)
    }

    private fun etaText(sec: Int) = if (sec < 45) "прибывает" else "${(sec + 59) / 60} мин"

    private fun chip(text: String, on: Boolean, click: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 12f
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (on) 0xFF1565C0.toInt() else 0xFFE0E0E0.toInt())
        setTextColor(if (on) Color.WHITE else Color.BLACK); setOnClickListener { click() }
    }

    private fun drawMap(stop: Zone?, shown: List<Coming> = emptyList()) {
        map.overlays.removeAll(dyn.toSet()); dyn.clear()
        for (ln in lines) dyn += Polyline(map).apply { setPoints(ln.points.map { GeoPoint(it.first, it.second) }); outlinePaint.color = 0xAA1565C0.toInt(); outlinePaint.strokeWidth = 7f }
        // остановки рядом с центром карты
        val c = map.mapCenter
        for (z in zones) {
            if (Math.abs(z.lat - c.latitude) > 0.006 || Math.abs(z.lon - c.longitude) > 0.01) continue
            val sel = z.id == stop?.id
            dyn += Marker(map).apply {
                position = GeoPoint(z.lat, z.lon); title = z.name; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = icon("", if (sel) 0xFF2E7D32.toInt() else 0xFF757575.toInt(), if (sel) 12 else 6)
                setOnMarkerClickListener { _, _ -> manual = true; selected = z; filter = null; onSelectionChanged(); true }
            }
        }
        val comingIds = shown.associateBy { it.vehicleId }
        for (v in vehicles) {
            if (filter != null && "${v.vt}/${v.route}" != filter) continue
            val cm = comingIds[v.id]
            dyn += Marker(map).apply {
                position = GeoPoint(v.lat, v.lon); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = icon(v.route, if (v.vt == "tr") 0xFFC62828.toInt() else 0xFF1565C0.toInt(), 15, cm != null)
                title = "${vtName(v.vt)} №${v.route} ${v.plate}" + (cm?.let { " → ${it.endName}, ${etaText(it.waitSec)}" } ?: "")
            }
        }
        me?.let { l -> dyn += Marker(map).apply { position = GeoPoint(l); icon = icon("", 0xFFFF6F00.toInt(), 8); title = "Вы здесь"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER) } }
        map.overlays.addAll(dyn); map.invalidate()
    }

    private fun icon(text: String, color: Int, radiusDp: Int, ring: Boolean = false): BitmapDrawable = icons.getOrPut("$text/$color/$radiusDp/$ring") {
        val d = resources.displayMetrics.density; val r = radiusDp * d
        val bmp = Bitmap.createBitmap((r * 2).toInt() + 8, (r * 2).toInt() + 8, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG); val m = r + 4
        p.color = color; c.drawCircle(m, m, r, p)
        p.color = if (ring) 0xFFFFC107.toInt() else Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = (if (ring) 3.5f else 2f) * d; c.drawCircle(m, m, r - d, p)
        if (text.isNotEmpty()) {
            p.color = Color.WHITE; p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
            p.textSize = r * (if (text.length > 2) 0.75f else 0.95f); c.drawText(text, m, m - (p.ascent() + p.descent()) / 2, p)
        }
        BitmapDrawable(resources, bmp)
    }

    // --- геолокация ---

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) if (lm.isProviderEnabled(p)) {
            lm.requestLocationUpdates(p, 4000L, 5f, this)
            lm.getLastKnownLocation(p)?.let { if (me == null) onLocationChanged(it) }
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) { super.onRequestPermissionsResult(rc, perms, res); startLocation() }

    override fun onLocationChanged(l: Location) {
        val first = me == null; me = l
        if (first) map.controller.animateTo(GeoPoint(l))
        pickNearest(); refreshUi()
    }

    override fun onDestroy() { super.onDestroy(); (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this) }
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
}
