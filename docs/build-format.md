# the build format

what a SharpEmu build is, what its `meta.json` says, and what a payload has to do to be launchable by this app.

**this is the document a third-party build has to satisfy.** the app refuses a build it does not recognise, and the refusal is the point: every failure this format exists to prevent is a silent or a misleading one. a pristine upstream build ignores `SHARPEMU_HOST_WINDOW`, constructs an SDL window and dies six seconds in on *"No available video device"*, which names the wrong component entirely.

`scripts/package-build.py` produces builds and [`scripts.md`](scripts.md) documents how to drive it. nothing here requires that script: everything below is the format itself, and a build assembled by hand that holds to it is a build.

## a build is a directory, not a file

a payload on its own is not a build. SharpEmu resolves `plugins/` **relative to its own executable** in two places — managed plugins in `SharpEmu.CLI`'s `Program.cs`, and `ffmpeg.RootPath` in `FfmpegNativeBinkFrameSource.cs` — so a payload staged alone is a payload with no audio and no video.

```
android-0.0.3-hotfix-2-20260808011145/
├── meta.json          the identity. read by the app, and by every staging script
├── SharpEmu           the payload: a linux-x64 ELF, executed as guest code
├── *.dll  *.so        the rest of the publish output
└── plugins/           managed plugins, and the ffmpeg tree the Bink decoder wants
```

**the host layer needs no part of this.** it takes a payload path and stays a thing that runs an ELF; `GuestProcFS::SetExe` is the `realpath` of the path it is handed, so `AppContext.BaseDirectory` follows the build directory by itself. name resolution, `meta.json` and the contract check are the launcher's job, in `app/src/main/java/.../SharpEmuBuild.java`, because that is where a build list needs them anyway and two implementations of one contract is one too many.

**the zip is the distribution format and the directory is what runs.** android's shell has no unzip worth relying on, so a zip is unpacked on a PC and pushed. `meta.json` sits at the **zip root**, not inside a wrapper directory — that is the single thing most likely to differ between two hand-made packages.

**and no archive is part of a build.** a zip inside the directory is those two nested the wrong way round, and it matters because everything that copies a build copies it whole — the APK bundling it carries it, a stage pushes it, and neither has any reason to look at what it is carrying, so the only symptom is a number nobody was reading. **a build directory containing one is refused by name**, which is the whole of the rule: no publish emits an archive, so there is nothing legitimate to weigh against it.

## `meta.json`

UTF-8, **no BOM**. `java.util.zip` and `org.json` both cope with one; every other tool that has to read this one day may not.

```json
{
  "id": "android",
  "name": "Android platform support",
  "sharpemuVersion": "0.0.3-hotfix-2",
  "packagedAt": 20260808011145,
  "hostContract": 2,
  "payload": "SharpEmu",
  "env": {},
  "notes": "SharpEmu expanded by Android platform support.",
  "author": "mircowuffwuff",
  "commit": "a1b2c3d",
  "source": "https://github.com/mircowuffwuff/sharpemu/tree/android"
}
```

| field | | absent means |
| --- | --- | --- |
| `id` | what this build *is*, stable across versions of it. the first-party ids are the fork's branch names, and the one that ships is `android` | the folder name |
| `name` | the display name a build list shows | the `id` |
| `sharpemuVersion` | the SharpEmu version the payload was built from, upstream's own string | `"0"` |
| `packagedAt` | when the build was packaged, `yyyyMMddHHmmss` **as a number**. the key everything is ordered by, since later is newer | `0` |
| `hostContract` | the launcher↔payload interface generation. see below | `0`, **which is refused** |
| `payload` | the executable's file name within the directory | `"SharpEmu"` |
| `env` | guest environment this build wants defaulted on. below everything but the app's own defaults, which it replaces | empty |
| `notes` | one line, printed at launch under the identity | empty |
| `author` | who produced this build. drawn on the build list beside the version | empty |
| `commit` | **provenance, not format.** the commit the payload was built from, with `-dirty.<8 hex>` after it when that checkout had uncommitted changes in it | — |
| `source` | **provenance, not format.** where the payload came from: the fork branch's own page, or the archive a build with no commit was made from | — |

the app reads the first eight, `author` and `commit`, and ignores `source`. they are recorded anyway, and deliberately: a build that renders differently from another has to be traceable to where it came from without a changelog. **`commit` is what tells two builds of one upstream version apart**, which is the common case rather than the rare one: `sharpemuVersion` is upstream's tag and a fork moves faster than upstream does. the build list shows it in place of the build number wherever there is one. it is **empty rather than absent** when a build was packaged from a published archive, and `source` says what it was instead — a build whose provenance is unknown should say so rather than leave a reader to notice a missing field. **`source` is the branch's own page** for a build cut from the fork, `https://<host>/<owner>/<project>/tree/<branch>`, built from the same `origin` remote the author comes from: the project alone would answer whose emulator it is and not which of their branches, and the branches are the whole point, one being the maintained tier and the rest archived at a commit and merged nowhere. it reads `unknown` where that remote will not parse.

**a build published from a checkout with uncommitted changes in it records `<hash>-dirty.<8 hex>`**, the marker the APK's own commit wears and a fingerprint of those changes after it. it is a suffix rather than a field beside it so that nothing has to be taught about it to carry it. the hash it sits behind is real, so the two are not interchangeable: the commit exists and the payload is not what is at it. **a release APK refuses to bundle such a build** — nothing can reconstruct it from a clone — while a development one says so and carries on, and the same is true of a build with no commit at all.

**the fingerprint is what makes an uncommitted state an identity rather than a word.** it covers the diff against the commit and every untracked file that is not ignored, so two builds published from two different sets of edits do not compare equal — which is the whole of what stops an APK being bundled with the payload from before the edit you are about to test. the app draws `<hash>-dirty` and leaves it off, because it is there for a comparison and a person reading a screen is not that comparison.

**`author` is who produced the build, and not who wrote the emulator.** `sharpemuVersion` and `commit` already say what the code is; this answers the question somebody holding two zips of one version actually has, which is whose zip each one is. it is drawn on the build list after the version, where a GPU driver card puts the same claim.

**and it is a claim rather than a fact, which is where it differs from `commit`.** a commit names something checkable against a repository; this is a string in a zip anybody can edit. so nothing reads it but the screen — no import rule consults it, nothing is trusted because of it, and a build with an author nobody recognises is refused for no reason it would not have been refused for anyway. `scripts/package-build.py --author` is how one is set, and it **defaults to the owner of the fork's `origin` remote** — the only place a checkout knows whose fork it is. `git config user.name` is who is sitting at the machine, which is a different claim and would credit them for somebody else's fork; the log is worse still, naming whoever wrote the last commit and so crediting an upstream contributor for a package they never made the moment the fork merges upstream. a remote that will not parse leaves it empty, which is a supported answer.

**`sharpemuVersion` orders by SharpEmu's own release order, which is not semver, and the app implements that order rather than assuming one.** the released versions run `0.0.1`, `0.0.2`, `0.0.2-beta.2` … `0.0.2-beta.5`, `0.0.3`, `0.0.3-hotfix-1`, `0.0.3-hotfix-2`, `0.0.3-release.2` — so **a bare version comes first and suffixed ones follow it**, where semver says a suffixed version precedes the bare one. the dotted numbers compare numerically, then a bare version sorts before any suffix of the same numbers, then suffix labels compare alphabetically and their own numbers numerically, so `hotfix-2` precedes `hotfix-10`. `Versions.java` is the rule and says all of this beside the code, because anybody reading it against semver will think it is inverted.

**a build list is ordered by that, then by `packagedAt` within one version**, and a build is called outdated when its `sharpemuVersion` is below the version the app ships inside itself. that comparison is never made on `packagedAt`: a timestamp compares nothing between two ids, and it is assignable, since a build can be repackaged carrying a given one.

**identity lives here and never in a filename.** the on-device folder name is *derived* from it, `<id>-<sharpemuVersion>-<packagedAt>` lowercased and restricted to `[a-z0-9._-]`, so two builds of the same source coexist and nothing ever has to guess which is which. both the packaging script and the staging script derive it the same way; neither uses what the directory or zip happens to be called on your disk.

**`packagedAt` is a time and not a version number.** a counter has to be bumped by whoever packages, which makes it wrong exactly when it matters — two packages of one source both claiming to be the first. **a packaging time assigns itself**, so there is nothing to forget, and "which of these two is newer" has an answer that needs no repository to check.

it is a **sortable integer** rather than an ISO string or an epoch second, deliberately: a person reading a directory listing can date it at a glance and a machine can compare it without parsing. it is read as 64-bit, since `20260808011145` does not fit in 32.

**the build that ships inside the APK omits `packagedAt` entirely**, and legitimately: absent means 0, exactly one of it exists, and there is nothing to order it against. its folder is the reserved word `bundled` rather than a derived name, and **its version, to a person, is its `commit`** — though what tells the app whether an app update brought a new one is a content hash beside the tree rather than any field in here. [the section on it](#the-build-that-ships-inside-the-apk) has the rest.

## `hostContract`

**the launcher-to-payload interface generation**: which environment variables the payload is expected to understand, which host window it must implement, and which pad format it reads.

the app declares a **range** rather than a single number — `CONTRACT_MIN` and `CONTRACT_MAX` in `SharpEmuBuild.java` — so bumping it does not silently invalidate every build a user has already imported. outside the range the launch is refused and both numbers are named in the log.

**the range is 1..4, and every generation in it runs.** each adds to the one before it:

| generation | what the payload does |
| --- | --- |
| 1 | implements the window selector |
| 2 | also implements the audio selector |
| 3 | also registers a host input source, which reads [the contract 3 pad format](pad.md#the-formats-and-the-check-that-replaces-a-shared-layout) |
| 4 | reads the contract 4 pad format instead: one of four ports per read, and the whole of the emulator's pad state |

**an older generation runs without what it lacks, and nothing reports it but the launch log.** a generation-1 payload does not know `SHARPEMU_HOST_AUDIO`, so it asks SDL for a device, SDL names four backends android does not have, and the port degrades to `backend=silent`: the game renders and makes no sound, with no error anywhere. a generation-2 payload does not know `SHARPEMU_HOST_INPUT`, so it registers no input source and its pad exports report a controller that is permanently connected and permanently neutral. the launch log names the generation that ran, which is where either is found.

**the host layer reads the contract 3 pad format as well as the contract 4 one, and this range is what keeps it doing so**: that format and its rumble command are needed only while 3 is in the range.

**it is a courtesy and not a guarantee.** a build that declares 2 and lies still dies inside SDL. the real guarantee is knowing where a build came from, which is what the launch log is for — it prints the name, id, version, build number, contract and directory of whatever it resolved.

**it gates the payload, and nothing else.** it moves when what a payload must do changes, and it stays still for a change on the launcher's side of the line that leaves every existing build byte-for-byte compatible. bumping it for one of those refuses working builds by name — a false negative in the mechanism built to prevent false negatives.

## what contract 2 asks of a payload

three environment variables, all set by the launcher, all read by the payload at startup. each selects an implementation **at runtime rather than at compile time**, because the payload's runtime identifier is still `linux-x64` — nothing about the build is android, and on a desktop linux none of these implementations must be reachable.

| variable | what the payload must do |
| --- | --- |
| `SHARPEMU_HOST_WINDOW=android` | construct a host window that does not go through SDL. SDL's linux build has X11, Wayland and KMSDRM and android has none of them, so an `SdlHostWindow` throws *"No available video device"* before the presenter makes its first vulkan call. the fork's `AndroidHostWindow` asks for a surface through `VK_EXT_headless_surface` instead — a standard extension meaning "a surface with no window", which the host layer supplies and owns the swapchain behind |
| `SHARPEMU_HOST_WINDOW_SIZE=WIDTHxHEIGHT` | report exactly that as the window's pixel size. **the host is the only thing that knows it** — it has the `ANativeWindow` and the guest process does not. a window whose size disagrees with the surface makes the vulkan presenter conclude the drawable was resized on every frame, so it recreates its swapchain forever and never renders, silently, with no call returning an error. a malformed value should be warned about and ignored, not thrown on |
| `SHARPEMU_HOST_AUDIO=android` | open host audio through AAudio. SDL's linux build knows PipeWire, PulseAudio, JACK and ALSA; android runs none of the three sound servers and does not let an app open the kernel device, so `/dev/snd/pcmC0D0p` is `system:audio` and an app's uid is not in group `audio`. implement the full PCM seam rather than only stereo PCM16: the guest asks for 48000 Hz, 2 channels, float32, and `AAUDIO_FORMAT_PCM_FLOAT` is exactly that, so guest float32 passes through without a conversion |

**generations 3 and 4 add a fourth**, `SHARPEMU_HOST_INPUT=android`: register a host input source that polls the host layer's pad bridge, in the format [`pad.md`](pad.md) gives for the generation.

**one more variable is set by the launcher and asks nothing of the payload.** `DOTNET_EnableWriteXorExecute=0` is .NET's own, not SharpEmu's: without it the host layer's SMC tracker cannot see CoreCLR's JIT writes and a boot costs 65x. it is listed here so that nobody removes it on the grounds that no payload code reads it.

the payload is otherwise an ordinary linux-x64 publish. it is `ET_DYN` and links the system libc, and it runs as guest code under the host layer's own dynamic linker search path — the x86-64 shared objects staged alongside it, not a rootfs and not a container.

## `env`, and what a build may not ask for

`env` sits **one tier above the bottom** of the environment sources. per launch, last wins:

```
the app's defaults  <  the build's own env  <  the settings rows  <  the launcher's own  <  the launch intent's extra guest environment
```

**the app's defaults are what a build replaces by naming a variable.** they tune the emulator for android without asking anything of a payload, and there is one:

| variable | default | why |
| --- | --- | --- |
| `SHARPEMU_RENDER_WORK_BUDGET_MS` | `12` | the render loop's time budget for draining guest work before it presents. SharpEmu defaults it on macOS alone and otherwise drains up to 1024 work items unbounded, which a desktop's per-item cost keeps short. on an android GPU driver one item can cost milliseconds, so a title heavy enough to reach the cap keeps reaching it, presents once per several hundred items, and runs at a sixth to a third of its frame rate. `12` is SharpEmu's own macOS value |

**on a light title the budget is a trade.** unbounded, a drain that keeps absorbing the next frame's work can leave the presenter showing a frame three or more behind the newest one, which never makes the render thread wait and is a few percent faster. a drain longer than 12 ms is split instead, so the presenter shows the newest frame and the render thread waits on the guest once a frame. no flat value gives a light title the unbounded behaviour and still stops a heavy one collapsing: the heavy one needs a budget shorter than the light one's ordinary drain.

a build that bounds its drain some other way sets `SHARPEMU_RENDER_WORK_BUDGET_MS` to `0` in its `env`, which is the emulator's own off switch.

the launcher's own are `DOTNET_EnableWriteXorExecute`, `SHARPEMU_HOST_WINDOW`, `SHARPEMU_HOST_AUDIO`, `SHARPEMU_HOST_INPUT`, `SHARPEMU_HOST_WINDOW_SIZE`, `SHARPEMU_SAVEDATA_DIR`, `SHARPEMU_VK_PIPELINE_CACHE_PATH`, `SHARPEMU_HOSTAPP_DIR` and `SHARPEMU_DEVLOG_APP_DIR`, plus `SHARPEMU_VK_PIPELINE_CACHE=0` while Disk shader cache is off. so neither a build nor a settings row **can** override any of them by declaring it: the launcher writes them after both maps. below all of it, the host layer's shell binary takes explicit `--env` on its own command line, which is a development path and not something the app reaches.

it is a **map** and not a list of flags, so a variable a build defaults on and a launch overrides reaches the guest once, with the override's value. two `--env` arguments naming one variable would be a coin toss over which the guest reads.

**a build may set guest environment and nothing else.** the host layer's own switches — SMC tracking mode, async signal delivery, the vulkan family — are properties of its correctness, and a payload able to ask for SMC tracking to be turned off is a payload able to break the thing running it. they are launch-time choices, never build-time ones.

what is worth defaulting here is a knob that is genuinely a property of the build: `SHARPEMU_AUDIO_LATENCY_MS` sets how much audio the device is asked to hold, and is the same variable SharpEmu's SDL backend reads with the same default, so it stays one knob on every platform.

## producing a build without a fork checkout

`scripts/package-build.py --from-archive <path|url> --id <id>` takes a published `linux-x64` tree — a `.tar.gz`, a `.zip`, or a bare publish directory — and gives it an identity. **it needs no fork clone, no .NET SDK and no git**, which is the path a third party takes and the one any automated job would take. what it cannot do is record a commit, so `commit` is empty and `source` names the archive.

the payload is *found* inside the archive rather than assumed at a fixed depth, since whether there is a wrapper directory depends on who packed it: the archive is searched recursively for a file named `SharpEmu` with a `plugins/` beside it, and the layout it was found in is named in the log. more than one candidate is an error rather than a guess. that search is what fixes the payload's name in this mode — a build whose `payload` field is something else has to be assembled by hand.

**packaging by hand is fine, and these are the parts that go wrong:**

- `plugins/` beside the payload, in the same directory
- `meta.json` at the **root** of the zip, not inside a wrapper folder
- UTF-8 without a BOM
- a `hostContract` inside the app's declared range, and a payload that actually implements it
- unix permissions are irrelevant. the payload is never executed as a file — the host layer reads it into guest memory — so an archiver that drops the executable bit costs nothing

## where a build lives on a device

| | |
| --- | --- |
| **staged** | `/storage/emulated/0/Android/data/<application id>/files/builds/<folder>` — external storage, which is what `adb` can write and the app can read |
| **the app's own** | the app's internal files directory, `builds/<folder>` — where an imported zip is extracted, beside its target as `.partial` and then renamed, so an interrupted import leaves nothing that resolution would find |
| **bundled** | `builds/bundled`, the one build that ships inside the APK, unpacked out of it on the first launch that needs it. a plain word rather than a derived name, and that is what makes it collision-proof: every other folder here is `<id>-<sharpemuVersion>-<packagedAt>` |

**a build runs where it is, and nothing is copied on the way.** a staged build runs from external storage, an imported one from the app's own directory because that is where a zip had to be written, and the bundled one from its reserved folder. copying one onto internal storage would buy durability against re-staging — which only a developer can do, and is exactly what they mean to do when they do it.

**that the volume does not matter is measured rather than assumed**: a build run in place off external FUSE storage boots in 874–902 ms against 879–907 ms from internal. the distinction is worth keeping straight because the other thing this app copies onto internal storage *does* have a hard reason — the GPU driver must be internal, since the linker refuses to `dlopen` a library from a volume other apps can write. the payload has no such constraint: it is read into anonymous memory, never `dlopen`ed, never executed as a file.

## the build that ships inside the APK

**exactly one does**, and it is the only build that is not a directory to begin with. it is an asset tree at `assets/sharpemu/` — the payload, its `plugins/`, the licenses and a `meta.json` generated when the APK was built — and it becomes `builds/bundled` the first time a game is launched with it selected.

**an asset tree rather than a zip.** an APK already is a zip, so a zip inside one would compress the payload twice and the device would pay to undo both. the format above is otherwise unchanged: what lands on disk is an ordinary build directory, and everything that reads one reads this one the same way.

| | |
| --- | --- |
| **`packagedAt` is absent** | exactly one of this build exists and nothing orders it against anything. **its version, to a person, is its `commit`** |
| **`name` is `Bundled build`** | what a person needs to know about this build is that it is the one the app came with, which is exactly what it does *not* share with a staged copy of the same commit. it is named here rather than in the build it was cut from, where the name would travel to every copy of it |
| **`author` is absent** | it is whoever produced the app, which the app's own screens say once. a card for the build that arrived with it repeating that says nothing |
| **`contents`** | a generated listing beside `meta.json`, `<size>` and a path per line, tab-separated. **it is packaging's file and not part of this format** — it is not extracted, and a build that is not an APK asset has no reason to carry one |
| **`identity`** | a content hash of the whole tree, one line, written after the listing so it is in neither the listing nor its own digest. packaging's file too, and never extracted: what lands on disk is `.identity`, written as the last file of the unpacking, holding the same string |
| **nothing is unpacked until a launch needs it** | so an app update that changed only the app costs nothing, and a device that never runs the bundled build never spends the storage |
| **staleness is the content** | the two short strings are compared on every launch and nothing is hashed on the device. **a `commit` cannot answer this**: a payload rebuilt from a dirty fork tree is at the commit it was already at, so an APK carrying bytes the directory does not have would agree in every field, and the launch would run the older payload without saying so |
| **out of space is refused before anything is written** | the listing is what makes that possible: a deflated asset has no length until it has been read, so without it the check would have to happen halfway through the write it exists to prevent |

`contents` exists for two things an APK asset cannot otherwise answer: how large the unpacking is before it starts, and which names are directories — `AssetManager` reports names without kinds, so telling a file from a directory otherwise means opening each one and reading a failure as "directory".

**a name android's asset packer does not ship is not in the tree.** aapt2 applies a default ignore pattern to `assets/` — dot-prefixed names among them — without saying so, so `scripts/build-apk.py` removes those before it writes the listing and then checks every line of the listing against the APK it produced. a listing that names a file the APK does not have is a launch that aborts part-way through unpacking.

## how a build is selected

`--es sharpemu <absolute path to a build directory>` runs that directory where it lies, and **an intent naming one always wins** — which is what keeps every script in this repository unaffected by anything a user chooses.

**nothing at all means whatever the build manager settled on**: the build the user chose, or, with nothing chosen, the one that shipped inside the APK. exactly one does, so that is a concrete artefact rather than a rule. where no build is bundled — the normal state of a development build of the app — it falls back to the most recently staged one, and a staged build wins over the app's own copy wholesale rather than by date, because `adb` writes the staged directory, so that is the one that moves.

**a bare id is refused outright rather than resolved**, and the refusal says so. an id names a build only up to its `packagedAt`, and resolving one means answering with the newest — so a freshly staged build loses to a later-stamped one still lying around, and the run is a plausible one attributed to the wrong artefact with nothing erroring. a path cannot be ambiguous about which directory it meant.

**nothing resolves a build by id at all.** the id groups the build list and decides which entry carries the *Latest* badge, and that is the whole of its job. answering "the newest build of this id" would only ever serve a recommendation, and with exactly one build shipping per APK there is none to make: the default is the bundled one, structurally and forever.

**a build the user chose is stored as a folder name**, which is derived from `meta.json` and so names one build rather than a family: a choice survives a newer build of the same id arriving, which a stored id would not.

**versions are compared numerically, not lexicographically.** `0.0.10` is above `0.0.9`, and a suffix orders *below* a bare version of the same numbers — `0.0.3-hotfix-2` is a `0.0.3`, not something after it — with two suffixes of the same shape ordered by their own numbers, so `hotfix-10` beats `hotfix-2`. third-party builds are why that is a real comparator rather than an assumption about our own version strings.

## a build directory holds only what was staged into it

SharpEmu is portable software: save data, the vulkan pipeline cache and a title's own log mounts all resolve under `user/` **next to its own executable**, and the desktop updater treats that directory as the one an update must not replace. on android there is no such promise to make — a build directory is re-staged from a PC, re-unpacked from the APK on an app update, and deleted from the build manager — so anything written beside the payload has a lifetime nobody chose.

**so the launcher points each of them at the app's own `user/` directory instead, and that is what makes a build disposable.** four variables, all of them the emulator's own:

| | |
| --- | --- |
| `SHARPEMU_SAVEDATA_DIR` | `<internal files>/user/savedata/`, with the emulator's own `<title id>/` underneath |
| `SHARPEMU_VK_PIPELINE_CACHE_PATH` | `<internal files>/user/pipeline_cache/<title id>/vulkan-pipeline-cache.bin` — the blob itself, since that is what the variable names |
| `SHARPEMU_HOSTAPP_DIR` | `<internal files>/user/game_logs/hostapp/` |
| `SHARPEMU_DEVLOG_APP_DIR` | `<internal files>/user/game_logs/devlog/app/` |

`user/game_logs/<title id>/` has no variable of its own and needs none: the emulator reaches that level only through the two mounts above, and each consults its own variable first. `/download0` is already outside `user/` — it follows `TMPDIR`, which a launch points at internal storage anyway.

**save data is one set for the app rather than one per build**: a save belongs to the game and not to the binary that wrote it, so keying it per build would make trying another build look like losing a save and switching back look like getting it returned.

**the layout under each variable is the emulator's own, including the per-title level of the pipeline cache.** the emulator keys save data itself, from the title id it reads out of the dump. the cache's variable names the blob rather than a root, so setting it replaces the whole path and not just its first half — which means the launcher has to name that title id, and it reads the same field of the same file, sanitised the same way: `titleId`, counted only when it is a JSON string, from `sce_sys/param.json` and then from beside the eboot, with each character kept only if it is an ASCII letter, digit, `-` or `_`, uppercased, and everything else becoming `_`. a dump offering none resolves to `UNKNOWN` at both ends.

**the two log mounts are the exception and are flattened**, having no equivalent read to hang a title id on and no measured title that has ever created one. a game that does writes into the app's directory rather than into a build directory, which is the whole point.

**none of the four moves the contract number.** each is read only when it is set, so a payload too old to know one keeps the portable behaviour for that one, and no existing build is refused by name — which would be a false negative in the mechanism built to prevent false negatives.
