# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in
this repository.

## What this is

`dev.arkaitz/uikit-base` — the look of a web-base host, decided once: a colour engine
that derives an accessible palette, light and dark, from one brand colour; a CSS kit
whose classes are its contract; and Hiccup components that put those classes on the
markup web-base already knows how to make correct. A plugin of web-base, the root of
the set, like db-base and auth-base.

The colour engine is a port of the user's own, in `../../zig/basaku` (`src/css/brand.zig`,
`color_space.zig`, `state_colors.zig`, `boundary.zig`, `cvd.zig`, `wcag_audit.zig`).
Their lesson, which this library exists to keep: **a colour system and a well-designed
kit beat a theme system.** Colour is the only knob; everything else is decided once;
hand CSS can always fine-tune anything.

The plan this repository was built from is the last section of
`~/.claude/plans/snazzy-imagining-sketch.md` ("uikit-base").

## The rules that must survive contact with code

**1 · The engine is never loaded at run time.** A host generates its colours with
`uikit-base.build`, commits the file, and a test of its own regenerates it and compares.
The components and the plugin never require `color`, `brand`, `audit`, `emit` or
`build`, and `structure_test` holds them to it.

**2 · Fidelity to basaku is measured, never assumed.** The port answers the same hex as
basaku for every token, seed and scheme of the golden table in `test/resources`, which
basaku itself produced (its commit sha is recorded beside it). `StrictMath`, exact
equality, no tolerance: a mismatch is a bug to diagnose.

**3 · Hand CSS always wins.** Every rule of `kit.css` is inside `@layer uikit` and none
carries `!important`; a host's unlayered stylesheet therefore overrides any of them with
no specificity to match. The one exception is deliberate: the bridge that maps web-base's
`--wb-*` onto the palette is **unlayered**, in the generated colours file, because
`wb.css` is unlayered and an unlayered rule beats every layered one.

**4 · Colour is the only knob.** Spacing, radius, type, shadows and the focus ring are
decided in `kit.css`. A new option needs the user's decision and a paper trail.

## Traps already identified

- **A layered remap of `--wb-*`** loses silently to `wb.css` and leaves the shell
  neutral with every test green. Only a computed-style check in a browser sees it.
- **A plugin stylesheet outside the plugin's `:assets` path** is refused by web-base
  (`plugin.clj:84-89`); the per-host colours file is the host's own stylesheet.
- **Native images** serve only what the metadata's globs name; `native_test` holds the
  globs to the files.
- **`color-scheme`** must be declared, or form controls and scrollbars stay light under
  dark tokens.

Commands go here only once they have been run and observed to work. Observed:

    clojure -M:test                        # whole suite; prints "Ran N tests containing M assertions."
    clojure -M:test -n <namespace>         # one namespace
    clojure -M:build-test                  # the release guards, against real git

`structure_test` starts a fresh JVM to see what the runtime namespaces load: a test JVM
has already loaded whatever the other tests needed, so only a new one can testify.
