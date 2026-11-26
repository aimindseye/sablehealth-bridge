package io.github.aimindseye.sablehealthbridge

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

internal fun endTime(point: JSONObject): Instant {
    val interval = point.getJSONObject("sleep").getJSONObject("interval")
    return Instant.parse(interval.getString("endTime"))
}

internal fun offset(
    interval: JSONObject,
    name: String,
): ZoneOffset? {
    if (!interval.has(name)) return null
    val seconds = interval.getString(name).removeSuffix("s").toInt()
    return ZoneOffset.ofTotalSeconds(seconds)
}
