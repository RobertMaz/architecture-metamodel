package arch.analyzer.lanes

import arch.analyzer.core.FactType
import arch.analyzer.core.RepoInput
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoirLaneTest {

    @Test
    fun `полка запускает адаптер и читает факты`() {
        val dir = Files.createTempDirectory("noir")
        val script = dir.resolve("noir-adapter.sh")
        script.writeText("#!/bin/sh\necho 'ENDPOINT|method=GET|path=/vets|src/X.java:5|0.8'\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        val fakeBin = dir.resolve("noir")
        fakeBin.writeText("")
        Files.setPosixFilePermissions(fakeBin, PosixFilePermissions.fromString("rwxr-xr-x"))

        val repo = Files.createTempDirectory("repo")
        val lane = NoirLane(adapter = script)
        val input = RepoInput("x", repo)
        assertTrue(lane.applicable(input), "бинарь рядом с адаптером найден")

        val ep = lane.extract(input).single()
        assertEquals(FactType.ENDPOINT, ep.type)
        assertEquals("/vets", ep.attrs["path"])
        assertEquals("src/X.java:5", ep.source)
        assertEquals(0.8, ep.confidence)
    }

    @Test
    fun `без адаптера полка не применима`() {
        val repo = Files.createTempDirectory("repo")
        val lane = NoirLane(adapter = repo.resolve("нет-такого.sh"))
        assertTrue(!lane.applicable(RepoInput("x", repo)))
    }

    /** Пилотный бинарь + фикстура lst-app: Java и Kotlin контроллеры видны оба. */
    @Test
    fun `e2e скан фикстуры настоящим noir — если бинарь на месте`() {
        val lane = NoirLane(
            adapter = Paths.get("noir/noir-adapter.sh"),
            binary = Paths.get("../workspace/_pilots/noir-exp/noir"),
        )
        val input = RepoInput("test.noir", Paths.get("src/test/resources/fixtures/lst-app"))
        org.junit.jupiter.api.Assumptions.assumeTrue(lane.applicable(input), "noir не на месте — пропуск")

        val endpoints = lane.extract(input).filter { it.type == FactType.ENDPOINT }
        val paths = endpoints.mapNotNull { it.attrs["path"] }.sorted()
        assertEquals(listOf("/api/j/owners/{ownerId}", "/api/k/owners/{ownerId}"), paths, "Java+Kotlin: $endpoints")
        assertTrue(endpoints.all { it.attrs["method"] == "GET" && it.confidence == 0.8 })
        assertTrue(endpoints.all { it.attrs["contextPrefix"] == "/api" }, "кандидат на срез: $endpoints")
    }
}
