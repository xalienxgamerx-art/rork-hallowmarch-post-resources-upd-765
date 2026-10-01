package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.WorldGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The enchanted-item foundation: enchantments are definitions, the weave on a
 * piece is that piece's alone, charge is spent by the one trigger door and the
 * one resolver, recharging drinks the wielder's own will, constants ride equip
 * and unequip without loops, and every mutable spark persists on the item's
 * own save line.
 */
class EnchantTest {

    private val world = WorldGenerator.generate(20260924L)

    // ------------------------------------------------------- definition / instance

    @Test
    fun aDefinitionNamesTheWorkingAnInstanceBelongsToThePiece() {
        val def = EnchantRegistry.get("fire_damage_weapon")
        assertNotNull(def)
        val one = Enchanting.enchantItem(Item(ItemArchetype.ARMING_SWORD, Material.STEEL, Quality.FINE).marked(), def!!)
        val two = Enchanting.enchantItem(Item(ItemArchetype.ARMING_SWORD, Material.STEEL, Quality.FINE).marked(), def)
        assertEquals("same definition, same shape", one.enchantments[0].defId, two.enchantments[0].defId)
        one.enchantments[0].currentCharge -= 6
        assertEquals("one blade's spark is its own", two.enchantments[0].maxCharge, two.enchantments[0].currentCharge)
    }

    @Test
    fun qualityAndMaterialTemperTheWeave() {
        val def = EnchantRegistry.get("fire_damage_weapon")!!
        val honest = Enchanting.capacityFor(Item(ItemArchetype.BLADE, Material.IRON, Quality.HONEST), def)
        val legend = Enchanting.capacityFor(Item(ItemArchetype.BLADE, Material.IRON, Quality.LEGENDARY), def)
        val arcane = Enchanting.capacityFor(Item(ItemArchetype.BLADE, Material.STAR_IRON, Quality.HONEST), def)
        assertTrue("legend holds more", legend > honest)
        assertTrue("the arcane three hold more", arcane > honest)
        assertTrue(
            "legend works harder",
            Enchanting.magnitudeFor(Item(ItemArchetype.BLADE, Material.IRON, Quality.LEGENDARY), def) >
                Enchanting.magnitudeFor(Item(ItemArchetype.BLADE, Material.IRON, Quality.HONEST), def)
        )
    }

    @Test
    fun aWeaveFitsOnlyWhatItIsWovenFor() {
        val fire = EnchantRegistry.get("fire_damage_weapon")!!
        val might = EnchantRegistry.get("might_ring")!!
        assertFalse("fire harm is not a ring's working", Enchanting.canEnchant(Item(ItemArchetype.GEM_RING, Material.GOLD), fire))
        assertTrue("might is a ring's working", Enchanting.canEnchant(Item(ItemArchetype.GEM_RING, Material.GOLD), might))
        assertTrue("fire harm is an arm's working", Enchanting.canEnchant(Item(ItemArchetype.BLADE, Material.IRON), fire))
        // enchanting a wrong-shaped piece leaves it untouched
        val ring = Item(ItemArchetype.GEM_RING, Material.GOLD).marked()
        assertEquals(ring, Enchanting.enchantItem(ring, fire))
    }

    // ------------------------------------------------------------------ triggers

    @Test
    fun anOnHitWeaveSpendsItsOwnChargeAndStrikesThroughTheOneResolver() {
        val engine = GameEngine(world, null, null)
        val blade = Enchanting.enchantItem(
            Item(ItemArchetype.ARMING_SWORD, Material.STEEL, Quality.FINE).marked(),
            EnchantRegistry.get("fire_damage_weapon")!!
        )
        val foe = Entity(3f, 3f, Sprites.HOUND, EntityKind.ENEMY, 1f, name = "foe", hp = 50, maxHp = 50)
        engine.map.entities += foe
        val chargeBefore = blade.enchantments[0].currentCharge
        Enchanting.triggerEnchantments(engine, blade, EnchantmentActivation.ON_HIT, engine, byPlayer = true, forcedTarget = foe)
        assertEquals(chargeBefore - 6, blade.enchantments[0].currentCharge)
        assertTrue("the weave struck through the resolver", foe.hp < 50)
    }

    @Test
    fun aDryWeaveNeitherFiresNorDestroysThePiece() {
        val engine = GameEngine(world, null, null)
        val blade = Enchanting.enchantItem(
            Item(ItemArchetype.ARMING_SWORD, Material.STEEL, Quality.FINE).marked(),
            EnchantRegistry.get("fire_damage_weapon")!!
        )
        blade.enchantments[0].currentCharge = 0
        val foe = Entity(3f, 3f, Sprites.HOUND, EntityKind.ENEMY, 1f, name = "foe", hp = 50, maxHp = 50)
        Enchanting.triggerEnchantments(engine, blade, EnchantmentActivation.ON_HIT, engine, byPlayer = true, forcedTarget = foe)
        assertEquals("no charge, no strike", 50, foe.hp)
        assertEquals(0, blade.enchantments[0].currentCharge)
        assertTrue("an empty piece stays enchanted", Enchanting.isEnchanted(blade))
    }

    @Test
    fun aTriggerAnswersOnlyItsOwnActivation() {
        val engine = GameEngine(world, null, null)
        val blade = Enchanting.enchantItem(
            Item(ItemArchetype.ARMING_SWORD, Material.STEEL, Quality.FINE).marked(),
            EnchantRegistry.get("fire_damage_weapon")!!
        )
        val before = blade.enchantments[0].currentCharge
        Enchanting.triggerEnchantments(engine, blade, EnchantmentActivation.WHEN_KILLING, engine, byPlayer = true)
        Enchanting.triggerEnchantments(engine, blade, EnchantmentActivation.WHEN_DAMAGED, engine, byPlayer = true)
        assertEquals("the wrong ask is refused", before, blade.enchantments[0].currentCharge)
    }

    @Test
    fun aCallByHandPaysChargeAndWorks() {
        val engine = GameEngine(world, null, null)
        val amulet = Enchanting.enchantItem(
            Item(ItemArchetype.AMULET, Material.SILVER, Quality.FINE).marked(),
            EnchantRegistry.get("mending_amulet")!!
        )
        val working = Enchanting.manualInstance(amulet)
        assertNotNull("an on-use weave is callable", working)
        val before = working!!.currentCharge
        assertTrue(engine.useEnchantedItem(amulet))
        assertEquals(before - 10, working.currentCharge)
        // a drained weave refuses the call
        working.currentCharge = 0
        assertFalse(engine.useEnchantedItem(amulet))
    }

    // ------------------------------------------------------------------ constants

    @Test
    fun aConstantWeaveRidesEquipAndUnequipWithoutStacking() {
        val engine = GameEngine(world, null, null)
        val ring = Enchanting.enchantItem(
            Item(ItemArchetype.GEM_RING, Material.GOLD, Quality.HONEST).marked(),
            EnchantRegistry.get("might_ring")!!
        )
        val base = engine.stats[Attr.MIGHT]
        engine.inventory.add(ring)
        engine.equipFromSatchel(ring)
        assertEquals("worn might answers", base + 5, engine.stats[Attr.MIGHT])
        engine.unequipFromEquipment(engine.equipment.slotOf(ring)!!)
        assertEquals("stripped might returns exactly", base, engine.stats[Attr.MIGHT])
        // and again: no stacking across wears
        engine.equipFromSatchel(ring)
        assertEquals(base + 5, engine.stats[Attr.MIGHT])
    }

    @Test
    fun theSavedSelfCarriesNoWornConstants() {
        val engine = GameEngine(world, null, null)
        val ring = Enchanting.enchantItem(
            Item(ItemArchetype.GEM_RING, Material.GOLD).marked(),
            EnchantRegistry.get("vigor_ring")!!
        )
        val base = engine.stats[Attr.VIGOR]
        engine.inventory.add(ring)
        engine.equipFromSatchel(ring)
        val saved = StatBlock.fromEncoded(engine.toSaveSlot().stats)!!
        assertEquals("the save holds the base self", base, saved[Attr.VIGOR])
    }

    @Test
    fun aCreatureTakesUpItsWornConstantsAtItsMaking() {
        val entity = Entity(0f, 0f, Sprites.HOUND, EntityKind.ENEMY, 1f, name = "wearer", stats = StatBlock.balanced())
        val ring = Enchanting.enchantItem(
            Item(ItemArchetype.GEM_RING, Material.GOLD).marked(),
            EnchantRegistry.get("might_ring")!!
        )
        entity.equipment = Equipment.empty()
        entity.equipment!!.equip(ring)
        val base = entity.stats!![Attr.MIGHT]
        Enchanting.applyConstantTo(entity)
        assertEquals(base + 5, entity.stats!![Attr.MIGHT])
    }

    // ------------------------------------------------------------------ recharging

    @Test
    fun aRechargePoursWielderWillIntoThePiece() {
        val engine = GameEngine(world, null, null)
        val blade = Enchanting.enchantItem(
            Item(ItemArchetype.ARMING_SWORD, Material.STEEL, Quality.HONEST).marked(),
            EnchantRegistry.get("fire_damage_weapon")!!
        )
        blade.enchantments[0].currentCharge = 0
        engine.magicka = 50
        val (charge, spent) = Enchanting.recharge(engine, blade, 100)
        assertEquals("the will pays one for one", 50, charge)
        assertEquals(50, spent)
        assertEquals("the well is emptied by what it gave", 0, engine.magicka)
        assertEquals(50, blade.enchantments[0].currentCharge)
    }

    @Test
    fun aFinerPieceDrinksMoreFromTheSameWill() {
        val engine = GameEngine(world, null, null)
        val blade = Enchanting.enchantItem(
            Item(ItemArchetype.ARMING_SWORD, Material.STEEL, Quality.FINE).marked(),
            EnchantRegistry.get("fire_damage_weapon")!!
        )
        blade.enchantments[0].currentCharge = 0
        engine.magicka = 50
        val (charge, spent) = Enchanting.recharge(engine, blade, 100)
        assertEquals("fine work wakes more charge per point of will", 65, charge)
        assertEquals("but never more will than it spends", 50, spent)
    }

    @Test
    fun aRechargeFillsNoFurtherThanThePieceHolds() {
        val engine = GameEngine(world, null, null)
        val blade = Enchanting.enchantItem(
            Item(ItemArchetype.ARMING_SWORD, Material.STEEL, Quality.HONEST).marked(),
            EnchantRegistry.get("fire_damage_weapon")!!
        )
        blade.enchantments[0].currentCharge = blade.enchantments[0].maxCharge - 5
        val (charge, spent) = Enchanting.recharge(engine, blade, 100)
        assertEquals(5, charge)
        assertEquals(5, spent)
        assertEquals(blade.enchantments[0].maxCharge, blade.enchantments[0].currentCharge)
    }

    @Test
    fun aRechargeWithoutWillGivesNothing() {
        val engine = GameEngine(world, null, null)
        val blade = Enchanting.enchantItem(
            Item(ItemArchetype.ARMING_SWORD, Material.STEEL, Quality.FINE).marked(),
            EnchantRegistry.get("fire_damage_weapon")!!
        )
        blade.enchantments[0].currentCharge = 0
        engine.magicka = 0
        val (charge, spent) = Enchanting.recharge(engine, blade, 100)
        assertEquals(0, charge)
        assertEquals(0, spent)
        assertEquals(0, blade.enchantments[0].currentCharge)
    }

    @Test
    fun theQuotePromisesWithoutTouchingThePiece() {
        val blade = Enchanting.enchantItem(
            Item(ItemArchetype.ARMING_SWORD, Material.STEEL, Quality.HONEST).marked(),
            EnchantRegistry.get("fire_damage_weapon")!!
        )
        blade.enchantments[0].currentCharge = 20
        val first = Enchanting.rechargeQuote(blade, 40, 100)
        val second = Enchanting.rechargeQuote(blade, 40, 100)
        assertEquals(first, second)
        assertEquals("a quote spends nothing", 20, blade.enchantments[0].currentCharge)
    }

    // ------------------------------------------------------------------ save & load

    @Test
    fun anEnchantedPieceRidesItsSaveLineSparkAndAll() {
        val item = Item(ItemArchetype.ARMING_SWORD, Material.STEEL, Quality.FINE)
            .copy(enchantments = listOf(ItemEnchantment("fire_damage_weapon", 12, 100, 43, "mage-1")))
        val restored = Item.fromEncoded(item.encode())!!
        assertEquals(1, restored.enchantments.size)
        val weave = restored.enchantments[0]
        assertEquals("fire_damage_weapon", weave.defId)
        assertEquals(12, weave.magnitude)
        assertEquals(100, weave.maxCharge)
        assertEquals("the charge in it is the charge saved", 43, weave.currentCharge)
        assertEquals("mage-1", weave.creatorId)
        assertTrue(restored.enchanted)
    }

    @Test
    fun anUnwovenPieceLoadsAsItAlwaysDid() {
        val restored = Item.fromEncoded(Item(ItemArchetype.BLADE, Material.BOG_IRON, Quality.WORN).marked().encode())!!
        assertFalse(restored.enchanted)
        assertEquals(ItemArchetype.BLADE, restored.archetype)
        // a shield with its wright's fields still wakes whole
        val shield = Item.fromEncoded(Shields.roll(Random(7L), 3, 1, null).encode())!!
        assertFalse(shield.enchanted)
        assertNotNull(shield.construction)
    }

    @Test
    fun aPieceMayHoldSeveralWeaves() {
        val item = Item(ItemArchetype.RELIC, Material.STAR_IRON, Quality.LEGENDARY)
            .copy(
                enchantments = listOf(
                    ItemEnchantment("might_ring", 5, 0, 0),
                    ItemEnchantment("embers_armor", 8, 120, 43)
                )
            )
        val restored = Item.fromEncoded(item.encode())!!
        assertEquals(2, restored.enchantments.size)
        assertEquals("might_ring", restored.enchantments[0].defId)
        assertEquals(43, restored.enchantments[1].currentCharge)
    }

    // ------------------------------------------------------------------ loot & worth

    @Test
    fun foundWeavesAreTheSeedSownTwiceTheSame() {
        val base = { Item(ItemArchetype.ARMING_SWORD, Material.STEEL, Quality.FINE).marked() }
        val one = Enchanting.maybeEnchant(Random(4242L), base())
        val two = Enchanting.maybeEnchant(Random(4242L), base())
        assertEquals("same hand, same weave", one.enchantments, two.enchantments)
    }

    @Test
    fun ammunitionIsNeverWoven() {
        repeat(60) { attempt ->
            val shot = Item(ItemArchetype.ARROW, Material.IRON, count = 12).marked()
            assertFalse("attempt $attempt wove a shaft", Enchanting.maybeEnchant(Random(attempt.toLong()), shot).enchanted)
        }
    }

    @Test
    fun aWovenPieceIsWorthMoreThanItsMetal() {
        val plain = Item(ItemArchetype.ARMING_SWORD, Material.STEEL, Quality.FINE).marked()
        val woven = Enchanting.enchantItem(plain, EnchantRegistry.get("fire_damage_weapon")!!)
        assertTrue(woven.value() > plain.value())
    }

    @Test
    fun aGraveKitIsTheSameWeaveEveryTimeTheSeedIsAsked() {
        val roster = ClassRoster(world)
        // the smith's mark is a global counter: wind it back so the two graves
        // are cut by the same hand
        ItemUids.reset(9000)
        val one = Loot.kit(Random(99L), roster.classes.first(), 4, 1)
        ItemUids.reset(9000)
        val two = Loot.kit(Random(99L), roster.classes.first(), 4, 1)
        assertEquals(one, two)
    }

    // ------------------------------------------------------------------ lore

    @Test
    fun theWorkingSpeaksInPlainWords() {
        val def = EnchantRegistry.get("fire_damage_weapon")!!
        val instance = ItemEnchantment(def.id, 12, 72, 72)
        val line = Enchanting.describeEffect(def, instance)
        assertTrue(line.contains("12"))
        assertTrue(line.contains("fire"))
        val might = EnchantRegistry.get("might_ring")!!
        assertTrue(
            "a constant names what it lifts, while worn",
            Enchanting.describeEffect(might, ItemEnchantment(might.id, 5, 0, 0)).contains("while worn")
        )
    }
}
