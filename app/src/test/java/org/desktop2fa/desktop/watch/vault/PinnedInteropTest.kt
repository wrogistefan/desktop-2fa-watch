package org.desktop2fa.desktop.watch.vault

import kotlinx.serialization.json.*
import org.desktop2fa.desktop.watch.model.VaultData
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Public synthetic resources only; failure messages deliberately omit keys/codes/passwords. */
class PinnedInteropTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun resource(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/interop/v1/$name")).use { it.readBytes() }
    private val manifest get() = Json.parseToJsonElement(resource("manifest.json").decodeToString()).jsonObject
    private val cases get() = manifest.getValue("cases").jsonArray.map { it.jsonObject }
    private val supported get() = cases.single { it.getValue("id").jsonPrimitive.content == "supported" }
    private val password get() = supported.getValue("password").jsonPrimitive.content
    private val expected get() = Json.decodeFromString(VaultData.serializer(), resource("supported.json").decodeToString())
    private fun hash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun hash(file: File): ByteArray = MessageDigest.getInstance("SHA-256").also { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
    }.digest()
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun checkCodes(vault: Vault) {
        val vectors = Json.parseToJsonElement(resource("fixed-time-codes.json").decodeToString()).jsonArray
        for (vector in vectors) {
            val row = vector.jsonObject
            val time = row.getValue("timestamp").jsonPrimitive.long
            val wanted = row.getValue("codes").jsonArray
            assertTrue("Synthetic entry count preserved", vault.entries.size == wanted.size)
            vault.entries.forEachIndexed { index, e ->
                val code = TotpGenerator.generateTotp(e.secret, time, e.period, e.digits, e.algorithm)
                assertTrue("Pinned fixed-time code matches", code == wanted[index].jsonPrimitive.content)
            }
        }
    }

    @Test fun immutableCorpusAndSupportedFieldsCodesSurviveKotlinSaveLoad() {
        manifest.getValue("files").jsonObject.forEach { (name, info) ->
            val data = resource(name)
            assertTrue("Pinned artifact hash matches: $name", hex(hash(data)) == info.jsonObject.getValue("sha256").jsonPrimitive.content)
        }
        val source = temporary.newFile("synthetic-python.d2fa").apply { writeBytes(resource("supported.d2fa")) }
        val original = hash(source)
        val loaded = Vault.load(source, password)
        assertTrue("All supported fields/order/text preserved", loaded.data == expected)
        checkCodes(loaded)
        val saved = File(temporary.root, "synthetic-kotlin.d2fa")
        loaded.saveNew(saved, password)
        val reloaded = Vault.load(saved, password)
        assertTrue("All re-saved fields preserved", reloaded.data == expected)
        checkCodes(reloaded)
        assertTrue("Python source is immutable", hash(source).contentEquals(original))
    }

    @Test fun unsupportedCasesAreRejectedWithoutChangingAnySource() {
        for (case in cases.filter { it.getValue("watch_load").jsonPrimitive.content == "reject" }) {
            val id = case.getValue("id").jsonPrimitive.content
            val file = File(temporary.newFolder(), "synthetic-$id.d2fa")
            val recipe = case["recipe"]?.jsonObject
            if (recipe == null) file.writeBytes(resource(case.getValue("file").jsonPrimitive.content))
            else {
                val prefix = resource(recipe.getValue("prefix").jsonPrimitive.content)
                file.outputStream().use { output ->
                    output.write(prefix)
                    var left = recipe.getValue("zero_pad_to").jsonPrimitive.int - prefix.size
                    val zeros = ByteArray(65_536)
                    while (left > 0) { val count = minOf(left, zeros.size); output.write(zeros, 0, count); left -= count }
                }
                assertTrue("Size recipe hash matches", hex(hash(file)) == case.getValue("sha256").jsonPrimitive.content)
            }
            val before = hash(file)
            try {
                Vault.load(file, case.getValue("password").jsonPrimitive.content)
                fail("Unsupported synthetic case accepted: $id")
            } catch (_: InvalidPasswordException) { /* Controlled rejection. */
            } catch (_: CorruptedVaultException) {
            } catch (_: UnsupportedFormatException) {
            }
            assertTrue("Rejected source unchanged: $id", hash(file).contentEquals(before))
        }
    }

    @Test fun actualPinnedPythonRunsBothRoundTripDirections() {
        assumeTrue("Use tools/interop/run.py for actual pinned Desktop execution", System.getenv("D2FA_INTEROP") == "1")
        val repo = File(requireNotNull(System.getenv("D2FA_INTEROP_REPO")))
        val work = temporary.newFolder("synthetic-interop-roundtrip")
        val fromPython = work.resolve("kotlin-from-python.d2fa")
        val source = work.resolve("python-original.d2fa").apply { writeBytes(resource("supported.d2fa")) }
        Vault.load(source, password).saveNew(fromPython, password)
        val fresh = work.resolve("kotlin-fresh.d2fa")
        Vault(expected).saveNew(fresh, password)
        val sourceHash = hash(source)
        val watchV2 = work.resolve("watch-v2.d2fa")
        Vault(expected).saveNew(watchV2, password)
        val originalV1 = hash(watchV2)
        val noBackup = work.resolve("no-backup").apply { mkdirs() }
        val migration = migrateVaultToV2(watchV2, noBackup, password, expected, VaultMigrationConsent.CONFIRMED)
        assertTrue("Synthetic migration verified", !migration.requiresReview)
        val archive = migrationArchive(noBackup)
        assertTrue("Migration retained exact v1 bytes", hash(archive).contentEquals(originalV1))
        val watchV2Hash = hash(watchV2)
        val process = ProcessBuilder(requireNotNull(System.getenv("D2FA_INTEROP_PYTHON")),
            repo.resolve("tools/interop/desktop_bridge.py").path,
            "--desktop-source", requireNotNull(System.getenv("D2FA_DESKTOP_SOURCE")),
            "--work-dir", work.path).redirectErrorStream(true).redirectOutput(work.resolve("bridge-status.txt")).start()
        try {
            assertTrue("Pinned Desktop bridge finished", process.waitFor(180, TimeUnit.SECONDS))
            assertTrue("Pinned Desktop bridge succeeded; inspect sanitized status", process.exitValue() == 0)
        } finally { if (process.isAlive) process.destroyForcibly() }
        for (name in listOf("kotlin-from-python", "kotlin-fresh")) {
            val roundTrip = Vault.load(work.resolve("$name-via-python.d2fa"), password)
            assertTrue("All cross-language fields/order/text preserved", roundTrip.data == expected)
            checkCodes(roundTrip)
        }
        val report = Json.parseToJsonElement(work.resolve("bridge-result.json").readText()).jsonObject
        assertTrue("Desktop source commit is pinned", report.getValue("desktop_commit") == manifest.getValue("desktop_commit"))
        assertTrue(report.getValue("cases").jsonPrimitive.int == 23)
        assertTrue(report.getValue("round_trips").jsonPrimitive.int == 2)
        assertTrue(report.getValue("fixed_time_codes").jsonPrimitive.int == 117)
        assertTrue("Pinned Desktop rejects v2 explicitly", report.getValue("watch_v2_rejected").jsonPrimitive.boolean)
        assertTrue("Pinned Desktop can read retained v1 copy", report.getValue("v1_archive_read").jsonPrimitive.boolean)
        assertTrue("Rejected Watch v2 unchanged", hash(watchV2).contentEquals(watchV2Hash))
        assertTrue("Retained v1 copy unchanged", hash(archive).contentEquals(originalV1))
        assertTrue("Original Python bytes unchanged", hash(source).contentEquals(sourceHash))
    }
}
