package com.mircowuffwuff.sharpdroid

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.InputStream

/**
 * where a game's files are, and the two ways that can be answered.
 *
 * **[Game] was built on `java.io.File` and a game inside a granted tree has none.** a directory the
 * user granted is not a path -- it is a tree, and everything under it is a document reached through a
 * content provider -- so the identity a row shows has to come from `DocumentsContract` instead of
 * from the filesystem. this is that difference, and it is the only place in the app that knows about
 * it: one scan, one adapter, one row.
 *
 * it is deliberately narrow. a source answers **what the directory is called**, **what to hand coil
 * for the artwork**, **how to open `param.json`** and **where it sits on a volume**, and nothing else
 * -- the guest never reads a file through here. a granted game's files reach the guest through [GuestFiles], on the other side of the
 * JNI boundary, and `docs/guest-files.md` describes that path.
 *
 * @see GameLibrary for where both kinds are enumerated.
 */
sealed class GameSource {

    /** the directory's own name, e.g. `Dreaming Sarah`. what the launch intent carries. */
    abstract val folder: String

    /**
     * what coil is handed for `sce_sys/icon0.png` -- a [File], a `content://` [Uri], or null.
     *
     * Coil loads either kind natively, so a granted dump's artwork needs no decoding of ours and no
     * copy. it is resolved when the game is scanned rather than when a row binds, because a staged
     * source stats the file to answer and a bind happens on the main thread.
     */
    abstract val icon: Any?

    /**
     * `param.json`, or null when there is none. the caller closes it.
     *
     * **under `sce_sys/` first and then beside the eboot**, which is the emulator's own search order
     * -- it looks in both, so a dump laid out the second way boots with an identity that a scan
     * looking only in the first would report as missing.
     */
    abstract fun openParam(): InputStream?

    /**
     * where this game's `eboot.bin` is, for a screen to show.
     *
     * **it is where the file is, not how this app reaches it**, and for a granted game those are
     * different answers: the guest reads that one through a content provider and never opens a path
     * at all. what a person wants from a screen naming a location is the place they could go looking.
     *
     * **always an answer, and a volume path wherever there is one to give.** where there is not, it
     * is the provider's own -- which names the same file in the only terms that provider has, and is
     * a good deal more use than an absent row.
     */
    abstract val ebootPath: String

    /** a game staged into the app's own external files by `scripts/stage.py`. */
    class Staged(val directory: File) : GameSource() {

        override val folder: String get() = directory.name

        override val icon: Any? = File(directory, Game.ICON).takeIf { it.isFile }

        override fun openParam(): InputStream? =
            (File(directory, Game.PARAM).takeIf { it.isFile }
                ?: File(directory, Game.PARAM_BESIDE_EBOOT).takeIf { it.isFile })
                ?.inputStream()

        override val ebootPath: String get() = File(directory, Game.EBOOT).absolutePath
    }

    /**
     * a game inside a tree the user granted, addressed as a document.
     *
     * [tree] travels with it because the app can hold more than one grant, and the launch intent has
     * to name which one. a launch that names none takes whichever persisted permission comes first,
     * which is exact with one granted library and a coin toss with two.
     *
     * the resolver is the application's, so a source outliving the screen that produced it is not a
     * leaked activity.
     */
    class Granted(
        val tree: Uri,
        /** the game directory's document id, from the cursor that listed it -- never guessed. */
        val documentId: String,
        override val folder: String,
        private val resolver: ContentResolver,
    ) : GameSource() {

        // no query, deliberately: an absent icon resolves to the same placeholder a failed decode
        // does, so checking first would be a provider round trip per game to learn nothing the
        // drawing does not already handle.
        override val icon: Any = TreeDocument.uri(tree, TreeDocument.childId(documentId, Game.ICON))

        override fun openParam(): InputStream? =
            open(Game.PARAM) ?: open(Game.PARAM_BESIDE_EBOOT)

        /**
         * **derived from the document id, which already carries the path.** the platform's storage
         * provider issues ids of `<volume>:<path from the volume root>`, and a child's is its
         * parent's plus `/name` -- so the id of a game inside a granted tree spells out where it is,
         * wherever the user keeps it. nothing is looked up and nothing needs a permission.
         *
         * **only that provider's ids mean that, though.** another one's mean whatever it decided, and
         * enough of them contain a colon that reading one this way would produce a path that looks
         * right and is not. a caller that goes on to open what it derived would find out; a screen
         * that prints it would not.
         *
         * **so the fallback is the provider's own path**, which is what the document uri says with
         * its encoding taken off -- `/tree/<the granted directory>/document/<this file>`. it is not a
         * place on a volume and does not pretend to be one, and it still names the file exactly.
         */
        override val ebootPath: String
            get() {
                val eboot = TreeDocument.childId(documentId, Game.EBOOT)
                if (TreeDocument.isOnAVolume(tree)) {
                    TreeDocument.path(eboot)?.let { return it.absolutePath }
                }
                val uri = TreeDocument.uri(tree, eboot)
                return uri.path ?: uri.toString()
            }

        private fun open(relative: String): InputStream? =
            try {
                resolver.openInputStream(
                    TreeDocument.uri(tree, TreeDocument.childId(documentId, relative))
                )
            } catch (e: Exception) {
                // absent is ordinary -- a dump with no sce_sys/ is a game that boots perfectly well --
                // and the provider answers that by throwing. Game.read logs what it could not read.
                null
            }
    }
}
