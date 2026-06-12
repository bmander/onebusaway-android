# HomeActivity Modernization Plan — Full Kotlin + Compose + MVVM

*Written 2026-06-11 on branch `modernization/compose-mvvm-report`. This is the deferred
follow-up that MODERNIZATION_DEBT.md's 2026-06-11 entry names: finishing the job that phases
P0–P6 started, taking `HomeActivity` from "Compose shell over a Java-driven bridge" to a screen
that looks like it was written from scratch today — idiomatic Kotlin, declarative Compose with
unidirectional data flow, coroutines for all async work — within the hard `minSdk 21` constraint.*

> **Status — 2026-06-11.** P7, then P9 (port), then P8 (hoist) have landed on
> `modernization/compose-mvvm-home-vm` (commits `Home P7` / `Home P9` / `Home P8`).
> **P8 and P9 were swapped from the numbering below — the Kotlin port was done *first*.** Wiring
> the ViewModel needs Kotlin-native idioms (`by viewModels`, `createSavedStateHandle`,
> `collectAsStateWithLifecycle`, `repeatOnLifecycle`) that have *zero* precedent from Java in this
> codebase, so porting first avoided writing throwaway Java↔Kotlin VM interop.
>
> Two P8 sub-goals were **deferred** because they're entangled with the still-present map +
> arrivals *fragments* (which P10/P11 dissolve): (a) `SavedStateHandle` + the focused-stop hoist —
> so `onSaveInstanceState` stays for now and the savedstate dependency was never needed; and
> (b) collapsing the int nav model — it's retained alongside the VM's `selectedItem`. What P8
> *did* hoist: the chrome/overlay/dialog/menu/nav-selection state (derived gates in a pure
> `buildState()`) plus the weather + GTFS async (`viewModelScope`). The arrivals sheet stays
> imperative until P10's declarative inversion. Region refresh stayed on `ObaRegionsTask` (it
> still notifies the map fragment), so P7's `RegionStatusRepository` is unused until P14. Net:
> `HomeActivity` 1,956 → ~1,360 lines; `HomeShellHost` lost its ~20 setters; 14 new JVM tests.
> A small behavior *improvement*: GTFS alerts now fire once per region (no duplicate dialogs).
>
> **Update — 2026-06-12.** P10, P11, and **P11b** have now landed on
> `modernization/compose-mvvm-home-vm`, all on-device verified:
> - **P10** inverted the arrivals sheet to declarative (visibility = business state via a keyed
>   `LaunchedEffect`; expansion = the live `SheetState` nudged by one-shot events) and dissolved
>   `HomeShellHost` into a real `HomeScreen`. Pure sheet-decision logic was extracted to
>   `HomeSheetLogic.kt` with JVM tests.
> - **P11** dissolved `ArrivalsPanelFragment` into a per-stop keyed Compose sheet host
>   (`ArrivalsSheetHost`); the focused stop hoisted into `HomeViewModel` + `SavedStateHandle`.
> - **P11b** retired Home's content fragments in two halves: (1) Home's three list views became
>   Compose overlays over the map, and the shared list helpers were generalized from `Fragment`- to
>   `AppCompatActivity`-scope; (2) the legacy `My*` tab/shell activities were rewritten in Kotlin +
>   Compose (`TabRow` + `HorizontalPager`, full Material3 `TopAppBar`), and **all seven list/search
>   fragments** + `MyTabActivityBase` + the vendored `ListFragment.java` were deleted. Pinned-shortcut
>   contracts (class names, manifest, `com.joulespersecond.seattlebusbot` aliases, `tab://` tags) were
>   preserved verbatim.
>
> Home is now **fragment-free except the native map host** (P14). Deferred from P8 are now closed:
> the focused-stop `SavedStateHandle` hoist (P10/P11) landed; the int nav model still rides alongside
> `selectedItem` (collapses with P14's permission-launcher work).
>
> **Update — 2026-06-12 (later).** **P12** has landed: Home's hosted `MaterialToolbar` + options menu
> became a Compose `TopAppBar` (`HomeTopBar.kt`) with an inline search field that fires the existing
> `ACTION_SEARCH` → `SearchActivity`; the legacy options-menu plumbing, the `main_options`/
> `home_list_options` menus, `UIUtils.setupSearch`, and the `OpenDrawer` event were deleted (the
> hamburger opens the drawer directly).
>
> **Update — 2026-06-12 (P13).** Home's last two View dialogs are now Compose: the **legend**
> (`LegendDialog` reuses the drawer peek's white-on-color `EtaPill` so its samples match a stop's ETA) and
> the **dismiss-donations** confirmation, both keyed off `HomeUiState.dialog` in `HomeDialogs.kt`;
> `showLegendDialog`/`buildDismissDonationsDialog` are gone. Toasts stay; the `Target.NONE` tutorials
> were unaffected by P12. Swept `legend_dialog`/`eta_header_view`/`ic_menu_hamburger`/`ic_action_search`
> + orphan dimens. Home now has **zero View dialogs**. **P14 (de-fragment the native map host) is the
> last phase.**
>
> **Update — 2026-06-12 (retrospective).** P14 landed (see its phase entry); Phase 1 is complete.
> A code-verified retrospective found three end-state promises unmet — Home's region refresh still
> rides `ObaRegionsTask` (an `AsyncTask`) while `RegionStatusRepository` sits unwired, the int nav
> model survives, and the Activity still owns a little UI state — now scoped as **P15–P17 in the
> "Phase 2" section at the bottom**, alongside two deviations accepted as decisions
> (`onSaveInstanceState`, `GoogleApiClient`).

## Where we are

P0–P6 converted all of Home's *chrome* to Compose: the nav drawer (`ModalNavigationDrawer`),
arrivals sheet (`BottomSheetScaffold`), map FABs/zoom/layers (`MapChrome`), weather chip,
donation card, and the Help/What's-New dialogs. Two third-party UI deps were deleted.

But the architecture is still inverted. `HomeActivity.java` (1,956 lines) remains the brain:

- **`HomeShellHost.kt` is an imperative puppet**, not a declarative screen. It exposes ~20
  setters (`setFabsVisible`, `showWeather`, `setSheetPeekHeightPx`, …) over private
  `mutableStateOf` fields, plus 4 callback interfaces back into Java. State lives in the
  activity; Compose just renders the last thing it was told.
- **`HomeViewModel.kt` is an unwired skeleton.** `HomeUiState`/`HomeEvent`/`FocusedStop`
  (HomeModels.kt) exist but nothing reads them. Config-change survival is still hand-rolled
  `onSaveInstanceState` (`mFocusedStopId`, `mBikeRentalStationId`).
- **Two parallel nav models**: the relocated `NAVDRAWER_ITEM_*` int constants +
  `mCurrentNavDrawerPosition`, bridged to the `HomeNavItem` enum via `toPosition()`/
  `toHomeNavItem()`.
- **Fragment show/hide juggling**: `showMapFragment` / `showStarredStopsFragment` /
  `showStarredRoutesFragment` / `showMyRemindersFragment` and their four `hide*` twins manage
  visibility by `FragmentTransaction.show/hide` in one container — even though the three list
  fragments are already thin Compose hosts (`composeFragmentView` around `MyListContent`).
- **Pre-coroutine async**: `ObaRegionsTask` and `WeatherRequestTask` are `AsyncTask`s;
  `getGtfsAlerts()` is a callback that hops back to the main thread via `new Handler(Looper)`;
  region/weather/donation/survey visibility is recomputed imperatively at call sites.
- **Legacy residue**: `GoogleApiClient` (15 refs), a `WeakReference<AppCompatActivity>` dance in
  `autoShowWhatsNew()`, dead commented-out code (`setupPermissions`, `checkBatteryOptimizations`,
  `handleNearbySelection`), a View-based legend dialog, an XML `ProgressBar` for map loading.

### What stays a View on purpose (not debt)

- The **native map view**: the google flavor already renders via the maps-compose bridge
  (`createComposeMapView` + `MapEffect`); overlays stay imperative ([project decision]). The
  map stays a hosted *view* — but its *fragment wrapper* (`BaseMapFragment` /
  `MapLibreMapFragment`) is in scope: P14 extracts the logic into a non-fragment host so Home
  ends fragment-free.
- The **route-mode header** (`route_info_head.xml`) — `RouteMapController` mutates it directly.
- The **survey card** (`item_survey` + `SurveyManager`, 541 lines, View-based) — its rewrite is
  a separate campaign.
- The hosted **legend dialog** can convert (small), see P13.

## Target architecture

The screen as it would be designed today. **End state: zero fragments in Home** — no
`getSupportFragmentManager()` anywhere in the file. The map, arrivals panel, and list
destinations are all hosted as composables (the map's native view via `AndroidView`).

```
ui/home/
├── HomeActivity.kt          ~120 lines: intent parsing, setContent { HomeRoute() },
│                            permission-launcher registration, event handling that
│                            genuinely needs an Activity (startActivity, finish)
├── HomeViewModel.kt         Owns HomeUiState (single StateFlow) + HomeEvent (one-shot
│                            effects). SavedStateHandle for focused stop / selected tab.
│                            All async via viewModelScope.
├── HomeModels.kt            (exists) HomeUiState / HomeNavItem / FocusedStop / HomeEvent
├── HomeScreen.kt            The declarative shell: ModalNavigationDrawer > Scaffold
│                            (TopAppBar) > BottomSheetScaffold > destination content.
│                            Replaces HomeShellHost. State down, events up — no setters.
├── HomeNavDrawer.kt         (exists, unchanged)
├── MapChrome.kt             (exists; gains the loading indicator)
├── WeatherCard.kt           (exists, unchanged)
├── DonationCard.kt          (exists, unchanged)
├── HomeDialogs.kt           (exists; gains legend + dismiss-donation dialogs)
├── HomeRepositories:
│   ├── RegionStatusRepository.kt   suspend regions refresh (replaces ObaRegionsTask use)
│   ├── WeatherRepository.kt        suspend weather fetch (replaces WeatherRequestTask)
│   └── (GtfsAlerts via a small suspend wrapper on the existing fetcher)
└── (deleted: HomeShellHost.kt — its composition moves into HomeScreen.kt)

map/ (per flavor, P14)
├── ObaMapHost              the existing ObaMapFragment contract minus the fragment-isms
├── GoogleMapHost.kt / MapLibreMapHost.kt   extracted fragment bodies (non-fragment)
└── ObaMapFragment          shrinks to a ~50-line wrapper kept for the three non-Home hosts
```

Data flow:

```
ObaMapFragment ──listener──▶ Activity ──▶ viewModel.onStopFocused(...)
ArrivalsPanel  ──listener──▶ Activity ──▶ viewModel.onPreferredHeight(...) etc.
prefs / region / weather / donations ──▶ repositories ──▶ HomeViewModel
HomeViewModel.uiState: StateFlow<HomeUiState> ──collectAsStateWithLifecycle──▶ HomeScreen
HomeViewModel.events: SharedFlow<HomeEvent> ──LaunchedEffect──▶ Activity side-effects
```

The map host keeps its listener interfaces (it's shared with other screens), but everything
it reports flows straight into the ViewModel — the Activity holds no UI state of its own, and
the arrivals panel's listener interface dissolves into ViewModel calls when it's embedded
directly (P11).

### Key design decisions

1. **`HomeUiState` is the single source of truth.** Everything `HomeShellHost` holds today as
   private `mutableStateOf` moves into it: selected item, sheet state + peek size, FAB/zoom/
   layers visibility, left-hand mode, bikeshare-active, weather (as a nullable
   `WeatherChip(iconRes, tempText, fitIcon)` value), donation visibility, dialog state, map
   loading. Derived gates (e.g. "layers FAB = bikeshare-enabled && NEARBY", "weather visible =
   has data && NEARBY && !hiddenPref") become *computed in the ViewModel*, deleting the
   call-site choreography (`updateLayersFab()`, `setWeatherData()`,
   `updateDonationsUIVisibility()` each re-invoked from 3–4 places).
2. **One nav model.** The `NAVDRAWER_ITEM_*` ints, `mCurrentNavDrawerPosition`,
   `toPosition()`/`toHomeNavItem()` all die; `HomeNavItem` + `SavedStateHandle` (and the
   existing `selected_navigation_drawer_position` pref, keyed by enum name with an int-pref
   migration read) replace them.
3. **No NavHost.** The four in-place destinations (Nearby, Starred Stops, Starred Routes,
   Reminders) are a `when (selectedItem)` over composable content — a drawer-switched
   single-level screen doesn't need Navigation-Compose, and the map must never be recreated on
   tab switch (it keeps state by staying mounted, just hidden). The map island stays in the
   composition and is toggled with visibility, exactly as today (hosted by a fragment until
   P14, then a plain lifecycle-forwarding host class), while the three list destinations
   become direct `MyListContent`-family composables (their fragments are already thin Compose
   hosts).
4. **Events, not callbacks-into-Java.** `HomeEvent` grows the full set of one-shot effects:
   `LaunchNavItem`, `ShowRouteOnMap`, `RecenterOnStop`, `SetMapPadding`, `ShowRegionFoundToast`,
   `LaunchFeedback(focusedStop?)`, `OpenUrl`, … The Activity collects them in
   `repeatOnLifecycle(STARTED)`.
5. **Coroutines end-to-end.** `viewModelScope` + suspend repository functions; `AsyncTask` and
   `Handler(Looper)` usages feeding Home are gone. The repositories follow the established
   in-repo template (`RegionsRepository`, `ArrivalsRepository`, …: small classes with suspend
   funs over the existing blocking IO on `Dispatchers.IO`).
6. **minSdk 21 ceiling respected**: stay on Compose BOM 2025.02.00 / compose-ui 1.7.8 /
   material3 1.3.1. Everything in this plan composes against APIs already in use on the branch
   (`BottomSheetScaffold`, `ModalNavigationDrawer`, `collectAsStateWithLifecycle`).

## Phases

Continues the P-numbering from MODERNIZATION_DEBT.md. Each phase is a separately verifiable
commit (squashable later into topic PRs): **both `obaGoogleDebug` and `obaMaplibreDebug` must
assemble after every phase**, with an on-device smoke test for the behavior the phase touched.
No full instrumented runs per phase.

### P7 — Suspend repositories for Home's async inputs *(prerequisite, no UI change)*

1. **`WeatherRepository`**: `suspend fun currentForecast(regionId: Long): ObaWeatherResponse`
   wrapping `ObaWeatherRequest.call()` on `Dispatchers.IO`. Delete `WeatherRequestTask`
   (33-line AsyncTask) and the `WeatherRequestListener` plumbing once Home is its last caller
   (verify: it is).
2. **`RegionStatusRepository`**: port the *logic* of `checkRegionStatus()` +
   `ObaRegionsTask.doInBackground` into a suspend
   `refreshRegions(force: Boolean): RegionStatus` (returns whether the current region changed;
   the "show progress" decision and the auto-select-closest behavior come along). Keep
   `ObaRegionsTask` itself alive for its other callers (map controllers) — it migrates with
   the Loader campaign (#7), not here. *Coordination note:* a staged conversion already exists
   on the sibling branch `modernization/coroutines-regionstask`; diff it first and lift it if
   it's compatible rather than re-deriving.
3. **GTFS alerts**: a 10-line `suspendCancellableCoroutine` (or `callbackFlow` if it can emit
   multiple alerts) wrapper over `Application.getGtfsAlerts().fetchAlerts(...)`, deleting the
   `Handler(Looper.getMainLooper())` hop.
4. JVM unit tests for the two repositories (the existing `kotlinx-coroutines-test` pattern;
   the regions one is the valuable test — version-gate / staleness / fixed-region rules).

### P8 — Wire `HomeViewModel`: hoist all state out of the Activity and the host  *(LANDED — port-first, reduced scope)*

> **As built:** done *after* P9 (port-first). Hoisted the chrome/overlay/dialog/menu/nav-selection
> state — derived gates computed in a pure `buildState()` from `selectedItem` + a host-snapshotted
> `HomeEnvironment` — and moved weather + GTFS alerts into `viewModelScope` (the P7
> `WeatherRepository`, remapped to return the decoupled `WeatherData`; `WideAlertsRepository` →
> `HomeEvent.ShowWideAlert`). `HomeShellHost` dropped its ~20 setters and now renders from
> `uiState`. **Deferred to P10/P11** (entangled with the map/arrivals fragments): `SavedStateHandle`
> + focused-stop (so `onSaveInstanceState` stays), the int-nav-model collapse (retained beside the
> VM's `selectedItem`), and the declarative sheet inversion (sheet/drawer-open stay imperative).
> The original full-scope bullets below remain as the spec for the deferred slices.

The pivotal phase. `HomeShellHost`'s private state fields become `HomeUiState` fields; the
activity's bookkeeping fields (`mCurrentNavDrawerPosition`, `mFocusedStopId`/`mFocusedStop`,
`mSheetPeekPx`, `mLastSheetState`, `weatherResponse`, `mShowStarred*Menu`,
`mLastMapProgressBarState`, `mInitialStartup`) become ViewModel state or die as derived values.

- Expand `HomeUiState` per design decision 1; expand `HomeEvent` per decision 4.
- `SavedStateHandle` for `focusedStop` (id/name/code) and `selectedItem` → delete
  `onSaveInstanceState` entirely (debt doc: closes 2 of the remaining 26 refs).
- ViewModel init: read launch params (stop id / route id from the intent, passed in by the
  activity), run `refreshRegions` + weather + GTFS alerts + What's-New/version checks through
  `viewModelScope`. The `autoShowWhatsNew` WeakReference dance becomes a plain state update.
- Preference-derived state (zoom controls, left-hand mode, weather hidden) read in
  `onResume`-equivalent: a `refreshFromPrefs()` the activity calls from `onResume`, or a
  preference-listener flow if cheap. Keep it simple: explicit refresh first.
- `HomeShellHost` keeps its public API but becomes a dumb adapter: constructor takes the
  ViewModel, `setContent` collects `uiState` and renders. The 20 setters delete one cluster at
  a time as their state moves (weather → donation → chrome → sheet → drawer → dialogs).
- The activity still exists in Java this phase; it calls `viewModel.onX()` instead of mutating
  fields. `ViewModelProvider` works fine from Java.
- Extend the ViewModel JVM test class to cover the visibility-gating logic (the
  layers-FAB/weather/donation rules, the sheet-state transitions, back-press logic — see P10).

### P9 — Port the activity to Kotlin  *(LANDED — done first)*

> **As built:** done *first* (before P8), so the port was the full ~1,956-line behavior-identical
> translation rather than a ~500-line rump — accepted in exchange for never writing throwaway
> Java↔Kotlin VM interop (see the Status banner). Dead code removed as planned; one correction —
> `BATTERY_OPTIMIZATIONS_PERMISSION_REQUEST` was **kept** (both map fragments reference it).
> `GoogleApiClient` was ported as-is with a `TODO(PR #1569)` (PR #1569 was *not* merged first).

With the state gone, the rump activity is mechanical to port (~500 lines at this point):
`HomeActivity.java` → `HomeActivity.kt`. Behavior-preserving: keep the static
`start()`/`makeIntent()` entry points as a `companion object` (callers across the app use
them), keep the fragment plumbing, convert listener implementations to the Kotlin idiom.
Delete in passing: `setupPermissions`/`requestPhysicalActivityPermission` (fully commented
out), `checkBatteryOptimizations`+dialog (call site commented since 2019 — git history
confirms deliberate), `handleNearbySelection()` (empty), `mActivityWeakRef`.

*GoogleApiClient:* don't port it — `mGoogleApiClient` only feeds
`Application.getLastKnownLocation` and `ReportActivity.start(...)`. **Prefer merging the
sibling branch `modernization/fused-location-client` (PR #1569) before this phase**, which
deletes those parameters app-wide; if it isn't mergeable yet, port the field as a
`lazy` nullable and leave a `TODO(PR #1569)`.

### P10 — Dissolve `HomeShellHost` into `HomeScreen`  *(LANDED)*

> **As built:** done in two commits (P10a hoisted the focused stop into `HomeViewModel` +
> `SavedStateHandle`; P10b dissolved `HomeShellHost` into a declarative `HomeScreen`). The sheet
> inverted to declarative as specified — visibility is keyed on the focused-stop id, expansion is the
> live `SheetState` nudged by `ToggleSheet`/`CollapseSheet` events, back-press via `BackHandler`. The
> peek-height/map-padding/FAB-lift parity constants carried over verbatim. Pure sheet-decision
> functions were extracted to `HomeSheetLogic.kt` (`shouldShowSheet`/`sheetReconcile`/
> `toggleSheetTarget`/`sheetBackAction`) with JVM tests. The map-loading `ProgressBar` became a
> `MapChrome` indicator driven by `state.mapLoading`.

Replace the bridge with a real declarative screen; the activity becomes
`setContent { ObaTheme { HomeRoute(viewModel, mapHost = ...) } }`.

- `HomeScreen(state: HomeUiState, callbacks...)` owns the composition currently inside
  `HomeShellHost.view`: drawer > toolbar > `BottomSheetScaffold` > map box + chrome + overlays
  + dialogs. The `AndroidView(factory = { mapContent })` and sheet/toolbar islands stay, passed
  in as Views (the map island and `home_map_content.xml` survive — they carry the survey card,
  route-mode header, and the fragment container).
- Sheet driving inverts to declarative: today a command-nonce `snapshotFlow` replays
  imperative `expand()/hide()` calls; instead `LaunchedEffect(state.sheetState)` reconciles the
  `SheetState` toward the ViewModel's value, and user drags report back via
  `viewModel.onSheetStateChanged(...)`. Peek height = `peekArrivalCount`/`routeFiltering`
  mapped to the dimens inside the screen (the `onPreferredHeight` px math moves out of the
  activity).
- Back handling becomes a `BackHandler(enabled = state.sheetState != Hidden)` calling
  `viewModel.onBackPressed()` (collapse-expanded / clear-focus rules live in the VM, tested in
  JVM). Delete the `OnBackPressedCallback`.
- Map loading: delete the XML `ProgressBar` + `show/hideMapProgressBar()`; a
  `LinearProgressIndicator` in `MapChrome` driven by `state.mapLoading` (keep the
  route-header-overlap offset in mind — anchor it top-of-box, it only shows on NEARBY).
- Drawer/scrim/gesture behavior, FAB inset animation, and the drag-handle allowance carry over
  verbatim from `HomeShellHost` — these were hard-won on-device parity fixes; treat them as
  spec, not as code to "clean up."
- Delete `HomeShellHost.kt` and its 4 listener interfaces (`MapActionListener` etc. become
  plain lambda parameters / VM calls).

### P11 — Retire the content fragments: destinations and arrivals panel become composables  *(LANDED)*

> **As built:** P11 dissolved `ArrivalsPanelFragment` into `ArrivalsSheetHost` — a per-stop
> `key(stop.id)` Compose host with a cleared-on-change `ViewModelStoreOwner`
> (`rememberClearedViewModelStoreOwner`) so the old stop's polling is cancelled on switch and the VM
> survives rotation; `createArrivalActionHandler`'s `AppCompatActivity` need is met via
> `LocalContext.findActivity()` (added in `ui/compose/ComposeHostUtils.kt`). The list destinations +
> the `show*/hide*` collapse moved to **P11b** (split out because the fragment *classes* were still
> hosted by the legacy `My*` activities — see below). A `/simplify` pass extracted the generic
> Compose-host helpers.

This phase removes every fragment from Home except the map host (which P14 handles).

- The content area becomes:
  ```kotlin
  Box {
      MapIsland(visible = state.selectedItem == NEARBY, ...)   // host stays mounted
      when (state.selectedItem) {
          STARRED_STOPS  -> StarredStopsDestination(...)        // MyListContent + its VM
          STARRED_ROUTES -> StarredRoutesDestination(...)
          MY_REMINDERS   -> RemindersDestination(...)
          NEARBY         -> {} // map island already showing
      }
  }
  ```
  using the same ViewModels/repositories the three thin fragments use today
  (`MyListViewModel` + `StarredStopsRepository` etc.). The fragment *classes* survive this
  phase only because the legacy `My*` tab activities still host them — P11b converts those
  shells and then deletes the fragments unconditionally.
- The eight `show*/hide*Fragment()` methods, `mShowStarredStopsMenu`/`mShowStarredRoutesMenu`,
  and the per-destination title/FAB/zoom choreography collapse into state derivation
  (`title = selectedItem.titleRes`, `fabsVisible = selectedItem == NEARBY && ...`).
- **Menu consequence:** the starred-list sort/clear menu groups currently ride the fragments'
  `onCreateOptionsMenu`. Until P12, keep the hosted Toolbar and drive group visibility from
  `state.selectedItem`; the menu actions call the same helpers (`chooseSortOrder`,
  `confirmClear`) the fragments use.
- **Dissolve `ArrivalsPanelFragment`** (140 lines, already a thin Compose host): embed
  `ArrivalsPanel` directly as the scaffold's `sheetContent`, keyed per stop —
  ```kotlin
  sheetContent = {
      state.focusedStop?.let { stop ->
          key(stop.id) {
              val vm: ArrivalsViewModel = viewModel(
                  key = stop.id,
                  factory = arrivalsViewModelFactory(stop.id)
              )
              ArrivalsPanel(
                  viewModel = vm,
                  collapsed = state.sheetState != Expanded,
                  initialTitle = stop.name.orEmpty(),
                  handler = rememberArrivalActionHandler(vm, ...),
                  onToggleExpand = homeViewModel::onToggleSheet,
                  onPreferredHeight = homeViewModel::onPreferredHeight
              )
              LaunchedEffect(vm) { vm.responses.collect(homeViewModel::onArrivalsLoaded) }
          }
      }
  }
  ```
  The fragment's `Listener` interface, `newInstance`/`setListener`/`setPanelCollapsed`, the
  `R.id.slidingFragment` container, and `home_arrivals_sheet.xml` are all deleted; `collapsed`
  becomes a derived parameter of the sheet state instead of an imperatively-pushed flag.
  Per-stop polling lifecycle is preserved by the keyed ViewModel: a new stop id → new VM (old
  one cleared with its scope when `key` swaps), rotation → same VM survives. Two things to
  verify on device: the keyed-VM teardown actually cancels the old stop's polling, and
  `createArrivalActionHandler`'s `AppCompatActivity` dependency is satisfied via
  `LocalContext`/a lambda from the activity rather than a fragment.

### P11b — Replace the legacy `My*` tab shells; delete the list fragments outright  *(LANDED)*

> **As built — done in two parts.**
> - **P11b-1 (Home half):** Home's three list views (starred stops/routes, reminders) became Compose
>   overlays drawn over the always-mounted map (`HomeListDestinations.kt`); the shared list helpers
>   (`MyListNav.kt` `openStop`/`stopActions`/…, `MyListClearDialog.kt`) were generalized from
>   `Fragment`- to `AppCompatActivity`-scope (taking an explicit `shortcutMode`), so the still-present
>   fragments kept compiling. The starred/reminders sort+clear menu moved to Home's (interim) View
>   toolbar. No fragment deleted yet.
> - **P11b-2 (My* half):** the legacy Java `My*` activities were rewritten in Kotlin + Compose. A new
>   reusable `MyTabsScreen` (`ui/mylists/`) hosts a Material3 `TopAppBar` (title + back + per-page
>   Sort/Clear) over a `TabRow` + swipeable `HorizontalPager`; the deep-link tags + `tab://` helpers
>   moved to `MyTabs.kt`; parameterized destinations (`MyListDestinations.kt`, which Home now delegates
>   to) and `hostListVm`/`hostSearchVm` (`ListViewModelHosting.kt`) are shared. Then **all seven
>   list/search fragments**, `MyTabActivityBase`, the vendored `ListFragment.java`, the
>   `MySearchFragmentOnCreateViewTest`, and the orphaned `activity_tabs`/`activity_toolbar` layouts +
>   four `my_*_options` menus were deleted.
>
> **Decisions/deviations:** (a) the `My*` toolbars went **full Compose `TopAppBar`** now (rather than
> waiting for P12, which only covers *Home's* toolbar) — these screens are self-contained, so a hosted
> View toolbar would have been the worst of both worlds. (b) Compose `painterResource` rejects
> state-list selectors (which the old `TabLayout.setIcon` accepted), so the three `ic_tab_*` selector
> icons were swapped for their `_unselected` PNG glyphs, which the Compose `Tab` tints by selected
> state. (c) `MyRemindersActivity` is non-exported *and* unreferenced (pre-existing dead code) — its
> `ReminderListDestination` content is verified via Home, but it couldn't be launched on-device.
> Manifest, class names, the `com.joulespersecond.seattlebusbot.*` aliases, intent-filters, shortcut
> labels/icons, and `tab://` tags were all preserved verbatim; pinned-shortcut creation + relaunch and
> the self-pin path were on-device verified.

The six list-hosting fragments can't die in P11 because a family of legacy Java activity
shells still hosts them: `MyTabActivityBase` (237 lines, `TabLayout` + `ViewPager2` +
`FragmentStateAdapter`) and its subclasses `MyStopsActivity` (starred/recent/search-stops
tabs), `MyRoutesActivity` (the route equivalents), `MyRecentStopsAndRoutesActivity` (launched
from Home's toolbar menu), plus the single-list shells `MyStarredStopsActivity`,
`MyRecentStopsActivity`, `MyRecentRoutesActivity`, and `MyRemindersActivity` (a toolbar shell
around `MyRemindersFragment`). This phase converts them so the whole fragment layer under Home
can be deleted.

- **The activity class names and manifest entries must survive.** Every one of these is a
  manifest-declared `CREATE_SHORTCUT` entry point (launcher shortcuts users pinned), and the
  manifest also declares `com.joulespersecond.seattlebusbot.MyStopsActivity`/`MyRoutesActivity`
  aliases so shortcuts created by ancient versions keep resolving. Rewrite the *insides*, keep
  the class names, intent filters, shortcut handling (`UIUtils.makeShortcutInfo`), and the
  deep-link `getDefaultTabFromUri` behavior.
- Replace `MyTabActivityBase` with a Kotlin Compose host: `setContent` → `ObaTheme` →
  Material3 `TabRow`/`PrimaryTabRow` + `HorizontalPager` (both available on compose 1.7.8 /
  material3 1.3.1), rendering the same destination composables P11 created (starred / recent /
  search lists). Subclasses shrink to a tab-list declaration + title + shortcut intent — the
  same `TabInfo` idea, but mapping to composables instead of fragment classes. Last-selected-
  tab prefs (`getLastTabPref`) carry over.
- The search tabs reuse the existing `MySearch*` content (their ViewModels/repositories —
  `StopSearchRepository`, `RouteSearchRepository` — already exist; only the fragment hosting
  goes).
- **Then delete**: `MyStarredStopsFragment`, `MyStarredRoutesFragment`, `MyRemindersFragment`,
  `MyRecentStopsFragment`, `MyRecentRoutesFragment`, `MySearchStopsFragment`,
  `MySearchRoutesFragment`, and the vendored `ListFragment.java` if `ReportTypeListFragment`
  no longer extends it (verify — debt doc lists it as the last extender).
- Smoke test beyond the usual: create a pinned shortcut of each type, relaunch from it; open a
  `onebusaway.org`-style deep link that selects a tab; rotate inside each tab.

### P12 — Compose toolbar  *(LANDED)*

> **As built:** `HomeTopBar.kt` is a Material3 `TopAppBar` rendered inside `HomeScreen` (no more
> `AndroidView(toolbar)`): hamburger → opens the drawer directly (so the `OpenDrawer` event +
> `requestOpenDrawer` were removed), title from `selectedItem.titleRes()`, and a search/Sort/overflow
> action set gated by `showListSortMenu`/`showListClearMenu`. **Search** was *not* a plain
> `startActivity` — `SearchActivity` is a results-only screen with no input — so (per the user's call)
> the search icon flips the bar into an **inline Compose search field** whose IME-search fires the
> legacy `ACTION_SEARCH` (the same `SearchManager.QUERY` intent the old `SearchView` sent). The bar owns
> its status-bar inset (the `Column` dropped `statusBarsPadding`). Deleted: `onCreateOptionsMenu`/
> `onPrepareOptionsMenu`/`setupOptionsMenu`/`onOptionsItemSelected` + `invalidateOptionsMenu` + the
> imperative `title=` calls, `setSupportActionBar`/`UIUtils.setupActionBar`, the `main_options` +
> `home_list_options` menus, and the now-orphaned `UIUtils.setupSearch`. `include_toolbar.xml` stays
> (3 other activities use it). Material vector icons for hamburger/search/overflow; `painterResource`
> for the Sort PNG. On-device verified (inline search → results, title-per-tab, Sort/Clear/Recent,
> insets, rotation).

Replace the hosted XML `Toolbar` + options menu with a Material3 `TopAppBar`:

- Hamburger → `IconButton` opening the drawer; title from state.
- Search: `UIUtils.setupSearch`'s `SearchView` action-view → a search `IconButton` that
  launches the existing search flow (verify what `setupSearch` wires — if it's the
  `SearchActivity`, this is a plain `startActivity`; an inline expanding field is not required
  for parity).
- Recent stops/routes action + the starred sort/clear actions as `TopAppBar` `actions` gated
  by `selectedItem`.
- Delete `include_toolbar` usage here, `onCreateOptionsMenu`/`onPrepareOptionsMenu`/
  `onOptionsItemSelected`, `setSupportActionBar`, and the `statusBarsPadding` workaround
  comment (the `TopAppBar` handles insets).

### P13 — Compose the remaining dialogs + final sweep  *(LANDED)*

> **As built:** the legend + dismiss-donations dialogs moved into `HomeDialogs.kt`, keyed off
> `HomeDialog.{LEGEND,DISMISS_DONATION}` (set by `HomeViewModel.showLegend()/showDismissDonation()`) —
> `HomeActivity.showLegendDialog()`/`buildDismissDonationsDialog()` deleted. The legend reuses the drawer
> peek's `EtaPill` (made `internal`, with an added `canceled` strikethrough) — the "above-the-peek"
> white-on-color "5 min" sample with the pulsing realtime dot — so it matches a real arrival. The 3-action
> donation dialog stacks its `TextButton`s. Toasts (region/weather) stay; the tutorials anchor to
> `Target.NONE` so P12's toolbar removal didn't touch them. Swept `legend_dialog.xml` +
> `eta_header_view.xml` (used only by the legend), `ic_menu_hamburger`/`ic_action_search` (P12 went to
> Material vector icons), and the five `arrival_header_*` dimens used only by those layouts. Home now has
> zero View dialogs. On-device: the legend renders + survives rotation (the donation dialog wasn't
> triggerable — `DonationsManager` isn't surfacing the card in the test region — but shares the verified
> mechanism).

- Legend dialog → Compose in `HomeDialogs.kt` (the ETA chips are simple colored rounded boxes —
  the GradientDrawable recoloring dance isn't needed in Compose; honor
  [feedback: info-window color rules don't apply here, this is a dialog]).
- Dismiss-donations dialog → Compose (`HomeDialog.DISMISS_DONATION` state).
- Region-found toast / weather-summary toast → either keep `Toast` (fine, idiomatic) driven by
  events, or a `SnackbarHost` — recommend keeping Toast for parity.
- Tutorials: `ShowcaseViewUtils` anchors to Views; the welcome tutorial + recent-stops one are
  the survivors. Keep them working (they anchor to the toolbar/activity decor); a Compose
  onboarding rewrite is out of scope.
- Sweep: orphaned strings/dimens/layouts left by P11–P12, and the debt-doc entry update.

### P14 — De-fragment the map host  *(LANDED — both flavors, device-verified)*

> **Update — 2026-06-12 (P14, the final phase).** Home is now **fragment-free end to end** —
> `grep supportFragmentManager HomeActivity.kt` returns zero hits. New `ObaMapHost` (interface) +
> `MapHostDeps` (the owner's permission hook) in `src/main/map/`; each flavor's fragment body moved
> verbatim into a non-fragment host — `GoogleMapHost` (raw `GoogleMap` via the existing Compose
> `createComposeMapView`) and `MapLibreMapHost` (a raw `org.maplibre.android.maps.MapView` with the
> 8 lifecycle calls forwarded in order, replacing the SDK's `SupportMapFragment`). Both `MapDialogFragment`
> child fragments became direct `AlertDialog`/`MaterialAlertDialogBuilder` calls; both
> `onRequestPermissionsResult` overrides became `onLocationPermissionResult(int)` fed by an
> `ActivityResultLauncher`. **Two deviations from the sketch below:** (1) the host view mounts via
> plain `addView` into the existing `R.id.main_fragment_container` (the map stays an imperative island —
> *not* moved into the Compose tree / no `MapIsland` `AndroidView`); (2) map saved-state rides the
> Activity's reintroduced `onSaveInstanceState` bundle (fed back to the host on recreation) + the
> on-pause prefs fallback, *not* the ViewModel's `SavedStateHandle`. The three other map screens
> (`TripResults`/`InfrastructureIssue`/`TripPlanLocationPicker`) keep working through a ~140-line thin
> `Fragment` wrapper per flavor that delegates to the host (listeners stashed + ops no-op'd until
> `onCreateView` creates it, matching the old `mMap`-null behavior). Device-verified on the google
> flavor (map render, focus/sheet/padding, my-location permission flow, font-scale recreation restoring
> camera/zoom/focus, drawer/list-tab switches keep the host mounted, TripPlanLocationPicker map) and on
> maplibre (raw-`MapView` render + background/foreground + rotation, no GL crash). Controllers/overlays
> untouched (debt #7).

The last fragment. `BaseMapFragment` (google, 1,533 lines) / `MapLibreMapFragment` (maplibre,
1,119 lines) are Fragments mostly by inertia: a grep shows the google one uses almost no
Fragment-specific machinery — no `registerForActivityResult`, no `LoaderManager`, no child
fragments; just `onCreateView` (which delegates to `createComposeMapView()`, already a plain
function) and `onSaveInstanceState` forwarding to the controllers. The map *view* is already
fragment-free; only the host shell isn't.

- Extract each fragment's body into a non-fragment host class per flavor implementing the
  existing `ObaMapFragment` contract minus the fragment-isms — call it `ObaMapHost`
  (`interface`, flavor implementations `GoogleMapHost` / `MapLibreMapHost`). It owns view
  creation, the `MapModeController` set, listeners, and explicit lifecycle hooks
  (`onStart/onStop/onSaveInstanceState/onDestroy`) — same shape as the controllers it already
  forwards to.
- In Home, the host's view mounts via `AndroidView(factory = { mapHost.view })` inside
  `MapIsland`, with a `DisposableEffect` (or `LifecycleEventObserver`) forwarding the
  activity's lifecycle; saved state goes through the ViewModel/`SavedStateHandle` rather than
  the fragment bundle. The `hidden`-fragment trick for non-NEARBY tabs becomes plain
  visibility on the island.
- Location-permission requests, which the fragment currently mediates
  (`setOnLocationPermissionResultListener`), move to an `ActivityResultLauncher` registered in
  `HomeActivity.kt`, with results forwarded to the host and the ViewModel (this also unblocks
  the `mInitialStartup` region-check flow living fully in the VM).
- **Scope control:** the other three `ObaMapFragment` hosts (`TripResultsFragment`,
  `TripPlanLocationPickerActivity`, `InfrastructureIssueActivity`) keep working via a ~50-line
  fragment *wrapper* around the new host class (the inverse of today: fragment delegates to
  the class instead of being the class). They migrate to direct hosting on their own
  schedules; no behavior change for them in this campaign.
- This phase deliberately does **not** touch the controllers or their Loaders (#7) — the host
  class forwards to them exactly as the fragment did. When the Loader campaign happens, a
  non-fragment host actually makes it easier (controllers stop needing a `LoaderManager`
  owner).
- After this phase: `getSupportFragmentManager()` has zero uses in Home; `HomeActivity` is
  fragment-free end to end.

## What this plan does *not* do (deliberate scope cuts)

- **Map controllers / Loaders (#7)** — `StopMapController`, `RouteMapController`,
  `BikeLoaderCallbacks` are untouched; the map stays an imperative island per project
  decision. P14 removes the *fragment wrapper* around them, nothing more.
- **SurveyManager rewrite** — the survey card stays a View in the map island; only its
  *visibility gating* moves into `HomeUiState`.
- **Route-mode header** (`route_info_head.xml`) — stays; `RouteMapController` owns it.
- **`ObaRegionsTask` deletion** — Home stops using it (P7) but the class remains for the map
  stack until the Loader campaign.
- **De-fragmenting the other three map hosts** (`TripResultsFragment`,
  `TripPlanLocationPickerActivity`, `InfrastructureIssueActivity`) — they get the thin
  fragment wrapper from P14 and migrate later; only *Home* must end fragment-free.
- **`StopInfoActivity`** (52-line deep-link shell) and the **Tutorial** (`TutorialFragment` +
  ShowcaseView) — separate small campaigns; P11b deliberately covers only the `My*` shells
  that block fragment deletion.
- **ArrivalsPanel internals** — already Compose+MVVM; P11 changes only its hosting.

## Risks and their controls

| Risk | Control |
|---|---|
| Sheet behavior regressions (peek heights, initial reveal, map padding, FAB lift) | P10 treats the existing `HomeShellHost` constants/effects as spec; on-device checklist below |
| Map recreated on tab switch → loses camera/state | Map island stays mounted with visibility toggling (never `when`-removed from composition) |
| Rotation: focused stop / sheet / arrivals must survive | `SavedStateHandle` (deferred from P8 to P10/P11 with the focused-stop hoist) + explicit rotation step in every phase's smoke test. Until then, `onSaveInstanceState` still covers it |
| Keyed per-stop `ArrivalsViewModel` lifecycle (P11): stale polling after stop switch, or VM lost on rotation | `key(stop.id)` + `viewModel(key = stop.id)`; explicit on-device check that the old stop's polling stops and rotation keeps the VM |
| Map host lifecycle (P14): missed forwarding (`onStart/onStop/onSaveInstanceState`) leaks the map or drops camera state | Host class has an explicit, small lifecycle surface; smoke-test backgrounding, rotation, and process-death (`don't keep activities`) on both flavors |
| Pinned launcher shortcuts break if `My*` activity names/intent filters change (P11b) | Class names, manifest entries, and the `com.joulespersecond.seattlebusbot.*` aliases are kept verbatim; only the internals are rewritten. Shortcut-relaunch is in the P11b smoke test |
| `gesturesEnabled` drawer subtlety (edge-drag must pan map) | Carry the existing one-liner + its comment verbatim into `HomeScreen` |
| GoogleApiClient conflict with PR #1569 | Resolved by quarantine: ported as-is in P9 with a `TODO(PR #1569)` (the branch was not merged first) |
| Flavor divergence (google vs maplibre map fragments) | Assemble **both** flavors every phase; Home only talks to the `ObaMapFragment` interface |
| Sibling `modernization/coroutines-*` branches duplicate P7 | Diff them first; lift, don't re-derive |

### Per-phase on-device smoke checklist (Pixel 7, `obaGoogleDebug`)

1. Cold start → map on Nearby, region auto-select toast (clear app data), weather chip.
2. Tap stop → sheet peeks at correct height → expand → back collapses → back hides + clears focus.
3. Drawer: hamburger opens, edge-drag does *not* open, scrim tap closes; switch to each
   destination and back to Nearby (map state preserved).
4. Rotate on each destination and with a focused stop.
5. Layers FAB on a bikeshare region; zoom-controls pref; left-hand-mode pref.
6. Help → each action; What's-New (bump `whatsNewVer` pref down); donation card close/learn-more.
7. Deep links: launch from a route (`makeIntent(routeId)`) and a stop notification intent.

## Sequencing summary

```
P7  repositories (parallel-safe, no UI)            ── small          ✅ DONE
P9  Kotlin port of HomeActivity (done first)       ── large, low-risk ✅ DONE
P8  wire HomeViewModel, hoist chrome/overlay/       ── the big one     ✅ DONE (reduced scope;
    dialog/nav state + weather/GTFS                                       savedstate/focused-stop/
                                                                          int-model/sheet → P10/P11)
P10 HomeShellHost → HomeScreen (declarative;       ── medium, parity-sensitive ✅ DONE
    + the deferred sheet inversion + SavedStateHandle)
P11 arrivals panel → keyed Compose sheet host;     ── medium             ✅ DONE
    focused stop → ViewModel/SavedStateHandle              (list destinations moved to P11b)
P11b legacy My* tab shells → Compose hosts;        ── medium (shortcut-   ✅ DONE
    delete all seven list/search fragments               compat sensitive; done in 2 parts)
P12 Compose TopAppBar + menu (Home)                ── small/medium       ✅ DONE
P13 dialogs + sweep                                ── small              ✅ DONE
P14 de-fragment the map host (ObaMapHost class,    ── large, both flavors ✅ DONE
    wrapper for the other three screens)                  (device-verified; Home is fragment-free)
```

Each lands as its own verified commit on a `modernization/compose-mvvm-home-vm` branch (created
from this branch per the modernization-base convention), squashed into topic PRs at the end.
End state: `HomeActivity.kt` ≈ 120 lines of intent/permission/event plumbing, a fully-tested
`HomeViewModel` owning every piece of screen state, one declarative `HomeScreen`, zero
`AsyncTask`/`Handler` in the Home path, `onSaveInstanceState` gone from the file, and **zero
fragments** — no `FragmentManager` use anywhere in Home, and the seven list/search fragments
(plus the vendored `ListFragment.java`, if freed) deleted app-wide via the P11b shell
conversion — closing the Home-sized slice of debt findings #2 and #6.

## Phase 2 — closing the gap to the written end state

*Added 2026-06-12 after a code-verified retrospective of P7–P14. The substantive goal landed —
Home is fragment-free, declarative, ViewModel-driven; both flavors compile; all 41 Home JVM
tests pass. But the end-state paragraph above makes five promises, and a grep of the tree shows
three were left unmet (plus two accepted deviations worth recording as decisions). These are the
fall-short items; each is small and independently landable.*

### P15 — Wire `RegionStatusRepository`  *(LANDED — both flavors, device-verified)*

> **As built — 2026-06-12.** Wired (not deleted). `HomeActivity.checkRegionStatus()` shrank to
> `viewModel.refreshRegions()`; the ViewModel runs `RegionStatusRepository.refreshRegions()` on
> `viewModelScope` and maps the sealed `RegionStatus` to side effects: `Changed`/`Unchanged` →
> a one-shot `HomeEvent.RegionResolved(changed, regionName?)` the activity carries out (map re-zoom via
> `(mMapHost as? ObaRegionsTask.Callback)?.onRegionTaskFinished`, the region-found toast, analytics on an
> auto-select change, and the verbatim `onRegionResolved` body = What's-New + drawer redraw +
> `pushEnvironment`); `NeedsManualSelection` → the forced-choice picker. Home's last `AsyncTask` use is
> gone and the once-dead repository now has a production caller. **The 100ms `doCallback` delay was
> dropped** — the region is set inside the suspend call before the event is collected, so the
> background-write race it guarded against can't occur. `ObaRegionsTask` + its `Callback` interface stay
> alive for SettingsActivity/BackupUtils; only Home stopped using them (the import remains for the
> `as?` cast). Swept four now-dead imports + the `CHECK_REGION_VER`/`REGION_UPDATE_THRESHOLD` constants
> (the repo owns its own).
>
> **Fork decisions (with the user):** (a) the manual picker is a **faithful non-dismissible Compose
> dialog** in `HomeDialogs.kt` (`HomeDialog` became a `sealed interface`; `ChooseRegion(regions)` carries
> the usable list, which `RegionStatus.NeedsManualSelection` now also carries), *not* a launch of
> `RegionsActivity` — preserving the old forced-choice UX and Home's "zero View dialogs." (b) the
> first-launch "Detecting region…" modal spinner was **dropped** (the map's own indicator covers it; the
> VM stays free of any `Application.get()` read, so no test seam). `HomeViewModelTest` 14 → 19 (a
> `FakeRegionStatusRepository` over each `RegionStatus` case + `onRegionChosen`); `RegionStatusTest`'s two
> manual-case asserts became `is`-checks. **Device-verified** on the google flavor (cold auto-select →
> map zoom + What's-New side effects; warm relaunch → quiet `Unchanged`, no toast; manual picker →
> forced dialog with usable regions alphabetically sorted, **back + scrim both no-ops**, pick → region
> set + map zoom) and on maplibre (cold auto-select, raw-MapView render, no GL crash). The original
> wire-or-delete spec is kept below.

**As found:** `RegionStatusRepository.kt` (174 lines, built + tested in P7 with 11 passing
tests) has **zero production callers** — `checkRegionStatus()` still constructs `ObaRegionsTask`
directly (`HomeActivity.kt:866–869`), and `ObaRegionsTask` is an `AsyncTask`
(`ObaRegionsTask.java:56`). So the "zero `AsyncTask`/`Handler` in the Home path" claim is unmet,
and the repository is shipped dead code — the most misleading kind of debt, because its passing
tests make it look done. The P8 banner said it was "unused until P14"; P14 landed without wiring
it (the entanglement was the task's callback into the map *fragment*, which P14 replaced with
`ObaMapHost` — so the blocker is now gone).

- Either: route `checkRegionStatus()` through `viewModelScope` +
  `RegionStatusRepository.refreshRegions(force)`, with the map-host notification and the
  progress-dialog decision driven from the returned `RegionStatus` (the activity's
  `onRegionTaskFinished` logic moves into the VM / an event).
- Or, if the wiring is judged premature before the Loader campaign (#7): **delete** the
  repository and `RegionStatusTest`, and strike the corresponding P7 bullet. Unwired-but-tested
  code must not survive this phase either way.
- `ObaRegionsTask` itself still stays alive for its other callers (per the deliberate scope cut
  above) — only *Home's* use of it ends.

### P16 — Collapse the int nav model  *(LANDED — google device-verified, maplibre compile-verified)*

> **As built — 2026-06-12.** The int model is gone: deleted the `NAVDRAWER_ITEM_*` constants,
> `mCurrentNavDrawerPosition`, and `toPosition()`/`toHomeNavItem()`. `goToNavDrawerItem(HomeNavItem,
> reselect)` is a `when` over the enum (bodies verbatim; the deprecated SIGN_IN/PROFILE/PINS/ACTIVITY_FEED
> cases were already unreachable and vanished); analytics moved to a `reportNavAnalytics(item)` helper
> (PAY_FARE still reports none). The remembered tab is now a `HomeNavItem.name` pref
> (`home_selected_nav_item`) read via a pure, unit-tested `persistedNavItem(name, legacyPosition)` helper
> that falls back to the legacy int key for old installs; the VM also persists `selectedItem` in
> `SavedStateHandle` (eliminates the process-death flicker). The redundancy guard became a captured
> `reselect` flag — **gated on a new one-shot `navSelectionApplied`** so the *first* (deferred) selection
> still runs `showMap()` even when the restored tab is NEARBY (the old `-1` sentinel). That same
> `navSelectionApplied` preserves the one non-obvious behavior: `setupSurvey()` *always early-returns at
> onCreate* today (position is `-1` before the posted selection), so the out-of-scope survey stays
> dormant — confirmed on device (no survey card surfaced). `HomeViewModelTest` 19→21 + a new
> `NavPersistenceTest`×4 (52 Home JVM tests pass). **Device-verified** (google): tab switch / de-highlight,
> region-gated Plan-a-trip + Pay-my-fare, cross-session remembered tab, **legacy-int→enum migration on a
> true cold start (and the new key rewritten)**, Help launcher fires without switching the destination,
> reselect no-ops, Nearby round-trip re-shows the map + re-gates the donation/weather overlays. The
> original spec is kept below.

**As found:** the `NAVDRAWER_ITEM_*` int constants, `mCurrentNavDrawerPosition`, and
`toPosition()`/`toHomeNavItem()` all survive in `HomeActivity.kt` (the comment at
`HomeActivity.kt:1214` admits it). The plan said this "collapses with P14's permission-launcher
work"; the launcher landed, the collapse didn't. Design decision 2 is the spec:

- `goToNavDrawerItem(Int)` becomes `goToNavDrawerItem(HomeNavItem)`; the deprecated positions
  (SIGN_IN/PROFILE/PINS/ACTIVITY_FEED) fold into the int-pref *migration read* only.
- The `selected_navigation_drawer_position` pref is re-keyed by enum name with a one-time
  int-pref migration; selection persistence moves beside the VM's `selectedItem`
  (`SavedStateHandle`).
- Delete the constants, the two mapping functions, and `mCurrentNavDrawerPosition`.

### P17 — Hoist the Activity's remaining UI state; shrink toward the thin shell

**As found:** `HomeActivity.kt` is **1,313 lines**, not ~120, and still owns UI state contra
"the Activity holds no UI state of its own": `mLastSettledSheet`, `mPendingMapFocus`,
`mInitialStartup` (and `mCurrentNavDrawerPosition`, which P16 kills). Much of the residue is
legitimate Activity work (survey wiring, `start()`/`makeIntent()` entry points, map-host
lifecycle, drawer item building) — the ~120 estimate was wrong, not just unmet — but the
*state* should still move:

- `mLastSettledSheet` / `mPendingMapFocus` / `mInitialStartup` → `HomeViewModel`
  (`mInitialStartup` pairs with P15: the initial region check is the thing it gates).
- Opportunistic shrink only where it falls out of P15/P16 (e.g. `onRegionTaskFinished` and the
  drawer-position plumbing). A line-count target is explicitly *not* the goal; statelessness is.
- Revise the end-state paragraph above to the honest number when done.

### Accepted deviations (record as decisions, not debt)

- **`onSaveInstanceState` stays.** P14 deliberately routed map saved-state (camera/zoom/focus/
  mode) through the Activity bundle + on-pause prefs fallback rather than `SavedStateHandle` —
  it replaces persistence the FragmentManager used to provide, and the map host is not
  VM-owned. The end-state claim "`onSaveInstanceState` gone from the file" is retracted rather
  than re-pursued.
- **`GoogleApiClient` remains** behind `TODO(PR #1569)` (`HomeActivity.kt:126–127`) — explicitly
  quarantined in P9; it dies when the fused-location branch merges, not in this campaign.

```
P15 wire RegionStatusRepository             ── small (kills Home's last AsyncTask)  ✅ DONE
P16 collapse the int nav model              ── small (pref migration is the only subtlety)  ✅ DONE
P17 hoist remaining Activity UI state       ── small/medium (rides on P15+P16)
```
