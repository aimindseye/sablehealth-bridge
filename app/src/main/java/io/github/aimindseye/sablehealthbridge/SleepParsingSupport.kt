package io.github.aimindseye.sablehealthbridge

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

internal fun sleepInterval(point: JSONObject): JSONObject = point.getJSONObject("sleep").getJSONObject("interval")

internal fun endTime(point: JSONObject): Instant = Instant.parse(sleepInterval(point).getString("endTime"))

private val OFFSET_PATTERN = Regex("-?[0-9]{1,5}(\\.[0-9]{1,9})?s")

internal fun offset(
    interval: JSONObject,
    name: String,
): ZoneOffset? {
    val raw = (interval.opt(name) as? String)?.takeIf(OFFSET_PATTERN::matches) ?: return null
    val seconds = raw.removeSuffix("s").toBigDecimalOrNull()?.stripTrailingZeros()
    return seconds?.takeIf { it.scale() <= 0 }?.let {
        runCatching { ZoneOffset.ofTotalSeconds(it.intValueExact()) }.getOrNull()
    }
}

internal fun sourceName(point: JSONObject): String {
    val name = point.opt("dataPointName") as? String
    val alternate = point.opt("name") as? String
    return name.orEmpty().ifBlank { alternate.orEmpty() }
}
