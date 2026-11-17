# Whiteboard performance audit

Audit date: 2026-11-16 (America/Argentina/Mendoza)  
Repository: `pincha1212/Whiteboard-`  
Branch: `main`

## Scope and evidence

This audit inspects the Android/Kotlin implementation, the Compose canvas, the shared Whiteboard/Notes store, rendering helpers, current unit tests, and GitHub Actions build workflow.

The initial source review was performed against `main` at `d9ef55ed665ce9263a843d1f5d43e11633a8d6c3`. Changes listed below were made incrementally on `main` after that inspection.

## What was already implemented

- `LocalFirstWorkspaceStore.commit()` updates in-memory state and publishes persistence requests through a non-blocking `MutableSharedFlow.tryEmit()`.
- A single `Dispatchers.IO` worker performs persistence, with a 250 ms debounce for ordinary requests and an immediate lifecycle flush.
- Disk replacement uses Android `AtomicFile`; the current loader also preserves a copy of an unreadable snapshot before returning an empty state.
- `flushToDiskBlocking()` is a separate explicit blocking fallback. It uses `runBlocking(Dispatchers.IO)` and is documented as unsuitable for UI-thread calls. The ordinary `commit()` path does not call it.
- Production startup loads the workspace in the background and delays ViewModel creation until the store reports readiness.
- Stroke and brush rendering already use LRU caches and stroke viewport bounds; shape fill paths are cached in local coordinates.
- Sticky-note font auto-fit is remembered using text, title, size, and density inputs, rather than recalculated for every camera change.
- The renderer already rejects many off-screen strokes and shapes before expensive path generation.

## Confirmed performance costs

1. `WhiteboardCanvasScreen` observes the complete `WhiteboardUiState` through a top-level `collectAsState()`. Camera, selection, tool, and content changes therefore invalidate a broad composable scope.
2. Sticky-note move, resize, and rotate handlers previously called `syncBoardFromWhiteboard()` for every pointer update, even with `persist = false`. This avoided disk writes but still rebuilt the shared Notes projection, projected boards from Notes, revised store state, and notified listeners.
3. The default grid type is `GRID`. Its previous implementation issued one Compose draw operation per visible vertical or horizontal line on every draw pass. DOTS and DIAMONDS similarly issued one circle operation per grid point.
4. Template creation is already a single UI-state mutation and one shared-store synchronization. Built-in templates generate 8–11 native canvas elements; there is no evidence here that the number of template elements alone explains the observed lag.

## Changes made

### Sticky-note transform batching

The ViewModel now treats a sticky-note drag, resize, or rotation as one transform transaction:

- Each pointer event updates the canvas state immediately.
- Shared Notes/Whiteboard projection is skipped during the active gesture.
- The final geometry is projected once when the gesture ends, then the existing asynchronous disk flush is requested.
- A following transform recovers a still-open previous transaction before starting a new undo snapshot.

Regression coverage verifies immediate visual state, no intermediate shared-store update, one final projection, and matching geometry in both board and Notes data.

Relevant commits:
- `595ba4806d40db4c29bafcc3930b04a2f2e02511`
- `1a7e54e63005a106b6048c234e8651fc210069df`
- `50ee53eae9e5a99cc77917efca0bda8fb8b25ac9`
- `6fd0e7c91c562477bc2c7477b1d3f427d18578ef` (required test fixture correction)

### Batched grid drawing

The grid now creates line-segment or point-coordinate arrays and sends them to Android Canvas in batched calls:

- GRID and LINES use `Canvas.drawLines()`.
- DOTS and DIAMONDS use `Canvas.drawPoints()` with round stroke caps to preserve circular marks.
- Grid spacing, viewport endpoints, point order, and the existing colors/radii are preserved in the generated geometry.
- Unit tests verify generated dot coordinates, line segments, line-only mode, and half-open viewport boundaries.

Relevant commits:
- `1c97451a6381e44800230a8b1d638a2d814b909e`
- `45b4747a5a231d80890a198000a80e9c3a3c3442`

## Validation

The repository workflow runs the full `:app:testDebugUnitTest` suite and builds `:app:assembleDebug`, then uploads the debug APK.

- Before these changes, run #112 on `d9ef55e` completed successfully.
- The sticky-note batching correction, including its regression test, passed the workflow on `6fd0e7c` (run #116).
- Grid batching and its geometry tests passed the full workflow on `99ee9b1` (run #120): `:app:testDebugUnitTest`, `:app:assembleDebug`, and APK artifact upload all succeeded. See [run #120](https://github.com/pincha1212/Whiteboard-/actions/runs/44002442791).

## Metrics not yet collected

No hardware performance baseline is claimed. The available repository workflow does not run Macrobenchmark or Perfetto, and no real-device profiling session was available during this code review.

Still unmeasured:
- FPS and frame-time distribution (P50/P90/P95/P99).
- Input-to-frame latency.
- CPU/GPU use and memory pressure on representative low-end devices.
- Garbage-collection pauses.
- Persistence latency under realistic workspaces.
- Long-session memory growth.

A successful build and unit-test run demonstrate code/build correctness, not a measured frame-rate improvement.

## Remaining priorities

1. Add a repeatable Macrobenchmark scenario for empty boards, each built-in template, and 50/100-object scenes; collect the same measurements on a low-end physical device.
2. Reduce the broad Compose invalidation scope by separating camera, canvas-content, selection, and toolbar observation, with recomposition tracing to verify the effect.
3. Profile the complete draw pass before adding further caches or a tile renderer; enforce a memory budget and measure cache hit rates.
4. Evaluate a dedicated fast geometry renderer only for cases where image-diff tests can prove visual compatibility with the existing brush pipeline.
5. Add automated interaction/regression coverage for undo/redo, Notes synchronization, cancellation, and persistence after transform gestures.
