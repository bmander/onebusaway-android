package org.onebusaway.android.ui.weather;

import android.content.Context;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.onebusaway.android.R;
import org.onebusaway.android.app.di.PreferencesEntryPoint;

import java.util.Locale;

public class WeatherUtils {

    public static void setWeatherImage(ImageView imageView, String weatherCondition) {
        // Adjusting scale for fog and wind icons.
        imageView.setScaleType(isFitIcon(weatherCondition)
                ? ImageView.ScaleType.CENTER : ImageView.ScaleType.CENTER_CROP);
        imageView.setImageResource(getWeatherIconRes(weatherCondition));
    }

    /** The weather icon drawable for a forecast condition string (e.g. "partly-cloudy-day"). */
    public static int getWeatherIconRes(String weatherCondition) {
        return getWeatherDrawableRes(weatherCondition.replaceAll("-", "_"));
    }

    /** Whether the icon should be shown unscaled (fog/wind) rather than center-cropped. */
    public static boolean isFitIcon(String weatherCondition) {
        return weatherCondition.equals("fog") || weatherCondition.equals("wind");
    }

    public static void setWeatherTemp(TextView weatherTempTxtView, double temp) {
        weatherTempTxtView.setText(formatTemperature(weatherTempTxtView.getContext(), temp));
    }

    /** Formats a Fahrenheit forecast temperature into the user's preferred unit, e.g. "29° F". */
    public static String formatTemperature(Context context, double temp) {
        String automatic = context.getString(R.string.preferences_preferred_units_option_automatic);
        String preferredTempUnit = PreferencesEntryPoint.get(context).getString(R.string.preference_key_preferred_temperature_units, automatic);

        String defaultTempUnit = getDefaultUserTemp();
        boolean isCelsius = defaultTempUnit.equals("C");

        int convertedTemp = (preferredTempUnit.equals(automatic))
                ? (int) (isCelsius ? convertToCelsius(temp) : temp)
                : (preferredTempUnit.equals(context.getString(R.string.celsius)) ? (int) convertToCelsius(temp) : (int) temp);

        String unit = (preferredTempUnit.equals(automatic)) ? defaultTempUnit : (preferredTempUnit.equals(context.getString(R.string.celsius)) ? "C" : "F");

        return convertedTemp + "° " + unit;
    }

    public static void toggleWeatherViewVisibility(boolean shouldShow, View weatherView) {
        if (weatherView == null) {
            return;
        }
        weatherView.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
    }

    private static int getWeatherDrawableRes(String condition) {
        switch (condition) {
            case "clear_night":
                return R.drawable.clear_night;
            case "rain":
                return R.drawable.rain;
            case "snow":
                return R.drawable.snow;
            case "sleet":
                return R.drawable.sleet;
            case "wind":
                return R.drawable.wind;
            case "fog":
                return R.drawable.fog;
            case "cloudy":
                return R.drawable.cloudy;
            case "partly_cloudy_day":
                return R.drawable.partly_cloudy_day;
            case "partly_cloudy_night":
                return R.drawable.partly_cloudy_night;
            default:
                return R.drawable.clear_day;
        }
    }

    public static String getDefaultUserTemp() {
        Locale locale = Locale.getDefault();
        String countryCode = locale.getCountry();
        if ("US".equals(countryCode) || "BS".equals(countryCode) || "KY".equals(countryCode) || "LR".equals(countryCode)) {
            return "F";
        } else {
            return "C";
        }
    }

    public static double convertToCelsius(double fahrenheitTemp) {
        return (fahrenheitTemp - 32) * 5 / 9;
    }
}