package com.mircowuffwuff.sharpdroid

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * which game a launch named, resolved once.
 *
 * **[MainActivity] used to carry this as three strings and re-derive the answer from them in five
 * places** -- `gameName`, `safGameName` and `safTreeUri`, with `startsWith("/")` deciding between a
 * staged directory and an absolute path wherever the question came up again. the app already had the
 * type that question has an answer in: [GameSource] is a staged directory or a document inside a
 * granted tree, and it is what the game list has always scanned into. so a launch resolves to one of
 * those, here, and everything downstream asks the source instead of reading the strings again.
 *
 * **the extras are the wire format and they stay exactly what they were.** what an `am start` sends
 * is unchanged, and so is what the game list sends -- see [GameLaunch]. this is the one place that
 * reads them.
 *
 * a launch that cannot name a game is a [Resolved.Refused] carrying the sentence a person is shown,
 * rather than a null each caller invents a message for. **that is the whole reason this returns a
 * type rather than a nullable source**: every way of failing here has something specific to say, and
 * the one thing none of them may do is fail quietly.
 *
 * ### the ways a game can be named
 *
 * | | |
 * | --- | --- |
 * | `intent.data` | a tree uri naming the dump's directory or its `eboot.bin`. what an app outside this one sends |
 * | `--es game` | a directory under the app's own `games/`, or an absolute path to one anywhere |
 * | `--es safgame` with `--es saftree` | a directory inside a folder the user granted this app. what the game list sends for one |
 *
 * **the uri is tested first and the reason is not precedence but capability.** an emulation frontend
 * has the intent and nothing else: it cannot know what this app has staged, and it does not hold this
 * app's grants. the game list never sets `intent.data`, so nothing of ours is displaced by looking
 * there first.
 */
object LaunchGame {

    private const val TAG = "sharpdroid"

    /** what a launch resolved to. */
    sealed class Resolved {

        /** the game to run. */
        class Found(val source: GameSource) : Resolved()

        /**
         * nothing runs, and [why] is what a person is shown -- see `MainActivity.abort`.
         *
         * it is a whole sentence rather than a code, because the only thing that consumes it is a
         * person reading it on the card the loading screen draws.
         */
        class Refused(val why: String) : Resolved()
    }

    /** what [intent] named, against the app's own `games/` directory at [staged]. */
    @JvmStatic
    fun of(context: Context, intent: Intent, staged: File?): Resolved {
        intent.data?.let { return fromUri(context, it) }
        val safGame = intent.getStringExtra("safgame")
        if (!safGame.isNullOrEmpty()) {
            return granted(context, safGame, intent.getStringExtra("saftree"))
        }
        val named = intent.getStringExtra("game")
        val name = if (named.isNullOrEmpty()) DEFAULT else named
        // **a leading slash is the whole distinction and it cannot be ambiguous** -- a directory name
        // under games/ does not begin with one. a path is what the game list sends for a game in a
        // granted folder while all-files access is held, what a script sends to reach a library
        // outside the app's own directory, and what a frontend holding all-files access sends.
        if (name.startsWith("/")) {
            return fromPath(context, File(name))
        }
        if (staged == null) {
            return Resolved.Refused(context.getString(R.string.launch_no_storage))
        }
        return Resolved.Found(GameSource.Staged(File(staged, name)))
    }

    /**
     * a game named by a uri on the intent, which is how an emulation frontend names one.
     *
     * **it has to be a tree uri, and that is a property of the dumps rather than a preference.** a
     * PS5 dump is a directory -- an eboot beside `sce_sys/`, `sce_module/` and everything else the
     * guest opens -- and a uri from `ACTION_OPEN_DOCUMENT` is good for exactly one document, with no
     * way to reach its siblings. only a tree grant can be walked, so only a tree grant can boot a
     * game, and a single document is refused in words rather than half-working.
     *
     * **either end of the dump may be named**: the directory, or the `eboot.bin` inside it. a
     * frontend's database usually points at the file it scanned, and going up one level costs
     * nothing where guessing which was meant would.
     *
     * **who granted the uri is deliberately not asked.** it may be a transient grant the frontend
     * attached to the intent, or a folder the user gave this app long ago -- the question that
     * decides a launch is whether the dump can be read through it, and that is the question asked.
     *
     * **what the uri resolves to is a document only while the app has no better way to the file.**
     * with all-files access held it becomes an ordinary path, exactly as it does for a game tapped on
     * the list -- [AllFiles] decides, per launch, and both callers ask it the same question.
     */
    private fun fromUri(context: Context, uri: Uri): Resolved {
        // a frontend holding all-files access may send an ordinary path with a scheme on it. that is
        // the path form wearing a uri, so the path form answers it.
        if (ContentResolver.SCHEME_FILE == uri.scheme) {
            return fromPath(context, File(uri.path ?: ""))
        }
        if (!DocumentsContract.isTreeUri(uri)) {
            AppLog.e(TAG, "[app] " + uri + " is not a tree uri, so nothing but the one document it"
                + " names can be read through it -- and a dump is a directory")
            return Resolved.Refused(context.getString(R.string.launch_needs_folder))
        }
        val resolver = context.applicationContext.contentResolver
        val named =
            if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri)
            else DocumentsContract.getTreeDocumentId(uri)
        val directory = directoryOf(resolver, uri, named)
        if (directory == null) {
            AppLog.e(TAG, "[app] no " + Game.EBOOT + " under " + named + ", and it is not an "
                + Game.EBOOT + " either")
            return Resolved.Refused(
                context.getString(R.string.launch_game_missing, named.substringAfterLast('/'))
            )
        }
        // **with all-files access held this is an ordinary path, which is the branch a tap on the
        // list already takes** -- see [GameLaunch]. it is the same call there and here, so one game
        // reaches the guest the same way whichever door it came in by, and the permission means one
        // thing rather than one thing per caller.
        AllFiles.pathTo(directory)?.let {
            AppLog.i(TAG, "[app] the intent named " + directory + ", opened as " + it
                + " with all-files access")
            return Resolved.Found(GameSource.Staged(it))
        }
        AppLog.i(TAG, "[app] the intent named " + directory + ", reached through the grant it"
            + " arrived with")
        return Resolved.Found(
            GameSource.Granted(uri, directory, directory.substringAfterLast('/'), resolver)
        )
    }

    /**
     * which of [named] and its parent is the game's directory, or null when neither is.
     *
     * the test is the one the library's scan applies and the one the mount applies: an `eboot.bin`
     * is there. **asked of the document rather than read off its name**, so a directory that happens
     * to be called `eboot.bin` and a dump whose eboot is missing are told apart by the answer rather
     * than by the spelling.
     */
    private fun directoryOf(resolver: ContentResolver, tree: Uri, named: String): String? {
        if (exists(resolver, tree, TreeDocument.childId(named, Game.EBOOT))) {
            return named
        }
        if (!named.endsWith("/" + Game.EBOOT)) {
            return null
        }
        val parent = named.substring(0, named.length - Game.EBOOT.length - 1)
        return if (exists(resolver, tree, TreeDocument.childId(parent, Game.EBOOT))) parent else null
    }

    /** whether a document is there, asked with a query rather than an open. */
    private fun exists(resolver: ContentResolver, tree: Uri, documentId: String): Boolean =
        try {
            resolver.query(
                TreeDocument.uri(tree, documentId),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null,
            )?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            // a provider answers a document that is not there by throwing about as often as by
            // returning nothing, and a grant that has gone answers the same way. absent is absent.
            false
        }

    /**
     * a game named by an absolute path.
     *
     * **three answers in order, and the middle one is the point.** a path this app can stat is opened
     * as a path, which is what a staged game and an all-files launch have always done. a path it
     * cannot stat is looked for inside a folder the user has already granted -- because all-files
     * access is an opt-in that is off by default, and a frontend holding it will hand over a path
     * this app cannot read a byte of. reaching it through the grant needs no permission at all.
     *
     * **and when neither answers, the path is handed back anyway.** saying a game is not there is
     * `MainActivity.openGame`'s job, so that it is said in one sentence from one place however the
     * game was named.
     */
    private fun fromPath(context: Context, path: File): Resolved {
        // the directory or the eboot inside it, the way the uri form takes either.
        val directory = if (path.name == Game.EBOOT) path.parentFile ?: path else path
        if (File(directory, Game.EBOOT).isFile) {
            return Resolved.Found(GameSource.Staged(directory))
        }
        grantFor(context, directory)?.let {
            AppLog.i(TAG, "[app] " + directory + " is not readable as a path, and it is inside the"
                + " granted folder " + it.tree + " -- reaching it through that instead")
            return Resolved.Found(it)
        }
        return Resolved.Found(GameSource.Staged(directory))
    }

    /**
     * [directory] as a document inside a folder this app holds a grant on, or null if it is in none.
     *
     * **this is [TreeDocument.path] run backwards**, and it rests on the same assumption: the
     * platform's own storage provider issues document ids of `<volume>:<path from the volume root>`.
     * a provider whose ids do not mean that is skipped rather than guessed at, which is what
     * [TreeDocument.isOnAVolume] is for.
     *
     * **each candidate is confirmed by looking for the eboot through it**, rather than by the path
     * arithmetic alone. two granted folders can both contain a path that spells correctly, and the
     * one that answers is the one with the game in it.
     */
    private fun grantFor(context: Context, directory: File): GameSource.Granted? {
        val resolver = context.applicationContext.contentResolver
        for (held in context.contentResolver.persistedUriPermissions) {
            if (!held.isReadPermission) continue
            val tree = held.uri
            if (!TreeDocument.isOnAVolume(tree)) continue
            val rootId = TreeDocument.rootId(tree)
            val root = TreeDocument.path(rootId) ?: continue
            val relative = under(root, directory) ?: continue
            val documentId = TreeDocument.childId(rootId, relative)
            if (!exists(resolver, tree, TreeDocument.childId(documentId, Game.EBOOT))) continue
            return GameSource.Granted(tree, documentId, directory.name, resolver)
        }
        return null
    }

    /** [directory] written relative to [root], or null when it is not inside it. */
    private fun under(root: File, directory: File): String? {
        val above = root.absolutePath
        val below = directory.absolutePath
        if (below == above) return ""
        if (!below.startsWith(above + "/")) return null
        return below.substring(above.length + 1)
    }

    /**
     * a game inside a tree the user granted, named by the pair of extras the game list sends.
     *
     * **the grant is looked up once**, where it used to be looked up twice -- once to resolve the
     * game's identity in `onCreate` and again to mount it several seconds later. a launch whose grant
     * is gone therefore says so before anything else happens, rather than after the driver has been
     * installed.
     */
    private fun granted(context: Context, directory: String, treeUri: String?): Resolved {
        val named = if (treeUri.isNullOrEmpty()) null else Uri.parse(treeUri)
        val tree = heldTree(context, named)
        if (tree == null) {
            // **the uri goes in the log and never in the sentence.** it is what tells the two cases
            // apart when reading a run, and it is meaningless to somebody who has just tapped a game
            // in another app.
            AppLog.e(
                TAG,
                "[app] no read grant is held on " + (named?.toString() ?: "any folder") +
                    " -- it was revoked, the volume is not mounted, or no folder was ever added"
            )
            return Resolved.Refused(
                context.getString(
                    if (named != null) R.string.launch_grant_revoked else R.string.launch_no_grant
                )
            )
        }
        AppLog.i(
            TAG,
            "[app] the game is in the granted tree " + tree +
                if (named == null) " (the first one held, since the launch named none)" else ""
        )
        return Resolved.Found(
            GameSource.Granted(
                tree,
                TreeDocument.childId(TreeDocument.rootId(tree), directory),
                directory,
                context.applicationContext.contentResolver,
            )
        )
    }

    /**
     * the persisted grant this launch's game is in, or null when we hold none that fits.
     *
     * **absent means the first read grant this app holds**, which is what `am start --es safgame`
     * means from a script: exact with one granted library and a coin toss with two, and the list is
     * the only thing that knows which folder the user tapped.
     */
    private fun heldTree(context: Context, named: Uri?): Uri? {
        for (held in context.contentResolver.persistedUriPermissions) {
            if (!held.isReadPermission) continue
            if (named == null) return held.uri
            if (named == held.uri) return named
        }
        return null
    }

    /**
     * the game a launch that names none runs.
     *
     * it is here rather than in [MainActivity] because this is the only thing that reads the extra it
     * stands in for, and a default that lives away from the read is one nothing points at.
     */
    private const val DEFAULT = "Dreaming Sarah [PPSA02929]"
}
