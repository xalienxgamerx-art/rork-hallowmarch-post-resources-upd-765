package com.rork.hollowmarch.game

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The ways an enchantment answers the world. One shared vocabulary for player
 * and NPC alike: combat and gameplay emit their facts, the enchantment system
 * reacts — nothing here knows a specific item or a specific spell.
 */
enum class EnchantmentActivation(val label: String) {
    CONSTANT("constant"),
    ON_HIT("on hit"),
    ON_USE("on use"),
    WHEN_DAMAGED("when damaged"),
    WHEN_ATTACKED("when attacked"),
    WHEN_KILLING("when killing"),
    WHEN_WORN("when worn"),
    WHEN_LOW_HEALTH("when health runs low"),
    WHEN_LOW_FATIGUE("when fatigue runs low"),
    WHEN_LOW_MAGICKA("when magicka runs low"),
    MANUAL("manual");

    /** True when the working costs charge to fire; constants and worn auras do not. */
    val consumesCharge: Boolean get() = this != CONSTANT
}

/**
 * What an enchantment does: one reusable definition, built from the existing
 * [MagicalComponent] catalog and resolved through the one resolver every
 * spell passes through. A definition names the working — Fire Damage, Restore
 * Health, Fortify Might — never the item. Every magnitude, charge, and maker
 * belongs to the instance on the item.
 */
data class EnchantDef(
    val id: String,
    val name: String,
    val componentId: String,
    /** The selection a generic component takes — an Attr name, or blank. */
    val target: String = "",
    val delivery: Delivery = Delivery.TOUCH,
    val activation: EnchantmentActivation,
    /** The working's magnitude on an honest piece; quality and material temper it. */
    val magnitudeBase: Int,
    /** The charge each firing drinks. Zero for a constant. */
    val chargeCost: Int,
    /** What a timed working holds, in seconds; zero for the instant. */
    val durationBase: Int = 0,
    /** The item kinds this working can be woven into. */
    val compat: Set<ItemSlot>
) {
    val component: MagicalComponent get() = ComponentRegistry.get(componentId)
    val school: MagicalSchool get() = component.school
}

/**
 * The registry of reusable enchantment definitions. A new component in the
 * magic catalog becomes an enchantment by registration here — never a rewrite.
 */
object EnchantRegistry {

    private val defs = LinkedHashMap<String, EnchantDef>()

    fun register(def: EnchantDef) {
        defs[def.id] = def
    }

    fun get(id: String): EnchantDef? = defs[id]

    fun all(): List<EnchantDef> = defs.values.toList()

    /** Definitions whose weave fits this kind of thing. */
    fun compatible(item: Item): List<EnchantDef> =
        all().filter { it.compat.contains(item.archetype.slot) }

    private val WEAPON = setOf(ItemSlot.WEAPON)
    private val ARMOR = setOf(ItemSlot.BODY, ItemSlot.SHIELD)
    private val JEWELRY = setOf(ItemSlot.TRINKET)

    private val CATALOG = listOf(
        // ------------------------------------------------------ arms: on hit
        EnchantDef("fire_damage_weapon", "Fire Damage", "fire_damage",
            activation = EnchantmentActivation.ON_HIT, magnitudeBase = 10, chargeCost = 6, compat = WEAPON),
        EnchantDef("frost_damage_weapon", "Frost Damage", "frost_damage",
            activation = EnchantmentActivation.ON_HIT, magnitudeBase = 10, chargeCost = 6, compat = WEAPON),
        EnchantDef("shock_damage_weapon", "Shock Damage", "shock_damage",
            activation = EnchantmentActivation.ON_HIT, magnitudeBase = 9, chargeCost = 5, compat = WEAPON),
        EnchantDef("poison_damage_weapon", "Poison Damage", "poison_damage",
            activation = EnchantmentActivation.ON_HIT, magnitudeBase = 6, chargeCost = 5,
            durationBase = 8, compat = WEAPON),
        EnchantDef("drain_attribute_weapon", "Drain Might", "damage_attribute",
            target = Attr.MIGHT.name, activation = EnchantmentActivation.ON_HIT,
            magnitudeBase = 4, chargeCost = 9, durationBase = 10, compat = WEAPON),
        // ------------------------------------------------------ arms: syphon
        EnchantDef("absorb_health_weapon", "Absorb Health", "syphon_health",
            activation = EnchantmentActivation.ON_HIT, magnitudeBase = 8, chargeCost = 8, compat = WEAPON),
        EnchantDef("absorb_magicka_weapon", "Absorb Magicka", "syphon_magicka",
            activation = EnchantmentActivation.ON_HIT, magnitudeBase = 8, chargeCost = 7, compat = WEAPON),
        // ------------------------------------------------------ arms: the bind
        EnchantDef("paralyze_weapon", "Paralyze", "paralyze",
            activation = EnchantmentActivation.ON_HIT, magnitudeBase = 2, chargeCost = 20,
            durationBase = 3, compat = WEAPON),
        EnchantDef("slow_weapon", "Slow", "slow",
            activation = EnchantmentActivation.ON_HIT, magnitudeBase = 10, chargeCost = 10,
            durationBase = 8, compat = WEAPON),
        EnchantDef("fear_weapon", "Fear", "fear",
            activation = EnchantmentActivation.ON_HIT, magnitudeBase = 6, chargeCost = 10,
            durationBase = 6, compat = WEAPON),
        // ------------------------------------------------------ arms: the kill
        EnchantDef("soul_eater_weapon", "Soul-Eater", "restore_magicka",
            delivery = Delivery.SELF, activation = EnchantmentActivation.WHEN_KILLING,
            magnitudeBase = 15, chargeCost = 8, compat = WEAPON),
        // ------------------------------------------- armor: the answering blow
        EnchantDef("embers_armor", "Retaliate in Flame", "fire_damage",
            activation = EnchantmentActivation.WHEN_ATTACKED, magnitudeBase = 8, chargeCost = 10,
            compat = ARMOR),
        EnchantDef("frost_armor", "Retaliate in Frost", "frost_damage",
            activation = EnchantmentActivation.WHEN_ATTACKED, magnitudeBase = 8, chargeCost = 10,
            compat = ARMOR),
        EnchantDef("warding_armor", "Warding Skin", "shield",
            delivery = Delivery.SELF, activation = EnchantmentActivation.WHEN_DAMAGED,
            magnitudeBase = 15, chargeCost = 12, durationBase = 20, compat = ARMOR),
        EnchantDef("turning_armor", "Turn the Blow", "reflect_damage",
            delivery = Delivery.SELF, activation = EnchantmentActivation.WHEN_ATTACKED,
            magnitudeBase = 10, chargeCost = 14, durationBase = 20, compat = ARMOR),
        // -------------------------------------------------- jewelry: the mends
        EnchantDef("mending_amulet", "Mending", "restore_health",
            delivery = Delivery.SELF, activation = EnchantmentActivation.ON_USE,
            magnitudeBase = 15, chargeCost = 10, compat = JEWELRY),
        EnchantDef("wind_amulet", "Second Wind", "restore_fatigue",
            delivery = Delivery.SELF, activation = EnchantmentActivation.ON_USE,
            magnitudeBase = 15, chargeCost = 8, compat = JEWELRY),
        EnchantDef("well_amulet", "Deep Well", "restore_magicka",
            delivery = Delivery.SELF, activation = EnchantmentActivation.ON_USE,
            magnitudeBase = 15, chargeCost = 10, compat = JEWELRY),
        EnchantDef("shadows_ring", "Ring of Shadows", "haste",
            delivery = Delivery.SELF, activation = EnchantmentActivation.MANUAL,
            magnitudeBase = 20, chargeCost = 12, durationBase = 20, compat = JEWELRY),
        EnchantDef("command_ring", "Command", "command",
            activation = EnchantmentActivation.MANUAL, magnitudeBase = 8, chargeCost = 18,
            durationBase = 20, compat = JEWELRY),
        // ------------------------------------------- jewelry: the constant wear
        EnchantDef("might_ring", "Might", "fortify_attribute",
            target = Attr.MIGHT.name, delivery = Delivery.SELF,
            activation = EnchantmentActivation.CONSTANT, magnitudeBase = 5, chargeCost = 0,
            compat = JEWELRY),
        EnchantDef("vigor_ring", "Vigor", "fortify_attribute",
            target = Attr.VIGOR.name, delivery = Delivery.SELF,
            activation = EnchantmentActivation.CONSTANT, magnitudeBase = 5, chargeCost = 0,
            compat = JEWELRY),
        EnchantDef("finesse_ring", "Finesse", "fortify_attribute",
            target = Attr.FINESSE.name, delivery = Delivery.SELF,
            activation = EnchantmentActivation.CONSTANT, magnitudeBase = 5, chargeCost = 0,
            compat = JEWELRY),
        EnchantDef("swiftness_ring", "Swiftness", "fortify_attribute",
            target = Attr.SWIFTNESS.name, delivery = Delivery.SELF,
            activation = EnchantmentActivation.CONSTANT, magnitudeBase = 5, chargeCost = 0,
            compat = JEWELRY),
        EnchantDef("wisdom_ring", "Wisdom", "fortify_attribute",
            target = Attr.WISDOM.name, delivery = Delivery.SELF,
            activation = EnchantmentActivation.CONSTANT, magnitudeBase = 5, chargeCost = 0,
            compat = JEWELRY),
        EnchantDef("intellect_ring", "Intellect", "fortify_attribute",
            target = Attr.INTELLECT.name, delivery = Delivery.SELF,
            activation = EnchantmentActivation.CONSTANT, magnitudeBase = 5, chargeCost = 0,
            compat = JEWELRY),
        EnchantDef("presence_ring", "Presence", "fortify_attribute",
            target = Attr.PRESENCE.name, delivery = Delivery.SELF,
            activation = EnchantmentActivation.CONSTANT, magnitudeBase = 5, chargeCost = 0,
            compat = JEWELRY),
        // ---------------------------------------------- jewelry: the worn aura
        EnchantDef("lamp_amulet", "Lamp", "light",
            delivery = Delivery.SELF, activation = EnchantmentActivation.WHEN_WORN,
            magnitudeBase = 6, chargeCost = 0, durationBase = 240, compat = JEWELRY)
    )

    init {
        CATALOG.forEach { register(it) }
    }
}

/**
 * Tunable enchantment arithmetic, in one place: what quality and material
 * grant a weave, what a recharge costs, when the low-resource weaves wake.
 * No UI, combat, or inventory code does this math itself.
 */
object EnchantRules {

    /** Recharge: magicka spent per point of charge restored. */
    const val MAGICKA_PER_CHARGE: Float = 1f

    /** The low-resource weaves wake below this fraction of the pool. */
    const val LOW_THRESHOLD: Float = 0.25f

    /** And stay quiet again once the pool climbs back past this. */
    const val LOW_RECOVER: Float = 0.35f

    /** How often the low-resource weaves are asked, in seconds — not every frame. */
    const val LOW_CHECK_INTERVAL: Float = 0.5f

    /** Charge capacity on an honest, common piece, per point of charge cost. */
    const val CAPACITY_PER_COST: Int = 12

    /** What each make of piece holds: poor work leaks, legend holds like a vow. */
    fun qualityCapacityMult(quality: Quality): Float = when (quality) {
        Quality.SHODDY -> 0.6f
        Quality.WORN -> 0.8f
        Quality.HONEST -> 1.0f
        Quality.FINE -> 1.3f
        Quality.SUPERB -> 1.7f
        Quality.LEGENDARY -> 2.2f
    }

    /** What each make of piece works at: fine work carries a stronger weave. */
    fun qualityMagnitudeMult(quality: Quality): Float = when (quality) {
        Quality.SHODDY -> 0.6f
        Quality.WORN -> 0.8f
        Quality.HONEST -> 1.0f
        Quality.FINE -> 1.2f
        Quality.SUPERB -> 1.45f
        Quality.LEGENDARY -> 1.8f
    }

    /**
     * The arcane three hold more weave and work it harder — the mark of the
     * otherwhere the material itself declares, read through [Material.arcane]
     * rather than any hard-coded name.
     */
    fun arcaneCapacityMult(material: Material): Float = if (material.arcane) 1.5f else 1f
    fun arcaneMagnitudeMult(material: Material): Float = if (material.arcane) 1.3f else 1f

    /** The kinds of thing a weave can be laid into. */
    val ENCHANTABLE_SLOTS: Set<ItemSlot> =
        setOf(ItemSlot.WEAPON, ItemSlot.BODY, ItemSlot.SHIELD, ItemSlot.TRINKET)

    /** How often a found thing is woven, one chance in [chanceDenominator]. */
    fun lootChanceDenominator(item: Item): Int {
        val base = when (item.quality) {
            Quality.SHODDY -> 18
            Quality.WORN -> 16
            Quality.HONEST -> 14
            Quality.FINE -> 10
            Quality.SUPERB -> 6
            Quality.LEGENDARY -> 3
        }
        return if (item.material.arcane) (base / 3).coerceAtLeast(1) else base
    }
}

/**
 * One enchantment living on one particular item: the definition's id, this
 * piece's own magnitude and capacity, and the charge currently in it. Two
 * blades may share a definition and never share a charge — the state is the
 * instance's, and rides the item's own save line.
 */
data class ItemEnchantment(
    val defId: String,
    val magnitude: Int,
    val maxCharge: Int,
    var currentCharge: Int,
    val creatorId: String? = null
) {
    val def: EnchantDef? get() = EnchantRegistry.get(defId)

    /** A charge-drinking working is dry when its charge cannot pay. */
    fun isDry(): Boolean = (def?.chargeCost ?: 0) > currentCharge

    fun describe(): String {
        val def = def ?: return defId
        return if (def.activation == EnchantmentActivation.CONSTANT) {
            "${def.name} (constant)"
        } else {
            "${def.name} (${def.activation.label})"
        }
    }

    fun encode(): String =
        listOf(defId, "$magnitude", "$maxCharge", "$currentCharge", creatorId ?: "-")
            .joinToString(FIELD)

    companion object {
        private const val FIELD = "\u0001"
        private const val ITEMS = "\u0003"

        fun encodeAll(list: List<ItemEnchantment>): String =
            list.joinToString(ITEMS) { it.encode() }

        fun fromEncoded(raw: String): ItemEnchantment? {
            val f = raw.split(FIELD)
            if (f.size < 4) return null
            val max = f[2].toIntOrNull() ?: return null
            return ItemEnchantment(
                defId = f[0],
                magnitude = f[1].toIntOrNull() ?: return null,
                maxCharge = max,
                currentCharge = (f[3].toIntOrNull() ?: 0).coerceIn(0, max.coerceAtLeast(0)),
                creatorId = f.getOrNull(4)?.takeUnless { it == "-" }
            )
        }

        fun fromEncodedAll(raw: String): List<ItemEnchantment> =
            raw.split(ITEMS).filter { it.isNotBlank() }.mapNotNull { fromEncoded(it) }
    }
}

/**
 * The enchantment service: one door for everything the province does with
 * woven things. Charges, recharges, constant bonuses, and triggers all pass
 * through here — no combat, inventory, or UI code does this arithmetic itself.
 */
object Enchanting {

    // ------------------------------------------------------------- inspection

    fun isEnchanted(item: Item): Boolean = item.enchantments.isNotEmpty()

    fun enchantmentsOf(item: Item): List<ItemEnchantment> = item.enchantments

    /** Whether this piece could bear this working at all. */
    fun canEnchant(item: Item, def: EnchantDef): Boolean =
        item.archetype.slot in EnchantRules.ENCHANTABLE_SLOTS &&
            def.compat.contains(item.archetype.slot)

    /** This piece's working strength for a definition: make and material temper it. */
    fun magnitudeFor(item: Item, def: EnchantDef): Int = (
        def.magnitudeBase *
            EnchantRules.qualityMagnitudeMult(item.quality) *
            EnchantRules.arcaneMagnitudeMult(item.material)
        ).roundToInt().coerceAtLeast(1)

    /** This piece's charge capacity for a definition: make and material hold it. */
    fun capacityFor(item: Item, def: EnchantDef): Int = (
        def.chargeCost * EnchantRules.CAPACITY_PER_COST *
            EnchantRules.qualityCapacityMult(item.quality) *
            EnchantRules.arcaneCapacityMult(item.material)
        ).roundToInt()

    /** The formula one instance resolves when its trigger answers. */
    fun effectFor(def: EnchantDef, instance: ItemEnchantment): SpellEffect = SpellEffect(
        componentId = def.componentId,
        magnitude = instance.magnitude,
        duration = if (def.durationBase > 0) def.durationBase else 0,
        area = 0f,
        delivery = def.delivery,
        range = 1,
        target = def.target
    )

    /** One line of lore: what this working does, in the world's plain words. */
    fun describeEffect(def: EnchantDef, instance: ItemEnchantment): String {
        val component = def.component
        val timed = if (def.durationBase > 0) ", holds ${def.durationBase}s" else ""
        val constant = def.activation == EnchantmentActivation.CONSTANT
        return when (component.behavior) {
            MagicBehavior.DAMAGE ->
                "Deals ${instance.magnitude} ${component.name.lowercase()}$timed"
            MagicBehavior.RESTORE ->
                "Restores ${instance.magnitude} ${component.aspect}$timed"
            MagicBehavior.FORTIFY ->
                "+${instance.magnitude} ${def.target.lowercase()}" + if (constant) ", while worn" else timed
            MagicBehavior.DRAIN ->
                "-${instance.magnitude} ${def.target.lowercase()} on the struck$timed"
            MagicBehavior.SYPHON ->
                "Draws ${instance.magnitude} ${component.aspect} from the struck to the wielder"
            MagicBehavior.SHIELD ->
                "A ward of ${instance.magnitude} against harm$timed"
            MagicBehavior.REFLECT ->
                "Turns a share of harm back on the striker$timed"
            MagicBehavior.HASTE ->
                "Quickens the wearer's stride$timed"
            MagicBehavior.LIGHT ->
                "A pale glow about the wearer$timed"
            MagicBehavior.PARALYZE, MagicBehavior.SLOW,
            MagicBehavior.FEAR, MagicBehavior.CALM,
            MagicBehavior.COMMAND, MagicBehavior.FRENZY, MagicBehavior.CHARM ->
                "${component.name} on the struck$timed"
            else -> component.name
        }
    }

    // ------------------------------------------------------------- enchanting

    /** Lay a working into a piece; the instance belongs to that piece alone. */
    fun enchantItem(item: Item, def: EnchantDef, creatorId: String? = null): Item {
        if (!canEnchant(item, def)) return item
        val capacity = capacityFor(item, def)
        val instance = ItemEnchantment(
            defId = def.id,
            magnitude = magnitudeFor(item, def),
            maxCharge = capacity,
            currentCharge = capacity,
            creatorId = creatorId
        )
        return item.copy(enchantments = item.enchantments + instance)
    }

    /** A compatible working rolled from the world's own rng — deterministic. */
    fun rollEnchant(rng: Random, item: Item): EnchantDef? {
        val pool = EnchantRegistry.compatible(item)
        if (pool.isEmpty()) return null
        return pool[rng.nextInt(pool.size)]
    }

    /**
     * The loot roll: most things stay mundane; fine work, legend work, and the
     * arcane three are woven more often. Uses only the given rng, so the same
     * world always weaves the same finds.
     */
    fun maybeEnchant(rng: Random, item: Item): Item {
        if (item.enchantments.isNotEmpty()) return item
        if (item.archetype.slot !in EnchantRules.ENCHANTABLE_SLOTS) return item
        if (rng.nextInt(EnchantRules.lootChanceDenominator(item)) != 0) return item
        val def = rollEnchant(rng, item) ?: return item
        return enchantItem(item, def)
    }

    /** What a woven piece adds to its own worth: the working and the charge in it. */
    fun enchantValue(item: Item): Int = item.enchantments.sumOf { instance ->
        (instance.magnitude * 3 + instance.maxCharge / 4).coerceAtLeast(5)
    }

    // ------------------------------------------------------- constant effects

    /**
     * What the worn pieces hold as constant weaves, by attribute. Read where
     * the wearer's numbers matter — never a firing loop, never a stack.
     */
    fun constantBonuses(items: List<Item>): Map<Attr, Int> {
        val bonuses = mutableMapOf<Attr, Int>()
        items.forEach { item ->
            item.enchantments.forEach { instance ->
                val def = instance.def ?: return@forEach
                if (def.activation != EnchantmentActivation.CONSTANT) return@forEach
                if (def.target.isNotBlank()) {
                    Attr.entries.firstOrNull { it.name == def.target }?.let { attr ->
                        bonuses[attr] = (bonuses[attr] ?: 0) + instance.magnitude
                    }
                }
            }
        }
        return bonuses
    }

    /** A creature takes up its worn constants once, at its making. */
    fun applyConstantTo(entity: Entity) {
        val stats = entity.stats ?: return
        constantBonuses(entity.equipment?.items() ?: emptyList()).forEach { (attr, bonus) ->
            stats.adjust(attr, bonus)
        }
    }

    // -------------------------------------------------------------- recharging

    /** The piece's emptiest working: the one a recharge fills first. */
    private fun chargeableInstance(item: Item): ItemEnchantment? =
        item.enchantments
            .filter { it.maxCharge > 0 && it.currentCharge < it.maxCharge }
            .maxByOrNull { it.maxCharge - it.currentCharge }

    /**
     * The quote: charge restorable and magicka it would cost, computed without
     * touching the piece. The UI shows this before the delver commits.
     */
    fun rechargeQuote(item: Item, wielderMagicka: Int, requested: Int): Pair<Int, Int> {
        val instance = chargeableInstance(item) ?: return Pair(0, 0)
        val efficiency = EnchantRules.qualityCapacityMult(item.quality) *
            EnchantRules.arcaneCapacityMult(item.material)
        val wanted = requested.coerceAtLeast(0)
            .coerceAtMost(instance.maxCharge - instance.currentCharge)
        if (wanted <= 0) return Pair(0, 0)
        // The will is spent one-for-one by the base rate; the piece's make and
        // temper decide how much charge each point of will awakens in it.
        val will = (wielderMagicka / EnchantRules.MAGICKA_PER_CHARGE).toInt().coerceAtLeast(0)
        val charge = (will * efficiency).roundToInt().coerceAtMost(wanted)
        if (charge <= 0) return Pair(0, 0)
        val magickaCost = (charge / efficiency).roundToInt().coerceAtLeast(1)
            .coerceAtMost(wielderMagicka)
        return Pair(charge, magickaCost)
    }

    /**
     * The one recharge operation: the wielder's will flows into the piece.
     * Returns the charge restored and the magicka actually spent — the caller
     * pays from its own pool. Efficiency answers to the piece alone; a future
     * school, skill, or artifact modifier joins here, not in the UI.
     */
    fun recharge(item: Item, wielderMagicka: Int, requested: Int): Pair<Int, Int> {
        val instance = chargeableInstance(item) ?: return Pair(0, 0)
        val (charge, cost) = rechargeQuote(item, wielderMagicka, requested)
        if (charge > 0) {
            instance.currentCharge = (instance.currentCharge + charge).coerceAtMost(instance.maxCharge)
        }
        return Pair(charge, cost)
    }

    /** The delver pays from their own well, by the same arithmetic as any soul. */
    fun recharge(engine: GameEngine, item: Item, requested: Int): Pair<Int, Int> {
        val (charge, spent) = recharge(item, engine.magicka, requested)
        if (spent > 0) engine.magicka = (engine.magicka - spent).coerceAtLeast(0)
        return Pair(charge, spent)
    }

    /** A creature pays from its own well: the same door, never a special path. */
    fun recharge(entity: Entity, item: Item, requested: Int): Pair<Int, Int> {
        val (charge, spent) = recharge(item, entity.magicka, requested)
        if (spent > 0) entity.magicka = (entity.magicka - spent).coerceAtLeast(0)
        return Pair(charge, spent)
    }

    // ---------------------------------------------------------------- triggers

    /**
     * The one trigger door. Combat and gameplay name the activation and hand
     * in the context; the enchantment system answers: charge checked, charge
     * paid, the existing resolver does the magic. Works for the delver and
     * every soul of the province alike.
     */
    fun triggerEnchantments(
        engine: GameEngine,
        item: Item,
        activation: EnchantmentActivation,
        wielder: Any,
        byPlayer: Boolean,
        forcedTarget: Any? = null
    ) {
        item.enchantments.forEach { instance ->
            val def = instance.def ?: return@forEach
            if (def.activation != activation) return@forEach
            if (def.chargeCost > instance.currentCharge) {
                if (byPlayer && instance.maxCharge > 0) {
                    engine.emit(GameEvent.EnchantmentDry(itemLabel(engine, item), def.name))
                }
                return@forEach
            }
            if (def.chargeCost > 0) {
                instance.currentCharge -= def.chargeCost
            }
            val effect = effectFor(def, instance)
            SpellCasting.resolveEffect(
                engine, effect,
                casterName = if (byPlayer) "you" else wielderName(wielder),
                byPlayer = byPlayer,
                caster = wielder,
                forcedTarget = forcedTarget
            )
            engine.emit(GameEvent.EnchantmentFired(itemLabel(engine, item), def.name, byPlayer))
            if (def.chargeCost > 0 && instance.currentCharge == 0) {
                engine.emit(GameEvent.EnchantmentSpent(itemLabel(engine, item)))
            }
        }
    }

    /** Every defensive weave the delver wears answers at once: board, mail, and rings. */
    fun triggerDefensive(engine: GameEngine, activation: EnchantmentActivation, attacker: Any) {
        val selfTarget: Any? = if (activation == EnchantmentActivation.WHEN_DAMAGED) engine else attacker
        engine.equipment.items().forEach { item ->
            triggerEnchantments(engine, item, activation, engine, byPlayer = true, forcedTarget = selfTarget)
        }
    }

    /** A defensive weave worn by a creature of the province. */
    fun triggerDefensive(engine: GameEngine, entity: Entity, activation: EnchantmentActivation) {
        val items = entity.equipment?.items() ?: return
        items.forEach { item ->
            triggerEnchantments(engine, item, activation, entity, byPlayer = false, forcedTarget = entity)
        }
    }

    /** The first ON_USE or MANUAL working the piece holds, for the use hand. */
    fun manualInstance(item: Item): ItemEnchantment? = item.enchantments.firstOrNull { instance ->
        val activation = instance.def?.activation
        activation == EnchantmentActivation.ON_USE || activation == EnchantmentActivation.MANUAL
    }

    // ------------------------------------------------------------------ naming

    fun itemLabel(engine: GameEngine, item: Item): String {
        val base = engine.magicItemName(item) ?: engine.styleRoster.nameFor(item)
        return base
    }

    /** The wielder's name in the log: the delver speaks as "you". */
    private fun wielderName(wielder: Any): String = when (wielder) {
        is GameEngine -> "you"
        is Entity -> wielder.name.ifBlank { "something" }
        else -> "something"
    }
}
