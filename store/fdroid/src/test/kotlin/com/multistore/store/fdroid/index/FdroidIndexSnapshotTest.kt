package com.multistore.store.fdroid.index

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.Sha256
import com.multistore.store.api.IndexRecord
import com.multistore.store.api.IndexSyncMode
import com.multistore.store.api.IndexToken
import com.multistore.store.fdroid.FdroidConfig
import com.multistore.store.fdroid.Fixtures
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The stream `:core:data` really consumes.
 *
 * The index's other classes are each proven on their own; here the one thing none of them can prove
 * alone is proven — that the records emitted are **usable by whoever receives them**, i.e. that a
 * payload saved today can still be interpreted in six months, when an incremental update arrives and
 * the only remaining context is the payload itself.
 */
@DisplayName("Index snapshot — what reaches whoever saves it")
class FdroidIndexSnapshotTest {

    private val json: Json = IndexStreamReader.DEFAULT_JSON
    private val projection = PackageProjection(repoUrl = FdroidConfig().repoUrl)

    private fun snapshot(fixture: String, mode: IndexSyncMode): FdroidIndexSnapshot {
        // VerifiedDownload deletes its own file on close(): the fixture has to be copied, not used.
        val temp = Files.createTempFile("index", ".json").toFile()
        Fixtures.file(fixture).copyTo(temp, overwrite = true)
        return FdroidIndexSnapshot(
            download = VerifiedDownload(temp, gzipped = false, expectedSha256 = ANY_SHA),
            token = IndexToken("1"),
            mode = mode,
            expectedRecords = null,
            expectedBytes = temp.length(),
            projection = projection,
            json = json,
        )
    }

    private suspend fun records(fixture: String, mode: IndexSyncMode): List<IndexRecord> =
        snapshot(fixture, mode).use { it.records().toList() }

    @Test
    @DisplayName("a complete document's payload can still be projected on its own")
    fun fullPayloadIsSelfDescribing() = runTest {
        val full = records(Fixtures.INDEX_SLICE, IndexSyncMode.FULL)
            .filterIsInstance<IndexRecord.Full>()

        assertThat(full).isNotEmpty()
        for (record in full) {
            // This is the proof of the `-packageName` written inside the payload. The v2 index holds
            // the name as the *key* of the `packages` map: a payload extracted and saved alone would
            // lose it, and the fault would show not here but on the first merge patch, months later,
            // when the only available context is the payload.
            val obj = json.parseToJsonElement(record.payload) as JsonObject
            assertThat(PackagePayload.packageNameOf(obj)).isEqualTo(record.ref.value)

            // Re-projecting the stored payload must give **the same answer** the projection gave
            // during the stream: it is this equality that makes a language change free and an
            // incremental update correct.
            val reprojected = projection.project(record.ref.value, obj)
            assertThat(reprojected?.summary?.packageName)
                .isEqualTo(record.detail?.summary?.packageName)
        }

        // "The same answer" must not be able to mean "no answer for anyone": the slice contains both
        // installable packages and the OTA's three `.zip` entries, which are not.
        val projectable = full.filter { it.detail != null }.map { it.ref.value }
        assertThat(projectable).isNotEmpty()
        assertThat(projectable).doesNotContain(Fixtures.PKG_OTA)
        assertThat(full.map { it.ref.value }).contains(Fixtures.PKG_OTA)
    }

    @Test
    @DisplayName("a package the diff introduces from scratch stays identifiable")
    fun patchOfAnUnknownPackageStillCarriesItsName() = runTest {
        val patch = records(Fixtures.DIFF_SLICE, IndexSyncMode.INCREMENTAL)
            .filterIsInstance<IndexRecord.Patch>()
            .single()

        // `previous = null`: the case of a package appearing for the first time inside an
        // incremental update. If the name lived only in the map's key, this would be an orphan
        // payload — saved, never projectable again, and silently.
        val merged = JsonMergePatch.apply(null, json.parseToJsonElement(patch.payload)) as JsonObject
        assertThat(PackagePayload.packageNameOf(merged)).isEqualTo(patch.ref.value)
    }

    @Test
    @DisplayName("a diff's `null` becomes a removal, not a malformed payload")
    fun nullMeansRemove() = runTest {
        val removed = records(Fixtures.DIFF_SLICE, IndexSyncMode.INCREMENTAL)
            .filterIsInstance<IndexRecord.Remove>()

        assertThat(removed.map { it.ref.value }).containsExactly(Fixtures.PKG_SNAKE)
    }

    @Test
    @DisplayName("the taxonomy is projected only when it is complete")
    fun catalogIsProjectedOnlyOnFullSync() = runTest {
        val fromFull = records(Fixtures.INDEX_SLICE, IndexSyncMode.FULL)
            .filterIsInstance<IndexRecord.Catalog>()
            .single()
        val fromDiff = records(Fixtures.DIFF_SLICE, IndexSyncMode.INCREMENTAL)
            .filterIsInstance<IndexRecord.Catalog>()
            .single()

        assertThat(fromFull.info?.categories).isNotEmpty()
        // On an incremental sync the `repo` block is a merge patch: projecting it alone would give a
        // truncated taxonomy, and would delete the categories that diff does not name.
        assertThat(fromDiff.info).isNull()
        assertThat(fromDiff.payload).isNotEmpty()
    }

    private companion object {
        /** Nothing is checked here: hash verification has its own tests. */
        val ANY_SHA: Sha256 = requireNotNull(Sha256.parseOrNull("00".repeat(32)))
    }
}
