package com.multistore.core.installer.container

/** The outcome of trying to put the game data where the app will look for it. */
sealed interface ExpansionResult {

    data class Placed(val files: Int, val bytes: Long) : ExpansionResult

    data class Failed(val reason: String) : ExpansionResult
}

/**
 * Whoever knows how to write into `Android/obb/<package>/`, i.e. **outside** where a normal app
 * lands.
 *
 * ### The measurement that decided this interface should exist, and be nullable
 *
 * `MANAGE_EXTERNAL_STORAGE` is not enough. Measured on 26/08/2026 on Android 16 (API 36), with the
 * permission granted and `Environment.isExternalStorageManager()` answering `true`:
 *
 * | path | outcome |
 * |---|---|
 * | `Android/obb/<another package>/` | `mkdirs` **false**, opening `ENOENT` |
 * | `Android/data/<another package>/` | `mkdirs` false, `ENOENT` |
 * | `Android/media/<another package>/` | `mkdirs` false, writing `EPERM` |
 * | `Documents/…` | succeeded — i.e. the probe **was** measuring something |
 *
 * It is the documented restriction on `Android/data`, `Android/obb` and `Android/sandbox`, which
 * that permission does not override. The only identity that writes there is the one carrying the
 * supplementary group `ext_obb_rw`, and on the device there is exactly one: **the shell** — i.e.
 * Shizuku or root. Verified from the other side: the shell's `id` answers `…,1079(ext_obb_rw),…` and
 * a `mkdir` plus a write into `Android/obb/com.rockstargames.gtactw/` from there succeeds.
 *
 * Two consequences follow, written into the type:
 *
 * 1. the permission **is not requested**, because it would be useless. MultiStore does not declare
 *    `MANAGE_EXTERNAL_STORAGE`, which is Android's most invasive permission;
 * 2. where there is no privileged shell this interface **does not exist**, and whoever installs
 *    notices from a `null` rather than from a failure halfway through.
 */
interface ExpansionWriter {

    suspend fun place(packageName: String, expansions: List<ExtractedPart>): ExpansionResult
}
