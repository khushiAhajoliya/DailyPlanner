package com.dailyplanner.app.render

import android.content.res.AssetManager
import android.graphics.Typeface

/**
 * Maps Figma font family names to bundled font files (assets/fonts).
 * Variable fonts get the exact weight axis value Figma used, so Fredoka 500 is Fredoka 500.
 */
class FontRegistry(private val assets: AssetManager) {

    private sealed interface Spec {
        data class Variable(val file: String, val min: Int, val max: Int, val extraAxes: String = "") : Spec
        data class Static(val byWeight: Map<Int, String>) : Spec
    }

    private val specs: Map<String, Spec> = mapOf(
        "Fredoka" to Spec.Variable("fredoka_var.ttf", 300, 700, ", 'wdth' 100"),
        "Fredoka One" to Spec.Static(mapOf(400 to "fredoka_one.ttf")),
        "Nunito" to Spec.Variable("nunito_var.ttf", 200, 1000),
        "Comic Sans MS" to Spec.Static(mapOf(400 to "comic_sans.ttf", 700 to "comic_sans_bold.ttf")),
        "Quicksand" to Spec.Variable("quicksand_var.ttf", 300, 700),
        "Poppins" to Spec.Static(
            mapOf(400 to "poppins_regular.ttf", 500 to "poppins_medium.ttf", 600 to "poppins_semibold.ttf", 700 to "poppins_bold.ttf"),
        ),
        "Caveat" to Spec.Variable("caveat_var.ttf", 400, 700),
        "Patrick Hand" to Spec.Static(mapOf(400 to "patrick_hand.ttf")),
        "Playfair Display" to Spec.Variable("playfair_var.ttf", 400, 900),
        "Inter" to Spec.Variable("inter_var.ttf", 100, 900, ", 'opsz' 14"),
        "Baloo 2" to Spec.Variable("baloo2_var.ttf", 400, 800),
        "Dancing Script" to Spec.Variable("dancing_var.ttf", 400, 700),
        "Roboto Condensed" to Spec.Variable("roboto_condensed_var.ttf", 100, 900),
        // Arimo is metric-compatible with Arial (same glyph widths), and freely licensed.
        "Arial" to Spec.Variable("arimo_var.ttf", 400, 700),
    )

    val families: List<String> get() = specs.keys.toList()

    fun has(family: String) = family in specs

    /** A resolved face plus the synthetic styling needed to reach the requested style. */
    data class Face(val typeface: Typeface, val fakeBold: Boolean, val fakeItalic: Boolean)

    private val cache = HashMap<Triple<String, Int, Boolean>, Face>()

    fun resolve(family: String, weight: Int, italic: Boolean): Face = synchronized(cache) {
        cache.getOrPut(Triple(family, weight, italic)) { load(family, weight, italic) }
    }

    private fun load(family: String, weight: Int, italic: Boolean): Face =
        runCatching { loadExact(family, weight, italic) }.getOrElse {
            // Font file missing from this build (e.g. Comic Sans not copied): use a similar free face.
            android.util.Log.w("FontRegistry", "Font $family unavailable, using fallback: $it")
            loadExact(if (family == "Comic Sans MS") "Patrick Hand" else "Fredoka", weight, italic)
        }

    private fun loadExact(family: String, weight: Int, italic: Boolean): Face {
        val spec = specs[family] ?: specs.getValue("Fredoka")
        return when (spec) {
            is Spec.Variable -> {
                val w = weight.coerceIn(spec.min, spec.max)
                val tf = Typeface.Builder(assets, "fonts/${spec.file}")
                    .setFontVariationSettings("'wght' $w${spec.extraAxes}")
                    .build()
                Face(tf, fakeBold = false, fakeItalic = italic)
            }
            is Spec.Static -> {
                val nearest = spec.byWeight.keys.minBy { kotlin.math.abs(it - weight) }
                val tf = Typeface.createFromAsset(assets, "fonts/${spec.byWeight.getValue(nearest)}")
                // Only fake bold when the family has no real bold at all.
                Face(tf, fakeBold = weight >= 600 && nearest < 600, fakeItalic = italic)
            }
        }
    }
}
