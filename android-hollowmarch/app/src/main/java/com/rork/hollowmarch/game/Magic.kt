package com.rork.hollowmarch.game

/**
 * The frame of all magic: six schools, four deliveries, and the spell formula.
 * Nothing here knows how a specific component behaves — [MagicalComponent]
 * carries the data, [MagicBehavior] names the application, and SpellCasting
 * resolves it. A new component is a registration, never a rewrite.
 */
enum class MagicalSchool(val label: String) {
    DESTRUCTION("Destruction"),
    RESTORATION("Restoration"),
    ALTERATION("Alteration"),
    ILLUSION("Illusion"),
    CONJURATION("Conjuration"),
    MYSTICISM("Mysticism")
}

/** How an effect travels from the caster: a parameter of the formula. */
enum class Delivery(val label: String) {
    SELF("Self"), TOUCH("Touch"), TARGET("Target"), AREA("Area")
}

/** What a component does when its spell resolves; the cast resolver implements these. */
enum class MagicBehavior {
    DAMAGE, RESTORE, FORTIFY, DRAIN, SYPHON, SHIELD, LIGHT,
    FEAR, CALM, FRENZY, CHARM, COMMAND, PARALYZE,
    HASTE, SLOW, REFLECT, CURE, SUMMON, DETECT_LIFE, DETECT_MAGIC
}

/** What kind of thing a generic component selects: an Attribute, a Skill, or nothing. */
enum class MagicTargetKind { NONE, ATTRIBUTE, SKILL }

/**
 * One reusable magical effect: data only. The registry holds these; the
 * spellcrafter consumes them; the resolver applies them by [behavior].
 * [aspect] names what RESTORE/FORTIFY/DRAIN touch: "health", "magicka",
 * "fatigue", or an Attr name for the older specific components. Components
 * with a [targetKind] take their selection from the SpellEffect instead.
 * [element] marks elemental harm — a property of the damage itself, so future
 * resistances answer at one door without touching any spell.
 */
data class MagicalComponent(
    val id: String,
    val name: String,
    val description: String,
    val school: MagicalSchool,
    val behavior: MagicBehavior,
    val baseCost: Float,
    val baseComplexity: Float = 1f,
    val deliveries: Set<Delivery>,
    /** The absolute limits the component allows; the caster's skill narrows them. */
    val magnitude: IntRange = 1..100,
    val duration: IntRange = 0..60,
    val area: ClosedFloatingPointRange<Float> = 0f..20f,
    val range: IntRange = 1..150,
    /** The attributes this effect exercises — Wisdom for control, Presence for fear. */
    val attributes: Set<Attr> = setOf(Attr.INTELLECT),
    val aspect: String = "health",
    val targetKind: MagicTargetKind = MagicTargetKind.NONE,
    val element: String? = null
)

/**
 * One entry in a spell formula: which component, and the parameters chosen.
 * [target] carries the selection for generic components — an Attr name for
 * Damage/Fortify Attribute, a Skill name for Damage/Fortify Skill — and is
 * blank for every other component.
 */
data class SpellEffect(
    val componentId: String,
    val magnitude: Int,
    val duration: Int = 0,
    val area: Float = 0f,
    val delivery: Delivery = Delivery.TOUCH,
    val range: Int = 1,
    val target: String = ""
) {
    fun component(): MagicalComponent = ComponentRegistry.get(componentId)

    internal fun encode(): String =
        listOf(componentId, "$magnitude", "$duration", area.toString(), delivery.name, "$range", target)
            .joinToString(FIELD)

    companion object {
        private const val FIELD = "\u001D"
        fun fromEncoded(raw: String): SpellEffect? {
            val f = raw.split(FIELD)
            if (f.size < 6) return null
            val delivery = Delivery.entries.firstOrNull { it.name == f[4] } ?: return null
            return SpellEffect(
                componentId = f[0],
                magnitude = f[1].toIntOrNull() ?: return null,
                duration = f[2].toIntOrNull() ?: 0,
                area = f[3].toFloatOrNull() ?: 0f,
                delivery = delivery,
                range = f[5].toIntOrNull() ?: 1,
                target = f.getOrElse(6) { "" }
            )
        }
    }
}

/**
 * A persistent formula: developer-made, player-made, NPC-made, or discovered —
 * all the same object. The derived numbers are computed once at creation and
 * travel with the spell, so a save reloads exactly what was forged.
 */
data class Spell(
    val id: String,
    val name: String,
    val effects: List<SpellEffect>,
    val creator: String,
    val cost: Int,
    val castingTime: Float,
    val value: Int,
    val complexity: Float,
    val schools: Set<MagicalSchool>
) {
    fun encode(): String = listOf(
        id, name, creator, "$cost", castingTime.toString(), "$value", complexity.toString(),
        schools.joinToString(",") { it.name },
        effects.joinToString(ENTRY) { it.encode() }
    ).joinToString(SECTION)

    companion object {
        private const val SECTION = "\u001F"
        private const val ENTRY = "\u001E"

        /** Restore from [encode]; a mangled string comes back null, never broken. */
        fun fromEncoded(raw: String?): Spell? {
            if (raw.isNullOrBlank()) return null
            val s = raw.split(SECTION)
            if (s.size < 9) return null
            val effects = s[8].split(ENTRY).mapNotNull { SpellEffect.fromEncoded(it) }
            if (effects.isEmpty()) return null
            return Spell(
                id = s[0],
                name = s[1],
                creator = s[2],
                cost = s[3].toIntOrNull() ?: return null,
                castingTime = s[4].toFloatOrNull() ?: return null,
                value = s[5].toIntOrNull() ?: return null,
                complexity = s[6].toFloatOrNull() ?: return null,
                schools = s[7].split(",").mapNotNull { n -> MagicalSchool.entries.firstOrNull { it.name == n } }.toSet(),
                effects = effects
            )
        }
    }
}

/** What a soul can work with: the components it knows and the spells it keeps. */
class KnownMagic(
    val knownComponents: MutableSet<String> = mutableSetOf(),
    val knownSpells: MutableSet<String> = mutableSetOf()
) {
    fun knows(id: String): Boolean = knownComponents.contains(id) || knownSpells.contains(id)

    fun learnComponent(id: String): Boolean = knownComponents.add(id)

    fun learnSpell(id: String): Boolean = knownSpells.add(id)

    fun forgetSpell(id: String): Boolean = knownSpells.remove(id)

    fun encode(): String = listOf(
        knownComponents.joinToString(ENTRY),
        knownSpells.joinToString(ENTRY)
    ).joinToString(SECTION)

    companion object {
        private const val SECTION = "\u001F"
        private const val ENTRY = "\u001E"

        fun fromEncoded(raw: String?): KnownMagic {
            val known = KnownMagic()
            if (raw.isNullOrBlank()) return known
            val s = raw.split(SECTION)
            if (s.isNotEmpty()) s[0].split(ENTRY).filter { it.isNotBlank() }.forEach { known.knownComponents += it }
            if (s.size > 1) s[1].split(ENTRY).filter { it.isNotBlank() }.forEach { known.knownSpells += it }
            return known
        }
    }
}

/**
 * Magical proficiency, one bar per school, grown by working magic — the same
 * learn-by-doing shape as every other skill in the province: deeds fill the
 * bar, a filled bar raises the school. Kept wholly apart from Spellcrafting.
 */
class MagicProficiencies private constructor(
    private val values: MutableMap<MagicalSchool, Int>,
    private val bars: MutableMap<MagicalSchool, Float>
) {
    fun value(school: MagicalSchool): Int = values[school] ?: MIN

    /** Work magic of a school; returns the school if its bar filled and it rose. */
    fun feed(school: MagicalSchool, amount: Float): MagicalSchool? {
        if (amount <= 0f) return null
        val current = value(school)
        if (current >= MAX) return null
        val now = (bars[school] ?: 0f) + amount
        return if (now >= threshold(current)) {
            bars[school] = now - threshold(current)
            values[school] = current + 1
            school
        } else {
            bars[school] = now
            null
        }
    }

    fun progressFraction(school: MagicalSchool): Float =
        ((bars[school] ?: 0f) / threshold(value(school))).coerceIn(0f, 0.999f)

    private fun threshold(value: Int): Float = 10f + value * 2.5f

    fun encode(): String = MagicalSchool.entries.joinToString(ENTRY) { "${it.name}$FIELD${value(it)}$FIELD${bars[it] ?: 0f}" }

    companion object {
        const val MIN = 1
        const val MAX = 20
        private const val ENTRY = "\u001E"
        private const val FIELD = "\u001D"

        fun fresh(): MagicProficiencies = MagicProficiencies(mutableMapOf(), mutableMapOf())

        fun fromEncoded(raw: String?): MagicProficiencies {
            val prof = fresh()
            if (raw.isNullOrBlank()) return prof
            raw.split(ENTRY).forEach { piece ->
                val f = piece.split(FIELD)
                val school = MagicalSchool.entries.firstOrNull { it.name == f.getOrNull(0) } ?: return@forEach
                f.getOrNull(1)?.toIntOrNull()?.let { prof.values[school] = it.coerceIn(MIN, MAX) }
                f.getOrNull(2)?.toFloatOrNull()?.let { if (it >= 0f) prof.bars[school] = it }
            }
            return prof
        }
    }
}

/**
 * What a caster of given capabilities can shape: the parameter ranges the
 * spellcrafter offers. Skill widens every limit — nothing is gated, only
 * narrowed. All constants here are tunable configuration, not UI logic.
 */
object SpellForge {
    // How much of a component's own limits a raw apprentice may reach.
    const val FLOOR_FRACTION = 0.2f
    const val SKILL_SPAN = 0.8f

    // Attributes widen the reach further: Intellect the formula, Wisdom the weave.
    const val WISDOM_EFFECT = 0.06f
    const val PRESENCE_EFFECT = 0.05f

    /** How high a magnitude this caster can push this component to. */
    fun magnitudeCap(c: MagicalComponent, craft: Int, stats: StatBlock): Int {
        val control = c.attributes.maxOfOrNull { stats[it] } ?: StatBlock.BASE
        val scale = FLOOR_FRACTION + SKILL_SPAN * craft / Skill.MAX +
            (control - StatBlock.BASE) * PRESENCE_EFFECT
        return (c.magnitude.first + (c.magnitude.last - c.magnitude.first) * scale)
            .toInt().coerceIn(c.magnitude.first, c.magnitude.last)
    }

    /** How long the weave holds: skill and Wisdom stretch it. */
    fun durationCap(c: MagicalComponent, craft: Int, stats: StatBlock): Int {
        if (c.duration.last <= 0) return 0
        val scale = FLOOR_FRACTION + SKILL_SPAN * craft / Skill.MAX +
            (stats[Attr.WISDOM] - StatBlock.BASE) * WISDOM_EFFECT
        return (c.duration.last * scale).toInt().coerceIn(c.duration.first, c.duration.last)
    }

    /** How wide the weave spreads: skill and Wisdom again. */
    fun areaCap(c: MagicalComponent, craft: Int, stats: StatBlock): Float {
        if (c.area.endInclusive <= 0f) return 0f
        val scale = FLOOR_FRACTION + SKILL_SPAN * craft / Skill.MAX +
            (stats[Attr.WISDOM] - StatBlock.BASE) * WISDOM_EFFECT
        return (c.area.endInclusive * scale).coerceIn(0f, c.area.endInclusive)
    }

    /** How far the hand throws: skill alone. */
    fun rangeCap(c: MagicalComponent, craft: Int): Int {
        val scale = FLOOR_FRACTION + SKILL_SPAN * craft / Skill.MAX
        return (c.range.first + (c.range.last - c.range.first) * scale)
            .toInt().coerceIn(c.range.first, c.range.last)
    }

    /** How many effects one formula may hold: Intellect carries the calculation. */
    fun maxEffects(craft: Int, stats: StatBlock): Int =
        (1 + craft / 6 + (stats[Attr.INTELLECT] - StatBlock.BASE) / 3).coerceIn(1, 6)

    /** The complexity budget a formula must not exceed to hold together. */
    fun complexityBudget(craft: Int, stats: StatBlock): Float =
        2f + craft * 0.4f + (stats[Attr.INTELLECT] - StatBlock.BASE) * 0.25f
}

/** The attributes magic may bend: Fortune alone stands outside the weave. */
val MAGIC_TARGET_ATTRIBUTES: List<Attr> = Attr.entries.filter { it != Attr.FORTUNE }
