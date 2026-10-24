# Third-party attribution

## Backdrop (Liquid Glass) — com.kyant.backdrop

Source vendored from **AndroidLiquidGlass** by Kyant0.
- Repository: https://github.com/Kyant0/AndroidLiquidGlass
- Maven artifact: `io.github.kyant0:backdrop:2.0.1`
- License: **Apache License 2.0**

The Kotlin sources under `com/kyant/backdrop/` are copied and compiled directly within
this app module (the library's Kotlin/Compose toolchain — Kotlin 2.4 / Compose 1.12 / KMP —
is newer than this app's, so the `expect`/`actual` KMP declarations were flattened into plain
Android implementations). The `org.intellij.lang.annotations.Language` annotation was dropped
as it is an IDE-only hint with no runtime effect.

Apache License 2.0 — you may use, modify, and distribute it, provided this notice is retained.
