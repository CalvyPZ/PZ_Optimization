---
name: override-game-class
description: Add a new overridden game class or change an existing one in src/overrides, keeping game source out of git and documenting the edit in prose. Use when an optimization needs a change inside a zombie.* or org.lwjglx.* class.
---

# Override a game class

1. Read the stock class from `decompiled/<package path>.java` (CFR) or `codegraph_explore`.
2. If the class is not yet in `OVERRIDES` in `scripts/build.sh`, add it there, then run
   `scripts/regen-overrides.sh` and copy the Vineflower output from `build/vineflower/` into
   `src/overrides/<package path>.java`. Vineflower output recompiles with at most one fix.
3. Make the edit. Prefer delegating logic into a new class under `src/pzopt/pzopt/` (committed)
   and keep the override diff to hooks and a `Config` flag defaulting to the safe value.
4. Describe the edit in words in `docs/override-edits.md` (class, method, what changed, why,
   which Config key). No code, no diff, no quoted game lines.
5. `scripts/build.sh && scripts/test.sh`, then the `build-install` skill, then a run via
   `bench-run` (parity gate if the change touches recalc/streaming).
6. Confirm `git status` shows nothing under `decompiled/` or `src/overrides/`.

Inner classes are shadowed with the outer class. Inspecting bodies CFR could not render:
`javap -c -p -cp <jar> <class>`. Display/Mouse (org.lwjglx) overrides are how Wayland,
MangoHud swap hand-off and the removed frame cap are done; read `docs/override-edits.md`
before touching them.
