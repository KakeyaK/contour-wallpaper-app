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
 * Nascer e pôr do sol pelo algoritmo do NOAA (o mesmo da planilha solar deles).
 * Só aritmética — nenhuma rede, nenhum serviço de localização. As coordenadas são
 * guardadas uma vez pela tela de configurações; o wallpaper apenas faz a conta.
 * Precisão de ~1 minuto, mais que suficiente para escolher cores.
 */
object SolarTime {

    /** Horas locais (0..24) do nascer e do pôr do sol. */
    data class Sun(val sunrise: Float, val sunset: Float)

    private const val ZENITH = 90.833 // inclui refração atmosférica e o raio do disco solar

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)

    /** Dia juliano às 0h UT da data civil informada. */
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
     * Calcula o nascer e o pôr do sol para a data de [cal] (com o fuso do próprio
     * Calendar). Retorna null quando o sol não nasce nem se põe naquele dia — sol da
     * meia-noite ou noite polar — e nesses casos quem chama volta ao relógio normal.
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
        if (cosHa > 1.0 || cosHa < -1.0) return null // sol da meia-noite ou noite polar
        val haMinutes = deg(acos(cosHa)) * 4.0

        val solarNoon = 720.0 - 4.0 * longitude - eqTime + tzMinutes
        val sunrise = normalizeHour((solarNoon - haMinutes) / 60.0)
        val sunset = normalizeHour((solarNoon + haMinutes) / 60.0)
        if (sunset <= sunrise) return null // fuso muito fora da longitude: melhor não distorcer
        return Sun(sunrise, sunset)
    }

    private fun normalizeHour(h: Double): Float {
        val x = ((h % 24.0) + 24.0) % 24.0
        return x.toFloat()
    }
}

/**
 * Converte a hora do relógio na hora que a paleta deve usar, esticando o dia e a noite
 * reais sobre os trechos de referência da paleta.
 *
 * A âncora das [REF_SUNRISE] passa a cair exatamente no nascer do sol e a das
 * [REF_SUNSET] no pôr; o que está entre elas é esticado ou comprimido junto. No inverno,
 * com o dia mais curto, o miolo claro da paleta encolhe e a noite se alonga — que é o
 * comportamento que se espera de "acompanhar o sol".
 *
 * O mapa é contínuo e monotônico, inclusive na virada da meia-noite, então as cores
 * continuam mudando sem saltos (critério de aceitação 2).
 */
object SolarWarp {

    /** Âncoras de referência: as horas do "Amanhecer" e do "Anoitecer" da paleta padrão. */
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
     * Caminho inverso: dada uma hora da paleta, em que hora do relógio ela acontece hoje.
     * É o que a tela de configurações usa para mostrar que o modo solar está
     * sobrescrevendo os horários definidos pelo usuário.
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
     * Hora que a paleta deve usar. Se o modo solar estiver desligado, sem coordenadas, ou
     * se for um dia sem nascer/pôr do sol, devolve a própria hora do relógio.
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
