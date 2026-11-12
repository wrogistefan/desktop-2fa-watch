package org.desktop2fa.desktop.watch.transfer

import kotlinx.serialization.json.*
import org.bouncycastle.util.encoders.Base32
import org.desktop2fa.desktop.watch.model.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class TransferOfferTest {
    private fun id(n: Int) = "%032x".format(n)
    private fun secret(n: Int) = Base32.toBase32String(byteArrayOf(n.toByte())).trimEnd('=')
    private fun account(n: Int = 1, transform: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("item_id", id(n)); put("kind", "totp"); put("issuer", "Synthetic")
        put("account", "test-$n"); put("secret", secret(n)); put("algorithm", "SHA1"); put("digits", 6); put("period", 30)
        transform()
    }
    private fun frame(accounts: List<JsonObject> = listOf(account()), transfer: Int = 999) = buildJsonObject {
        put("v", 1); put("type", "OFFER"); put("transfer_id", id(transfer)); put("accounts", JsonArray(accounts))
    }.toString().toByteArray()
    private fun offer(accounts: List<JsonObject> = listOf(account()), transfer: Int = 999) = TransferOffer.parse(frame(accounts, transfer))
    private fun failure(code: TransferError, block: () -> Unit): TransferValidationFailure {
        try { block(); fail("Expected rejection") } catch (e: TransferValidationFailure) { assertEquals(code, e.code); return e }
        throw AssertionError("Unreachable")
    }
    private fun candidate(vault: VaultData, offer: TransferOffer = offer()) =
        (TransferPlanner.prepare(vault, offer) as TransferPreparation.Review).candidate
    private fun existing(n: Int = 1) = TotpEntry("Synthetic", "test-$n", secret(n))

    @Test fun digestBindsExactBytesAndFrameOwnership() {
        val bytes = frame(); val parsed = TransferOffer.parse(bytes)
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, parsed.sha256)
        val spaced = (" " + bytes.toString(Charsets.UTF_8) + "\n").toByteArray()
        assertNotEquals(parsed.sha256, TransferOffer.parse(spaced).sha256)
        bytes.fill(0); assertEquals(secret(1), parsed.accounts.single().entry.secret)
        assertEquals("TransferOffer(redacted)", parsed.toString())
        assertEquals("IncomingAccount(redacted)", parsed.accounts.single().toString())
    }
    @Test fun strictWireSchemaAndFrameLimits() {
        failure(TransferError.LIMIT_EXCEEDED) { TransferOffer.parse(ByteArray(262145)) }
        failure(TransferError.LIMIT_EXCEEDED) { offer(emptyList()) }
        failure(TransferError.LIMIT_EXCEEDED) { offer((1..51).map { account(it) }) }
        val base = frame().toString(Charsets.UTF_8)
        listOf(base.replace("\"v\":1", "\"v\":\"1\""), base.replace("\"v\":1", "\"v\":1.0"),
            base.replace("\"v\":1", "\"v\":1,\"\\u0076\":1"), "\ufeff" + base,
            base.dropLast(1) + ",\"extra\":0}").forEach { bad ->
            val error = failure(TransferError.INVALID_MESSAGE) { TransferOffer.parse(bad.toByteArray()) }
            assertNull(error.cause); assertEquals("INVALID_MESSAGE", error.message)
        }
        failure(TransferError.UNSUPPORTED_VERSION) { TransferOffer.parse(base.replace("\"v\":1", "\"v\":2").toByteArray()) }
        failure(TransferError.INVALID_ACCOUNT) { offer(listOf(account { put("digits", "6") })) }
        failure(TransferError.INVALID_ACCOUNT) { offer(listOf(account { put("secret", JsonNull) })) }
        failure(TransferError.INVALID_ACCOUNT) { offer(listOf(account { put("other", 0) })) }
    }
    @Test fun namesPreserveUnicodeButRejectControlsBidiAndInvalidSurrogates() {
        val unicode = "  Zażółć 😀  "
        val parsed = offer(listOf(account { put("issuer", ""); put("account", unicode) }))
        assertEquals(unicode, parsed.accounts.single().entry.accountName)
        assertEquals("", parsed.accounts.single().entry.issuer)
        listOf("", " \t", "a\n", "x\u202ey", "é".repeat(129)).forEach { name ->
            failure(TransferError.INVALID_ACCOUNT) { offer(listOf(account { put("account", name) })) }
        }
        val unpaired = frame().toString(Charsets.UTF_8).replace("test-1", "\\ud800")
        failure(TransferError.INVALID_ACCOUNT) { TransferOffer.parse(unpaired.toByteArray()) }
        assertEquals(256, offer(listOf(account { put("account", "é".repeat(128)) })).accounts.single().entry.accountName!!.toByteArray().size)
        failure(TransferError.INVALID_ACCOUNT) { offer(listOf(account { put("issuer", "x".repeat(257)) })) }
    }
    @Test fun secretsAreCanonicalUppercaseUnpaddedAndBounded() {
        val maximum = Base32.toBase32String(ByteArray(128) { 42 }).trimEnd('=')
        assertEquals(maximum, offer(listOf(account { put("secret", maximum) })).accounts.single().entry.secret)
        listOf("", "aA", "AA=", "A A", "AB", "0A", "A", Base32.toBase32String(ByteArray(129)).trimEnd('=')).forEach { value ->
            failure(TransferError.INVALID_ACCOUNT) { offer(listOf(account { put("secret", value) })) }
        }
    }
    @Test fun fullSupportedTotpProfileIsPreservedWithoutSubstitution() {
        for (algorithm in listOf("SHA1", "SHA256", "SHA512")) for (digits in listOf(6, 8)) for (period in listOf(1, 45, 86400)) {
            val entry = offer(listOf(account { put("algorithm", algorithm); put("digits", digits); put("period", period) })).accounts.single().entry
            assertEquals(algorithm, entry.algorithm); assertEquals(digits, entry.digits); assertEquals(period, entry.period)
        }
        listOf(account { put("kind", "hotp") }, account { put("algorithm", "sha1") }, account { put("digits", 7) },
            account { put("period", 0) }, account { put("period", 86401) }).forEach {
            failure(TransferError.UNSUPPORTED_TOTP) { offer(listOf(it)) }
        }
    }
    @Test fun newAndDuplicateItemsProduceOneCandidateWithoutMutatingBaseline() {
        val old = existing().copy(issuer="Historical", accountName=null, secret=" " + secret(1).lowercase() + " ")
        val baseline = VaultData(2, mutableListOf(old))
        val parsed = offer(listOf(account(), account(2)))
        val plan = candidate(baseline, parsed)
        assertEquals(listOf(id(2)), plan.receipt.addedItemIds)
        assertEquals(listOf(id(1)), plan.receipt.skippedItemIds)
        assertEquals(listOf(old), baseline.entries)
        val result = plan.vault().data
        assertEquals(old, result.entries[0]); assertEquals(secret(2), result.entries[1].secret)
        assertEquals(listOf(plan.receipt), result.transferReceipts)
        assertTrue(plan.matches(baseline))
        baseline.entries += existing(3); assertFalse(plan.matches(baseline)); assertEquals(2, plan.vault().data.entries.size)
    }
    @Test fun allDuplicatesStillRequireReviewAndCreateReceipt() {
        val baseline = VaultData(2, mutableListOf(existing()))
        val plan = candidate(baseline)
        assertTrue(plan.receipt.addedItemIds.isEmpty()); assertEquals(listOf(id(1)), plan.receipt.skippedItemIds)
        assertEquals(baseline.entries, plan.vault().data.entries)
        assertEquals(1, plan.vault().data.transferReceipts.size); assertTrue(baseline.transferReceipts.isEmpty())
    }
    @Test fun conflictsOverrideEveryHistoricalDuplicateRegardlessOfOrder() {
        val valid = existing()
        for (conflict in listOf(valid.copy(digits=7), valid.copy(period=100000), valid.copy(algorithm="SHA256"))) {
            for (old in listOf(listOf(valid, conflict), listOf(conflict, valid))) {
                failure(TransferError.PARAMETER_CONFLICT) { candidate(VaultData(2, old.toMutableList())) }
            }
        }
        val sameNameDifferentSecret = valid.copy(secret=secret(2))
        for (old in listOf(listOf(valid, sameNameDifferentSecret), listOf(sameNameDifferentSecret, valid))) {
            failure(TransferError.NAME_CONFLICT) { candidate(VaultData(2, old.toMutableList())) }
        }
        assertEquals(listOf(id(1)), candidate(VaultData(2, mutableListOf(valid, valid))).receipt.skippedItemIds)
    }
    @Test fun repeatedIncomingSecretNameOrItemIdRejectsWholeBatch() {
        failure(TransferError.DUPLICATE_IN_BATCH) { offer(listOf(account(), account { put("account", "different") })) }
        for (second in listOf(account(2) { put("secret", secret(1)) }, account(2) { put("account", "test-1") })) {
            val error = failure(TransferError.DUPLICATE_IN_BATCH) { candidate(VaultData(2), offer(listOf(account(), second))) }
            assertEquals(listOf(id(1), id(2)), error.itemIds)
        }
    }
    @Test fun receiptsAreIdempotentDetectDigestConflictAndEvictOldestOnly() {
        val parsed = offer()
        val previous = (0 until 128).map { TransferReceipt(id(it), "0".repeat(64), listOf(id(2000+it)), emptyList()) }
        val baseline = VaultData(2, transferReceipts=previous)
        val plan = candidate(baseline, parsed)
        val result = plan.vault().data
        assertEquals(128, result.transferReceipts.size)
        assertEquals(previous.drop(1) + plan.receipt, result.transferReceipts)
        assertEquals(previous, baseline.transferReceipts)
        val replay = TransferPlanner.prepare(result, parsed) as TransferPreparation.AlreadySaved
        assertEquals(plan.receipt, replay.receipt)
        assertEquals(plan.receipt, TransferPlanner.status(result, parsed.transferId, parsed.sha256))
        assertNull(TransferPlanner.status(result, id(500), "0".repeat(64)))
        failure(TransferError.TX_CONFLICT) { TransferPlanner.status(result, parsed.transferId, "0".repeat(64)) }
        failure(TransferError.TX_CONFLICT) { TransferPlanner.prepare(result, offer(listOf(account(2)))) }
        assertFalse(plan.matches(result))
    }
    @Test fun sharedDesktopHandoffVectorsKeepExactBytesAndExpectedOutcomes() {
        val resource = javaClass.getResourceAsStream("/transfer-v1/offer-vectors.json")!!.use { it.readBytes() }
        val vectors = Json.parseToJsonElement(resource.toString(Charsets.UTF_8)).jsonObject["vectors"]!!.jsonArray
        assertTrue(vectors.size >= 20)
        for (vector in vectors) {
            val row = vector.jsonObject
            val bytes = row["frame_utf8"]!!.jsonPrimitive.content.toByteArray()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(row["sha256"]!!.jsonPrimitive.content, digest)
            if (row["error"] != null) failure(TransferError.valueOf(row["error"]!!.jsonPrimitive.content)) { TransferOffer.parse(bytes) }
            else {
                val parsed = TransferOffer.parse(bytes)
                assertEquals(digest, parsed.sha256)
                val expected = row["accounts"]!!.jsonArray
                assertEquals(expected.size, parsed.accounts.size)
                parsed.accounts.zip(expected).forEach { (actual, record) ->
                    val item = record.jsonObject
                    assertEquals(item["issuer"]!!.jsonPrimitive.content, actual.entry.issuer)
                    assertEquals(item["account"]!!.jsonPrimitive.content, actual.entry.accountName)
                    assertEquals(item["secret"]!!.jsonPrimitive.content, actual.entry.secret)
                    assertEquals(item["algorithm"]!!.jsonPrimitive.content, actual.entry.algorithm)
                    assertEquals(item["digits"]!!.jsonPrimitive.int, actual.entry.digits)
                    assertEquals(item["period"]!!.jsonPrimitive.int, actual.entry.period)
                }
            }
        }
    }

    @Test fun unavailableStorageUsesTypedErrorsWithoutChangingLegacyDataOrLeakingCauses() {
        val legacy = VaultData(1, mutableListOf(existing().copy(digits=7)))
        val before = legacy.entries.toList()
        val parsed = offer()
        val error = failure(TransferError.STORAGE_ERROR) { TransferPlanner.status(legacy, parsed.transferId, parsed.sha256) }
        assertNull(error.cause); assertTrue(error.itemIds.isEmpty())
        failure(TransferError.STORAGE_ERROR) { TransferPlanner.prepare(legacy, parsed) }
        assertEquals(1, legacy.version); assertEquals(before, legacy.entries); assertTrue(legacy.transferReceipts.isEmpty())
        val invalid = VaultData(2, mutableListOf(existing().copy(period=0)))
        val invalidError = failure(TransferError.STORAGE_ERROR) { TransferPlanner.prepare(invalid, parsed) }
        assertNull(invalidError.cause)
    }

    @Test fun invalidV2ReceiptsUseStorageErrorWithoutChangingAnySnapshot() {
        val receipt = TransferReceipt(id(2), "0".repeat(64), listOf(id(3)), emptyList())
        val cases = listOf(listOf(receipt, receipt), listOf(receipt.copy(offerSha256="bad")),
            listOf(receipt.copy(addedItemIds=emptyList())))
        for (receipts in cases) {
            val baseline = VaultData(2, mutableListOf(existing()), receipts)
            val before = receipts.map { it.snapshot() }
            val error = failure(TransferError.STORAGE_ERROR) { TransferPlanner.prepare(baseline, offer()) }
            assertNull(error.cause); assertTrue(error.itemIds.isEmpty())
            assertEquals(before, baseline.transferReceipts)
            assertEquals(listOf(existing()), baseline.entries)
        }
    }

}
