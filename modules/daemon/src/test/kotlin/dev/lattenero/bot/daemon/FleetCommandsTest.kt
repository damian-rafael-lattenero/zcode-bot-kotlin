package dev.lattenero.bot.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * /fleet_<verbo>'s pure half: the snake_case ↔ kebab-case mapping, the deploy
 * routing (those never ride the runner), the discovery parse (`fleet list
 * --json` — one row per verb, tolerant of garbage), the owner gate (fail-
 * closed) and the words the phone reads.
 */
class FleetCommandsTest {

    // ---- the mapping ---------------------------------------------------------

    @Test
    fun snakeCaseCommandsMapToKebabVerbs() {
        assertEquals("ram-clean", FleetCommands.verbOf("/fleet_ram_clean"))
        assertEquals("deploy-all", FleetCommands.verbOf("/fleet_deploy_all"))
        assertEquals("fleet-errors", FleetCommands.verbOf("/fleet_fleet_errors"))
        assertEquals("brain", FleetCommands.verbOf("/fleet_brain"))
        assertEquals("audit-fp", FleetCommands.verbOf("/fleet_audit_fp"))
    }

    @Test
    fun aWordWithoutAVerbIsNotAFleetCommand() {
        assertNull(FleetCommands.verbOf("/fleet"))
        assertNull(FleetCommands.verbOf("/fleet_"))
        assertNull(FleetCommands.verbOf("/ram_clean"))
        assertNull(FleetCommands.verbOf("fleet_ram_clean"))
    }

    // ---- the deploy routing (the launchd lane) --------------------------------

    @Test
    fun theDeployVerbsRouteToTheirFlowLeg() {
        assertEquals("all", FleetCommands.deployTarget("/fleet_deploy_all"))
        assertEquals("brain", FleetCommands.deployTarget("/fleet_deploy_brain"))
        assertEquals("bot", FleetCommands.deployTarget("/fleet_deploy_bot"))
        assertEquals("fork", FleetCommands.deployTarget("/fleet_deploy_fork"))
        assertEquals("whatsapp", FleetCommands.deployTarget("/fleet_deploy_whatsapp"))
    }

    @Test
    fun theDiscoveredDeployTwinsAnswerTheSameLeg() {
        assertEquals("bot", FleetCommands.deployTarget("/fleet_zcode_bot_kotlin_deploy"))
        assertEquals("all", FleetCommands.deployTarget("/fleet_zcode_deploy_all"))
    }

    @Test
    fun everyOtherVerbHasNoDeployLeg() {
        assertNull(FleetCommands.deployTarget("/fleet_brain"))
        assertNull(FleetCommands.deployTarget("/fleet_healthcheck"))
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
    fun theArgumentsAreTheOwnersOwnWords() {
        assertEquals(listOf("report"), FleetCommands.argvOf("/fleet_ram_clean report"))
        assertEquals(listOf("--k", "9", "hola"), FleetCommands.argvOf("/fleet_brain --k 9 hola"))
        assertEquals(emptyList(), FleetCommands.argvOf("/fleet_brain"))
    }

    @Test
    fun theLogsNeverCollideBetweenVerbs() {
        assertEquals("fleet-ram-clean.log", FleetCommands.logFileFor("ram-clean"))
        assertTrue(FleetCommands.DISCOVERY_LOG != FleetCommands.logFileFor("list"))
    }
}
