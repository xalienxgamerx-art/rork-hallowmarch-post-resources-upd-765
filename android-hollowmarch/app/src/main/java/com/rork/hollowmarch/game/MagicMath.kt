package com.rork.hollowmarch.game

import kotlin.math.roundToInt

/**
 * The one door elemental harm passes through. Today the province tracks no
 * resistances, so harm passes unchanged; tomorrow, a resistance or weakness is
 * a table here keyed by element and body — no spell, component, or resolver
 * changes at all. Elemental nature stays a property of the damage, never of
 * the spell that carries it.
 */
object ElementalRules {

    /** Elements the realm's components carry. */
    const val FIRE = "fire"
    const val FROST = "frost"
    const val SHOCK = "shock"
    const val POISON = "poison"

    /** How raw elemental harm lands on a body; the extension point for resistances. */
    fun modify(raw: Int, element: String?): Int = raw
}

/**
 * The one place spell numbers are made: cost, casting time, complexity, value.
 * Every caller — the spellcrafter, the UI, the economy to come — asks here, so
 * the arithmetic is tuned once and is the same everywhere.
 */
object SpellCalc {

    // ---------------------------------------------------------------- tuning
    private const val MAGNITUDE_WEIGHT = 0.12f
    private const val DURATION_WEIGHT = 0.06f
    private const val AREA_WEIGHT = 0.08f
    private const val COMPLEXITY_PER_EFFECT = 0.14f
    private const val CAST_BASE = 0.5f
    private const val CAST_PER_EFFECT = 0.18f
    private const val CAST_PER_COMPLEXITY = 0.22f
    private const val CAST_SKILL_DISCOUNT = 0.012f
    private const val VALUE_PER_COST = 4
    private const val VALUE_PER_COMPLEXITY = 18

    /** What one delivery costs to shape: the safer the throw, the dearer the magic. */
    fun deliveryModifier(delivery: Delivery): Float = when (delivery) {
        Delivery.SELF -> 0.6f
        Delivery.TOUCH -> 1f
        Delivery.TARGET -> 1.2f
        Delivery.AREA -> 1.45f
    }

    /** The magicka one effect asks, from its component and the parameters chosen. */
    fun effectCost(component: MagicalComponent, effect: SpellEffect): Float {
        val magnitude = 1f + effect.magnitude * MAGNITUDE_WEIGHT
        val duration = 1f + effect.duration * DURATION_WEIGHT
        val area = 1f + effect.area * AREA_WEIGHT
        return component.baseCost * component.baseComplexity *
            magnitude * duration * area * deliveryModifier(effect.delivery)
    }

    /** The whole formula's cost: effects together strain the weave more than apart. */
    fun spellCost(effects: List<SpellEffect>): Int =
        effects.sumOf { effectCost(ComponentRegistry.get(it.componentId), it).toDouble() }
            .times(1 + (effects.size - 1) * COMPLEXITY_PER_EFFECT)
            .roundToInt()
            .coerceAtLeast(1)

    /** How tangled the formula is: effects, plus spread, plus held duration. */
    fun complexity(effects: List<SpellEffect>): Float =
        effects.sumOf { e ->
            val c = ComponentRegistry.get(e.componentId)
            (c.baseComplexity +
                (if (e.area > 0f) 0.5 else 0.0) +
                (if (e.duration > 0) 0.5 else 0.0)).toDouble()
        }.toFloat()

    /** How long the formula takes to shape: skill quickens the hand. */
    fun castingTime(effects: List<SpellEffect>, craft: Int): Float {
        val raw = CAST_BASE + effects.size * CAST_PER_EFFECT +
            complexity(effects) * CAST_PER_COMPLEXITY +
            effects.sumOf { it.magnitude / 250.0 } +
            effects.sumOf { it.area / 120.0 }
        return (raw * (1f - craft * CAST_SKILL_DISCOUNT)).toFloat().coerceAtLeast(0.3f)
    }

    /** What the formula is worth: power and tangle together, in brass. */
    fun value(cost: Int, complexity: Float): Int =
        cost * VALUE_PER_COST + (complexity * VALUE_PER_COMPLEXITY).roundToInt()

    /** The schools a formula belongs to, from its components. */
    fun schools(effects: List<SpellEffect>): Set<MagicalSchool> =
        effects.map { ComponentRegistry.get(it.componentId).school }.toSet()

    /** Forge a complete spell from a formula: the only door to a Spell object. */
    fun forge(id: String, name: String, creator: String, effects: List<SpellEffect>): Spell {
        val cost = spellCost(effects)
        val complexity = complexity(effects)
        return Spell(
            id = id,
            name = name,
            effects = effects,
            creator = creator,
            cost = cost,
            castingTime = castingTime(effects, Skill.MIN),
            value = value(cost, complexity),
            complexity = complexity,
            schools = schools(effects)
        )
    }
}
