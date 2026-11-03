# Flood pipeline — open items

Deferred work on the DARE flood run (flood → network disruption → behavioural response →
with vs without metrics). Health impacts are out of scope.

## Modelling

- [ ] **Tram and rail flooding.** The change events only touch road links; tram/rail run on
      dedicated links (`tram_<osmID>`, `pt_rail_*`) that never flood. Add flooded track and
      flooded stations (service suspended or slowed for the affected lines and times).
- [ ] **Recovery curve.** The network is restored to dry speeds at 22:20 (end of the raster
      window + 20 min) although water is still standing. Replace with a realistic recovery
      curve.
- [ ] **NHB mode switching.** Non-home-based trips are open plans, which SubtourModeChoice
      cannot change, so they keep their MITO mode under disruption.
- [ ] **OSM bridge check.** The Overpass bridge query in `flood_road_exposure.ipynb` (section 6)
      is switched off; only the depth-profile heuristic flags bridges.
- [ ] **PT access/egress and transfer walks.** Teleported along the straight line, so they do
      not see the flood. Proposed fix: switch on SwissRailRaptor's intermodal access/egress for
      `walk`. Walk routing is already bound to the flood-aware network router, so access/egress
      legs would follow the walk network and pick up the flood speeds and impassable penalty with
      no new flood code. Cost: one walk routing per candidate stop, so cap the search radius
      (~1 km) and the number of stops, and time a test run. Fallback if too slow: a custom
      `RaptorIntermodalAccessEgress` that scales the beeline walk time by the depth at the stop's
      link and the activity's link. Transfer walks inside a stop area can stay beeline.
- [ ] **Active-mode depth response.** `FloodResponse` values are first estimates (walk 0.10 /
      0.50 m, factor 0.5; bike 0.05 / 0.30 m, factor 0.2) and `FLOOD_HAZARD_WEIGHT` = 1 has no
      direct estimate. Revisit after the final runs; run 0 and 3 as sensitivities.
- [ ] **Age-dependent walking threshold.** Children and older adults lose stability at lower
      depths. `FloodAwareActiveTravelTime` already receives the person.
- [ ] **Depth and velocity.** The hazard rating is `d(v + 0.5) + DF`; the depth series carries
      depth only. Export velocity from the hydraulic model if available.
- [ ] **Weather in the counterfactual.** Both stages use the same weather shift, so the
      comparison isolates the network effect of the flood. Add a dry-day base run later if
      "without flooding" should also mean "without rain".
- [ ] **Sync the notebook figure.** Section 16 of `flood_road_exposure.ipynb` (cell with `D0`,
      `D1`, `PHI_MIN`) still plots the old 0.10 / 0.30 m thresholds for both modes; update it to
      the per-mode values in `FloodResponse`.

## Before the final runs (remote PC)

- [ ] Check the MATSim scale factor against MITO's sample with `CheckMatsimScaleFactor`.
- [ ] Check the link ids of `flood_networkChangeEvents.xml` and
      `flood_link_depth_timeseries.csv.gz` against the multimodal network used in the run.
- [ ] A base run made before the settings check existed has no
      `ITERS/it.100/2021.base_settings.txt`, so stage 2 refuses to reuse it. Either rerun stage 1
      or, if it is known to match, save the record printed in the error message as that file.
