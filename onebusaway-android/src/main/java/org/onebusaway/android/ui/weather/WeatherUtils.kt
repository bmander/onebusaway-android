package org.onebusaway.android.ui.weather

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import org.onebusaway.android.R
import org.onebusaway.android.app.di.PreferencesEntryPoint
import java.util.Locale

object WeatherUtils {

    fun setWeatherImage(imageView: ImageView, weatherCondition: String) {
        // Adjusting scale for fog and wind icons.
        imageView.scaleType = if (isFitIcon(weatherCondition)) {
            ImageView.ScaleType.CENTER
        } else {
            ImageView.ScaleType.CENTER_CROP
        }
        imageView.setImageResource(getWeatherIconRes(weatherCondition))
    }

    /** The weather icon drawable for a forecast condition string (e.g. "partly-cloudy-day"). */
    fun getWeatherIconRes(weatherCondition: String): Int {
        return getWeatherDrawableRes(weatherCondition.replace("-", "_"))
    }

    /** Whether the icon should be shown unscaled (fog/wind) rather than center-cropped. */
    fun isFitIcon(weatherCondition: String): Boolean {
        return weatherCondition == "fog" || weatherCondition == "wind"
    }

    fun setWeatherTemp(weatherTempTxtView: TextView, temp: Double) {
        weatherTempTxtView.text = formatTemperature(weatherTempTxtView.context, temp)
    }

    /** Formats a Fahrenheit forecast temperature into the user's preferred unit, e.g. "29° F". */
    fun formatTemperature(context: Context, temp: Double): String {
        val automatic = context.getString(R.string.preferences_preferred_units_option_automatic)
        val preferredTempUnit = PreferencesEntryPoint.get(context)
            .getString(R.string.preference_key_preferred_temperature_units, automatic)

        // Automatic follows the locale default; otherwise honor the explicit Celsius/Fahrenheit choice.
        val useCelsius = if (preferredTempUnit == automatic) {
            getDefaultUserTemp() == "C"
        } else {
            preferredTempUnit == context.getString(R.string.celsius)
        }

        val convertedTemp = (if (useCelsius) convertToCelsius(temp) else temp).toInt()
        val unit = if (useCelsius) "C" else "F"

        return "$convertedTemp° $unit"
    }

    fun toggleWeatherViewVisibility(shouldShow: Boolean, weatherView: View?) {
        if (weatherView == null) {
            return
        }
        weatherView.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun getWeatherDrawableRes(condition: String): Int {
        return when (condition) {
            "clear_night" -> R.drawable.clear_night
            "rain" -> R.drawable.rain
            "snow" -> R.drawable.snow
            "sleet" -> R.drawable.sleet
            "wind" -> R.drawable.wind
            "fog" -> R.drawable.fog
            "cloudy" -> R.drawable.cloudy
            "partly_cloudy_day" -> R.drawable.partly_cloudy_day
            "partly_cloudy_night" -> R.drawable.partly_cloudy_night
            else -> R.drawable.clear_day
        }
    }

    fun getDefaultUserTemp(): String {
        val countryCode = Locale.getDefault().country
        return if ("US" == countryCode || "BS" == countryCode ||
            "KY" == countryCode || "LR" == countryCode
        ) {
            "F"
        } else {
            "C"
        }
    }

    fun convertToCelsius(fahrenheitTemp: Double): Double {
        return (fahrenheitTemp - 32) * 5 / 9
    }
}
