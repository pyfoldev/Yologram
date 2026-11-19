package kz.pavlodar.transit.data

import kotlin.math.*

enum class VType(val label: String) { BUS("Автобус"), TRAM("Трамвай") }

data class Stop(val id: String, val name: String, val lat: Double, val lon: Double)

/** Маршрут: линейная последовательность остановок (туда и обратно по одной линии). */
data class Route(val id: String, val type: VType, val number: String, val stops: List<Stop>) {
    /** Накопленное расстояние (м) от начала линии до каждой остановки. */
    val stopOffsets: DoubleArray = DoubleArray(stops.size).also { o ->
        for (i in 1 until stops.size) o[i] = o[i - 1] + dist(stops[i - 1].lat, stops[i - 1].lon, stops[i].lat, stops[i].lon)
    }
    val length: Double get() = stopOffsets.lastOrNull() ?: 0.0

    /** Проекция точки на линию маршрута: расстояние от начала (м) и отклонение от линии (м). */
    fun project(lat: Double, lon: Double): Pair<Double, Double> {
        var best = Double.MAX_VALUE; var bestPos = 0.0
        for (i in 0 until stops.size - 1) {
            val a = stops[i]; val b = stops[i + 1]
            val kx = cos(Math.toRadians(a.lat)) * 111320.0; val ky = 110540.0
            val ax = 0.0; val ay = 0.0
            val bx = (b.lon - a.lon) * kx; val by = (b.lat - a.lat) * ky
            val px = (lon - a.lon) * kx; val py = (lat - a.lat) * ky
            val l2 = bx * bx + by * by
            val t = if (l2 == 0.0) 0.0 else ((px - ax) * bx + (py - ay) * by).div(l2).coerceIn(0.0, 1.0)
            val d = hypot(px - t * bx, py - t * by)
            if (d < best) { best = d; bestPos = stopOffsets[i] + t * sqrt(l2) }
        }
        return bestPos to best
    }
}

/** forward: true — едет к последней остановке, false — к первой, null — неизвестно (определим по движению). */
data class Vehicle(val id: String, val routeId: String, val lat: Double, val lon: Double,
                   val speedKmh: Double, val forward: Boolean?)

data class Arrival(val route: Route, val vehicle: Vehicle, val etaMin: Int, val toward: String)

fun dist(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
    val r = 6371000.0
    val dLa = Math.toRadians(la2 - la1); val dLo = Math.toRadians(lo2 - lo1)
    val h = sin(dLa / 2).pow(2) + cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLo / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}
