package com.multistore.core.remoteconfig

import com.google.common.truth.Truth.assertThat
import java.util.Base64
import org.junit.Test

/**
 * The signature is the only thing separating "we decide the configuration" from "whoever talks to
 * the phone in our place decides it". Every exit of [SignedDocuments] has its case here.
 */
class SignedDocumentsTest {

    private val keys = SigningFixture()
    private val documents = keys.documents()

    @Test
    fun `an envelope signed with the right key returns the payload intact`() {
        val document = """{"schemaVersion":1,"stores":{}}"""

        val outcome = documents.open(keys.envelope(document))

        assertThat(outcome).isEqualTo(OpenOutcome.Opened(document))
    }

    @Test
    fun `an envelope signed with another key is refused`() {
        val impostor = SigningFixture(seed = 2)

        val outcome = documents.open(impostor.envelope("""{"schemaVersion":1}"""))

        assertThat(outcome).isEqualTo(OpenOutcome.Rejected(ConfigRejection.BAD_SIGNATURE))
    }

    /**
     * The case that gives all the others meaning: valid signature, payload changed **by one byte**.
     *
     * It is the shape a real attack would take — the envelope arrives from a CDN or a proxy, not
     * from a local file — and if it passed, everything else would be theatre.
     */
    @Test
    fun `a payload tampered with after signing is refused`() {
        val original = keys.envelope("""{"schemaVersion":1,"stores":{}}""").decodeToString()
        val decoder = Base64.getDecoder()
        val encoder = Base64.getEncoder()
        val payloadField = Regex(""""payload":"([^"]+)"""").find(original)!!.groupValues[1]
        val tampered = decoder.decode(payloadField).also { it[it.lastIndex] = ' '.code.toByte() }

        val outcome = documents.open(
            original.replace(payloadField, encoder.encodeToString(tampered)).encodeToByteArray(),
        )

        assertThat(outcome).isEqualTo(OpenOutcome.Rejected(ConfigRejection.BAD_SIGNATURE))
    }

    @Test
    fun `an envelope with no signature is refused, and says so in its own words`() {
        val outcome = documents.open(
            """{"algorithm":"ed25519","payload":"e30=","signature":""}""".encodeToByteArray(),
        )

        assertThat(outcome).isEqualTo(OpenOutcome.Rejected(ConfigRejection.MISSING_SIGNATURE))
    }

    @Test
    fun `the bare document, with no envelope, is refused`() {
        val outcome = documents.open("""{"schemaVersion":1,"stores":{}}""".encodeToByteArray())

        // No `signature` field: the envelope decodes with the defaults and the signature comes out
        // empty. It is the "then publish an unsigned one: the app refuses it" case.
        assertThat(outcome).isEqualTo(OpenOutcome.Rejected(ConfigRejection.MISSING_SIGNATURE))
    }

    @Test
    fun `an algorithm we do not know is refused without looking at the signature`() {
        val outcome = documents.open(keys.envelope("""{"schemaVersion":1}""", algorithm = "rsa-pss"))

        assertThat(outcome).isEqualTo(OpenOutcome.Rejected(ConfigRejection.UNSUPPORTED_ALGORITHM))
    }

    @Test
    fun `what is not JSON is not an envelope`() {
        val outcome = documents.open("<html>403 Forbidden</html>".encodeToByteArray())

        assertThat(outcome).isEqualTo(OpenOutcome.Rejected(ConfigRejection.MALFORMED_ENVELOPE))
    }

    @Test
    fun `broken base64 is a defect of the envelope, not of the signature`() {
        val outcome = documents.open(
            """{"algorithm":"ed25519","payload":"non-base64!!","signature":"AAAA"}""".encodeToByteArray(),
        )

        assertThat(outcome).isEqualTo(OpenOutcome.Rejected(ConfigRejection.MALFORMED_ENVELOPE))
    }

    /**
     * BouncyCastle throws on a wrongly sized signature. An exception escaping here would reach app
     * startup: a malformed remote configuration must not be able to stop MultiStore opening.
     */
    @Test
    fun `a wrongly sized signature is a refusal, not an exception`() {
        val outcome = documents.open(
            """{"algorithm":"ed25519","payload":"e30=","signature":"AAAA"}""".encodeToByteArray(),
        )

        assertThat(outcome).isEqualTo(OpenOutcome.Rejected(ConfigRejection.BAD_SIGNATURE))
    }

    @Test
    fun `an envelope with fields this version does not know still opens`() {
        val document = """{"schemaVersion":1,"stores":{}}"""
        val withExtras = keys.envelope(document).decodeToString()
            .replaceFirst("{", """{"keyId":"2027-01","expiresAt":"2027-01-01T00:00:00Z",""")

        val outcome = documents.open(withExtras.encodeToByteArray())

        assertThat(outcome).isEqualTo(OpenOutcome.Opened(document))
    }
}
