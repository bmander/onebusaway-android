/*
 * Copyright (C) 2012-2017 Paul Watts (paulcwatts@gmail.com),
 * University of South Florida (sjbarbeau@gmail.com),
 * Microsoft Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onebusaway.android.ui.home

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.flow.MutableStateFlow
import org.onebusaway.android.R
import org.onebusaway.android.io.ObaAnalytics
import org.onebusaway.android.io.elements.ObaRegion
import org.onebusaway.android.map.MapCameraSeed
import org.onebusaway.android.map.MapViewModel
import org.onebusaway.android.report.ui.reportGraph
import org.onebusaway.android.ui.HomeActivity
import org.onebusaway.android.ui.arrivals.ArrivalsViewModel
import org.onebusaway.android.ui.arrivals.arrivalsGraph
import org.onebusaway.android.ui.compose.components.OptOutInfoDialog
import org.onebusaway.android.ui.compose.findActivity
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.ui.home.donation.DonationViewModel
import org.onebusaway.android.ui.home.help.HelpViewModel
import org.onebusaway.android.ui.home.nav.extraDestinations
import org.onebusaway.android.ui.home.weather.WeatherViewModel
import org.onebusaway.android.ui.mylists.myListsGraph
import org.onebusaway.android.ui.nav.NavHelp
import org.onebusaway.android.ui.nav.NavRoutes
import org.onebusaway.android.ui.settings.settingsGraph
import org.onebusaway.android.ui.survey.SurveyViewModel
import org.onebusaway.android.ui.tripdetails.tripGraph
import org.onebusaway.android.ui.tripplan.tripPlanGraph
import org.onebusaway.android.util.ExternalIntents
import org.onebusaway.android.util.PreferenceUtils

/**
 * The HOME destination's dependency surface — the one destination that consumes the full home bundle
 * (the feature ViewModels, the list VMs, the arrivals factory, the callbacks, and the map seed). Built
 * once in [HomeActivity.onCreate] and passed to [HomeNavHost]. Every *other* destination instead
 * recovers the host via `LocalContext.current.findActivity()` and reads its (non-private) members, so
 * only HOME needs this holder (the six feature VMs + the list VMs are private to the activity).
 */
class HomeDestinationDeps(
    val homeViewModel: HomeViewModel,
    val mapViewModel: MapViewModel,
    val surveyViewModel: SurveyViewModel,
    val donationViewModel: DonationViewModel,
    val weatherViewModel: WeatherViewModel,
    val helpViewModel: HelpViewModel,
    val listVms: HomeListViewModels,
    val arrivalsViewModelFactory: ArrivalsViewModel.Factory,
    val callbacks: HomeCallbacks,
    val mapSeed: MapCameraSeed,
    val savedInstanceState: Bundle?,
)

/**
 * The single-Activity Navigation-Compose backbone (Campaign C): every screen is a NavHost destination.
 * Hosted by [HomeActivity] (which created [navController] and staged any external deep-link route);
 * external intents are routed here by `HomeActivity.routeForIntent`. Extracted out of `onCreate` so the
 * activity is a thin Compose-host shell. The HOME destination consumes [home]; the rest live in
 * per-feature `NavGraphBuilder` graphs that recover the host via [findActivity].
 */
@Composable
fun HomeNavHost(
    navController: NavHostController,
    home: HomeDestinationDeps,
) {
    NavHost(navController = navController, startDestination = NavRoutes.HOME) {
        composable(NavRoutes.HOME) {
            val state by home.homeViewModel.uiState.collectAsStateWithLifecycle()
            val routeHeader by home.mapViewModel.routeHeader.collectAsStateWithLifecycle()
            HomeScreen(
                state = state,
                events = home.homeViewModel.events,
                homeViewModel = home.homeViewModel,
                mapViewModel = home.mapViewModel,
                mapSeedLat = home.mapSeed.lat,
                mapSeedLon = home.mapSeed.lon,
                mapSeedZoom = home.mapSeed.zoom,
                mapSavedInstanceState = home.savedInstanceState,
                routeHeader = routeHeader,
                surveyViewModel = home.surveyViewModel,
                donationViewModel = home.donationViewModel,
                weatherViewModel = home.weatherViewModel,
                helpViewModel = home.helpViewModel,
                listVms = home.listVms,
                arrivalsViewModelFactory = home.arrivalsViewModelFactory,
                callbacks = home.callbacks,
                onShowRouteInfo = { routeId ->
                    navController.navigate(NavRoutes.routeInfo(routeId))
                },
                onShowArrivals = { stopId, stopName ->
                    navController.navigate(NavRoutes.arrivals(stopId, stopName))
                },
            )
        }
        // The rest of the graph, grouped by feature (each a NavGraphBuilder extension near its
        // feature; they recover the host via findActivity rather than threading dependencies).
        arrivalsGraph(navController)
        tripGraph(navController)
        myListsGraph(navController)
        settingsGraph(navController)
        reportGraph(navController)
        tripPlanGraph(navController)
        extraDestinations(navController)
    }
}

/**
 * Consumes a staged deep-link route ([pendingDeepLinkRoute]) once the NavHost is ready (and on each
 * `onNewIntent`): navigates to it, popping up to HOME, then clears the latch. Lifted verbatim from the
 * former inline `onCreate` effect.
 */
@Composable
internal fun DeepLinkEffect(
    navController: NavHostController,
    pendingDeepLinkRoute: MutableStateFlow<String?>,
) {
    val pending by pendingDeepLinkRoute.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        pending?.let { route ->
            navController.navigate(route) {
                popUpTo(NavRoutes.HOME) { inclusive = false }
                launchSingleTop = true
            }
            pendingDeepLinkRoute.value = null
        }
    }
}

/**
 * Reports the device accessibility (TalkBack) state to Firebase on each ON_START — replaces the former
 * HomeActivity.onStart. Re-reports on every foreground so a TalkBack toggle made while backgrounded is
 * captured. Fetches the analytics process-singleton from the Context per the codebase convention
 * (ExtraDestinations/MapFeature) rather than holding it as a host field.
 */
@Composable
internal fun AccessibilityAnalyticsEffect() {
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        ObaAnalytics.setAccessibility(
            FirebaseAnalytics.getInstance(context),
            am.isTouchExplorationEnabled,
        )
    }
}

/**
 * Re-home when leaving the settings subtree if the user re-enabled auto-select-region or changed the
 * custom OTP URL (ported from the former SettingsActivity.onDestroy). The auto-select baseline is
 * captured on entry and compared on exit, so a re-home fires only when it was turned back on during this
 * settings visit. Lifted verbatim from the former inline `onCreate` effect; recovers the host to reach
 * the OTP-URL flag and the go-home helper.
 */
@Composable
internal fun SettingsRehomeEffect(navController: NavHostController) {
    val activity = LocalContext.current.findActivity() as HomeActivity
    DisposableEffect(navController) {
        val settingsRoutes = setOf(NavRoutes.SETTINGS, NavRoutes.SETTINGS_ADVANCED)
        val autoSelectKey = activity.getString(R.string.preference_key_auto_select_region)
        var autoSelectInitial: Boolean? = null
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            if (destination.route in settingsRoutes) {
                if (autoSelectInitial == null) {
                    autoSelectInitial = PreferenceUtils.getBoolean(autoSelectKey, true)
                }
            } else if (autoSelectInitial != null) {
                val reEnabledAutoSelect =
                    PreferenceUtils.getBoolean(autoSelectKey, true) && autoSelectInitial == false
                autoSelectInitial = null
                if (reEnabledAutoSelect) {
                    NavHelp.goHome(activity, false)
                } else if (activity.otpCustomAPIUrlChanged) {
                    activity.setOtpCustomAPIUrlChanged(false)
                    NavHelp.goHome(activity, false)
                }
            }
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
}

/**
 * The fare-payment warning (former imperative payment_warning_dialog): shown over any destination when
 * the PAY_FARE menu item needs a region's warning before launching. Driven by [paymentWarningRegion];
 * lifted verbatim from the former inline `onCreate` dialog.
 */
@Composable
internal fun PaymentWarningDialog(paymentWarningRegion: MutableStateFlow<ObaRegion?>) {
    val activity = LocalContext.current.findActivity()
    val warnRegion by paymentWarningRegion.collectAsStateWithLifecycle()
    warnRegion?.let { region ->
        ObaTheme {
            OptOutInfoDialog(
                title = region.paymentWarningTitle.orEmpty(),
                icon = painterResource(android.R.drawable.ic_dialog_alert),
                iconTint = colorResource(R.color.alert_icon_error),
                body = region.paymentWarningBody.orEmpty(),
                optOutLabel = stringResource(R.string.main_never_ask_again),
                onOptOut = {
                    PreferenceUtils.saveBoolean(
                        activity.getString(R.string.preference_key_never_show_payment_warning_dialog), it
                    )
                },
                confirmText = stringResource(R.string.ok),
                onConfirm = {
                    paymentWarningRegion.value = null
                    ExternalIntents.startPaymentIntent(activity, region)
                },
                onDismissRequest = { paymentWarningRegion.value = null },
            )
        }
    }
}
