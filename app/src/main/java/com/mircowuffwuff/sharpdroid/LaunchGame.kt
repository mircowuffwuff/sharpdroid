package com.mircowuffwuff.sharpdroid

import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * | `--es game` | a directory under the app's own `games/`, or an absolute path to one anywhere |
 * | `--es safgame` with `--es saftree` | a directory inside a folder the user granted this app. what the game list sends for one |
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
        val safGame = intent.getStringExtra("safgame")
        if (!safGame.isNullOrEmpty()) {
            return granted(context, safGame, intent.getStringExtra("saftree"))
        }
        val named = intent.getStringExtra("game")
        val name = if (named.isNullOrEmpty()) DEFAULT else named
        // **a leading slash is the whole distinction and it cannot be ambiguous** -- a directory name
        // under games/ does not begin with one. a path is what the game list sends for a game in a
        // granted folder while all-files access is held, and what a script sends to reach a library
        // outside the app's own directory.
        if (name.startsWith("/")) {
            return Resolved.Found(GameSource.Staged(File(name)))
        }
        if (staged == null) {
            return Resolved.Refused(context.getString(R.string.launch_no_storage))
        }
        return Resolved.Found(GameSource.Staged(File(staged, name)))
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
