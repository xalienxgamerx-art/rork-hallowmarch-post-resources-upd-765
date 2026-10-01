package com.rork.hollowmarch.world

import com.rork.hollowmarch.game.SkyGen
import com.rork.hollowmarch.game.SkyMoon
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * The forge's shared parts: the name pools, the land, the pantheons' coinage,
 * the ages, the omens and the road-talk. Kept beside the generator so the
 * forge itself stays readable; everything here is deterministic from its rng.
 */

// ---------------------------------------------------------------------------
// Name pools
// ---------------------------------------------------------------------------

internal val ONSETS = listOf(
    "k", "b", "d", "g", "h", "m", "n", "r", "s", "t", "v", "th", "br", "dr",
    "gr", "kr", "sk", "st", "tr", "vr", "fl", "gl", "sh", "ch", "orr", "w"
)
internal val NUCLEI = listOf("a", "e", "i", "o", "u", "ae", "ei", "au", "y", "ea", "ou")
internal val CODAS = listOf(
    "l", "n", "r", "s", "th", "ll", "rn", "st", "ck", "sk", "m", "g",
    "d", "nd", "rk", "lm", "tt", "ff", "sh"
)
internal val TERRAINS = listOf(
    "the fens", "the salt flats", "the ash hills", "the barrow downs", "the drowned quarter",
    "the reed lakes", "the brass road", "the cold moors", "the black pine woods", "the chalk cliffs"
)
internal val EPITHETS = listOf(
    "fen freeholders", "barrow-keepers", "salt-burners", "road-wardens", "reed-cutters",
    "ash-tenders", "grave-tithers", "moor-drovers", "bell-ringers", "ferry-folk", "exiled"
)
internal val CRAFTS = listOf(
    "reed-boats", "bone lacquer", "brass casting", "salt glass", "black iron",
    "ash pottery", "rope and pitch", "carved antler", "peat vellum", "tin bells"
)
internal val ADJECTIVES = listOf(
    "Ashen", "Verdigris", "Drowned", "Gilded", "Hollow", "Rust", "Pale", "Iron",
    "Salt", "Black", "Cinder", "Bone", "Brass", "Grey", "Silent", "Weeping"
)
internal val NOUNS = listOf(
    "Hand", "Key", "Bell", "Lantern", "Chain", "Vigil", "Crown", "Nail",
    "Wound", "Coin", "Thorn", "Gate", "Ledger", "Mask", "Reed"
)
internal val AGE_WORDS = listOf(
    "Reeds", "Brass", "Cinders", "Salt", "Wolves", "Vigils", "Ash", "Chains",
    "Bells", "the Quiet", "Floods", "Rust", "Lanterns", "the Long Thaw"
)
internal val SITE_SUFFIX = listOf(
    "gate", "mill", "ford", "hollow", "reach", "fell", "mere", "wick", "stead", "moor"
)
internal val CREEDS = listOf(
    "that debts outlive the dead", "that the roads are unclean", "that fire cleans what water spoils",
    "that every grave is owed a name", "that brass remembers what men forget",
    "that the drowned should not be counted", "that a sworn word binds three generations",
    "that no vault should be opened twice"
)
internal val VALUES = listOf(
    "endurance", "honest debts", "silence", "hospitality", "blood-price",
    "patience", "sworn oaths", "thrift", "vengeance", "tidiness of graves"
)
internal val TABOOS = listOf(
    "naming the drowned", "eating river eels", "whistling after dark",
    "burying the dead face-up", "selling iron to strangers",
    "counting the dead aloud", "crossing the ford at dusk", "burning driftwood"
)
internal val DOMAINS = listOf(
    "the tide", "graves", "the road", "brass", "famine", "the pale moon",
    "sworn debts", "rust", "the gate", "salt", "the hunt", "quiet",
    "smoke", "the ford"
)
internal val DEITY_TITLES = listOf(
    "Mother of", "Father of", "Keeper of", "Warden of", "Lady of", "Lord of"
)
internal val DEATHS = listOf(
    "a fever", "a fall in the dark", "gout", "a bad winter",
    "poison no one confessed to", "a wound that reopened", "old debts"
)
internal val WAR_CAUSES = listOf(
    "an unpaid tithe", "a desecrated shrine", "the brass road tolls", "an insult at a wedding",
    "a stolen relic", "old claims on the downs", "the grain barges", "a broken betrothal"
)
internal val ARTIFACT_KINDS = listOf(
    "blade", "chalice", "crown", "bell", "key", "mask", "nail", "lantern"
)
internal val BEAST_KINDS = listOf(
    "tar-wyrm", "marsh-lurker", "bone-tyrant", "grave-tithe", "fen terror", "hollow beast"
)
internal val RUMOR_SOURCES = listOf(
    "drover", "smith", "reed-cutter", "tithe-taker", "ferryman", "bell-ringer",
    "salt-burner", "grave-digger", "pedlar", "watchman"
)
internal val AGENDAS = listOf(
    "expand the borders", "protect the capital", "suppress unrest",
    "avenge old grudges", "grow rich", "press old claims", "end the rival line"
)

/** Per-culture sound: each people draws from its own small set of syllables. */
internal class Phonetics(
    val onsets: List<String>,
    val nuclei: List<String>,
    val codas: List<String>
) {
    fun word(rng: Random, syllables: Int): String {
        val sb = StringBuilder()
        repeat(syllables) { i ->
            sb.append(onsets.random(rng))
            sb.append(nuclei.random(rng))
            if (i == syllables - 1 || rng.nextInt(3) == 0) sb.append(codas.random(rng))
        }
        return sb.toString().replaceFirstChar { it.uppercase() }
    }

    companion object {
        fun forCulture(rng: Random, cultureId: Int): Phonetics {
            val local = Random(rng.nextLong() * 31 + cultureId * 7919L)
            return Phonetics(
                onsets = ONSETS.shuffled(local).take(5),
                nuclei = NUCLEI.shuffled(local).take(3),
                codas = CODAS.shuffled(local).take(4)
            )
        }
    }
}

/** A chronicle with finite pages: once full, the world lives on unwritten. */
internal class EventLedger(val cap: Int) {
    val list = mutableListOf<ChronicleEvent>()

    operator fun plusAssign(event: ChronicleEvent) {
        if (list.size < cap) list += event
    }
}

// ---------------------------------------------------------------------------
// Peoples, ages, terrain
// ---------------------------------------------------------------------------

internal fun generateCultures(rng: Random, requested: Int): List<Culture> {
    val count = if (requested >= 1) requested else 3 + rng.nextInt(3)
    val usedTerrain = mutableSetOf<String>()
    val usedEpithet = mutableSetOf<String>()
    val usedCraft = mutableSetOf<String>()
    return (0 until count).map { id ->
        val ph = Phonetics.forCulture(rng, id)
        val name = ph.word(rng, 2)
        Culture(
            id = id,
            name = name,
            adjective = name + "ish",
            epithet = pickUnused(rng, EPITHETS, usedEpithet),
            craft = pickUnused(rng, CRAFTS, usedCraft),
            homeland = pickUnused(rng, TERRAINS, usedTerrain),
            values = VALUES.shuffled(rng).take(2),
            taboo = TABOOS.random(rng)
        )
    }
}

internal fun generateAges(rng: Random, totalYears: Int): List<Age> {
    val count = 3 + rng.nextInt(2)
    val used = mutableSetOf<String>()
    val cuts = mutableListOf(1)
    for (i in 1 until count) {
        cuts += (totalYears * i / count) + rng.nextInt(20) - 10
    }
    cuts += totalYears
    return (0 until count).map { i ->
        Age(
            name = "Age of ${pickUnused(rng, AGE_WORDS, used)}",
            startYear = cuts[i].coerceAtLeast(1),
            endYear = cuts[i + 1]
        )
    }
}

/**
 * The province's land: fBm height shaped so the borders drown, a second field
 * for wetness, and rivers that walk downhill from the high ground to the sea,
 * wetting everything they pass. Deterministic from its own rng.
 */
internal fun generateTerrain(rng: Random, riverNames: List<String>): TerrainMap {
    val size = 96
    val heights = FloatArray(size * size)
    val moisture = FloatArray(size * size)
    val hs = rng.nextInt(1 shl 30)
    val ms = rng.nextInt(1 shl 30)

    for (y in 0 until size) {
        for (x in 0 until size) {
            val nx = x / size.toFloat()
            val ny = y / size.toFloat()
            val base = fbm(nx * 3.4f, ny * 3.4f, hs)
            val ridge = 1f - abs(fbm(nx * 6.3f, ny * 6.3f, hs + 7) - 0.5f) * 2f
            var h = base * 0.8f + ridge * 0.2f
            val ex = (nx - 0.5f) * 2f
            val ey = (ny - 0.5f) * 2f
            val edge = max(abs(ex), abs(ey))
            h += 0.08f - edge * edge * 0.42f
            heights[y * size + x] = h.coerceIn(0f, 1f)
            moisture[y * size + x] = fbm(nx * 2.6f, ny * 2.6f, ms).coerceIn(0f, 1f)
        }
    }

    val rivers = mutableListOf<River>()
    repeat(3 + rng.nextInt(3)) {
        var sx = -1
        var sy = -1
        var attempts = 0
        while (sx < 0 && attempts < 80) {
            attempts++
            val x = 8 + rng.nextInt(size - 16)
            val y = 8 + rng.nextInt(size - 16)
            if (heights[y * size + x] > 0.60f) {
                sx = x
                sy = y
            }
        }
        if (sx < 0) return@repeat

        val pts = mutableListOf<RiverPoint>()
        val visited = mutableSetOf<Int>()
        var cx = sx
        var cy = sy
        while (pts.size < 160) {
            visited += cy * size + cx
            pts += RiverPoint((cx + 0.5f) / size, (cy + 0.5f) / size)
            if (heights[cy * size + cx] < 0.34f) break
            var bx = -1
            var by = -1
            var bh = Float.MAX_VALUE
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx2 = cx + dx
                    val ny2 = cy + dy
                    if (nx2 < 0 || ny2 < 0 || nx2 >= size || ny2 >= size) continue
                    val key = ny2 * size + nx2
                    if (key in visited) continue
                    val nh = heights[key]
                    if (nh < bh) {
                        bh = nh
                        bx = nx2
                        by = ny2
                    }
                }
            }
            if (bx < 0) break
            cx = bx
            cy = by
        }
        if (pts.size > 10 && riverNames.isNotEmpty()) {
            rivers += River(riverNames[rivers.size % riverNames.size], pts)
        }
    }

    // Rivers wet the land around them, which turns their banks to marsh and forest.
    for (river in rivers) {
        for (p in river.points) {
            val cx = (p.x * size).toInt()
            val cy = (p.y * size).toInt()
            for (dy in -2..2) {
                for (dx in -2..2) {
                    val nx2 = cx + dx
                    val ny2 = cy + dy
                    if (nx2 !in 0 until size || ny2 !in 0 until size) continue
                    val d = abs(dx) + abs(dy)
                    val idx = ny2 * size + nx2
                    moisture[idx] = (moisture[idx] + (0.16f - d * 0.035f)).coerceIn(0f, 1f)
                }
            }
        }
    }
    return TerrainMap(size, heights, moisture, rivers)
}

internal fun fbm(x: Float, y: Float, seed: Int): Float {
    var amp = 0.5f
    var freq = 1f
    var sum = 0f
    var norm = 0f
    for (octave in 0 until 4) {
        sum += valueNoise(x * freq, y * freq, seed + octave * 101) * amp
        norm += amp
        amp *= 0.5f
        freq *= 2.1f
    }
    return sum / norm
}

/** Cheap deterministic value noise, independent of the renderer's copy. */
internal fun valueNoise(x: Float, y: Float, seed: Int): Float {
    val xi = x.toInt()
    val yi = y.toInt()
    val xf = x - xi
    val yf = y - yi
    val v00 = hash(xi, yi, seed)
    val v10 = hash(xi + 1, yi, seed)
    val v01 = hash(xi, yi + 1, seed)
    val v11 = hash(xi + 1, yi + 1, seed)
    val sx = xf * xf * (3 - 2 * xf)
    val sy = yf * yf * (3 - 2 * yf)
    val top = v00 + (v10 - v00) * sx
    val bottom = v01 + (v11 - v01) * sx
    return top + (bottom - top) * sy
}

private fun hash(x: Int, y: Int, seed: Int): Float {
    var h = x * 374761393 + y * 668265263 + seed * 1442695040888963407L.toInt()
    h = (h xor (h shr 13)) * 1274126177
    return ((h xor (h shr 16)) and 0xFFFF) / 65535f
}

// ---------------------------------------------------------------------------
// Omens and road-talk
// ---------------------------------------------------------------------------

/**
 * Sky omens: one named comet (and its return), eclipses, and strange moon years.
 * Moon text draws on the world's actual moons, so the chronicle matches the sky
 * you sleep under.
 */
internal fun generateOmens(
    rng: Random,
    moons: List<SkyMoon>,
    phonetics: List<Phonetics>,
    cultures: List<Culture>,
    totalYears: Int
): List<ChronicleEvent> {
    val out = mutableListOf<ChronicleEvent>()

    // One comet per world, named in the province's own tongue — and it comes back.
    val cometName = phonetics.random(rng).word(rng, 2)
    val cometCulture = cultures.random(rng)
    val firstYear = 2 + rng.nextInt((totalYears - 4).coerceAtLeast(1))
    out += ChronicleEvent(
        year = firstYear,
        kind = EventKind.COMET,
        subject = cometName,
        text = "In $firstYear, the comet $cometName crossed the night for ${3 + rng.nextInt(9)} nights. " +
            "The ${cometCulture.adjective} folk called it a lamp swung before the world, and would not plant while it burned."
    )
    val returnYear = firstYear + 62 + rng.nextInt(30)
    if (returnYear <= totalYears) {
        out += ChronicleEvent(
            year = returnYear,
            kind = EventKind.COMET,
            subject = cometName,
            text = "The comet $cometName returned, exactly as the old tables promised. Pilgrim roads were crowded that season."
        )
    }

    // Eclipses: the sun went out at noon.
    repeat(1 + rng.nextInt(2)) {
        val year = 2 + rng.nextInt((totalYears - 3).coerceAtLeast(1))
        out += ChronicleEvent(
            year = year,
            kind = EventKind.ECLIPSE,
            text = "In $year, the sun went black at noon. The birds went to bed, the wells went still, and the priests lit every candle they had."
        )
    }

    // Moon wonders, read straight from the world's own moons.
    repeat(1 + rng.nextInt(3)) {
        val year = 2 + rng.nextInt((totalYears - 3).coerceAtLeast(1))
        val text = if (moons.size >= 2 && rng.nextInt(2) == 0) {
            "In $year, both moons stood in a row at dusk and held there past the third bell. Pilots steered by it all winter."
        } else {
            val moon = moons.random(rng)
            if (rng.nextInt(2) == 0) {
                "In $year, the ${moon.tintName} moon rose full twice in one night. The chroniclers wrote it down three times to be sure."
            } else {
                "In $year, the ${moon.tintName} moon dimmed at its setting, as if a hand had passed over it. The old folk called it a warning and got on with the harvest."
            }
        }
        out += ChronicleEvent(year = year, kind = EventKind.MOONWONDER, text = text)
    }
    return out
}

/** Road-talk drawn from what actually happened: wars, beasts, lost relics, the vault. */
internal fun generateRumors(
    rng: Random,
    events: List<ChronicleEvent>,
    sites: List<Site>,
    actors: List<Actor>,
    vault: Site,
    beasts: List<Beast>,
    artifacts: List<Artifact>,
    conflicts: List<Conflict>
): List<Rumor> {
    val out = mutableListOf<Rumor>()
    val town = sites.firstOrNull { it.isSettlement && !it.ruined } ?: sites.first()
    fun named(id: Int?): Actor? = actors.firstOrNull { it.id == id }

    out += Rumor(
        text = "\"A vault below the barrow was opened. Nobody paid the keepers.\"",
        source = "${RUMOR_SOURCES.random(rng)} at ${town.name}",
        daysOld = 1 + rng.nextInt(3),
        aboutPlayer = true
    )

    events.takeLast(3).forEach { event ->
        out += Rumor(
            text = "\"" + shortRumor(event, rng) + "\"",
            source = "${RUMOR_SOURCES.random(rng)} at ${sites.random(rng).name}",
            daysOld = 1 + rng.nextInt(30),
            aboutPlayer = false
        )
    }

    beasts.filter { it.alive }.take(2).forEach { beast ->
        val lair = sites.firstOrNull { it.id == beast.lairSiteId }
        out += Rumor(
            text = "\"${beast.name} still walks. ${beast.raids} steadings gone this year. Keep clear of ${lair?.name ?: "the fens"}.\"",
            source = "${RUMOR_SOURCES.random(rng)} near ${lair?.name ?: town.name}",
            daysOld = 2 + rng.nextInt(10),
            aboutPlayer = false,
            siteId = lair?.id ?: -1
        )
    }

    artifacts.filter { it.keeperSiteId == null }.take(1).forEach { artifact ->
        out += Rumor(
            text = "\"They still hunt ${artifact.name} — ${artifact.whereabouts}, they say.\"",
            source = "${RUMOR_SOURCES.random(rng)} on the road",
            daysOld = 3 + rng.nextInt(20),
            aboutPlayer = false
        )
    }

    conflicts.lastOrNull { it.kind == ConflictKind.REALM_WAR }?.let { war ->
        val a = named(war.attackerId)
        val b = named(war.defenderId)
        if (a != null && b != null) {
            out += Rumor(
                text = "\"${a.name} have not forgotten what ${b.name} took from them. There will be another war.\"",
                source = "${RUMOR_SOURCES.random(rng)} at ${town.name}",
                daysOld = 2 + rng.nextInt(14),
                aboutPlayer = false
            )
        }
    }

    val buyer = actors.filter { !it.extinct }.randomOrNull(rng) ?: actors.first()
    out += Rumor(
        text = "\"${buyer.name} pay well for anything brass out of ${vault.name}.\"",
        source = "${RUMOR_SOURCES.random(rng)} on the road",
        daysOld = 2 + rng.nextInt(12),
        aboutPlayer = false,
        siteId = vault.id
    )
    return out
}

internal fun shortRumor(event: ChronicleEvent, rng: Random): String {
    val trimmed = event.text.substringBefore('.').trim()
    return when (event.kind) {
        EventKind.WAR, EventKind.BATTLE -> "$trimmed. They are still burying them."
        EventKind.PLAGUE, EventKind.FAMINE -> "$trimmed. Do not drink the well water."
        EventKind.SCHISM -> "$trimmed. Two creeds, one knife."
        EventKind.BEAST -> "$trimmed. Keep a blade by the door."
        EventKind.TREATY -> "$trimmed. Paper peace, iron anger."
        EventKind.RAID, EventKind.REBELLION -> "$trimmed. Travel with a sharpened stake."
        EventKind.SUCCESSION -> "$trimmed. New seat, same debts."
        EventKind.COMET -> "$trimmed. They still name children after it."
        EventKind.ECLIPSE -> "$trimmed. No one lights a lamp that day, even now."
        EventKind.MOONWONDER -> "$trimmed. The old folk still point at the sky when they tell it."
        else -> "$trimmed, they say."
    } + if (rng.nextInt(4) == 0) " I had it from my cousin." else ""
}

// ---------------------------------------------------------------------------
// Small shared helpers
// ---------------------------------------------------------------------------

internal fun <T> pickUnused(rng: Random, pool: List<T>, used: MutableSet<T>): T {
    val free = pool.filterNot { used.contains(it) }
    val chosen = if (free.isEmpty()) pool.random(rng) else free.random(rng)
    used += chosen
    return chosen
}

internal fun formatCount(n: Int): String =
    n.toString().reversed().chunked(3).joinToString(",").reversed()

internal fun seedCode(seed: Long, cultureName: String): String {
    val digits = (abs(seed) % 10000L).toString().padStart(4, '0')
    return "$digits-${cultureName.uppercase()}"
}
