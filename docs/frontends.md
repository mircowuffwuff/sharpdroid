# starting a game from another app

**this is the integration contract for emulation frontends** — the launchers that present a handheld's library as one console-like home screen and hand each title to whichever emulator runs it. it is also what a script uses, and deliberately so: there is one way in, and the tooling in this repository takes it, so it cannot rot without a run noticing.

everything here is stable surface. the rest of [`app.md`](app.md) describes the app; this page describes only what another application needs.

## the shortest version

**send an absolute path in `--es game`.** it works whatever the person has set up, and it is what a launcher holding all-files access already has in its database.

the uri form below is worth supporting too, and there is one condition on it that the rest of this page is mostly about.

## the component

```
com.mircowuffwuff.sharpdroid/com.mircowuffwuff.sharpdroid.MainActivity
```

named explicitly. **there is no intent filter and there is not meant to be one**: nothing should resolve this activity implicitly, and a frontend's per-emulator entry carries a component name anyway. the application id above is the release identity; a development install is `com.mircowuffwuff.sharpdroid.debug`.

it is a landscape activity in a process of its own, given to one run and ended with it — so when the game exits, the task unwinds and the person is back in the app they started from.

## a game is a directory, and that is the whole difference

`eboot.bin` beside `sce_sys/`, `sce_module/` and the game's data. **that is the unit**, and it is what makes this unlike an emulator that loads a single ROM file — where handing over one file, with a uri permission attached to the intent, is both natural and sufficient.

it is not sufficient here, and the reason is worth stating once so nobody rediscovers it:

> **a uri permission attached to an intent covers the document it names and not the documents inside it.** the receiving app is granted that one directory and refused its `eboot.bin`. `FLAG_GRANT_PREFIX_URI_PERMISSION` does not widen it — the whole document id is a single path segment. `Context.grantUriPermission` on the tree does not either, because a grant taken with `takePersistableUriPermission` does not carry the right to re-grant. and a tree uri cannot be minted for a folder the sender holds no tree grant on.

so **an attached grant is not what makes the uri form work**, and sending one costs nothing but does not buy what it looks like it buys.

## naming the game

two forms. **send whichever you have; send both if you have both, and the uri is the one that will be used.**

| | |
| --- | --- |
| **a path**, in an extra | `--es game /storage/emulated/0/…` |
| **a tree uri**, on the intent's own data | `Intent.setData(uri)` |

**either may name the dump's directory or the `eboot.bin` inside it.** a frontend's database usually points at the file it scanned during a sweep, so going up one level is done here rather than asked of you.

### the path form — the one that always works

`--es game` takes an absolute path. a launcher holding all-files access sends what its database holds and there is nothing else to arrange.

**a path this app cannot open is looked for inside a folder the person has already given it**, and reached through that, before anything is refused.

### the uri form — and its one condition

it must be a **tree** uri: one from `ACTION_OPEN_DOCUMENT_TREE`, or a document uri built on a tree grant with `DocumentsContract.buildDocumentUriUsingTree`. both shapes work:

```
content://com.android.externalstorage.documents/tree/primary%3Aroms%2Fps5
content://com.android.externalstorage.documents/tree/primary%3Aroms%2Fps5/document/primary%3Aroms%2Fps5%2FA%20Game%20%5BPPSA00000%5D
```

**a uri from `ACTION_OPEN_DOCUMENT` is refused, in words, on the screen** — it names one document, and a dump is a directory.

**the condition: this app has to be able to reach the folder itself.** one of these has to be true, and either is ordinary:

- **the person added that folder under Settings → Game files**, which is the usual setup for anyone running PS5 titles — per-game settings and JIT options are kept per title, so a library gets registered here early; or
- **the person granted this app all-files access**, in which case the uri is resolved to a path and opened directly.

where neither holds, the launch is refused and says so. that is not a failure you can fix from your side by attaching a grant — see the box above.

a `file://` uri is treated as the path form.

## what a launch is configured with

**a game started this way runs exactly as it runs when its cover is tapped in the app**, per-game settings winning. you do not send configuration and there is nothing to keep in step. a tap and an intent produce byte-identical argument vectors, measured with all-files access held and without it.

the app merges four levels — a build's own environment, the app's settings, the game's own settings, then anything on the intent, last winning — and it finds the game's settings by reading the title id out of `sce_sys/param.json`, so the merge works for a game it has never seen in its own list. the loading screen reads that dump's display name and cover art from the same file, which is why there is nothing to send for those either.

**an extra you do not send contributes nothing.** every other extra in [`app.md`](app.md) stays available for a frontend that wants to override one, but a launch that names only a game is the launch a tap makes.

## when it will not start

a refusal is drawn on the loading screen the launch is already showing, with the reason and a Close button, and the run is held open until it is read. the reasons a frontend can cause: the game is not where the intent said, the uri names a single file rather than a folder, the uri names a folder this app cannot reach, or nothing on the intent named a game at all.

**nothing is returned to you.** the person reads it and closes it, and the task unwinds to where they came from. `logcat -s sharpdroid` carries the same sentence with the uri, document id or path beside it.

## a worked launch

```bash
adb shell am start -n com.mircowuffwuff.sharpdroid/com.mircowuffwuff.sharpdroid.MainActivity --es game "/storage/emulated/0/roms/ps5/A Game [PPSA00000]"
```

the tooling here drives the same door: `py scripts/run.py --game <path>` sends the extra and `py scripts/run.py --game-uri <uri>` sends the uri. [`scripts.md`](scripts.md) has both.

**a shell cannot rehearse the uri form the way an application uses it.** `adb shell` holds no permission on a content uri, so asking the platform to pass one on fails the launch outright; `--game-uri` sends the uri bare and relies on what this app has been granted. that is the same thing an application's launch relies on, per the condition above — but the two are not the same code path in the platform, and only an application exercises the grant it attaches.
