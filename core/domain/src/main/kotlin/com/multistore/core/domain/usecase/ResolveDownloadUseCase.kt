package com.multistore.core.domain.usecase

import com.multistore.core.common.result.AppError
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.data.mapper.toAppError
import com.multistore.core.data.repository.AppDetailRepository
import com.multistore.core.data.repository.SettingsRepository
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.model.AppVersion
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.store.api.DownloadHint
import com.multistore.store.api.DownloadMode
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.StoreResult
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** What could be established about how to reach the file. */
sealed interface ResolvedDownload {

    /** It can be downloaded programmatically. */
    data class Direct(
        val version: AppVersion,
        val resolution: DownloadResolution.Direct,
    ) : ResolvedDownload

    /**
     * A real human gesture on the store's page is needed.
     *
     * The line is explicit: really doing what the site asks is legitimate; pretending to have done it
     * is not. This case *is* the legitimate form — the real page, the real tap — and from there on the
     * verification and installation flow is identical to the direct one.
     */
    data class UserAssisted(
        val version: AppVersion,
        val pageUrl: String,
        val hint: DownloadHint,
    ) : ResolvedDownload

    /**
     * The only compatible version has a signature incompatible with the installed one.
     *
     * It is not "nothing to be done": there is a possible action and it is uninstalling and
     * reinstalling with data loss. Presenting it as a generic error would hide the only way out — and
     * it is the case that stays available even with `allow_signer_mismatch` off.
     */
    data class SignerConflict(val available: List<AppVersion>) : ResolvedDownload

    /** No published version runs on this device: minSdk too high, ABI absent. */
    data object Incompatible : ResolvedDownload

    data class Unavailable(val error: AppError) : ResolvedDownload
}

/**
 * Chooses the version and resolves the link, in that order.
 *
 * The order matters: resolving first would mean asking the store for a file one then discovers
 * cannot be installed — on modyolo, where about half the binaries answer 500, every useless request
 * is also a useless wait for the user.
 */
class ResolveDownloadUseCase @Inject constructor(
    private val registry: StoreRegistry,
    private val details: AppDetailRepository,
    /**
     * It serves one question: **is the user-assisted rung allowed?**
     *
     * It lives here and not in the ViewModel for the same reason the adult-content filter lives in
     * `SearchRepositoryImpl` and not in the caller: a setting passed as a parameter is a setting
     * somebody eventually forgets to pass, and forgetting produces no error — only a path the user
     * had asked not to see.
     */
    private val settings: SettingsRepository,
) {

    suspend operator fun invoke(
        storeId: StoreId,
        ref: StoreAppRef,
        /** A version chosen by hand by the user; `null` = the rule decides. */
        explicit: AppVersion? = null,
    ): ResolvedDownload {
        val adapter = registry.adapter(storeId)
            ?: return ResolvedDownload.Unavailable(AppError.NotFound)

        // Read once, used twice: here to exit early, and further down on the real outcome.
        val assistedBlocked = settings.network.first().blockUserAssistedChallenge

        // The early exit saves a request to the store, it decides nothing: a store declared
        // `USER_ASSISTED_ONLY` has no other route, so querying it only to refuse the one outcome it
        // can produce would be traffic spent on a no that is already written. The real rule stays the
        // one on the `UserAssisted` branch: **it is applied there**, because a `DIRECT_WITH_FALLBACK`
        // store can end up in that branch too.
        if (assistedBlocked && adapter.capabilities.downloadMode == DownloadMode.USER_ASSISTED_ONLY) {
            return ResolvedDownload.Unavailable(AppError.UserAssistanceDisabled)
        }

        val detail = details.detail(storeId, ref)
            ?: return ResolvedDownload.Unavailable(AppError.NotFound)

        val version = explicit ?: when (val selection = detail.selection) {
            is VersionSelection.Outcome.Offer -> selection.version
            is VersionSelection.Outcome.UpToDate -> selection.version
            is VersionSelection.Outcome.SignerConflict ->
                return ResolvedDownload.SignerConflict(selection.available)

            VersionSelection.Outcome.Incompatible -> return ResolvedDownload.Incompatible

            VersionSelection.Outcome.NothingInstallable ->
                return ResolvedDownload.Unavailable(AppError.NotFound)

            // Same outcome as `NothingInstallable` **until the user asks**: the version exists, but
            // sits in a channel we do not offer by ourselves. The branch stays distinct because
            // `explicit` reaches it — whoever chooses a version by hand overrides the rule, and it is
            // exactly the "explicit user request" the rule provides for.
            is VersionSelection.Outcome.OnlyOtherChannels ->
                return ResolvedDownload.Unavailable(AppError.NotFound)

            // The user's pin is a decision, not a technical obstacle: it is respected by offering what
            // is **within** the pin, and it is not circumvented on our own. Whoever wants to go beyond
            // does so with `explicit`, which comes from a tap on a specific version — i.e. from the
            // same place the pin came from.
            is VersionSelection.Outcome.Pinned -> selection.offer?.version
                ?: return ResolvedDownload.Unavailable(AppError.NotFound)
        }

        val resolution = when (val result = adapter.getDownloadLink(ref, version.ref)) {
            is StoreResult.Success -> result.value
            is StoreResult.Failure -> return ResolvedDownload.Unavailable(result.error.toAppError())
            StoreResult.Unsupported -> return ResolvedDownload.Unavailable(AppError.NotFound)
        }

        return when (resolution) {
            is DownloadResolution.Direct -> {
                // The preventive HEAD exists for modyolo, where **about half the binaries answer 500**:
                // without it, the user would discover the fault after pressing Download. For healthy
                // stores the default is "available" and costs no extra request.
                val reachable = adapter.preflight(resolution).getOrNull() ?: true
                if (reachable) {
                    ResolvedDownload.Direct(version, resolution)
                } else {
                    ResolvedDownload.Unavailable(AppError.NotFound)
                }
            }

            is DownloadResolution.UserAssisted ->
                if (assistedBlocked) {
                    ResolvedDownload.Unavailable(AppError.UserAssistanceDisabled)
                } else {
                    ResolvedDownload.UserAssisted(version, resolution.pageUrl, resolution.hint)
                }
        }
    }
}
