package com.mircowuffwuff.sharpdroid

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * a game the app can launch: a directory holding an `eboot.bin`, and whatever identity the dump
 * carries beside it.
 *
 * **the directory name stays the identity the rest of the app works in**, and [name] and [titleId]
 * are decoration on top of it. [folder] is what the host layer is handed, what every staging script
 * writes and what every log line says, so a row whose display name is missing or wrong is still a
 * row that can be launched and found on disk.
 *
 * **where the files are is [source]'s to know and nobody else's.** a staged directory and a game
 * inside a granted tree produce the same row and the same launch, and differ only in what the intent
 * carries -- see [GameSource] and [GameListActivity.launch].
 *
 * @see GameLibrary for where these come from and which volumes are searched.
 */
data class Game(
    val source: GameSource,
    /** what to call it on screen. the dump's own title name, or [folder] when there is none. */
    val name: String,
    /** e.g. `PPSA02929`. null when neither the dump nor the directory name offers one. */
    val titleId: String?,
    /**
     * the dump's own content version, e.g. `01.005.000`. null when it does not carry one.
     *
     * **`contentVersion` and not one of the four other version fields beside it.** a dump names a
     * master version, an origin content version, a target content version and the SDK it was built
     * against; the first is the release the disc or the download *is*, and the rest are either
     * build provenance or a patch's account of where it came from.
     */
    val version: String?,
    /**
     * what this game's own settings are filed under. see [Game.configKey].
     *
     * **carried rather than asked for**, because the scan that built this row already parsed the file
     * it comes from: a screen that wanted it later would open `param.json` a second time, and for a
     * game inside a granted tree that is a provider round trip to learn what is already in hand.
     */
    val configKey: String,
    /**
     * what the *emulator* calls this game -- the name of its save data and pipeline cache directories.
     * see [Game.emulatorTitleId], which is the rule this is resolved by.
     *
     * **it is [configKey]'s answer for every dump that names a title id, and a different one for a
     * dump that does not.** such a dump is filed under [UNKNOWN_TITLE_ID] by the emulator and under
     * its directory name by us, deliberately -- so a screen reaching for save data on disk wants this
     * one and a screen reaching for settings wants the other. [sharesSaveDirectory] is how a screen
     * asks which case it is in.
     *
     * carried for [configKey]'s reason: the scan that resolved one resolved both.
     */
    val emulatorTitleId: String,
) {

    /**
     * whether this game's save data sits in a directory it does not have to itself.
     *
     * **a dump naming no title id resolves to [UNKNOWN_TITLE_ID], and so does every other one**, so
     * what is under that directory belongs to no single game. a screen that offered to export or
     * delete "this game's" saves there would be naming one game and acting on several.
     */
    val sharesSaveDirectory: Boolean get() = emulatorTitleId == UNKNOWN_TITLE_ID


    /** the directory name, e.g. `Dreaming Sarah [PPSA02929]`. what [MainActivity] takes as `game`. */
    val folder: String get() = source.folder

    /** the dump's artwork for coil, or null. a real PNG, not the `.dds` beside it. */
    val icon: Any? get() = source.icon

    /** where this game's `eboot.bin` is. see [GameSource.ebootPath]. */
    val ebootPath: String get() = source.ebootPath

    companion object {

        const val EBOOT = "eboot.bin"

        const val PARAM = "sce_sys/param.json"

        /** where the emulator looks second. see [GameSource.openParam]. */
        const val PARAM_BESIDE_EBOOT = "param.json"

        const val ICON = "sce_sys/icon0.png"

        /**
         * a cap on `param.json`, beyond which it is not read at all.
         *
         * both dumps checked are a few kilobytes. this is not a real format limit -- it is a refusal
         * to pull an arbitrarily large file into memory on the strength of its path, since the
         * directory it comes from is one anybody can write with `adb push` or hand us with a grant.
         *
         * **it is enforced on the stream rather than on a reported length**, which is what lets one
         * reader serve both sources: a staged file could be measured with `length()` first, and a
         * document could not without a second provider round trip to learn what reading it says
         * anyway.
         */
        private const val PARAM_MAX_BYTES = 1 * 1024 * 1024

        private const val TAG = "sharpdroid"

        /**
         * one directory's identity, whichever kind of directory it is.
         *
         * **every part of this degrades to the directory name rather than failing.** a dump with no
         * `sce_sys/`, a truncated `param.json`, a `param.json` that is not JSON at all -- each of
         * those is a game that boots perfectly well, so none of them may cost a row.
         */
        @JvmStatic
        fun read(source: GameSource): Game {
            val param = readParam(source)
            // **the strict read of the same field, and deliberately not the `titleId` line below.**
            // that one is the list's answer and falls back to the `[PPSA…]` in a directory name,
            // which is a staging convention of ours; this one has to be what the emulator will
            // resolve, so it counts the field only when it is a JSON string, exactly as
            // [emulatorTitleId] does -- and it is resolved once here because both of the identities
            // this row carries are built out of it.
            val resolved = sanitizeTitleId(param?.opt("titleId") as? String)
            return Game(
                source = source,
                name = param?.let(::titleName) ?: source.folder,
                titleId = param?.optString("titleId")?.takeIf { it.isNotBlank() }
                    ?: titleIdFromFolder(source.folder),
                version = param?.optString("contentVersion")?.takeIf { it.isNotBlank() },
                configKey = configKeyFor(resolved, source.folder),
                emulatorTitleId = resolved,
            )
        }

        /**
         * **the title id the emulator will resolve for this dump, sanitized the way it sanitizes
         * one.** never null: a dump that offers none resolves to `UNKNOWN`, which is the emulator's
         * own answer rather than a placeholder of ours.
         *
         * this exists so the launcher can name a per-title directory the emulator would otherwise
         * have named itself -- the pipeline cache, whose environment variable takes the blob's path
         * rather than a root to hang a layout under. **so the rule has to be the emulator's rule and
         * not a plausible imitation**, and every part of it is matched deliberately: the field is
         * `titleId` and it counts only when it is a JSON *string*, `param.json` is looked for under
         * `sce_sys/` and then beside the eboot, and each character survives only if it is an ASCII
         * letter, digit, `-` or `_`, uppercased, with everything else becoming `_`.
         *
         * **a disagreement here is silent.** it would not break a run: the cache is validated by the
         * driver and rebuilt when it is rejected. it would file one game's pipelines under a name
         * nothing else uses, so a launch would quietly recompile what it had already compiled, and
         * the directory would sit beside a save data directory named the other way. [titleId] is the
         * *list's* answer to the same question and is deliberately not this one -- it falls back to
         * the `[PPSA…]` in a directory name, which is a staging convention of ours that the emulator
         * knows nothing about.
         */
        @JvmStatic
        fun emulatorTitleId(param: InputStream?, folder: String): String {
            val raw = try {
                param?.use { stream ->
                    readCapped(stream, folder)?.let { JSONObject(it) }?.opt("titleId") as? String
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "[app] could not read " + PARAM + " of " + folder + ": " + e)
                null
            }
            return sanitizeTitleId(raw)
        }

        /**
         * **what this game's settings are filed under**, and the same string the emulator files its
         * save data and its pipeline cache under -- so one game is one name across all three rather
         * than three spellings of one idea.
         *
         * **a dump that offers no title id is filed under its directory name instead.**
         * [emulatorTitleId] answers [UNKNOWN_TITLE_ID] for those, which is the right answer to *what
         * will the emulator call this* and the wrong one to key a configuration with: every such dump
         * would share one store, so a setting made for one game would appear on another. the name is
         * sanitized the same way, so a store's identity is always the same shape.
         *
         * **the consequence is that for those dumps this key and the emulator's own directory name
         * differ**, and that is deliberate: the collision is the emulator's to have, and copying it
         * here would spread it to something that does not need it.
         *
         * **it takes an id that has already been resolved rather than a stream**, so that a caller
         * needing both this and the emulator's own name -- which is every caller that launches a game
         * -- opens `param.json` once. for a game inside a granted tree that file is a provider round
         * trip, and doing it twice per launch to answer one question would be paying twice.
         */
        @JvmStatic
        fun configKeyFor(resolvedTitleId: String, folder: String): String =
            if (resolvedTitleId == UNKNOWN_TITLE_ID) sanitizeTitleId(folder) else resolvedTitleId

        /** what [emulatorTitleId] answers for a dump that names no title id. the emulator's own. */
        const val UNKNOWN_TITLE_ID = "UNKNOWN"

        private fun sanitizeTitleId(raw: String?): String {
            val trimmed = raw?.trim()
            if (trimmed.isNullOrEmpty()) {
                return UNKNOWN_TITLE_ID
            }
            val out = StringBuilder(trimmed.length)
            for (character in trimmed) {
                val kept = character in 'A'..'Z' || character in 'a'..'z' ||
                    character in '0'..'9' || character == '-' || character == '_'
                out.append(if (kept) character.uppercaseChar() else '_')
            }
            return out.toString()
        }

        private fun readParam(source: GameSource): JSONObject? =
            try {
                source.openParam()?.use { JSONObject(readCapped(it, source.folder) ?: return null) }
            } catch (e: Exception) {
                // and the row falls back to the directory name. logged rather than swallowed,
                // because a dump the emulator boots and the list cannot name is worth seeing once.
                AppLog.w(TAG, "[app] could not read " + PARAM + " of " + source.folder + ": " + e)
                null
            }

        /** the whole stream as text, or null if it turned out to be larger than [PARAM_MAX_BYTES]. */
        private fun readCapped(stream: InputStream, folder: String): String? {
            // one byte past the cap, so "exactly at the cap" and "over it" are distinguishable
            // without asking anything how long it is.
            val raw = stream.readAtMost(PARAM_MAX_BYTES + 1)
            if (raw.size > PARAM_MAX_BYTES) {
                AppLog.w(TAG, "[app] ignoring oversized " + PARAM + " of " + folder)
                return null
            }
            return String(raw, Charsets.UTF_8)
        }

        /** at most [limit] bytes, which the standard `readBytes` has no form of. */
        private fun InputStream.readAtMost(limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (out.size() < limit) {
                val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (n <= 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        /**
         * the display name, in the language the device is set to if the dump has it.
         *
         * `localizedParameters` holds one object per language tag -- `en-US`, `ja-JP` -- alongside a
         * plain `defaultLanguage` string, which is why the entries are filtered to objects before
         * anything looks at them.
         *
         * the order is: the exact tag, then any entry in the same language whatever its region, then
         * the dump's own default, then simply the first name present. the last step is what stops a
         * dump localised into languages this device is not set to from showing a directory name.
         */
        private fun titleName(param: JSONObject): String? {
            val localized = param.optJSONObject("localizedParameters") ?: return null
            val entries = localized.keys().asSequence()
                .mapNotNull { key -> localized.optJSONObject(key)?.let { key to it } }
                .toList()
            if (entries.isEmpty()) return null

            val locale = Locale.getDefault()
            val wanted = buildList {
                add(locale.toLanguageTag())
                if (locale.language.isNotEmpty()) add(locale.language)
                localized.optString("defaultLanguage").takeIf { it.isNotBlank() }?.let { add(it) }
            }

            for (tag in wanted) {
                val exact = entries.firstOrNull { it.first.equals(tag, ignoreCase = true) }
                exact?.second?.localTitleName()?.let { return it }

                val language = tag.substringBefore('-')
                val loose = entries.firstOrNull {
                    it.first.substringBefore('-').equals(language, ignoreCase = true)
                }
                loose?.second?.localTitleName()?.let { return it }
            }

            return entries.firstNotNullOfOrNull { it.second.localTitleName() }
        }

        private fun JSONObject.localTitleName(): String? =
            optString("titleName").takeIf { it.isNotBlank() }

        /**
         * the title id out of a directory named `Dreaming Sarah [PPSA02929]`.
         *
         * the staging convention rather than the format: this is only reached when the dump did not
         * say, and a directory somebody named that way is telling us something the file did not.
         */
        private fun titleIdFromFolder(folder: String): String? =
            Regex("""\[([A-Za-z0-9-]+)]\s*$""").find(folder)?.groupValues?.get(1)
    }
}
