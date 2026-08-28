package com.multistore.core.remoteconfig

import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * The signed envelope `parsers.json` and `index.json` travel in.
 *
 * ### Why the payload is base64 instead of nested JSON
 *
 * Because **one signs a sequence of bytes, not an object**. If the envelope contained the document
 * as a JSON object, whoever verifies would have to re-serialise it to obtain the bytes the signature
 * was computed over: same key order, same spacing, same number formatting. One difference is enough
 * — a `1.0` coming back as `1`, a space after a colon — and a valid signature comes out invalid. In
 * the worst case the opposite holds: two different documents reducing to the same canonical bytes
 * have the same signature.
 *
 * With the payload in base64, the bytes verified and the bytes interpreted are **the same bytes**,
 * by construction. It is the same reasoning by which `SessionInstaller` computes the SHA-256 while
 * writing the bytes into the session, rather than on the staged file.
 *
 * ### Why BouncyCastle and not the JCA
 *
 * Ed25519 enters Android's system provider **at API 33**. The `minSdk` is 26: on everything below,
 * `Signature.getInstance("Ed25519")` would throw `NoSuchAlgorithmException` — i.e. signature
 * verification would not work precisely on the older devices, which are also the ones the app is
 * installed on from alternative sources most often. With BouncyCastle's lightweight API the
 * implementation is the same everywhere, and it is testable on the JVM with no emulator.
 */
@Serializable
internal data class SignedEnvelope(
    val algorithm: String = "",
    val payload: String = "",
    val signature: String = "",
)

/**
 * Why a document was discarded.
 *
 * Six values and not three: the message the user reads groups some of them together (see
 * `:feature:settings`), but diagnosis and tests need to tell them apart. "The signature is missing"
 * and "the signature is there and does not match" lead to two different investigations: the first is
 * nearly always a publishing pipeline that skipped a step, the second a wrong key or a tampered
 * document.
 */
enum class ConfigRejection {
    /** It is not JSON, or does not have the shape of an envelope. */
    MALFORMED_ENVELOPE,

    /** A well-formed envelope with an empty or missing `signature` field. */
    MISSING_SIGNATURE,

    /** `algorithm` other than `ed25519`: an envelope produced for another signature scheme. */
    UNSUPPORTED_ALGORITHM,

    /** The signature is there and does not correspond to the pinned key. */
    BAD_SIGNATURE,

    /** Valid signature, but what is inside is not the document expected. */
    MALFORMED_PAYLOAD,

    /** Valid signature and readable document, but of a schema this version does not know. */
    UNSUPPORTED_SCHEMA,
}

/** The outcome of opening an envelope: either the document's text, or the reason for refusal. */
sealed interface OpenOutcome {
    data class Opened(val document: String) : OpenOutcome
    data class Rejected(val reason: ConfigRejection) : OpenOutcome
}

/**
 * Whoever knows how to verify and store a signed document.
 *
 * It exists for one reason: [RemoteConfigFetcher] does exactly the same thing for `parsers.json` and
 * for `index.json` — decides whether asking is worthwhile, downloads with a cap, describes a host
 * that does not answer — and none of those three things knows what is in the document. What changes
 * is who verifies it and where it is put, and that is exactly this interface.
 *
 * Duplicating the fetcher would have meant two size caps, two refresh windows and two ways of
 * describing a 404 — i.e. two places to fix the same defect, and one of the two forgotten.
 */
interface SignedDocumentSink {

    /** When the cached document was written, or `null` if there is none. */
    fun storedAt(): kotlin.time.Instant?

    /** Verifies the bytes just downloaded and, if they hold up, caches them. */
    fun accept(bytes: ByteArray): FetchAttempt

    /** Records an outcome that produced no document: network down, 404, 304. */
    fun note(attempt: FetchAttempt)
}

/**
 * Opens a signed envelope, or says why it cannot.
 *
 * It knows neither `parsers.json` nor `index.json`: it only knows about signatures. The same class
 * opens the index too, with the same key or another, without changing a line.
 */
class SignedDocuments(
    private val publicKey: ByteArray,
    private val json: Json = LENIENT,
) {

    fun open(bytes: ByteArray): OpenOutcome {
        val envelope = runCatching { json.decodeFromString<SignedEnvelope>(bytes.decodeToString()) }
            .getOrElse { return OpenOutcome.Rejected(ConfigRejection.MALFORMED_ENVELOPE) }

        if (envelope.signature.isBlank()) {
            return OpenOutcome.Rejected(ConfigRejection.MISSING_SIGNATURE)
        }
        if (!envelope.algorithm.equals(ALGORITHM, ignoreCase = true)) {
            return OpenOutcome.Rejected(ConfigRejection.UNSUPPORTED_ALGORITHM)
        }

        val decoder = Base64.getDecoder()
        val payload = runCatching { decoder.decode(envelope.payload) }
            .getOrElse { return OpenOutcome.Rejected(ConfigRejection.MALFORMED_ENVELOPE) }
        val signature = runCatching { decoder.decode(envelope.signature) }
            .getOrElse { return OpenOutcome.Rejected(ConfigRejection.MALFORMED_ENVELOPE) }

        return if (verify(payload, signature)) {
            OpenOutcome.Opened(payload.decodeToString())
        } else {
            OpenOutcome.Rejected(ConfigRejection.BAD_SIGNATURE)
        }
    }

    private fun verify(payload: ByteArray, signature: ByteArray): Boolean = runCatching {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        signer.update(payload, 0, payload.size)
        signer.verifySignature(signature)
    }.getOrDefault(false)
    // `runCatching` and not an `if` on the length: BouncyCastle throws on a wrongly sized signature
    // and on a malformed key, and a document arriving from the network can be anything. An exception
    // here still means "not verified", which is exactly what `false` says.

    private companion object {
        const val ALGORITHM = "ed25519"

        /**
         * `ignoreUnknownKeys` on the **envelope**, not on the document.
         *
         * A future envelope will be able to carry a `keyId` or an expiry date without this version
         * of the app refusing it: what it needs to decide — algorithm, payload, signature — is
         * already there. The document inside follows a different and stricter rule, see
         * [JsonOverride].
         */
        val LENIENT = Json { ignoreUnknownKeys = true }
    }
}
