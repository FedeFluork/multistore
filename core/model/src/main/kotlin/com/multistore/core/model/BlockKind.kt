package com.multistore.core.model

/** How the store is blocking us. Decides which `ChallengeResolver` rung to try. */
enum class BlockKind {
    /** A JS challenge solvable by actually executing it (rungs 2-3). */
    CHALLENGE,

    /** A captcha meant for a human: rung 4 only, with the user's tap. */
    CAPTCHA,

    /** A geographic block. No rung solves it, and we do not try. */
    GEO,

    /** A bare 403 with no challenge. Often it is the User-Agent. */
    FORBIDDEN,
}
