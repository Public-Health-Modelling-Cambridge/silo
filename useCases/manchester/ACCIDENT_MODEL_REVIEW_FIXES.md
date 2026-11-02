# Accident model: review findings and fixes

Branch: `accidentModel_fixes_tmp` (based on `accidentModel` at `dc6f29b7`)
Commits: `4dd5c095` (fixes), `c97d68e3` (accident-only offline runner)
Status: awaiting a full test run, then merge into `accidentModel`.

This note covers two findings from the code review of the OSM-based accident model
(`AccidentModelMCR` → `AccidentRateModelOsmMCR` → `OsmLink` + `CasualtyRateCalculationOsmMCR`).

---

## Finding 1: street attributes taken from an arbitrary link; a missing speed limit counts as under 20 mph

**Where:** `OsmLink.java` 65-70, `CasualtyRateCalculationOsmMCR.java` 254

### Problem

The network is grouped by `osmID` into a `HashSet<Link>`, and each group becomes one `OsmLink`.
`computeAttributes()` copied `type`, `speedLimitMPH` and `onwysmm` from
`networkLinks.iterator().next()`, i.e. from whichever member link the set happens to return first.

- If the member links of one OSM street had different values, the category used in the logit
  (speed band, primary/trunk or not) would depend on set iteration order.
- A missing `speedLimitMPH` defaulted to `0.0`. That puts the link in the `<20 mph` band, and for
  ordinary roads it also triggers the `road<20MPH` interaction term.
- Links without an `osmID` all fell into a single pseudo-street with OSM ID 0.

The `<20 mph` band is the safest category in `input/accident/binaryModel.csv`:

| Term | BIKE_MINOR | CAR_ONEWAY | CAR_TWOWAY | PED |
|---|---|---|---|---|
| `speed_limit<20 MPH` | -1.76 | **-12.43** | -0.78 | 0 |
| `speed_limit20 - 29 MPH` | -0.39 | -1.60 | -0.62 | 0 |
| `road<20MPH` | 0 | 0 | 0 | -1.53 |

### What the data shows (checked 2026-10-29)

These are the network actually used, checked against `network_v3.13.gpkg` and the production
MATSim `network.xml`. The XML has 1,053,677 links in 262,877 OSM groups.

- No OSM ID has links with different `speedLimitMPH`, `type`, speed band, road class or `onwysmm`.
  This is expected: `highway` and `maxspeed` are tags on the OSM way, and the matsim-jibe converter
  copies the edge attributes to both the `<edgeID>out` and `<edgeID>rtn` links. The converter also
  throws if `maxspeed` is null, and speed limits are already imputed upstream (5-10 mph on paths
  that don't carry cars).
- Only **12 links** are missing `osmID`, `type` and `speedLimitMPH`. These are the motorway vehicle
  connectors added by `NetworkGpkgToMatsim.createVehicleConnectors` (IDs such as
  `409267out_409269out`). They carry only car and truck traffic, have `motorway=true` and
  `edgeID=-1`, and total about 2.4 km.

**Impact:** on the current network only those 12 connectors were affected. They were pooled into
OSM ID 0, which mixed traffic flows from 12 different locations. They were also coded as `<20 mph`
and as non-motorway roads, so the model gave them essentially zero car casualty risk. All other
links get the same result as before the fix.

### Fix

- `OsmLink`:
  - `roadType`, `speedLimitMPH` and `onwysmm` are now the **length-weighted mode** over the unique
    edges. The two directions of an edge are counted once, missing values are ignored, and ties
    always resolve to the same value.
  - A missing speed limit (or a value of 0 or less) is stored as `NaN`.
  - When no member link has a `type`, it is taken from the `motorway`/`trunk`/`primary` flags.
  - The new `mixedAttributes` field flags groups whose member links disagree.
  - The unused `highway` field is removed.
- `AccidentRateModelOsmMCR.initializeOsmLinks`: each link without a valid `osmID` becomes its own
  `OsmLink`, with a negative synthetic ID (-1, -2, ...) assigned in link-ID order. The log reports
  how many links had no OSM ID, how many groups had mixed attributes, and how many had no speed limit.
- `CasualtyRateCalculationOsmMCR.calculateUtility`: when the speed limit is `NaN`, the link uses the
  **reference category** (30 mph and above). No speed dummies and no `road<20/20-29` terms are added.

---

## Finding 2: two network sources in the injury pipeline

**Where:** `AccidentRateModelOsmMCR.java` 91, `DataBuilderHealth.java` 143

### Problem

| Component | Network read |
|---|---|
| MATSim runs (these produce the event link IDs) | network in the MATSim config (`args[1]`) |
| `DataBuilderHealth` (builds the `linkInfoByDay` keys) | `config.network().getInputFile()` |
| `HealthExposureModelMCR` (routing and risk lookup) | `config.network().getInputFile()` |
| `AccidentRateModelOsmMCR` | hard-coded `input/mito/trafficAssignment/network_<scenario>.xml` |

What happens when these networks differ depends on the case:

- A link in the accident network but not in `linkInfoByDay`: `AccidentModelMCR` threw a bare NPE
  when copying the risks.
- A link in `linkInfoByDay` but not in the accident network: the link silently got zero injury risk
  in the exposure model.
- Same link IDs from a different network version: the logit silently used the wrong attributes.

### Fix

- `AccidentModelMCR` now receives the MATSim `Config`. The accident model reads
  `config.network().getInputFile()`, the same file as `DataBuilderHealth` and the exposure model.
  The hard-coded path is removed.
- `checkLinkIdsMatch()` runs right after the network is read. If the link IDs differ from the
  `linkInfoByDay` keys, it throws with the count on each side and example IDs.
- The copy step in `AccidentModelMCR` now throws an explicit error instead of an NPE.
- The constructors now take Qin's upstream `Random random` parameter together with the new
  `networkFile` and `expectedLinkIds` arguments.

---

## Offline test runner (commit `c97d68e3`)

`RunExposureHealthOffline` now runs **only the accident model**, through the new
`AccidentModelMCR.runForYear(2021)`. The exposure, sport PA and disease models are commented out.

This was needed because `endYear(2021)` does nothing with `siloMCR_longitudinal.properties`:
the start year is 2021, `base.exposure.file` is set and `exposure.model.years = -1`, so none of its
trigger conditions is met.

To restore the full runner before merging, either run `git revert c97d68e3`, or cherry-pick only
`4dd5c095` into `accidentModel`.

### Running the test

```
mvn clean install -pl useCases/manchester -am -DskipTests
java -Xmx50g ... de.tum.bgu.msm.health.RunExposureHealthOffline <silo.properties> <matsimConfig.xml>
```

Required inputs: the network named in the config, the per-day MATSim outputs in
`scenOutput/<scenario>/matsim/2021/{thursday,saturday,sunday}/{car,bikePed}/`
(`2021.output_events.xml.gz` and `2021.output_vehicles.xml.gz`), and `input/accident/binaryModel.csv`.

Expected log lines on the v3.13 network:

- `Accident model network matches linkInfoByDay: 1053677 links.`
- `12 links have no valid OSM ID and are treated as individual OsmLinks with negative IDs.`
- `12 OsmLinks have no speed limit; the reference speed category is used.`
- no "different road type, speed limit or one-way status" warning

In `casualtyRates.csv` and `hourlyCasualtyRates.csv`, the connectors appear with negative `osmId`s.

---

## Not changed

- `AccidentRateModelMCR` and `CasualtyRateCalculationMCR`: the legacy model. It is commented out in
  `AccidentModelMCR`, but it has the same `speedLimitMPH -> 0.0` default.
- `RunAggregateAccidentModel`: a debug runner. It benefits from the `OsmLink` changes, but its own
  grouping still pools links without an OSM ID into group 0. It only reports a hard-coded list of
  OSM IDs, so this doesn't affect its output.

## Re-checking a new network version

The data checks above should be repeated whenever the network changes. To do this, stream-parse
the MATSim network XML; at 3.7 GB it is too large to load in one go. For each link, read `osmID`,
`edgeID`, `type`, `speedLimitMPH` and `onwysmm`, then:

- count the links where any of these attributes is missing;
- group the links by `osmID` and count the groups with more than one speed band
  (`<20`, `20-29`, `>=30`) or road class (primary/trunk, motorway, other).

After the fix, the model's own log warnings report both counts on every run.
