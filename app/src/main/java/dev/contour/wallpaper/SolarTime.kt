package dev.contour.wallpaper

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise and sunset using NOAA's algorithm (the same as their solar spreadsheet).
 * Pure arithmetic — no network, no location services. The coordinates are stored once
 * by the settings screen; the wallpaper just does the math.
 * Accurate to ~1 minute, more than enough for picking colours.
 */
object SolarTime {

    /** Local times (0..24) of sunrise and sunset. */
    data class Sun(val sunrise: Float, val sunset: Float)

    private const val ZENITH = 90.833 // includes atmospheric refraction and the solar disc radius

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)

    /** Julian day at 0h UT of the given civil date. */
    private fun julianDay(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    /**
     * Computes sunrise and sunset for the date of [cal] (in the Calendar's own time
     * zone). Returns null when the sun neither rises nor sets that day — midnight sun or
     * polar night — and in those cases the caller falls back to the normal clock.
     */
    fun forDate(cal: Calendar, latitude: Double, longitude: Double): Sun? {
        if (latitude.isNaN() || longitude.isNaN()) return null
        if (abs(latitude) > 90.0 || abs(longitude) > 180.0) return null

        val tzMinutes = (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 60000.0
        val jd = julianDay(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        ) - tzMinutes / 1440.0
        val jc = (jd - 2451545.0) / 36525.0

        val meanLong = (280.46646 + jc * (36000.76983 + jc * 0.0003032)) % 360.0
        val meanAnom = 357.52911 + jc * (35999.05029 - 0.0001537 * jc)
        val eccent = 0.016708634 - jc * (0.000042037 + 0.0000001267 * jc)

        val eqCenter = sin(rad(meanAnom)) * (1.914602 - jc * (0.004817 + 0.000014 * jc)) +
            sin(rad(2 * meanAnom)) * (0.019993 - 0.000101 * jc) +
            sin(rad(3 * meanAnom)) * 0.000289
        val trueLong = meanLong + eqCenter
        val appLong = trueLong - 0.00569 - 0.00478 * sin(rad(125.04 - 1934.136 * jc))

        val meanObliq = 23.0 + (26.0 + (21.448 - jc * (46.815 + jc * (0.00059 - jc * 0.001813))) / 60.0) / 60.0
        val obliqCorr = meanObliq + 0.00256 * cos(rad(125.04 - 1934.136 * jc))
        val declination = deg(asin(sin(rad(obliqCorr)) * sin(rad(appLong))))

        val varY = tan(rad(obliqCorr / 2)) * tan(rad(obliqCorr / 2))
        val eqTime = 4 * deg(
            varY * sin(2 * rad(meanLong)) -
                2 * eccent * sin(rad(meanAnom)) +
                4 * eccent * varY * sin(rad(meanAnom)) * cos(2 * rad(meanLong)) -
                0.5 * varY * varY * sin(4 * rad(meanLong)) -
                1.25 * eccent * eccent * sin(2 * rad(meanAnom)),
        )

        val cosHa = cos(rad(ZENITH)) / (cos(rad(latitude)) * cos(rad(declination))) -
            tan(rad(latitude)) * tan(rad(declination))
        if (cosHa > 1.0 || cosHa < -1.0) return null // midnight sun or polar night
        val haMinutes = deg(acos(cosHa)) * 4.0

        val solarNoon = 720.0 - 4.0 * longitude - eqTime + tzMinutes
        val sunrise = normalizeHour((solarNoon - haMinutes) / 60.0)
        val sunset = normalizeHour((solarNoon + haMinutes) / 60.0)
        if (sunset <= sunrise) return null // time zone far off the longitude: better not to distort
        return Sun(sunrise, sunset)
    }

    private fun normalizeHour(h: Double): Float {
        val x = ((h % 24.0) + 24.0) % 24.0
        return x.toFloat()
    }
}

/**
 * Converts the clock time into the time the palette should use, stretching the actual
 * day and night over the palette's reference segments.
 *
 * The [REF_SUNRISE] anchor lands exactly on sunrise and the [REF_SUNSET] one on sunset;
 * whatever lies between them is stretched or compressed along with them. In winter, with
 * a shorter day, the bright middle of the palette shrinks and the night gets longer —
 * which is the behaviour you'd expect from "following the sun".
 *
 * The mapping is continuous and monotonic, including across midnight, so the colours
 * keep changing without jumps (acceptance criterion 2).
 */
object SolarWarp {

    /** Reference anchors: the times of the default palette's "Dawn" and "Dusk". */
    const val REF_SUNRISE = 5.0f
    const val REF_SUNSET = 20.0f

    fun warp(clockHour: Float, sun: SolarTime.Sun): Float {
        val dayLength = sun.sunset - sun.sunrise
        val nightLength = 24f - dayLength
        if (dayLength <= 0.01f || nightLength <= 0.01f) return clockHour

        val t = ((clockHour % 24f) + 24f) % 24f
        return if (t >= sun.sunrise && t < sun.sunset) {
            REF_SUNRISE + (t - sun.sunrise) / dayLength * (REF_SUNSET - REF_SUNRISE)
        } else {
            val tn = if (t < sun.sunrise) t + 24f else t
            val fraction = (tn - sun.sunset) / nightLength
            val mapped = REF_SUNSET + fraction * (24f - (REF_SUNSET - REF_SUNRISE))
            ((mapped % 24f) + 24f) % 24f
        }
    }

    /**
     * Inverse mapping: given a palette time, at what clock time it happens today.
     * The settings screen uses this to show that solar mode is overriding the times set
     * by the user.
     */
    fun unwarp(paletteHour: Float, sun: SolarTime.Sun): Float {
        val dayLength = sun.sunset - sun.sunrise
        val nightLength = 24f - dayLength
        if (dayLength <= 0.01f || nightLength <= 0.01f) return paletteHour

        val p = ((paletteHour % 24f) + 24f) % 24f
        return if (p >= REF_SUNRISE && p < REF_SUNSET) {
            sun.sunrise + (p - REF_SUNRISE) / (REF_SUNSET - REF_SUNRISE) * dayLength
        } else {
            val pn = if (p < REF_SUNRISE) p + 24f else p
            val fraction = (pn - REF_SUNSET) / (24f - (REF_SUNSET - REF_SUNRISE))
            val mapped = sun.sunset + fraction * nightLength
            ((mapped % 24f) + 24f) % 24f
        }
    }

    /**
     * Time the palette should use. If solar mode is off, there are no coordinates, or it
     * is a day without sunrise/sunset, returns the clock time itself.
     */
    fun paletteHour(
        clockHour: Float,
        enabled: Boolean,
        latitude: Float,
        longitude: Float,
        calendar: Calendar = Calendar.getInstance(),
    ): Float {
        if (!enabled || latitude.isNaN() || longitude.isNaN()) return clockHour
        val sun = SolarTime.forDate(calendar, latitude.toDouble(), longitude.toDouble()) ?: return clockHour
        return warp(clockHour, sun)
    }
}
