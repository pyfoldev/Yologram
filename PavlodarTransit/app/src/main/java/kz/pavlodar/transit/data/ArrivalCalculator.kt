package kz.pavlodar.transit.data

/** Считает, какие транспортные средства едут к остановке и через сколько минут приедут. */
class ArrivalCalculator {
    private val lastProgress = HashMap<String, Double>()

    fun compute(stop: Stop, routes: List<Route>, vehicles: List<Vehicle>): List<Arrival> {
        val byRoute = vehicles.groupBy { it.routeId }
        val result = ArrayList<Arrival>()
        for (route in routes) {
            val idx = route.stops.indexOfFirst { it.id == stop.id }
            if (idx < 0) continue
            val sOff = route.stopOffsets[idx]
            for (v in byRoute[route.id].orEmpty()) {
                val (p, off) = route.project(v.lat, v.lon)
                if (off > 300) continue // далеко от линии маршрута — пропускаем
                val prev = lastProgress.put(v.id, p)
                val fwd = v.forward ?: when {
                    prev == null || Math.abs(p - prev) < 3 -> null
                    else -> p > prev
                } ?: continue
                val d = if (fwd) sOff - p else p - sOff
                if (d < -30) continue // уже проехал остановку
                val speed = maxOf(v.speedKmh, 15.0) / 3.6
                val toward = if (fwd) route.stops.last().name else route.stops.first().name
                result += Arrival(route, v, Math.ceil(maxOf(d, 0.0) / speed / 60).toInt(), toward)
            }
        }
        return result.sortedBy { it.etaMin }
    }
}
