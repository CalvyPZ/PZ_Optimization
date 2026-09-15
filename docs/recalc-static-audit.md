# Static-state audit of the chunk recalc pass

Build 42.20.4, revision `b0bbce05d5`. Produced for change
`parallel-chunk-grid-recalc`, tasks 5.1–5.4.

## Method

`tools/StaticAudit.java` walks the bytecode of `projectzomboid.jar` from a set
of entry methods, following every `invoke*` transitively (virtual and interface
calls include every override found in the jar, so the walk is conservative) and
records every `GETSTATIC`/`PUTSTATIC` with the shortest call chain from an
entry. Raw output is in `build/audit/*.txt` (regenerate with the commands
below). Classes under `zombie.debug` and `zombie.core.logger` are treated as
opaque leaves; logging is not part of the world state.

```sh
JAR=/games/steamapps/common/ProjectZomboid/projectzomboid.jar
java tools/StaticAudit.java $JAR zombie/ \
  'zombie/iso/IsoGridSquare.RecalcProperties()V' \
  'zombie/iso/IsoGridSquare.RecalcAllWithNeighbours(ZLzombie/iso/IsoGridSquare$GetSquare;)V' \
  'zombie/iso/IsoGridSquare.getNew(Ljava/util/ArrayDeque;Lzombie/iso/IsoCell;Lzombie/iso/SliceY;III)Lzombie/iso/IsoGridSquare;' \
  --leaf zombie/debug/ --leaf zombie/core/logger/
```

Entry methods are the three things `IsoChunk.loadInWorldStreamerThread()` calls
on squares: `RecalcProperties()` (loop 1, plus `getNew` through
`ensureNotNull3x3`), and `RecalcAllWithNeighbours(true, chunkGetter)` (loop 3),
which itself reaches `RecalcPropertiesIfNeeded → RecalcProperties`,
`ReCalculateAll → ReCalculateCollide/PathFind/VisionBlocked`, and `doGridNav`.
Cross-check against the decompiled source: every chain in the output was read
in `decompiled/zombie/iso/IsoGridSquare.java` and the classes it names; nothing
in the source reaches a static the walk did not list.

Reachable methods: 236 from `RecalcAllWithNeighbours`, 153 from the
`RecalcProperties`/`getNew` pair.

## Two structural facts that shape everything below

1. **The streamer pass is chunk-local.** `IsoChunk.ChunkGetter.getGridSquare`
   returns `null` for any coordinate outside its own chunk, and every neighbour
   lookup in `RecalcAllWithNeighbours(boolean, GetSquare)` — the 3×3×3 loop,
   `ReCalculateAll(…, getter)`, `doGridNav(getter)` and the four diagonal
   fix-ups — goes through that getter. The pass therefore never reads or writes
   a square of another chunk. Cross-chunk boundary recalculation happens later,
   on the game thread, in `doLoadGridsquare()` (which uses the unparameterised
   `RecalcAllWithNeighbours(true)` = whole-cell getter).
2. **`RecalcProperties` runs inside loop 3 too.** `RecalcProperties` ends with
   `propertiesDirty = (chunk == null || chunk.loaded)`, and `LoadChunk` has
   already set `loaded = true`, so `RecalcPropertiesIfNeeded` re-runs
   `RecalcProperties` for every square during `RecalcAllWithNeighbours`.
   Everything `RecalcProperties` touches is therefore in scope for whatever
   thread runs loop 3.

## Classification

### Pure / read-only

| Static | Where | Why it is safe |
|---|---|---|
| `IsoFlagType.*`, `IsoObjectType.*`, `IsoPropertyType.FUEL_AMOUNT`, `IsoDirections.*`, `IsoDirections.VALUES`, `IsoGridSquare.DIRECTIONS`, `ZoneGeometryType.*`, `Zone$PolygonHit.*`, `ClipperOffset$EndType/JoinType.*` | throughout | enum constants / final arrays, never written |
| `IsoFlagType.fromStringMap`, `IsoFlagType.MAX`, `IsoObjectType.MAX` | `PropertyContainer.set`, `IsoObject.getType` | built in static init, read-only |
| `IsoWorld.instance`, `GameServer.server`, `ServerMap.instance`, `IsoPlayer.numPlayers`, `IsoChunkMap.chunkGridWidth`, `chunkWidthInTiles`, `IsoGridSquare.doSlowPathfinding`, `LightingJNI.init`, `DebugOptions.instance` | `isValidSquare`, `CalculateCollide`, `getNew`, … | set at startup / world load, only read here |
| `TilePropertyAliasMap.instance` | `PropertyContainer.has/get` | alias tables built at tile-definition load; read-only afterwards |
| `IsoLot.MapFiles`, `MapFiles.LotHeaderFileNameCache` | `IsoMetaGrid.getZoneAt → getLotHeader` | `infoHeaders` map is read-only after load; the filename cache is a `ThreadLocal` (per-thread `TLongObjectHashMap`), so its lazy `put` is thread-private |
| `IsoGridSquare.setMatrixBit` | `ReCalculate*` | pure function on an `int` (5.3, confirmed: the only static *method* the four `ReCalculate*` routines reach, and the `RecalcAllWithNeighbours`-only walk lists no mutable static outside `RecalcProperties`) |
| `IsoGridSquare.isoGridSquareCache` | `getNew(cell, …)` | `CappedConcurrentQueue` — thread-safe |

### Per-chunk / per-thread scratch

| Static | Where | Classification |
|---|---|---|
| `IsoGridSquare.col`, `path`, `pathdoor`, `vision` | written to 0 in both `getNew` overloads | write-only: no method in the jar reads them (grep of `IsoGridSquare.java`: only the four `= 0` stores). Dead scratch; a racing store of `0` over `0` is harmless. |
| `IsoChunk.lightCheck`, `IsoChunkLevel.lightCheck` (via `checkLightingLater_AllPlayers_OneLevel`) | `RecalcProperties` | instance arrays of the square's own chunk — chunk-local |

### Genuinely shared, mutable

| Static | Where | Hazard | Resolution |
|---|---|---|---|
| `IsoMetaGrid.clipperOffset`, `clipperBuffer` | `RecalcProperties → getZoneAt → Zone.contains → checkPolylineOutline` (only when `sq.zone == null`, which stays true for every square outside any zone) | Shared scratch used to compute a polyline zone's outline **once per zone**, cached in `zone.polylineOutlinePoints`. Two threads computing outlines for two not-yet-cached zones at the same time corrupt each other. | Run loop 1 (`RecalcProperties` for every square) on the streamer thread before handing the chunk to the pool. Loop 1 issues the same `getZoneAt` queries loop 3 will, so every polyline outline a worker could need is already cached and the worker only reads. |
| `AnimalPathfind.instance` | `RecalcProperties → clearWater → Mesh$1.release → AnimalPathfind.getInstance()` | Lazy singleton (`if (instance == null) instance = new …`) — two threads could construct two instances. | Same as above (first call happens on the streamer thread); additionally the pool calls `AnimalPathfind.getInstance()` once at creation so it is initialised before any worker runs. |
| `IsoGridSquare.idMax` | `IsoGridSquare.<init>` via `getNew` | Non-atomic `++idMax` (the stock game already races this between the streamer and game threads) | Workers never construct squares: only loop 1 calls `getNew` (`ensureNotNull3x3`), and loop 1 stays on the streamer thread. |
| `OffMeshConnection.pool`, `IsoWaterGeometry.pool`, `IsoPuddlesGeometry.pool`, `CollideWithObstaclesPoly$CCEdge.pool`, `$CCEdgeRing.pool` | `clearWater`, `getPuddles` | `zombie.popman.ObjectPool` — all methods are `synchronized`, so concurrent alloc/release is safe; listed here because they are mutable, not because they need work. | none needed |
| `PoolStatistic.instance`, `ObjectPoolCounter.id` | pool construction | statistics counters (`AtomicLong`), touched only when a pool is first built | none needed |

### Verdict for 5.4

After moving loop 1 to the streamer thread there is no unresolved shared
mutable static on the code the pool runs (loops 2–4 of
`loadInWorldStreamerThread`: the roof/rain column pass, `RecalcAllWithNeighbours`
for every square, and the `propertiesDirty` pass). The pool pass reads and
writes only the squares of its own chunk, enum constants, read-only world
tables, and synchronized pools.

## Consequence for the design (raised with the user)

Because the streamer pass is chunk-local, two chunks recalculated at the same
time cannot touch each other's squares, so the adjacency rule of design
Decision 4 is not needed for correctness or for parity: the pass output is a
function of the chunk's own data on disk. What the design still needs is
in-order publication to the game thread, which the pool preserves.
