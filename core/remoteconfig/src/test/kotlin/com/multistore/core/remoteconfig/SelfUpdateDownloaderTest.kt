package com.multistore.core.remoteconfig

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * MultiStore's APK transfer.
 *
 * Three things, and the second is what this class exists to hold still: **a file that does not match
 * is deleted**. Leaving it in staging would mean an unverified APK inside the directory the
 * installer draws from, and no other path would know it is to be thrown away.
 */
class SelfUpdateDownloaderTest {

    @get:Rule val folder = TemporaryFolder()

    private val server = MockWebServer().apply { start() }

    private fun downloader() = SelfUpdateDownloader(
        calls = OkHttpClient(),
        io = UnconfinedTestDispatcher(),
    )

    private fun release(sha256: String?) = SelfUpdateRelease(
        versionCode = 2,
        versionName = "0.5.0",
        url = server.url("/multistore.apk").toString(),
        sha256 = sha256,
        size = APK.size.toLong(),
    )

    @Test
    fun `the hash is computed as the bytes arrive`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(APK)).build())
        val destination = File(folder.newFolder(), "update.apk")

        val outcome = downloader().download(release(EXPECTED_SHA), destination)

        val success = outcome as SelfUpdateSource.Outcome.Success
        assertThat(success.sha256).isEqualTo(EXPECTED_SHA)
        assertThat(success.bytes).isEqualTo(APK.size.toLong())
        assertThat(destination.readBytes()).isEqualTo(APK)
    }

    @Test
    fun `a file that does not match is deleted`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(APK)).build())
        val destination = File(folder.newFolder(), "update.apk")

        val outcome = downloader().download(release("00".repeat(32)), destination)

        assertThat(outcome).isInstanceOf(SelfUpdateSource.Outcome.Mismatch::class.java)
        // The defence: without this line an APK the index did not declare would stay in staging, and
        // the installation path has no way of knowing it is not right.
        assertThat(destination.exists()).isFalse()
    }

    @Test
    fun `with no declared hash the file is kept, and the hash is reported anyway`() = runTest {
        // The case is possible because the type allows it, and this is the right outcome: the
        // pipeline's next step compares the signer with the installed one anyway, which is the
        // defence that cannot be delegated to the index.
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(APK)).build())
        val destination = File(folder.newFolder(), "update.apk")

        val outcome = downloader().download(release(sha256 = null), destination)

        assertThat((outcome as SelfUpdateSource.Outcome.Success).sha256).isEqualTo(EXPECTED_SHA)
    }

    @Test
    fun `a 404 is a failure, with the code`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        val destination = File(folder.newFolder(), "update.apk")

        val outcome = downloader().download(release(EXPECTED_SHA), destination)

        assertThat((outcome as SelfUpdateSource.Outcome.Failed).httpCode).isEqualTo(404)
    }

    @Test
    fun `progress arrives while the file comes down`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(APK)).build())
        val destination = File(folder.newFolder(), "update.apk")
        val seen = mutableListOf<Pair<Long, Long?>>()

        downloader().download(release(EXPECTED_SHA), destination) { received, total ->
            seen += received to total
        }

        assertThat(seen).isNotEmpty()
        assertThat(seen.last().first).isEqualTo(APK.size.toLong())
    }

    private companion object {
        val APK: ByteArray = ByteArray(70_000) { (it % 251).toByte() }
        val EXPECTED_SHA: String = MessageDigest.getInstance("SHA-256")
            .digest(APK)
            .joinToString("") { "%02x".format(it) }
    }
}
