package com.multistore.core.model

/**
 * What the app agrees to receive from the project's own published documents.
 *
 * A group of its own rather than a field of `NetworkSettings`, for the reason settings flows are
 * split here: the consumers are disjoint. This one is read by the configuration refresh at
 * startup and by the Settings screen. Merging them would wake each on every change of the other.
 *
 * The three fields are three answers to the same question — how far the app trusts what we
 * publish.
 */
data class RemoteConfigSettings(
    /**
     * `true` = do not download `parsers.json`; the compiled defaults apply.
     *
     * Negative, because the proto3 zero value is the default and the default must be "receive
     * the fixes". See the field comment in `settings.proto`.
     */
    val blockRemoteParsers: Boolean = false,
    /**
     * `true` = do not download `index.json`; Home stays whatever the local catalogue holds.
     *
     * Negative for the same reason as the other: the default is "download".
     */
    val blockRemoteIndex: Boolean = false,
    /**
     * `true` = do not check whether a newer MultiStore exists.
     *
     * Negative, and here the cost of the opposite would be the highest of the three: MultiStore
     * is on no store, so starting off would mean a security fix reaching only those who had
     * already gone and enabled the switch.
     */
    val blockSelfUpdateCheck: Boolean = false,
)
