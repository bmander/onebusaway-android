/*
 * Copyright (C) 2026 Open Transit Software Foundation
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
package org.onebusaway.android.map

import android.content.Context
import android.graphics.Color
import android.location.Location
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.onebusaway.android.R
import org.onebusaway.android.directions.util.OTPConstants
import org.onebusaway.android.io.ObaApi
import org.onebusaway.android.io.elements.ObaReferences
import org.onebusaway.android.io.elements.ObaRoute
import org.onebusaway.android.io.elements.ObaShape
import org.onebusaway.android.io.elements.ObaShapeElement
import org.onebusaway.android.io.elements.ObaStop
import org.onebusaway.android.io.elements.Status
import org.onebusaway.android.io.request.ObaStopsForLocationResponse
import org.onebusaway.android.io.request.ObaStopsForRouteResponse
import org.onebusaway.android.io.request.ObaTripsForRouteResponse
import org.onebusaway.android.map.bike.BikeAction
import org.onebusaway.android.map.bike.BikeStationsRepository
import org.onebusaway.android.map.bike.bikeAction
import org.onebusaway.android.map.bike.filterStations
import org.onebusaway.android.map.render.BikeMarker
import org.onebusaway.android.map.render.CameraCommand
import org.onebusaway.android.map.render.CameraSnapshot
import org.onebusaway.android.map.render.GeoPoint
import org.onebusaway.android.map.render.MapRenderState
import org.onebusaway.android.map.render.RoutePolyline
import org.onebusaway.android.map.render.StopMarker
import org.onebusaway.android.map.render.VehicleMarker
import org.onebusaway.android.map.render.primaryRouteType
import org.onebusaway.android.app.Application
import org.onebusaway.android.location.LocationRepository
import org.onebusaway.android.preferences.PreferencesRepository
import org.onebusaway.android.region.RegionRepository
import org.onebusaway.android.util.LayerUtils
import org.onebusaway.android.util.LocationUtils
import org.onebusaway.android.util.PermissionUtils
import org.onebusaway.android.util.PreferenceUtils
import org.onebusaway.android.util.AndroidUtils
import org.onebusaway.android.util.MyTextUtils
import org.onebusaway.android.util.RegionUtils
import org.onebusaway.android.util.getRouteDescription
import org.onebusaway.android.util.getRouteDisplayName
import org.opentripplanner.api.model.EncodedPolylineBean
import org.opentripplanner.api.model.Itinerary
import org.opentripplanner.api.model.Leg
import org.opentripplanner.api.model.VertexType
import org.opentripplanner.routing.bike_rental.BikeRentalStation
import org.opentripplanner.routing.core.TraverseMode

/**
 * The route-mode header content (the old `R.id.route_info` overlay): the route's short/long name +
 * agency, or a loading state while the route loads. Published while in [MapMode.Route] and rendered
 * as a Compose overlay by the home screen. Null when not in route mode.
 */
data class RouteHeader(
    val loading: Boolean,
    val shortName: String,
    val longName: String,
    val agency: String,
)

/**
 * The map's view model and single source of truth. It owns the flavor-neutral [MapRenderState]
 * (overlays + padding + the camera-command write path), shapes the raw `io/elements` responses into
 * render markers, **and** orchestrates loading: [setMode] launches the reactive loaders that replaced
 * the old `MapModeController` set (StopMapController / RouteMapController / DirectionsMapController /
 * BikeshareMapController). The loaders react to the live camera ([onCameraIdle]) on
 * [viewModelScope], so there is no imperative host driving them — a flavor adapter only renders
 * [renderState] and feeds the camera + taps back in.
 *
 * Scoped to the hosting Activity/Fragment (obtained via `by viewModels()`), so all map screens in a
 * host share one model, and the rendered state survives a configuration change.
 *
 * Its data collaborators are constructor-injected — Hilt provides the repositories, the
 * [RegionRepository]/[LocationRepository] singletons, and the `@ApplicationContext` in production;
 * tests construct it directly with fakes — the standard pattern here, alongside HomeViewModel /
 * WeatherViewModel. The current region is read from [RegionRepository]; the cold-start framing poll
 * calls [LocationRepository.lastKnownLocation] (the lazy provider poll), and the live position reads
 * [LocationRepository.location].
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val stopsRepository: StopsRepository,
    private val routeRepository: RouteMapRepository,
    private val bikeStationsRepository: BikeStationsRepository,
    private val regionRepo: RegionRepository,
    private val locationRepository: LocationRepository,
    private val prefsRepository: PreferencesRepository,
    private val mapInteractionBus: MapInteractionBus,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val renderState = MapRenderState()

    init {
        // Re-center when the region changes from the one present at startup (replaces the host's
        // onRegionChanged push). We compare each emission's id against the last-seen id, seeded with the
        // region at construction — rather than dropping the first emission — so it's race-free: even if
        // the auto-select resolves before this collector starts (making the first value the new region),
        // id != seed still frames it. The startup region of a cached restart matches the seed, so the
        // persisted camera isn't yanked. (The StateFlow already dedups same-id republishes via ObaRegion's
        // id-based equals, so the unchanged-refresh doesn't emit.)
        var lastRegionId = regionRepo.region.value?.id
        viewModelScope.launch {
            regionRepo.region.collect { region ->
                val id = region?.id
                if (region != null && id != lastRegionId) rezoomForRegion()
                lastRegionId = id
            }
        }

        // Apply Home's outbound map interactions via the shared bus (replaces the old HomeMapController
        // VM-on-VM call path). In a host with no Home (e.g. trip-results directions), the bus is idle, so
        // these collectors are no-ops beyond the initial padding=0.
        viewModelScope.launch {
            mapInteractionBus.bottomPadding.collect { setBottomPadding(it) }
        }
        viewModelScope.launch {
            mapInteractionBus.commands.collect { command ->
                when (command) {
                    is MapCommand.RecenterOnFocusedStop ->
                        recenterOnFocusedStop(command.lat, command.lon)
                    is MapCommand.ShowRoute -> showRoute(command.routeId)
                    MapCommand.ClearFocus -> clearFocus()
                }
            }
        }
    }

    // ----- Camera read-back (the hot path) -----

    // The live camera, published by the flavor adapter on every camera-idle. The reactive loaders
    // debounce + dedup off this, replacing the controllers' MapWatcher/onMapChanged callbacks.
    private val _camera = MutableStateFlow<CameraSnapshot?>(null)

    val camera: StateFlow<CameraSnapshot?> = _camera.asStateFlow()

    fun onCameraIdle(snapshot: CameraSnapshot) {
        _camera.value = snapshot
        // A region change that arrived before the map was ready deferred its framing to here (the first
        // idle means the adapter is composed + subscribed to cameraCommands, so the command won't be lost).
        if (pendingRegionFrame) {
            pendingRegionFrame = false
            frameCurrentRegion()
        }
    }

    // ----- Loading / region status + one-shot effects -----

    private val _progress = MutableStateFlow(false)

    /** Whether a viewport/route load is in flight (the old `Callback.showProgress`). */
    val progress: StateFlow<Boolean> = _progress.asStateFlow()

    private val _effects = MutableSharedFlow<MapEffect>(extraBufferCapacity = 8)

    /** One-shot events that need an Activity (e.g. the out-of-range prompt). */
    val effects: SharedFlow<MapEffect> = _effects.asSharedFlow()

    // ----- Bikeshare layer toggle (overlays every mode) -----

    // Defaults off; the host pushes the real `LayerUtils.isBikeshareLayerVisible()` value on resume
    // (kept off the construction path so the model stays JVM-constructible, like WeatherViewModel).
    private val _bikeshareVisible = MutableStateFlow(false)

    fun setBikeshareLayerVisible(visible: Boolean, persist: Boolean = false) {
        // The user-driven toggle persists the choice through the seam; the startup sync (which reads
        // the pref) just applies it, so persistence stays opt-in.
        if (persist) {
            prefsRepository.setBoolean(R.string.preference_key_layer_bikeshare_visible, visible)
        }
        _bikeshareVisible.value = visible
    }

    // ----- Mode + the loaders it drives -----

    private var currentMode: MapMode? = null

    private var stopJob: Job? = null

    private var routeJob: Job? = null

    private var vehicleJob: Job? = null

    private var bikeJob: Job? = null

    // Route-mode loader state (the old RouteMapController fields).
    private var routeId: String? = null

    private var lineOverlayColor: Int = Color.BLUE

    private var zoomToRoute = false

    private var zoomIncludeClosestVehicle = false

    // The last vehicle load's timestamp (nanos), so a resume mid-period waits only the remainder.
    private var lastVehicleLoadNanos = 0L

    // Directions-mode loader state.
    private var selectedBikeStationIds: List<String>? = null

    private val directionsMarkerIds = HashSet<Int>()

    // The directions framing intent, kept so [frameDirections] can (re)apply it once the map is ready
    // (the one-shot camera command dispatched at setMode time is lost before the adapter subscribes).
    private var directionsHasRoute = false

    private var directionsStart: GeoPoint? = null

    /**
     * Switches what the map shows. Cancels the prior mode's loaders, clears its transient overlays
     * (keeping the focused stop), then launches the new mode's loaders. Re-entering the same route
     * is a no-op (the overlays + poll + header stay), matching the legacy "returning to the route
     * view just shows the header" path.
     */
    fun setMode(mode: MapMode) {
        val prev = currentMode
        if (mode is MapMode.Route && prev is MapMode.Route && prev.routeId == mode.routeId) {
            return
        }

        cancelModeJobs()
        // Leaving the prior mode: clear its transient overlays (keep the focused stop, like the
        // controllers' clearCurrentState / removeStopOverlay(false)).
        clearRoute()
        clearVehicles()
        clearDirectionsMarkers()
        clearStops(false)
        setRouteHeader(null)
        _progress.value = false
        selectedBikeStationIds = null
        currentMode = mode

        when (mode) {
            is MapMode.Stop -> stopJob = launchStopLoader()
            is MapMode.Route -> startRouteMode(mode)
            is MapMode.Directions -> {
                selectedBikeStationIds = bikeStationIdsFromItinerary(mode.itinerary)
                startDirectionsMode(mode.itinerary)
            }
        }
        bikeJob = launchBikeLoader(mode)
    }

    private fun cancelModeJobs() {
        stopJob?.cancel()
        routeJob?.cancel()
        vehicleJob?.cancel()
        bikeJob?.cancel()
        stopJob = null
        routeJob = null
        vehicleJob = null
        bikeJob = null
    }

    // ----- Stop loader (replaces StopMapController) -----

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun launchStopLoader(): Job = viewModelScope.launch {
        // The "is the last load still good for this viewport?" state (the old StopsResponse): the
        // camera the last completed load was made at + whether it had a response + its limit flag.
        var lastLoad: CameraSnapshot? = null
        var lastHadResponse = false
        var lastLimitExceeded = false

        _camera
            .filterNotNull()
            .debounce(STOP_LOAD_DEBOUNCE_MS)
            .filterNot { next -> stopRequestFulfilled(lastLoad, lastHadResponse, lastLimitExceeded, next) }
            .flatMapLatest { snapshot ->
                // flatMapLatest cancels an in-flight load when a newer viewport arrives, matching the
                // controller's `loadJob?.cancel()`; a cancelled load leaves lastLoad untouched.
                flow {
                    _progress.value = true
                    val response = stopsRepository
                        .getStops(snapshot.center.toLocation(), snapshot.latSpan, snapshot.lonSpan)
                        .getOrNull()
                    lastLoad = snapshot
                    lastHadResponse = response != null
                    lastLimitExceeded = response?.limitExceeded ?: false
                    emit(response)
                }
            }
            .collect { response ->
                _progress.value = false
                onStopsLoaded(response)
            }
    }

    private fun onStopsLoaded(response: ObaStopsForLocationResponse?) {
        if (response == null) {
            // Initial install can generate a null response if all is still ok, so do nothing (#615).
            return
        }
        if (response.code != ObaApi.OBA_OK) {
            MapUtils.showMapError(response)
            return
        }
        if (response.outOfRange) {
            notifyOutOfRange()
            return
        }

        // Workaround for https://github.com/OneBusAway/onebusaway-application-modules/issues/59 where
        // the outOfRange element is false even if the location was out of range. We also make sure the
        // list of stops is empty, otherwise we'd screen out valid responses.
        val myLocation = locationRepository.location.value
        val region = regionRepo.region.value
        if (myLocation != null && region != null) {
            var inRegion = true // Assume user is in region unless we detect otherwise.
            try {
                inRegion = RegionUtils.isLocationWithinRegion(myLocation, region)
            } catch (e: IllegalArgumentException) {
                // Issue #69 - some devices are providing invalid lat/long coordinates.
                Log.e(
                    TAG, "Invalid latitude or longitude - lat = " + myLocation.latitude +
                            ", long = " + myLocation.longitude
                )
            }
            if (!inRegion && response.stops.isEmpty()) {
                Log.d(TAG, "Device location is outside region range, notifying...")
                notifyOutOfRange()
                return
            }
        }

        showStops(response.stops.toList(), response)
    }

    private fun notifyOutOfRange() {
        _effects.tryEmit(MapEffect.OutOfRange)
    }

    // ----- Route loader (replaces RouteMapController) -----

    private fun startRouteMode(mode: MapMode.Route) {
        routeId = mode.routeId
        zoomToRoute = mode.zoomToRoute
        zoomIncludeClosestVehicle = mode.zoomIncludeClosestVehicle
        setRouteHeader(RouteHeader(loading = true, shortName = "", longName = "", agency = ""))
        _progress.value = true
        loadRoute()
        startVehiclePolling(0L)
    }

    private fun loadRoute() {
        val id = routeId ?: return
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            onRouteLoaded(routeRepository.getRoute(id).getOrNull())
        }
    }

    private fun onRouteLoaded(response: ObaStopsForRouteResponse?) {
        if (response == null || response.code != ObaApi.OBA_OK) {
            MapUtils.showMapError(response)
            return
        }
        val route = response.getRoute(response.routeId)
        setRouteHeader(
            RouteHeader(
                loading = false,
                shortName = MyTextUtils.formatDisplayText(getRouteDisplayName(route))!!,
                longName = MyTextUtils.formatDisplayText(getRouteDescription(route))!!,
                agency = response.getAgency(route.agencyId).name ?: "",
            )
        )
        route.color?.let { lineOverlayColor = it }
        setRoute(lineOverlayColor, response.shapes, clear = true)
        showStops(response.stops, response)
        _progress.value = false
        if (zoomToRoute) {
            dispatchCamera(CameraCommand.FitToRoute)
            zoomToRoute = false
        }
    }

    /**
     * (Re)starts the real-time vehicle poll: after [initialDelayMs], reload vehicles every
     * [VEHICLE_REFRESH_PERIOD_MS] measured from each load's completion (so network time is excluded),
     * matching the legacy `postDelayed`-after-`onLoadFinished` cadence. The loop continues on a fixed
     * cadence even if a load fails.
     */
    private fun startVehiclePolling(initialDelayMs: Long) {
        vehicleJob?.cancel()
        vehicleJob = viewModelScope.launch {
            if (initialDelayMs > 0L) {
                delay(initialDelayMs)
            }
            while (isActive) {
                val id = routeId
                if (id != null) {
                    onVehiclesLoaded(id, routeRepository.getVehicles(id).getOrNull())
                }
                delay(VEHICLE_REFRESH_PERIOD_MS)
            }
        }
    }

    private fun onVehiclesLoaded(routeId: String, response: ObaTripsForRouteResponse?) {
        if (response == null || response.code != ObaApi.OBA_OK) {
            MapUtils.showMapError(response)
            return
        }
        val routes = hashSetOf(routeId)
        updateVehicles(routes, response)
        if (zoomIncludeClosestVehicle) {
            dispatchCamera(CameraCommand.IncludeClosestVehicle(routes, response))
            zoomIncludeClosestVehicle = false
        }
        lastVehicleLoadNanos = AndroidUtils.getCurrentTimeForComparison()
    }

    // ----- Directions loader (replaces DirectionsMapController) -----

    private fun startDirectionsMode(itinerary: Itinerary) {
        val legs = itinerary.legs
        if (legs.isEmpty()) {
            return
        }
        val firstLeg = legs.first()
        val lastLeg = legs.last()
        val startLat = firstLeg.from.lat
        val startLon = firstLeg.from.lon
        val endLat = lastLeg.to.lat
        val endLon = lastLeg.to.lon

        var hasRoute = false
        for (leg in legs) {
            val shape = LegShape(leg.legGeometry)
            if (shape.length > 0) {
                hasRoute = true
                // Append each leg's polyline in its own color (clear=false), like the legacy controller.
                setRoute(resolveLegColor(leg), arrayOf<ObaShape>(shape), clear = false)
            }
        }

        directionsMarkerIds.add(addMarker(startLat, startLon, HUE_GREEN))
        directionsMarkerIds.add(addMarker(endLat, endLon, HUE_RED))

        directionsHasRoute = hasRoute
        directionsStart = GeoPoint(startLat, startLon)
        frameDirections()
    }

    /**
     * Frames the current directions itinerary: fit the route shape, or (no route — start == end)
     * center on the start at the default zoom. Re-appliable so the owner can frame once the map is
     * ready (the frame dispatched at [setMode] time is lost before the adapter subscribes).
     */
    fun frameDirections() {
        if (directionsHasRoute) {
            dispatchCamera(CameraCommand.FitToItinerary)
        } else {
            directionsStart?.let {
                dispatchCamera(CameraCommand.Recenter(it.latitude, it.longitude, animate = false, applyRouteBias = false))
                dispatchCamera(CameraCommand.SetZoom(MapParams.DEFAULT_ZOOM.toFloat()))
            }
        }
    }

    private fun clearDirectionsMarkers() {
        directionsMarkerIds.forEach { removeMarker(it) }
        directionsMarkerIds.clear()
    }

    // ----- Bike loader (replaces BikeshareMapController) -----

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun launchBikeLoader(mode: MapMode): Job = viewModelScope.launch {
        val directions = mode is MapMode.Directions
        combine(
            _camera.filterNotNull().debounce(STOP_LOAD_DEBOUNCE_MS),
            _bikeshareVisible,
        ) { camera, layerVisible -> camera to layerVisible }
            // collectLatest so a newer viewport cancels an in-flight station load (the old loadJob?.cancel()).
            .collectLatest { (camera, layerVisible) ->
                if (!Application.isBikeshareEnabled()) {
                    return@collectLatest
                }
                when (bikeAction(directions, selectedBikeStationIds, layerVisible)) {
                    BikeAction.LEAVE -> {}
                    BikeAction.CLEAR -> clearBikeStations()
                    BikeAction.SHOW -> {
                        val stations = bikeStationsRepository
                            .getStations(camera.southWest.toLocation(), camera.northEast.toLocation())
                            .getOrNull() ?: return@collectLatest
                        filterStations(stations, selectedBikeStationIds)?.let {
                            showBikeStations(it, bikeshareVisible = directions || layerVisible)
                        }
                    }
                }
            }
    }

    private fun bikeStationIdsFromItinerary(itinerary: Itinerary): List<String> {
        val ids = ArrayList<String>()
        for (leg in itinerary.legs) {
            if (TraverseMode.BICYCLE.toString() == leg.mode) {
                if (VertexType.BIKESHARE == leg.from.vertexType) {
                    ids.add(leg.from.bikeShareId)
                }
                if (VertexType.BIKESHARE == leg.to.vertexType) {
                    ids.add(leg.to.bikeShareId)
                }
            }
        }
        return ids
    }

    // The route-mode header, published while in route mode and rendered as a Compose overlay by the
    // home screen. Null outside route mode.
    private val _routeHeader = MutableStateFlow<RouteHeader?>(null)

    val routeHeader: StateFlow<RouteHeader?> = _routeHeader.asStateFlow()

    fun setRouteHeader(header: RouteHeader?) {
        _routeHeader.value = header
    }

    // Map content padding: the route-mode header sets the top, the arrivals sheet sets the bottom.
    // Declarative state the renderer applies (Google: GoogleMap contentPadding), replacing the old
    // imperative mapView.setPadding(...) relay through HomeActivity.
    fun setTopPadding(px: Int) = renderState.setTopPadding(px)

    fun setBottomPadding(px: Int) = renderState.setBottomPadding(px)

    private fun dispatchCamera(command: CameraCommand) = renderState.dispatchCamera(command)

    // ----- Focus -----
    // The view model owns only the *render* focus (the 1.5x icon, via renderState.focusedStopId) and
    // the camera move. The home-side focus (the arrivals sheet + analytics) is driven directly by the
    // owner's tap callback calling its own focus handler — so a re-tap of the same stop isn't swallowed
    // by state-dedup, and there's no tap → state → home feedback flow to reason about.

    /** A stop marker was tapped: render-focus it + center on it (the old GoogleMapHost.onStopClick). */
    fun onStopTapped(stop: ObaStop) {
        setFocusedStopId(stop.id)
        val loc = stop.location
        dispatchCamera(CameraCommand.CenterOnStopTap(loc.latitude, loc.longitude))
    }

    /** A tap away from any marker clears the render focus (the old onMapClick). */
    fun onMapTapped() {
        setFocusedStopId(null)
    }

    /**
     * Programmatic focus for a restored/deep-linked stop once its arrivals load (the old
     * MapCommand.FocusStop): ensure the stop is on the map + render-focused, and center on it
     * (route-header bias only in route mode when the sheet settled expanded).
     */
    fun focusStop(stop: ObaStop, routes: List<ObaRoute>?, overlayExpanded: Boolean) {
        val loc = stop.location
        dispatchCamera(
            CameraCommand.Recenter(
                loc.latitude, loc.longitude,
                animate = false,
                applyRouteBias = isRouteMode && overlayExpanded,
            )
        )
        setFocusStop(stop, routes)
    }

    /** Clear the render focus (back-press from a peeking sheet; the old MapCommand.ClearFocus). */
    fun clearFocus() {
        setFocusStop(null, null)
    }

    /** Animate/move the camera to a point with no route-header bias (a general recenter for any screen). */
    fun centerOn(lat: Double, lon: Double, animate: Boolean) {
        dispatchCamera(CameraCommand.Recenter(lat, lon, animate, applyRouteBias = false))
    }

    /** Recenter on the focused stop after the arrivals sheet expands over it (old MapCommand.Recenter). */
    fun recenterOnFocusedStop(lat: Double, lon: Double) {
        dispatchCamera(
            CameraCommand.Recenter(lat, lon, animate = true, applyRouteBias = isRouteMode)
        )
    }

    private val isRouteMode: Boolean get() = currentMode is MapMode.Route

    /** The current mode, or null before the first [setMode] (used to init mode once after process death). */
    val currentMapMode: MapMode? get() = currentMode

    // ----- "Show route on map" / leave route mode (replaces ShowRoute / ExitRouteMode commands) -----

    /** Enter route mode for [routeId], framing the nearest vehicle (the "show route on map" action). */
    fun showRoute(routeId: String) {
        setMode(MapMode.Route(routeId, zoomToRoute = false, zoomIncludeClosestVehicle = true))
    }

    /** Leave route mode back to stop mode, preserving the current camera (the route header's cancel). */
    fun exitRouteMode() {
        setMode(MapMode.Stop)
    }

    // ----- Region re-zoom (the old ObaRegionsTask.Callback.onRegionTaskFinished) -----

    // Set when a region change wants to re-center but the map hasn't published its camera yet — the
    // camera-command flow has no replay, so a command dispatched before the adapter subscribes is lost.
    // Consumed on the first onCameraIdle (when the adapter is definitely subscribed).
    private var pendingRegionFrame = false

    /**
     * The current region changed (driven by the region collector, so this only fires on a real change to
     * a present region). Frames it once the map is ready; if the map hasn't published a camera yet, defer
     * to the first [onCameraIdle] so the camera command isn't lost.
     */
    private fun rezoomForRegion() {
        if (_camera.value == null) {
            pendingRegionFrame = true
            return
        }
        frameCurrentRegion()
    }

    /**
     * Frame the current region: if we have no location, or the camera is still at the (0,0) seed, frame
     * the user's location if we have one, else the region — but don't yank a camera the user already moved.
     */
    private fun frameCurrentRegion() {
        // lastKnownLocation() (not the repo's .value): this runs at cold-start framing and must trigger
        // the lazy provider poll so the first frame can target the user's location.
        val location = locationRepository.lastKnownLocation()
        val center = _camera.value?.center
        val atSeed = center == null || (center.latitude == 0.0 && center.longitude == 0.0)
        when (regionRezoom(changed = true, hasLocation = location != null, cameraAtSeed = atSeed)) {
            RegionRezoom.FrameMyLocation -> requestMyLocation(useDefaultZoom = true, animate = false)
            RegionRezoom.FrameRegion -> dispatchCamera(CameraCommand.ZoomToRegion)
            RegionRezoom.None -> {}
        }
    }

    // ----- My-location / styling / permission (replaces the host's location machinery) -----

    private val _myLocationEnabled = MutableStateFlow(false)

    /** Whether the blue-dot my-location layer is enabled (granted permission). Applied by the adapter. */
    val myLocationEnabled: StateFlow<Boolean> = _myLocationEnabled.asStateFlow()

    /** Re-reads the location permission and reflects it in [myLocationEnabled] (call on resume/grant). */
    fun refreshMyLocationEnabled() {
        _myLocationEnabled.value =
            PermissionUtils.hasGrantedAtLeastOnePermission(context, PermissionUtils.LOCATION_PERMISSIONS)
    }

    /**
     * Called once when the map first shows: enable the blue dot if we already have permission, else —
     * unless the user already declined — raise the rationale so the first-launch flow can ask (this is
     * the host's old initMap → requestPermissionAndInit eager prompt, which also drives the deferred
     * first-launch region check via the permission result).
     */
    fun requestLocationPermissionIfNeeded() {
        if (PermissionUtils.hasGrantedAtLeastOnePermission(context, PermissionUtils.LOCATION_PERMISSIONS)) {
            _myLocationEnabled.value = true
        } else if (!PreferenceUtils.userDeniedLocationPermission()) {
            _effects.tryEmit(MapEffect.ShowPermissionRationale)
        }
    }

    /** The Activity delivered a location-permission result; reflect it (blue dot on grant). */
    fun onLocationPermissionResult(granted: Boolean) {
        PreferenceUtils.setUserDeniedLocationPermissions(!granted)
        if (granted) {
            _myLocationEnabled.value = true
            // onResume already ran startUpdates() with no permission (a no-op); now that it's granted,
            // begin the live feed immediately rather than waiting for the next resume.
            locationRepository.startUpdates()
        }
    }

    /**
     * The my-location FAB / a programmatic recenter: center on the user's location, or raise the
     * appropriate effect (services off / permission needed / no fix yet). Ported from
     * GoogleMapHost.setMyLocation; the dialogs + the permission launcher are Activity effects.
     */
    fun requestMyLocation(useDefaultZoom: Boolean, animate: Boolean) {
        val app = context
        // lastKnownLocation() (not the repo's .value): the FAB must trigger the lazy provider poll, like
        // the legacy Application.getLastKnownLocation. Reading .value would show the "waiting" toast
        // whenever nothing has seeded the flow yet (e.g. the cold-start poll ran before permission was
        // granted, or the region never changed so frameCurrentRegion never polled).
        val last = locationRepository.lastKnownLocation()
        val action = myLocationAction(
            locationEnabled = LocationUtils.isLocationEnabled(app),
            neverShowLocationDialog =
                prefsRepository.getBoolean(R.string.preference_key_never_show_location_dialog, false),
            hasLastKnownLocation = last != null,
            hasPermission = PermissionUtils.hasGrantedAtLeastOnePermission(
                app, PermissionUtils.LOCATION_PERMISSIONS
            ),
            userDeniedPermission = PreferenceUtils.userDeniedLocationPermission(),
        )
        when (action) {
            MyLocationAction.MoveToLocation -> last?.let {
                dispatchCamera(
                    CameraCommand.MoveToLocation(it.latitude, it.longitude, useDefaultZoom, animate)
                )
            }
            MyLocationAction.ShowNoLocationDialog -> _effects.tryEmit(MapEffect.NoLocation)
            MyLocationAction.ShowPermissionRationale -> _effects.tryEmit(MapEffect.ShowPermissionRationale)
            MyLocationAction.ShowWaitingToast -> _effects.tryEmit(MapEffect.WaitingForLocation)
            MyLocationAction.None -> {}
        }
    }

    fun zoomIn() = dispatchCamera(CameraCommand.ZoomIn)

    fun zoomOut() = dispatchCamera(CameraCommand.ZoomOut)

    /** Frame the current region's bounds (the out-of-range dialog's "take me there"). */
    fun zoomToRegion() = dispatchCamera(CameraCommand.ZoomToRegion)

    // ----- Lifecycle (the owner forwards onPause/onResume) -----

    /** Stop the vehicle poll and persist the camera for the next launch's seed (the host's onPause). */
    fun onPause() {
        vehicleJob?.cancel()
        // Stop the live location feed so the providers don't run in the background (battery).
        locationRepository.stopUpdates()
        _camera.value?.let {
            PreferenceUtils.saveMapViewToPreferences(it.center.latitude, it.center.longitude, it.zoom.toFloat())
        }
    }

    /** Refresh prefs-backed state and restart the vehicle poll if in route mode (the host's onResume). */
    fun onResume() {
        setBikeshareLayerVisible(LayerUtils.isBikeshareLayerVisible(context))
        refreshMyLocationEnabled()
        // Begin the live location feed for as long as the map is shown (permission-gated; a no-op until
        // granted). This is what makes `location` a live stream — the legacy host's LocationHelper feed.
        locationRepository.startUpdates()
        if (isRouteMode && vehicleJob?.isActive != true) {
            startVehiclePolling(nextVehicleDelay(lastVehicleLoadNanos, AndroidUtils.getCurrentTimeForComparison()))
        }
    }

    // Stop accumulation across pans (capped, keeping the focused stop) + the routes cache used to
    // resolve a stop's icon route type and to report a stop's routes to focus listeners.
    private val stopAccum = LinkedHashMap<String, StopMarker>()

    private val cachedRoutes = HashMap<String, ObaRoute>()

    // routeId -> ObaRoute.TYPE_*, maintained alongside cachedRoutes so toStopMarker doesn't rebuild
    // the lookup on every pan.
    private val routeTypeById = HashMap<String, Int>()

    /** Adds routes to the caches that a stop tap reports + the icon route-type lookup. */
    private fun cacheRoutes(routes: Iterable<ObaRoute>) {
        for (route in routes) {
            cachedRoutes[route.id] = route
            routeTypeById[route.id] = route.type
        }
    }

    // ----- Stops -----

    fun showStops(stops: List<ObaStop>, refs: ObaReferences) {
        cacheRoutes(refs.routes)
        capStopAccumulation(stopAccum, renderState.snapshot.value.focusedStopId, FUZZY_MAX_STOP_COUNT)
        for (stop in stops) {
            if (!stopAccum.containsKey(stop.id)) {
                stopAccum[stop.id] = toStopMarker(stop)
            }
        }
        renderState.setStops(ArrayList(stopAccum.values))
    }

    /** Clears accumulated stops; keeps the focused one unless [clearFocusedStop]. */
    fun clearStops(clearFocusedStop: Boolean) {
        if (clearFocusedStop) {
            stopAccum.clear()
            renderState.setFocusedStopId(null)
        } else {
            retainOnlyFocusedStop(stopAccum, renderState.snapshot.value.focusedStopId)
        }
        renderState.setStops(ArrayList(stopAccum.values))
    }

    /** Programmatic focus (intent/rotation): ensures the stop is on the map, then focuses it. */
    fun setFocusStop(stop: ObaStop?, routes: List<ObaRoute>?) {
        if (stop == null) {
            renderState.setFocusedStopId(null)
            return
        }
        if (!stopAccum.containsKey(stop.id)) {
            routes?.let { cacheRoutes(it) }
            stopAccum[stop.id] = toStopMarker(stop)
            renderState.setStops(ArrayList(stopAccum.values))
        }
        renderState.setFocusedStopId(stop.id)
    }

    fun setFocusedStopId(stopId: String?) = renderState.setFocusedStopId(stopId)

    /** A snapshot copy of the cached routes, for reporting a stop's routes to focus listeners. */
    fun cachedRoutes(): HashMap<String, ObaRoute> = HashMap(cachedRoutes)

    private fun toStopMarker(stop: ObaStop): StopMarker {
        val routeType = primaryRouteType(stop.routeIds, routeTypeById)
        val loc = stop.location
        // ObaStop.getDirection() is "N".."NW" or the literal "null" string for no direction.
        val direction = stop.direction ?: "null"
        return StopMarker(stop.id, GeoPoint(loc.latitude, loc.longitude), direction, routeType, stop)
    }

    // ----- Route polylines -----

    fun setRoute(color: Int, shapes: Array<ObaShape>, clear: Boolean) {
        val polylines = ArrayList<RoutePolyline>(if (clear) emptyList() else renderState.getRoutePolylines())
        for (shape in shapes) {
            val points = shape.points.map { GeoPoint(it.latitude, it.longitude) }
            polylines.add(RoutePolyline(color, points))
        }
        renderState.setRoutePolylines(polylines)
    }

    fun clearRoute() = renderState.clearRoutePolylines()

    /** The route/itinerary point lists, for the host to compute camera bounds. */
    fun routePoints(): List<List<GeoPoint>> = renderState.getRoutePolylines().map { it.points }

    // ----- Vehicles -----

    fun updateVehicles(routeIds: HashSet<String>, response: ObaTripsForRouteResponse) {
        val markers = ArrayList<VehicleMarker>()
        for (trip in response.trips) {
            val status = trip.status ?: continue
            val activeRoute = response.getTrip(status.activeTripId).routeId
            if (!routeIds.contains(activeRoute) || Status.CANCELED == status.status) {
                continue
            }
            // Use the (possibly extrapolated) last-known location when present; it's "real-time" only
            // if that location exists and the trip is predicted (else fall back to the last position).
            val location = status.lastKnownLocation ?: status.position
            val isRealtime = status.lastKnownLocation != null && status.isPredicted
            markers.add(
                VehicleMarker(
                    status.activeTripId,
                    GeoPoint(location.latitude, location.longitude),
                    isRealtime,
                    status,
                )
            )
        }
        renderState.setVehicles(markers, response)
    }

    fun clearVehicles() = renderState.clearVehicles()

    // ----- Bikes -----

    fun showBikeStations(stations: List<BikeRentalStation>, bikeshareVisible: Boolean) {
        val markers = stations.map {
            BikeMarker(it.id, GeoPoint(it.y, it.x), it.isFloatingBike, it)
        }
        renderState.setBikeStations(markers, bikeshareVisible)
    }

    fun clearBikeStations() = renderState.clearBikeStations()

    // ----- Generic markers -----

    fun addMarker(latitude: Double, longitude: Double, hue: Float?): Int =
        renderState.addMarker(GeoPoint(latitude, longitude), hue)

    fun removeMarker(id: Int) = renderState.removeMarker(id)

    private fun GeoPoint.toLocation(): Location = LocationUtils.makeLocation(latitude, longitude)

    private fun resolveLegColor(leg: Leg): Int {
        // Color for transit routes when planning a trip.
        if (TraverseMode.valueOf(leg.mode).isTransit) {
            return OTPConstants.OTP_TRANSIT_COLOR
        }
        // Use the route's custom color if available.
        leg.routeColor?.let { hex ->
            try {
                return java.lang.Long.decode("0xFF$hex").toInt()
            } catch (ex: Exception) {
                Log.e(TAG, "Error parsing color=$hex: ${ex.message}")
            }
        }
        // Defaults to grey, which represents walking.
        return Color.GRAY
    }

    /** An [ObaShape] over an OTP [EncodedPolylineBean] leg geometry (ported from DirectionsMapController). */
    private class LegShape(private val bean: EncodedPolylineBean) : ObaShape {
        override fun getLength(): Int = bean.length
        override fun getRawLevels(): String = bean.levels
        override fun getLevels(): List<Int> = ObaShapeElement.decodeLevels(bean.levels, bean.length)
        override fun getPoints(): List<Location> = ObaShapeElement.decodeLine(bean.points, bean.length)
        override fun getRawPoints(): String = bean.points
    }

    companion object {
        private const val TAG = "MapViewModel"

        private const val FUZZY_MAX_STOP_COUNT = 200

        // Debounce before reacting to a camera move, matching the old MapWatcher poll cadence.
        private const val STOP_LOAD_DEBOUNCE_MS = 250L

        // BitmapDescriptorFactory hues for the directions start/end pins (green/red), kept as literals
        // since the map package can't depend on the Google Maps classes.
        private const val HUE_GREEN = 120.0f

        private const val HUE_RED = 0.0f
    }
}
