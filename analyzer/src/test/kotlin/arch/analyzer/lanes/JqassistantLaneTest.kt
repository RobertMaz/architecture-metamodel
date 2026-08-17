package arch.analyzer.lanes

import arch.analyzer.core.FactType
import arch.analyzer.core.RepoInput
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JqassistantLaneTest {

    @Test
    fun `парсер строк фактов`() {
        val facts = parseFactLines(
            listOf(
                "ENDPOINT|method=GET|path=/vets|jar!demo.VetResource#list|0.75",
                "SUBSCRIBE|channel=order.created|group=cg|jar!demo.L#on|0.75",
                "мусорная строка без разделителей",
                "",
            ),
            "jqassistant",
        )
        assertEquals(2, facts.size)
        val ep = facts.single { it.type == FactType.ENDPOINT }
        assertEquals("GET", ep.attrs["method"])
        assertEquals("/vets", ep.attrs["path"])
        assertEquals("jar!demo.VetResource#list", ep.source)
        assertEquals(0.75, ep.confidence)
    }

    @Test
    fun `полка запускает адаптер и читает факты`() {
        val tools = Files.createTempDirectory("jqa")
        val script = tools.resolve("extract.sh")
        script.writeText("#!/bin/sh\necho 'ENDPOINT|method=GET|path=/from-jqa|jar!X#m|0.75'\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))

        val repo = Files.createTempDirectory("repo")
        val jar = repo.resolve("app.jar")
        jar.writeText("")

        val lane = JqassistantLane(adapter = script)
        val input = RepoInput("x", repo, jar = jar)
        assertTrue(lane.applicable(input))
        val facts = lane.extract(input)
        assertEquals("/from-jqa", facts.single().attrs["path"])
    }

    @Test
    fun `без адаптера или jar полка не применима`() {
        val repo = Files.createTempDirectory("repo")
        repo.resolve("x").createDirectories()
        val lane = JqassistantLane(adapter = repo.resolve("нет-такого.sh"))
        assertTrue(!lane.applicable(RepoInput("x", repo, jar = null)))
    }
}
