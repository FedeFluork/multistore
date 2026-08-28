package com.multistore.core.download

import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.result.AppError
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreId
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.core.network.http.StoreNetworkProfile
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The download engine against a real server (fake, but one that really speaks HTTP).
 *
 * These tests' core is a single property, the one easiest to get wrong: **the SHA-256 of a file
 * resumed halfway has to be that of the whole file.** An engine feeding the digest with only the new
 * bytes passes any test that resumes nothing, and fails in production at the first interrupted
 * download.
 */
class OkHttpDownloadEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var clients: StoreHttpClients
    private lateinit var workDir: File
    private lateinit var engine: OkHttpDownloadEngine

    /** A deterministic 200 KB: enough to cross more than one 64 KB buffer. */
    private val content: ByteArray = ByteArray(200 * 1024) { (it % 251).toByte() }
    private val contentSha: Sha256 =
        Sha256.ofBytes(MessageDigest.getInstance("SHA-256").digest(content))

    private val etag = "\"v1\""

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        workDir = Files.createTempDirectory("download").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = File(workDir, "http")))
        engine = OkHttpDownloadEngine(
            clients = clients,
            profiles = { StoreNetworkProfile(userAgent = "MultiStoreTest/1.0", permitsPerSecond = 1000.0) },
            io = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        server.close()
        clients.shutdown()
        workDir.deleteRecursively()
    }

    private fun destination() = File(workDir, "app.apk")

    private fun request(
        resume: PartialDownload? = null,
        expectedSha: Sha256? = contentSha,
        expectedSize: Long? = content.size.toLong(),
    ) = DownloadRequest(
        storeId = StoreId.FDROID,
        url = server.url("/app.apk").toString(),
        destination = destination(),
        expectedSha256 = expectedSha,
        expectedSize = expectedSize,
        resume = resume,
    )

    /** A server that honours `Range` and `If-Range`, as a real CDN would. */
    private fun serveRanges(currentEtag: String = etag) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.headers["Range"]
                val ifRange = request.headers["If-Range"]
                val validatorStillMatches = ifRange == null || ifRange == currentEtag
                if (range != null && validatorStillMatches) {
                    val start = range.removePrefix("bytes=").substringBefore('-').toInt()
                    val slice = content.copyOfRange(start, content.size)
                    return MockResponse.Builder()
                        .code(206)
                        .addHeader("ETag", currentEtag)
                        .addHeader("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
                        .body(Buffer().write(slice))
                        .build()
                }
                return MockResponse.Builder()
                    .code(200)
                    .addHeader("ETag", currentEtag)
                    .body(Buffer().write(content))
                    .build()
            }
        }
    }

    @Test
    fun `complete download - hash and size match`() = runTest {
        serveRanges()

        val outcome = engine.download(request())

        val success = outcome as DownloadOutcome.Success
        assertThat(success.sha256).isEqualTo(contentSha)
        assertThat(success.bytes).isEqualTo(content.size.toLong())
        assertThat(destination().readBytes()).isEqualTo(content)
    }

    @Test
    fun `resumption - the digest covers the whole file, not just the tail`() = runTest {
        serveRanges()
        val alreadyDownloaded = 120 * 1024
        destination().writeBytes(content.copyOfRange(0, alreadyDownloaded))

        val outcome = engine.download(
            request(resume = PartialDownload(alreadyDownloaded.toLong(), etag)),
        )

        // If the digest started at byte 122,880 instead of zero, this comparison would fail even though
        // the file on disk is perfectly correct — and it is exactly how a wrong download engine
        // presents itself: "corrupt file" on healthy files.
        val success = outcome as DownloadOutcome.Success
        assertThat(success.sha256).isEqualTo(contentSha)
        assertThat(destination().readBytes()).isEqualTo(content)
    }

    @Test
    fun `resumption - only the missing piece is asked for`() = runTest {
        serveRanges()
        val alreadyDownloaded = 150 * 1024
        destination().writeBytes(content.copyOfRange(0, alreadyDownloaded))

        engine.download(request(resume = PartialDownload(alreadyDownloaded.toLong(), etag)))

        val sent = server.takeRequest()
        assertThat(sent.headers["Range"]).isEqualTo("bytes=$alreadyDownloaded-")
        assertThat(sent.headers["If-Range"]).isEqualTo(etag)
    }

    @Test
    fun `the file changed on the server - resumption degrades into a download from scratch`() = runTest {
        // The stored validator is no longer the current one: with `If-Range` the server answers 200
        // with the whole file instead of 206 with the tail.
        serveRanges(currentEtag = "\"v2\"")
        destination().writeBytes(ByteArray(100 * 1024) { 0x7F })

        val outcome = engine.download(request(resume = PartialDownload(100L * 1024, etag)))

        // Without `If-Range` the result would be a file stitched from two versions: 100 KB of garbage
        // followed by the new one's tail, and no way of noticing except the hash — which not every
        // store publishes.
        val success = outcome as DownloadOutcome.Success
        assertThat(success.sha256).isEqualTo(contentSha)
        assertThat(destination().readBytes()).isEqualTo(content)
    }

    @Test
    fun `a server ignoring Range - we start from zero without corrupting anything`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(content)).build())
        destination().writeBytes(ByteArray(50 * 1024) { 0x41 })

        val outcome = engine.download(request(resume = PartialDownload(50L * 1024, validator = null)))

        assertThat((outcome as DownloadOutcome.Success).sha256).isEqualTo(contentSha)
        assertThat(destination().length()).isEqualTo(content.size.toLong())
    }

    @Test
    fun `416 - the range no longer exists, we start again`() = runTest {
        server.enqueue(MockResponse.Builder().code(416).build())
        destination().writeBytes(ByteArray(300 * 1024))

        val outcome = engine.download(request(resume = PartialDownload(300L * 1024, etag)))

        val interrupted = outcome as DownloadOutcome.Interrupted
        assertThat(interrupted.partial.bytesDownloaded).isEqualTo(0)
        assertThat(destination().exists()).isFalse()
    }

    @Test
    fun `wrong hash - a non-resumable failure, and the file disappears`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(content)).build())
        val wrong = requireNotNull(Sha256.parseOrNull("ab".repeat(32)))

        val outcome = engine.download(request(expectedSha = wrong))

        assertThat(outcome).isInstanceOf(DownloadOutcome.Failed::class.java)
        assertThat((outcome as DownloadOutcome.Failed).error)
            .isEqualTo(AppError.IntegrityFailed("sha256"))
        // Keeping around bytes that are not the promised ones only serves to try installing them again.
        assertThat(destination().exists()).isFalse()
    }

    @Test
    fun `connection dropped halfway - what is there is kept and where to resume is stated`() = runTest {
        val truncated = content.copyOfRange(0, 80 * 1024)
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(truncated)).build())

        val outcome = engine.download(request())

        val interrupted = outcome as DownloadOutcome.Interrupted
        assertThat(interrupted.partial.bytesDownloaded).isEqualTo(truncated.size.toLong())
        assertThat(destination().length()).isEqualTo(truncated.size.toLong())
    }

    @Test
    fun `404 - it is not resumable`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())

        val outcome = engine.download(request())

        assertThat((outcome as DownloadOutcome.Failed).error).isEqualTo(AppError.NotFound)
    }

    @Test
    fun `403 - the store bars our way, and it is told from a network fault`() = runTest {
        server.enqueue(MockResponse.Builder().code(403).build())

        val outcome = engine.download(request())

        assertThat((outcome as DownloadOutcome.Failed).error).isInstanceOf(AppError.Blocked::class.java)
    }

    @Test
    fun `a resume state larger than the file on disk leaves no holes`() = runTest {
        serveRanges()
        // The cache has been cleared, or the write stopped halfway: the byte we thought we had reached
        // is no longer there. Asking the server to restart from there would leave a hole nobody
        // fills.
        destination().writeBytes(content.copyOfRange(0, 10 * 1024))

        val outcome = engine.download(request(resume = PartialDownload(150L * 1024, etag)))

        assertThat((outcome as DownloadOutcome.Success).sha256).isEqualTo(contentSha)
        assertThat(destination().readBytes()).isEqualTo(content)
    }

    @Test
    fun `the validator seen on the first attempt reaches whoever has to store it`() = runTest {
        serveRanges()
        var seen: String? = null

        engine.download(
            request(),
            object : DownloadListener {
                override fun onStarted(totalBytes: Long?, validator: String?, resumedFrom: Long) {
                    seen = validator
                }
            },
        )

        assertThat(seen).isEqualTo(etag)
    }

    @Test
    fun `progress arrives, and never exceeds the total`() = runTest {
        serveRanges()
        val samples = mutableListOf<Pair<Long, Long?>>()

        engine.download(
            request(),
            object : DownloadListener {
                override fun onProgress(bytesDownloaded: Long, totalBytes: Long?) {
                    samples += bytesDownloaded to totalBytes
                }
            },
        )

        assertThat(samples).isNotEmpty()
        assertThat(samples.last().first).isEqualTo(content.size.toLong())
        assertThat(samples.all { (done, total) -> total == null || done <= total }).isTrue()
    }
}
