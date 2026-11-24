package dev.lattenero.bot.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * /fleet_<verbo>'s pure half: the two command forms (the owner's `/fleet
 * ram-clean args` and the menu's `/fleet_ram_clean args`), the deploy routing
 * (those never ride the runner), the discovery parse (`fleet list --json` —
 * one row per verb, tolerant of garbage), the owner gate (fail-closed) and
 * the words the phone reads.
 */
class FleetCommandsTest {

    // ---- the two forms -------------------------------------------------------

    @Test
    fun theSpaceFormAnswersTheVerbsName() {
        assertEquals("ram-clean", FleetCommands.callOf("/fleet ram-clean").verb)
        assertEquals("brain", FleetCommands.callOf("/fleet brain").verb)
        assertEquals("audit-fp", FleetCommands.callOf("/fleet audit-fp").verb)
        assertEquals(listOf("hola", "--k", "9"), FleetCommands.callOf("/fleet brain hola --k 9").argv)
    }

    @Test
    fun theTapFormAnswersTheSameVerbs() {
        assertEquals("ram-clean", FleetCommands.callOf("/fleet_ram_clean").verb)
        assertEquals("fleet-errors", FleetCommands.callOf("/fleet_fleet_errors").verb)
        assertEquals(listOf("--since", "2h"), FleetCommands.callOf("/fleet_fleet_errors --since 2h").argv)
    }

    @Test
    fun bothFormsAgreeVerbByName() {
        for (verb in listOf("ram-clean", "brain", "deploy-all", "worktree-sweep")) {
            val tapped = FleetCommands.callOf("/fleet_" + verb.replace('-', '_'))
            val spaced = FleetCommands.callOf("/fleet " + verb)
            assertEquals(verb, tapped.verb, tapped.toString())
            assertEquals(verb, spaced.verb, spaced.toString())
        }
    }

    @Test
    fun aBareFleetAsksForTheList() {
        assertNull(FleetCommands.callOf("/fleet").verb)
        assertEquals("list", FleetCommands.callOf("/fleet list").verb)
        assertEquals(emptyList(), FleetCommands.callOf("/fleet list").argv)
    }

    @Test
    fun aWordWithoutAVerbIsNotAFleetCommand() {
        assertNull(FleetCommands.callOf("/ram_clean").verb)
        assertNull(FleetCommands.callOf("fleet ram-clean").verb)
        assertNull(FleetCommands.callOf("/fleet_").verb)
        assertTrue(!FleetCommands.isFleet("/ram_clean"))
    }

    // ---- the deploy routing (the launchd lane) -------------------------------

    @Test
    fun theDeployVerbsRouteToTheirFlowLegInBothForms() {
        assertEquals("all", FleetCommands.deployTarget("/fleet_deploy_all"))
        assertEquals("brain", FleetCommands.deployTarget("/fleet deploy-brain"))
        assertEquals("bot", FleetCommands.deployTarget("/fleet_deploy_bot"))
        assertEquals("fork", FleetCommands.deployTarget("/fleet deploy-fork"))
        assertEquals("whatsapp", FleetCommands.deployTarget("/fleet_deploy_whatsapp"))
    }

    @Test
    fun theDiscoveredDeployTwinsAnswerTheSameLeg() {
        assertEquals("bot", FleetCommands.deployTarget("/fleet_zcode_bot_kotlin_deploy"))
        assertEquals("all", FleetCommands.deployTarget("/fleet zcode-deploy-all"))
    }

    @Test
    fun everyOtherVerbHasNoDeployLeg() {
        assertNull(FleetCommands.deployTarget("/fleet_brain"))
        assertNull(FleetCommands.deployTarget("/fleet healthcheck"))
        assertNull(FleetCommands.deployTarget("/fleet_list"))
    }

    // ---- the discovery parse -------------------------------------------------

    private val json = """
        [
          {"name":"brain","kind":"script","script":"/hub/scripts/brain.nu",
           "arguments":"[question] <string> · --k <int>","description":"the brain at terminal speed"},
          {"name":"deploy-bot","kind":"alias","script":"/hub/scripts/zcode-bot-kotlin-deploy.nu",
           "arguments":"-","description":"the mirror bot only"}
        ]
    """.trimIndent()

    @Test
    fun theRowsComeBackInTheHubsOrder() {
        val verbs = FleetCommands.parse(json)
        assertEquals(listOf("brain", "deploy-bot"), verbs.map { it.name })
        assertEquals("alias", verbs[1].kind)
        assertEquals("/hub/scripts/zcode-bot-kotlin-deploy.nu", verbs[1].script)
        assertTrue("[question] <string>" in verbs[0].arguments)
    }

    @Test
    fun garbageAnswersAnEmptyTableNeverHalfOfIt() {
        assertEquals(emptyList(), FleetCommands.parse("no json at all"))
        assertEquals(emptyList(), FleetCommands.parse(""))
        // a row missing its script is not a row: the phone could not run it
        assertEquals(emptyList(), FleetCommands.parse("""[{"name":"brain"}]"""))
    }

    // ---- the gate (the second lock) ------------------------------------------

    @Test
    fun onlyTheOwnerRunsCommands() {
        assertNull(FleetCommands.gate(fromId = 42L, owner = 42L))
        assertEquals(FleetCommands.NOT_OWNER, FleetCommands.gate(fromId = 7L, owner = 42L))
        assertEquals(FleetCommands.NOT_OWNER, FleetCommands.gate(fromId = null, owner = 42L))
        assertEquals(FleetCommands.NOT_OWNER, FleetCommands.gate(fromId = 42L, owner = null))
    }

    // ---- the words -----------------------------------------------------------

    @Test
    fun theListSpeaksEveryVerbWithItsArguments() {
        val text = FleetCommands.listText(FleetCommands.parse(json))
        assertTrue("/fleet_brain" in text, text)
        assertTrue("[question] <string>" in text, text)
        assertTrue("/fleet_deploy_bot" in text, text)
        assertTrue(text.contains("<b>2 comandos nu</b>"), text)
    }

    @Test
    fun anEmptyDiscoverySaysItCouldNotReadTheTable() {
        assertEquals(FleetCommands.DISCOVERY_FAILED, FleetCommands.listText(emptyList()))
    }

    @Test
    fun anUnknownVerbNamesItselfAndSuggestsNearMatches() {
        val verbs = FleetCommands.parse(json)
        val text = FleetCommands.unknown("brains", verbs)
        assertTrue("brains" in text, text)
        assertTrue(text.contains("/fleet_list"), text)
        assertTrue(text.contains("brain"), text)
    }

    @Test
    fun theTimeoutNamesTheVerbAndTheBudget() {
        val text = FleetCommands.timeout("ram-clean", 90)
        assertTrue("/fleet_ram_clean" in text, text)
        assertTrue("90" in text, text)
    }

    @Test
    fun theLogsNeverCollideBetweenVerbs() {
        assertEquals("fleet-ram-clean.log", FleetCommands.logFileFor("ram-clean"))
        assertTrue(FleetCommands.DISCOVERY_LOG != FleetCommands.logFileFor("list"))
    }
}
