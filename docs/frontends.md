# starting a game from another app

**this is the integration contract for emulation frontends** — the launchers that present a handheld's library as one console-like home screen and hand each title to whichever emulator runs it. it is also what a script uses, and deliberately so: there is one way in, and the tooling in this repository takes it, so it cannot rot without a run noticing.

everything here is stable surface. the rest of [`app.md`](app.md) describes the app; this page describes only what another application needs.

## the component

```
com.mircowuffwuff.sharpdroid/com.mircowuffwuff.sharpdroid.MainActivity
```

named explicitly. **there is no intent filter and there is not meant to be one**: nothing should resolve this activity implicitly, and a frontend's per-emulator entry carries a component name anyway. the application id above is the release identity; a development install is `com.mircowuffwuff.sharpdroid.debug`.

it is a landscape activity in a process of its own, given to one run and ended with it — so when the game exits, the task unwinds and the person is back in the app they started from.

## a game is a directory

`eboot.bin` beside `sce_sys/`, `sce_module/` and the game's data. **that is the unit**, and it is what makes this unlike an emulator whose games are single ROM files, where handing one file over with a uri permission on the intent is both natural and sufficient.

it is not sufficient here, and the reason decides everything below:

> **a uri permission attached to an intent covers the document it names and not the documents inside it.** the receiving app is granted the game's directory and refused its `eboot.bin`. `FLAG_GRANT_PREFIX_URI_PERMISSION` does not widen it — the whole document id is a single path segment. `Context.grantUriPermission` on the tree does not either, because a grant taken with `takePersistableUriPermission` carries no right to re-grant. and a tree uri cannot be minted for a folder the sender holds no tree grant on.

so **attaching a grant is not what makes a launch work**, and no combination of flags on your side changes that.

## the one thing the person has to have done

**sharpdroid has to have been given the folder the games are in** — Settings → Game files → Game folders, once, with the platform's own folder picker.

that is a precondition on **both** forms below, not a property of either, and it is not a workaround: this app reaches a game library through a folder grant by default, holds no storage permission, and asks for none. anyone running PS5 titles registers their library early anyway, since settings and JIT options are kept per title and the app has to be able to see a game to offer them.

**where the person has additionally given this app all-files access, that requirement lifts** and any readable path works. treat it as a bonus that some installs have rather than something to design for: it is off by default, it is a permission this app deliberately does not need, and a frontend that only works with it held will not work for most people.

## via a uri

**the form to prefer.** it is the more general of the two: a document id that does not correspond to a filesystem path at all — a card behind another provider, say — resolves through the grant, where nothing could turn it into a path.

put it on the intent's data:

```java
intent.setData(uri);
```

it must be a **tree** uri: one from `ACTION_OPEN_DOCUMENT_TREE`, or a document uri built on a tree grant with `DocumentsContract.buildDocumentUriUsingTree`. both shapes work, and either may name the dump's directory or the `eboot.bin` inside it — a scan usually records the file it found, so going up one level is done here rather than asked of you.

```
content://com.android.externalstorage.documents/tree/primary%3Aroms%2Fps5
content://com.android.externalstorage.documents/tree/primary%3Aroms%2Fps5/document/primary%3Aroms%2Fps5%2FDreaming%20Sarah
```

**a uri from `ACTION_OPEN_DOCUMENT` is refused, in words, on the screen** — it names one document, and a dump is a directory.

attach `FLAG_GRANT_READ_URI_PERMISSION` if you like; it is harmless and it is not what makes this work. what makes it work is the grant *this* app holds over the same folder.

## via a path

**an absolute path in an extra**, which is what a launcher that scanned the filesystem already has:

```
--es game /storage/emulated/0/roms/ps5/Dreaming Sarah
```

the directory or the `eboot.bin` inside it, as above.

a path this app cannot open directly is **matched against the folders it has been given** and reached through the grant instead, so the same precondition serves this form too. what it cannot do is reach a game in a folder nobody registered.

## what a launch is configured with

**a game started this way runs exactly as it runs when its cover is tapped in the app**, per-game settings winning. you do not send configuration and there is nothing to keep in step. a tap and an intent produce byte-identical argument vectors, measured with all-files access held and without it.

the app merges four levels — a build's own environment, the app's settings, the game's own settings, then anything on the intent, last winning — and it finds the game's settings by reading the title id out of `sce_sys/param.json`, so the merge works for a game it has never seen in its own list. the loading screen reads that dump's display name and cover art from the same file, which is why there is nothing to send for those either.

**an extra you do not send contributes nothing.** every other extra in [`app.md`](app.md) stays available for a frontend that wants to override one, but a launch that names only a game is the launch a tap makes.

## when it will not start

a refusal is drawn on the loading screen the launch is already showing, with the reason and a Close button, and the run is held open until it is read. the reasons a frontend can cause: the game is not where the intent said, the uri names a single file rather than a folder, the game is in a folder this app has not been given, or nothing on the intent named a game at all.

**nothing is returned to you.** the person reads it and closes it, and the task unwinds to where they came from. `logcat -s sharpdroid` carries the same sentence with the uri, document id or path beside it.

## a worked launch

```bash
adb shell am start -n com.mircowuffwuff.sharpdroid/com.mircowuffwuff.sharpdroid.MainActivity --es game "/storage/emulated/0/roms/ps5/Dreaming Sarah"
```

the tooling here drives the same door: `py scripts/run.py --game-uri <uri>` sends a uri on the intent's data and `py scripts/run.py --game <path>` sends the extra. [`scripts.md`](scripts.md) has both.

**a shell cannot rehearse the uri form the way an application uses it.** `adb shell` holds no permission on a content uri, so asking the platform to pass one on fails the launch outright; `--game-uri` sends the uri bare and relies on the grant this app holds. that is what an application's launch relies on too — but the two are not the same path through the platform, and only an application exercises the permission it attaches.
