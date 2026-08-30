# starting a game from another app

**this is the integration contract for emulation frontends** — the launchers that present a handheld's library as one console-like home screen and hand each title to whichever emulator runs it. it is also what a script uses, and deliberately so: there is one way in, and the tooling in this repository takes it, so it cannot rot without a run noticing.

everything here is stable surface. the rest of [`app.md`](app.md) describes the app; this page describes only what another application needs.

## the component

```
com.mircowuffwuff.sharpdroid/com.mircowuffwuff.sharpdroid.MainActivity
```

named explicitly. **there is no intent filter and there is not meant to be one**: nothing should resolve this activity implicitly, and a frontend's per-emulator entry carries a component name anyway. the application id above is the release identity; a development install is `com.mircowuffwuff.sharpdroid.debug`.

it is a `singleTask`-free, landscape activity in a process of its own, given to one run and ended with it — so when the game exits, the task unwinds and the person is back in the app they started from.

## naming the game

a PS5 dump is a **directory**: `eboot.bin` beside `sce_sys/`, `sce_module/` and the game's data. that is the unit, and it is what makes this different from an emulator that loads a single ROM file.

two forms. **send whichever you have; send both if you have both, and the uri is the one that will be used.**

| | |
| --- | --- |
| **a tree uri**, on the intent's own data | `Intent.setData(uri)`, with `FLAG_GRANT_READ_URI_PERMISSION` |
| **an absolute path**, in an extra | `--es game /storage/emulated/0/…` |

**either may name the dump's directory or the `eboot.bin` inside it.** a frontend's database usually points at the file it scanned during a sweep, so going up one level is done here rather than asked of you.

### the uri form

it must be a **tree** uri — one from `ACTION_OPEN_DOCUMENT_TREE`, or a document uri built on top of a tree grant with `DocumentsContract.buildDocumentUriUsingTree`. both shapes work:

```
content://com.android.externalstorage.documents/tree/primary%3Aroms%2Fps5
content://com.android.externalstorage.documents/tree/primary%3Aroms%2Fps5/document/primary%3Aroms%2Fps5%2FA%20Game%20%5BPPSA00000%5D
```

**a uri from `ACTION_OPEN_DOCUMENT` is refused, in words, on the screen.** such a uri grants access to exactly one document with no way to reach its siblings — so it can open the eboot and nothing else in the dump, not even `sce_sys/param.json`, which is where the identity that keys a game's own settings comes from. a launch that half-worked would be worse than one that says what it needs.

**attach the read grant.** the uri is used through whatever permission stands behind it, and the app does not ask who granted it: a transient grant on the intent and a folder the user gave this app long ago are the same thing here. a uri with no grant behind it is refused like any unreadable game.

**what the uri resolves to is a document only while that is the app's best way to the file.** where the person has given this app all-files access, the game is opened as an ordinary path instead — which is what a tap on the app's own list does with that permission held, and the two produce the same launch. nothing changes for you: send the uri either way.

a `file://` uri is treated as the path form below.

### the path form

`--es game` takes an absolute path, and has since before any of this. a frontend holding all-files access can simply send what its database holds — the directory, or the `eboot.bin` in it.

**a path this app cannot open is looked for inside a folder the user has already granted it**, and reached through that instead, before anything is refused. all-files access is an opt-in here that is off by default, so a path that is readable to you may not be readable to this app — and a game the user has already pointed the app at should not need a second permission to start.

## what a launch is configured with

**a game started this way runs exactly as it runs when its cover is tapped in the app**, per-game settings winning. you do not send configuration and there is nothing to keep in step.

the app merges four levels — a build's own environment, the app's settings, the game's own settings, then anything on the intent, last winning — and it finds the game's settings by reading the title id out of `sce_sys/param.json`, so the merge works for a game it has never seen in its own list. the loading screen reads that dump's display name and cover art from the same file, which is why there is nothing to send for those either.

**an extra you do not send contributes nothing.** every other extra in [`app.md`](app.md) stays available for a frontend that wants to override one, but a launch that names only a game is the launch a tap makes.

## when it will not start

a refusal is drawn on the loading screen the launch is already showing, with the reason and a Close button, and the run is held open until it is read. the reasons a frontend can cause: the game is not where the intent said, the uri names a single file rather than a folder, the uri has no grant behind it, or nothing on the intent named a game at all.

**nothing is returned to you.** the person reads it and closes it, and the task unwinds to where they came from. `logcat -s sharpdroid` carries the same sentence with the uri, document id or path beside it.

## a worked launch

```bash
adb shell am start -n com.mircowuffwuff.sharpdroid/com.mircowuffwuff.sharpdroid.MainActivity --es game "/storage/emulated/0/roms/ps5/A Game [PPSA00000]"
```

the tooling here drives the same door: `py scripts/run.py --game-uri <uri>` sends a uri on the intent's data, and `py scripts/run.py --game <path>` sends the extra. [`scripts.md`](scripts.md) has both.

**`adb shell` cannot forge the uri form's grant.** the shell holds no permission on a content uri, so asking it to pass one on is refused by the platform before the activity is resolved; `--game-uri` therefore sends the uri bare, and the app resolves it against the folders it has been granted. a real frontend holds the grant and attaches it, which is the case that flag cannot rehearse.
