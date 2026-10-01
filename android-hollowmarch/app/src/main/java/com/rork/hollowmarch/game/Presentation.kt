package com.rork.hollowmarch.game

/**
 * The presentation boundary: the immutable shapes that cross from engine to
 * screen. The UI never reads engine fields — it receives [PlayerInput] in,
 * and [HudState] / [RenderScene] out.
 */

/** One line of the chronicle, already faded to the alpha it should carry. */
data class FadingLine(val text: String, val alpha: Float)

data class HudState(
    val locationTitle: String = "",
    val heading: String = "N",
    val depthLabel: String = "",
    val vitality: Int = 0,
    val maxVitality: Int = 1,
    val fatigue: Int = 0,
    val maxFatigue: Int = 1,
    val magicka: Int = 0,
    val maxMagicka: Int = 1,
    val torch: Float = 1f,
    val outdoor: Boolean = false,
    val lines: List<FadingLine> = emptyList(),
    val usePrompt: String = "",
    val day: Int = 1,
    val clock: String = "0:00",
    val timeOfDay: String = "dusk",
    val brass: Int = 0,
    val kills: Int = 0,
    val explored: Int = 0,
    val dead: Boolean = false,
    val warded: Boolean = false,
    val level: Int = 1,
    val className: String = "",
    val crouched: Boolean = false,
    val blocking: Boolean = false,
    val detection: String = "",
    val load: Float = 0f,
    val capacity: Float = 1f,
    val lootable: String = "",
    val satchelCount: Int = 0,
    /** What the marksmanship hand is doing: the draw, the charge, the count of shot. */
    val rangedLine: String = "",
    /** True on the open province itself — the one ground that keeps no automap. */
    val overland: Boolean = false
)

/**
 * Everything the hands can hold at once: the thumb-sticks' axes. Immutable, so
 * it passes through the boundary as a value; the engine keeps it as the held
 * input and replaces it whole each time a hand moves.
 */
data class PlayerInput(
    val move: Float = 0f,
    val strafe: Float = 0f,
    val turn: Float = 0f,
    val look: Float = 0f
)
