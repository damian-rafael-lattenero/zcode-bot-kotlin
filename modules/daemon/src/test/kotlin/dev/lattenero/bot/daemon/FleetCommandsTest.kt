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

    // ---- the phone dialect (the bare-form defaults, the owner's 24/11) --------

    @Test
    fun aBareRamCleanTapIsTheCleanNotThePlan() {
        // the menu tap cannot carry args: the bare form IS the clean
        assertEquals(listOf("--clean"), FleetCommands.callOf("/fleet_ram_clean").argv)
        // the space form answers the same verb with the same words
        assertEquals(listOf("--clean"), FleetCommands.callOf("/fleet ram-clean").argv)
    }

    @Test
    fun theSpelledWordsKeepTheirMeaning() {
        // «report» = the read-only leg, exactly as /ram_clean spells it: the
        // script has no positional, so it maps to the bare argv (the plan)
        assertEquals(emptyList(), FleetCommands.callOf("/fleet_ram_clean report").argv)
        assertEquals(emptyList(), FleetCommands.callOf("/fleet ram-clean report").argv)
        assertEquals(listOf("--clean"), FleetCommands.callOf("/fleet_ram_clean clean").argv)
    }

    @Test
    fun anExplicitFlagRidesVerbatim() {
        assertEquals(listOf("--clean"), FleetCommands.callOf("/fleet_ram_clean --clean").argv)
        // terminal-only flags stay reachable (read-only ones)
        assertEquals(listOf("--trend"), FleetCommands.callOf("/fleet_ram_clean --trend").argv)
    }

    @Test
    fun theDialectLeavesEveryOtherVerbAlone() {
        assertEquals(emptyList(), FleetCommands.callOf("/fleet_brain").argv)
        assertEquals(listOf("hola"), FleetCommands.callOf("/fleet_brain hola").argv)
        assertEquals(listOf("--since", "2h"), FleetCommands.callOf("/fleet_fleet_errors --since 2h").argv)
        assertEquals(emptyList(), FleetCommands.phoneArgv("audit-fp", emptyList()))
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
        assertTrue("/fleet_deploy_bot" in text, text)
        assertTrue(text.contains("<b>2 nu commands</b>"), text)
    }

    // THE POISON-BODY LAW (measured 24/11): the arguments carry `<string>`,
    // and with parse_mode=HTML Telegram read it as a broken tag — the whole
    // list was rejected 5 times and dropped, so /fleet_list answered nothing.
    @Test
    fun theDataIsHtmlEscapedAndTheMarkupSurvives() {
        val text = FleetCommands.listText(FleetCommands.parse(json))
        assertTrue("[question] &lt;string&gt;" in text, text)
        assertTrue("&lt;int&gt;" in text, text)
        assertTrue("&lt;path&gt;" !in text, "no path argument in this fixture")
        // the only raw angle brackets left are the two of <b>…</b>
        assertEquals(4, text.count { it == '<' } + text.count { it == '>' }, text)
        assertTrue(text.startsWith("🧰 <b>2 nu commands</b>"), text)
    }

    @Test
    fun anUnknownVerbIsEscapedBeforeItReachesTheParser() {
        val text = FleetCommands.unknown("<script>", FleetCommands.parse(json))
        assertTrue("&lt;script&gt;" in text, text)
        assertTrue("<script>" !in text, text)
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

    // ---- the usage guard -----------------------------------------------------

    @Test
    fun aVerbWithARequiredArgumentAndNoWordsAnswersItsUsage() {
        // a REQUIRED positional (rendered quoted) with no words = missing
        assertTrue(FleetCommands.missingRequired("\"repo\" <path>", emptyList()))
        assertTrue(!FleetCommands.missingRequired("\"repo\" <path>", listOf("/tmp")))
        // brain's question is OPTIONAL (bracketed) — nothing is missing
        assertTrue(!FleetCommands.missingRequired("[question] <string> · --k <int>", emptyList()))
        // no arguments at all: nothing to miss
        assertTrue(!FleetCommands.missingRequired("-", emptyList()))
    }

    @Test
    fun theUsageLineCarriesTheArgumentsAndTheSpaceFormHint() {
        val row = FleetCommands.parse(json).first { it.name == "brain" }
        val text = FleetCommands.usageOf(row)
        assertTrue("/fleet_brain" in text, text)
        assertTrue("&lt;string&gt;" in text, text)
        assertTrue("/fleet brain" in text, text)
    }
}
