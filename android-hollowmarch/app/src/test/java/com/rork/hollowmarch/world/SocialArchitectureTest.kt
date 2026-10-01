package com.rork.hollowmarch.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The social architecture's founding promise: only a realm is sovereign,
 * organizations operate in society, houses are lineage, and outlaw groups
 * live outside political authority — all of it deterministic per seed.
 */
class SocialArchitectureTest {

    private val world by lazy { WorldGenerator.generate(424242L) }

    // ------------------------------------------------------------- sovereignty

    @Test
    fun onlyRealmsHoldGround() {
        world.sites.filter { !it.ruined }.forEach { site ->
            val realm = world.realmOf(site)
            if (site.sovereignRealmId != null) {
                assertNotNull("a claimed site has a realm (${site.name})", realm)
                assertFalse("sovereigns are not extinct", realm!!.extinct)
            } else {
                assertEquals(
                    "camps, vaults and barrows keep no sovereign (${site.name})",
                    null,
                    realm
                )
            }
        }
    }

    @Test
    fun organizationsNeverRule() {
        val orgIds = world.organizations.map { it.id }.toSet()
        world.sites.forEach { site ->
            assertTrue(
                "no organization holds ${site.name}",
                site.sovereignRealmId == null || site.sovereignRealmId !in orgIds
            )
        }
    }

    @Test
    fun groupsNeverRule() {
        val groupIds = world.groups.map { it.id }.toSet()
        world.sites.forEach { site ->
            assertTrue(
                "no group holds ${site.name}",
                site.sovereignRealmId == null || site.sovereignRealmId !in groupIds
            )
        }
    }

    // ------------------------------------------------------- realm machinery

    @Test
    fun realmsKeepRulersCapitalsAndGovernments() {
        world.realms.filter { !it.extinct }.forEach { realm ->
            realm.rulerFigureId?.let { fid ->
                assertNotNull("a ruler stands behind ${realm.name}", world.figure(fid))
            }
            assertNotNull("a government administers ${realm.name}", world.government(realm.governmentId))
            realm.capitalSiteId?.let { cid ->
                val capital = world.site(cid)
                assertTrue("the realm's seat is a settlement", capital.isSettlement || capital.ruined)
                assertEquals("the seat answers to its realm", realm.id, capital.sovereignRealmId)
            }
        }
    }

    @Test
    fun everyGovernmentBelongsToARealm() {
        world.governments.forEach { government ->
            val realm = world.realm(government.realmId)
            assertNotNull("governments administer real realms", realm)
            assertEquals("the realm acknowledges its government", government.id, realm?.governmentId)
        }
    }

    @Test
    fun realmNamesMatchTheirTypes() {
        world.realms.forEach { realm ->
            val ok = when (realm.type) {
                RealmType.TRIBAL_REALM -> realm.name.startsWith("Tribes of")
                RealmType.CONFEDERATION -> realm.name.startsWith("Concord of")
                else -> realm.name.contains(realm.type.label, ignoreCase = true)
            }
            assertTrue("the realm's name carries its kind (${realm.name})", ok)
        }
    }

    @Test
    fun realmsHoldTerritoryInDepth() {
        val holdings = world.realms.filter { !it.extinct }.map { realm ->
            realm.id to world.sites.count { it.sovereignRealmId == realm.id && !it.ruined }
        }
        assertTrue(
            "realms hold more than a lone seat: $holdings",
            holdings.any { it.second >= 2 }
        )
    }

    // ---------------------------------------------------------- organizations

    @Test
    fun guildHallsBelongToOrganizations() {
        world.sites.flatMap { it.structures }.filter { it.kind == StructureKind.GUILD }.forEach { hall ->
            hall.organizationId?.let { orgId ->
                assertNotNull("the hall's organization exists", world.organization(orgId))
            }
        }
        world.organizations.filter { it.kind == OrganizationKind.GUILD }.forEach { org ->
            org.headquartersSiteId?.let { hid ->
                assertNotNull("the guild's headquarters is a real site", world.siteOrNull(hid))
            }
        }
    }

    // ----------------------------------------------------------------- houses

    @Test
    fun housesServeRealmsAndFeudWithHouses() {
        world.houses.forEach { house ->
            house.patronRealmId?.let { rid ->
                assertNotNull("a patron house's realm exists", world.realm(rid))
            }
        }
        world.houses.forEach { house ->
            house.rivalHouseIds.forEach { rivalId ->
                assertTrue(
                    "a feud names a real house and runs both ways",
                    world.house(rivalId)?.rivalHouseIds?.contains(house.id) == true
                )
            }
        }
    }

    // ------------------------------------------------------------------ groups

    @Test
    fun warbandsBaseAtCamps() {
        world.groups.filter { it.kind == GroupKind.WARBAND && !it.extinct }.forEach { band ->
            band.baseSiteId?.let { bid ->
                val base = world.siteOrNull(bid)
                if (base != null) {
                    assertEquals("a warband's base is a camp", SiteKind.CAMP, base.kind)
                }
            }
        }
    }

    // ----------------------------------------------------------- coexistence

    @Test
    fun settlementsLayerSovereigntyAndSociety() {
        val great = world.sites
            .filter { !it.ruined && it.isSettlement && it.population > 0 }
            .maxByOrNull { it.population }!!
        val sovereign = world.realmOf(great)
        assertNotNull("the province's greatest settlement has a sovereign", sovereign)
        // Society stands beside sovereignty: organizations, houses and groups
        // exist in the province without ruling anything.
        assertTrue("organizations operate in the province", world.organizations.isNotEmpty())
        assertTrue("houses keep their names in the province", world.houses.isNotEmpty())
        assertTrue("groups live outside the law", world.groups.isNotEmpty())
        // And the great settlement's influence ledger names its sovereign.
        val realmInfluence = great.influences.firstOrNull { it.kind == InfluenceKind.REALM }
        assertEquals(sovereign?.id, realmInfluence?.actorId)
    }

    @Test
    fun influenceIsTypedAndSumsCoherently() {
        world.sites.filter { !it.ruined && it.isSettlement && it.population > 0 }.forEach { site ->
            val influences = site.influences
            assertTrue("influence is recorded (${site.name})", influences.isNotEmpty())
            assertTrue(
                "shares are coherent (${site.name}): ${influences.sumOf { it.share }}",
                influences.sumOf { it.share } in 90..110
            )
            influences.forEach { influence ->
                when (influence.kind) {
                    InfluenceKind.REALM -> assertNotNull(
                        "realm influence names its realm (${site.name})",
                        influence.actorId
                    )
                    InfluenceKind.HOUSE -> influence.actorId?.let {
                        assertNotNull("house influence names a real house", world.house(it))
                    }
                    InfluenceKind.GUILD -> influence.actorId?.let {
                        assertNotNull("guild influence names a real organization", world.organization(it))
                    }
                    InfluenceKind.TEMPLE, InfluenceKind.GARRISON, InfluenceKind.TOWNSFOLK -> {}
                }
            }
        }
    }

    // --------------------------------------------------------------- conflicts

    @Test
    fun conflictsKeepTheirOwnRules() {
        // Realm wars are fought realm against realm, with battles.
        world.conflictsOf(ConflictKind.REALM_WAR).forEach { war ->
            assertNotNull("realm wars attack realms", world.realm(war.attackerId))
            assertNotNull("realm wars defend realms", world.realm(war.defenderId))
        }
        // Rebellions are a rebel movement against a realm.
        world.conflictsOf(ConflictKind.REBELLION).forEach { rising ->
            assertNotNull(
                "a rising's rebels exist (group or consolidated realm)",
                world.group(rising.attackerId) ?: world.realm(rising.attackerId)
            )
            assertNotNull("a rising answers to a realm", world.realm(rising.defenderId))
        }
        // Raids are bands against realms — pillage, never conquest.
        world.conflictsOf(ConflictKind.RAID).forEach { raid ->
            assertNotNull("raiders exist", world.group(raid.attackerId))
            assertNotNull("raids strike realms", world.realm(raid.defenderId))
        }
        // Guild conflicts are organization against organization.
        world.conflictsOf(ConflictKind.GUILD_CONFLICT).forEach { spat ->
            assertNotNull("a guild feud attacks organizations", world.organization(spat.attackerId))
            assertNotNull("a guild feud answers organizations", world.organization(spat.defenderId))
        }
        // House feuds are house against house.
        world.conflictsOf(ConflictKind.HOUSE_FEUD).forEach { feud ->
            assertNotNull("a feud attacks houses", world.house(feud.attackerId))
            assertNotNull("a feud answers houses", world.house(feud.defenderId))
        }
    }

    // ------------------------------------------------------------ determinism

    @Test
    fun sameSeedSameSocialOrder() {
        val a = WorldGenerator.generate(909090L)
        val b = WorldGenerator.generate(909090L)
        assertEquals(a.realms, b.realms)
        assertEquals(a.organizations, b.organizations)
        assertEquals(a.groups, b.groups)
        assertEquals(a.governments, b.governments)
        assertEquals(a.houses, b.houses)
        assertEquals(a.claims, b.claims)
        assertEquals(
            a.sites.map { it.influences },
            b.sites.map { it.influences }
        )
    }
}
