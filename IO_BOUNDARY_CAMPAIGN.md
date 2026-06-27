# Campaign: confine wire DTOs to io.client; feature code uses model interfaces only

Goal (user-chosen, strict boundary): no file outside `org.onebusaway.android.io` references a wire
DTO (`StopReference`/`RouteReference`/`TripStatus`/`TripReference`/`TripDetailsEntry`/`ArrivalsForStop`/
`ArrivalDeparture`/`SituationReference`/`AgencyCoverage`/`AgencyReference`/…), an envelope/refs
container (`ObaEnvelope`/`EntryWithReferences`/`ListWithReferences`/`References`), or `ObaWebService`.
The wire fetch **and** the DTO→model adaptation move INTO `io.client`, which exposes data sources
returning the domain model types (the `models` interfaces `ObaStop`/`ObaRoute`/`ObaTripDetails`/… for
stops/routes/trips; `AgencyItem`/`ArrivalData`/survey models for the rest). The `Dto*` adapters STAY
(they're the in-io conversion); the feature repos keep only their domain/state logic.

Pattern (mirrors the existing io.client `RouteRepository` → `RouteDetails`): for each endpoint, an
io.client `@Singleton` data source (interface + Default impl, Hilt-bound) with `suspend` methods that
call the WebService, `requireData()`, adapt via the Dto* adapters, and return `Result<model>` /
model. Feature repos inject the data source; drop the `ObaWebService`/envelope/DTO imports.

## Leak sites (feature files importing io.client wire types)
- map/StopsRepository, map/RouteMapRepository  (DtoStop/DtoRoute/References/requireData/ObaWebService)
- ui/searchresults/SearchResultsRepository      (RouteReference/StopReference/DtoRoute/DtoStop/ListWithReferences)
- ui/routeinfo/RouteInfoRepository              (stops-for-route)
- ui/arrivals/ArrivalsRepository + ArrivalData  (ArrivalsForStop/ArrivalDeparture/EntryWithReferences/References/StopReference/DtoStop/DtoRoute)
- ui/agencies/AgenciesRepository                (AgencyCoverage/ListWithReferences)
- extrapolation/data/RouteTrips + TripObservationFetcher (TripDetailsEntry/ObaEnvelope/References/toObaTripSchedule)
- database/survey/SurveyDbHelper                (StudyResponse)
- mock/test fixtures (adjust to the new data-source seam)

Allowed to remain outside io (the io public API, not wire DTOs): the io.client repository/data-source
types themselves, `ObaApiException`, `Result`. (DI wiring in app/di references WebServices for binding
— that's the composition root; fine.)

## Phases (each compiles + commits; device-verify the map/arrivals ones)
- P1 (pilot) — SearchResults: io.client `RouteSearchDataSource`/`StopSearchDataSource` (or one
  `SearchDataSource`) returning `List<ObaRoute>`/`List<ObaStop>`; rewrite SearchResultsRepository.
- P2 — Map: StopsRepository → io.client nearby-stops source (Result<NearbyStops> of ObaStop/ObaRoute);
  RouteMapRepository → io.client route-map source (Result<RouteMap>). DEVICE.
- P3 — RouteInfo (stops-for-route).
- P4 — Agencies (agencies-with-coverage → List<AgencyItem>).
- P5 — Arrivals: move the arrivals fetch+adapt (poll/stale/lastGood stays? decide) so ArrivalData/refs
  are produced in io; ArrivalsRepository keeps ArrivalInfo building. DEVICE.
- P6 — extrapolation: RouteTrips construction + TripObservationFetcher fetch move into io.
- P7 — Survey DB (StudyResponse).
- Final: grep proves zero wire-DTO/envelope/ObaWebService imports outside io/.

## Notes
- P2 GeoPoint wrinkle: `GeoPoint` lives in `map/render` (MapRenderState.kt). io.client must NOT depend
  on it — the io route data source returns polylines as `List<List<Location>>`; the map layer maps
  Location→GeoPoint. `NearbyStops`/route holder (model interfaces only) can live in io.client and be
  consumed by the map (same as `RouteDetails` today — io model holders are fine outside io; only wire
  DTOs are not). The `obaApiCall`/`hasObaApiEndpoint` endpoint gate moves into io too.

## Status (all DONE phases: compile both flavors + JVM unit green)
- P1 (SearchResults): DONE 7a91be34 — io.client `LocationSearchRepository` → `Result<List<ObaRoute/ObaStop>>`.
- P2 (map): DONE 251deed2 — io.client `MapDataSource` (NearbyStops + RouteMapData, polylines as
  Location); map repos thin (DefaultRouteMapRepository does Location→GeoPoint). DEVICE-VERIFIED
  (stops-for-location 200 + markers; stops-for-route?includePolylines=true 200 + route line drawn).
- P3 (routeinfo): DONE 2ac18f12 — io.client `RouteStopsRepository` → `List<RouteStopGroup>`; ui keeps
  RouteStopGroup→RouteDirection presentation. Mapper test split (wire→io, presentation→ui).
- P4 (agencies): DONE 8db12169 — whole AgenciesRepository + AgencyItem moved into io.client.
- P5 (arrivals): NOT STARTED — the big one. ArrivalsRepository builds ArrivalInfo (Context/resources) +
  has poll/stale/lastGood state + ArrivalData wraps ArrivalDeparture; situations via SituationUtils.
  Move the fetch+adapt (ArrivalsForStop → ArrivalData list + ObaStop/ObaRoute refs + situations) into
  io; ArrivalsRepository keeps ArrivalInfo building. DEVICE-GATED.
- P6 (extrapolation): NOT STARTED — RouteTrips construction (asRouteTrips, currently in extrapolation/
  data) + TripObservationFetcher fetch (tripDetails/shape) move into io. DEVICE-GATED (vehicles).
- P7 (survey DB): NOT STARTED — SurveyDbHelper persists the wire StudyResponse; needs a survey domain
  model. Check ripple into SurveyViewModel before sizing.
