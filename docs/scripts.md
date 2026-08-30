# scripts

every script in this repository, what it does, and the arguments worth knowing.

everything is Python 3 and there is no build system on top of it. every script is safe to run from the repository root, every script resolves its own location, and **none of them contains a toolchain path or a version number** — those live in [`toolchain.json`](../toolchain.json) and are resolved in exactly one place. where each artefact comes from, and how a toolchain is found, is [`repo-structure.md`](repo-structure.md).

## from nothing to a game on your phone

**you need Python and git. that is the whole list.** everything else — the JDK, the android SDK, the NDK, the .NET SDK — is fetched into this repository's own `toolchain/` by the first command below.

```
git clone --recurse-submodules https://github.com/mircowuffwuff/sharpdroid
cd sharpdroid
py scripts/fetch-toolchain.py --install
py scripts/run.py --sharpemu build --game "D:/games/Dreaming Sarah"
```

**that last command is the whole build.** it publishes and packages the emulator from the fork, builds the host layer and everything under it, builds the APK with that build and the guest's x86-64 libraries inside it, installs it on the attached device, puts the game there, launches it and follows the log. it takes a while the first time; afterwards `py scripts/run.py --game existing` is the loop, and it needs nothing else said.

**the game is the only thing a run stages.** everything else the app needs is in the APK it just installed, and the app unpacks what it needs on the launch that needs it.

four notes, and then the rest of this document is detail:

- **Python 3.9 or newer, and only the standard library.** there is nothing to `pip install` and no virtual environment to activate.
- **on windows the command is `py`, not `python`.** a machine without Python installed answers `python` with an app-store stub that prints an install prompt and exits non-zero; the launcher does not have that problem.
- **`--recurse-submodules` is not optional.** the fork the emulator is built from is one of three submodules, and `--sharpemu build` has nothing to publish without it. already cloned without it? `git submodule update --init --recursive`.

### the pieces of that one command, when you want them separately

```
py scripts/package-build.py            publish and package the emulator from the fork
py scripts/build.py --install          the host layer, the thunks, the guests, the APK. installs it
py scripts/run.py --game existing      launch what is already on the device
```

**do the first one before the first `build.py`.** exactly one emulator build ships inside each APK, so the APK step needs one to bundle — and it **refuses** rather than quietly producing an APK with nothing in it. that refusal names these same commands.

`fetch-toolchain.py` never touches a machine-wide install and never modifies `PATH`. already have your own JDK, android SDK, NDK or .NET SDK? point `SHARPDROID_JDK`, `SHARPDROID_SDK`, `SHARPDROID_NDK` or `SHARPDROID_DOTNET` at it and that piece is left alone. run it with no arguments to see what it would fetch without fetching anything.

**no fork checkout, or no .NET SDK?** `py scripts/package-build.py --from-archive <a linux-x64 release archive> --id android` gives any published SharpEmu tree an identity, and needs neither.

## the everyday loop

| | |
| --- | --- |
| **`scripts/run.py`** | **build it, put it on the device, start it, show the log.** the one command you want most of the time |
| `scripts/build.py` | build everything in dependency order. `--list` prints the steps and what each does, `--install` installs the APK, `--clean` wipes what the native steps write, `--force` fetches again as well, `--only <step>` runs one |
| `scripts/regression.py` | stage the shell binary and run the host layer's 15 regression modes on the device. **exits non-zero if any fail**, so it can gate anything |

### the screen

**anything that launches wakes the device first.** a run onto a dozing panel is a wasted one and does not announce itself: the app starts, the log fills, and the guest renders into a surface nobody is looking at. so every launch brings the display up and takes an insecure lock screen off it, and does nothing at all on a device that is already awake.

**a lock with a credential behind it is said out loud and left alone.** no script here types one.

staging does not do this and neither does the regression set — copying files needs no display, and the host layer's regression modes have no app and no window.

### which app you are building

**every script here works against the debug app by default** — application id `com.mircowuffwuff.sharpdroid.debug`, labelled *SharpDroid Debug*. that is a different app to android: its own internal storage, its own external files directory, its own save data, installed beside a release SharpDroid. so **nothing you do while developing can disturb a personal install on the same phone**, and you have to ask for the release identity rather than remember to ask for the debug one.

`--release` builds and acts on the manifest's own identity. `--package <application id>` names a third one. the two are mutually exclusive, and every script that acts on an *app* takes both.

**`regression.py` takes neither, and that is right rather than an omission**: it stages the shell binary to a directory that belongs to no app, which is the whole reason it can run the host layer without an APK at all. `--serial` is on every script that reaches a device, including that one.

**the identity chooses the build type with it**, and the difference between the two types is one attribute: the release one is not debuggable. a debuggable APK lets anything on the device attach to the process and read the app's private directory, which is where save data lives — so the identity a stranger installs is not debuggable, and the two development identities are, because that is the loop where attaching to it is the point. neither is minified.

**the release identity is signed with a key that is not in this repository**, and `build-apk.py` refuses it rather than making one. that asymmetry is the whole point: a debug key is disposable and a missing one is generated on the spot, where a release key is the one thing that has to be the same next time — android refuses an update signed by another key, and the recovery is an uninstall, which takes the save data of everybody who installed the last release. a key that quietly regenerated would be a *new* key, and nothing would say so until somebody else's upgrade failed.

so a release is signed by a key made once, by hand, and kept somewhere that is not the machine that built it:

```
keytool -genkeypair -keystore app/release.keystore -alias sharpdroid \
    -keyalg RSA -keysize 4096 -validity 10000 -dname CN=sharpdroid
```

`app/release-signing.properties` names it and holds its passwords — `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. both files are ignored by git, and the refusal that arrives without them says this. the app's build file reads the passwords itself rather than being handed them, because a password passed as a project property is a password in the command line of a process anybody on the machine can list. **v3 signing is on**, so a key that is lost or has to be retired can be succeeded rather than stranding every install.

**a release APK proves it is one before it is written.** packaging reads the finished archive back and refuses a debuggable one, and refuses one whose signing certificate is the generated debug key's.

## existing, build, none, or a path

`--game`, `--sharpemu` and `--driver` read one vocabulary, the same in every script. **a value names a source; a flag never does.**

| | |
| --- | --- |
| `existing` | use what is already on the device. creates nothing, which is the whole difference from `build` |
| `build` | produce one from the fork checkout now. `--sharpemu` only |
| `none` | name nothing. for a driver that pins the platform's own over whatever the app has stored |
| *a path here* | staged if the device has not got those bytes, reused if it has |
| `/storage/emulated/0/…` | used where it lies. nothing is staged and nothing is copied |
| *omitted* | the script names nothing, and whatever is downstream answers |

**each script accepts the values that mean something for it and refuses the rest by name.** staging takes a path here and only that, because `existing` names nothing to copy and a device path names something already there. the APK build refuses a device path too, because the build it bundles has to be readable on *this* machine — `--install` afterwards is the only part of it that reaches a device at all.

**a bare name is not a value.** an id names a family of builds rather than one artefact, so answering with the newest of that id would let an older build beat one that was just staged. a path is how a build is named.

**omitting one is not "pick one for me".** it names nothing and lets the app answer:

- **no `--game`** — the app opens its game list and no guest runs. this is the frontend run, and `py scripts/run.py` with nothing else said is what asks for it
- **`--game <a device path>`** names the game's directory **or the `eboot.bin` inside it**, which is what the app accepts and what a library that scanned the device is most likely holding
- **`--game-uri <uri>`** instead of `--game` — the game is named on the intent's own data, the way an emulation frontend names one, rather than in an extra. it stages nothing, since a uri points at a dump already on the device, and the two cannot both be given. **`adb shell` cannot attach the read grant a frontend would**: it holds no permission on a content uri, and asking the platform to pass one on fails the launch outright, so the uri goes bare and the app resolves it against the folders it has been granted. [`frontends.md`](frontends.md) is the contract this rehearses
- **no `--sharpemu`** — no build is named, so the app runs what its build manager settled on, which on an untouched install is the build it ships with. **that is a choice the app holds across runs**, so a run naming no build is not necessarily a run on the bundled one
- **no `--driver`** — the app loads what its settings hold, which on an untouched install is the platform's own

### the ones that only a measurement wants

**`--log-tids` widens every guest log line's stamp to name the host thread that wrote it** — `[+   6.402 t14053]` instead of `[+   6.402]`. it is what makes a thread the emulator names in its own output comparable with a counter that names a thread id, which nothing else in a log does: every line reaching logcat carries the log pump's thread rather than its author's. **it is off by default and should stay that way for an ordinary run**, because the boot checkpoints and anything else matching a guest line by its text see a prefix that has moved.

**`--audio-watchdog` reports the audio stream's state once a second whether or not the guest is submitting.** the periodic report on the write path cannot see the guest *stopping*, so without this a run that goes silent says nothing at all and reads exactly like a run that did not.

**`--fex-preset none` passes no `--fex` at all**, where naming a rung passes every knob it sets. every rung states all nine values it covers, so an ordinary launch spells the whole JIT configuration out and leaves nothing to whatever FEXCore defaults to — which is right for a run somebody is playing and wrong for one being compared against a figure recorded before that was true. `none` is that comparison, and it is not a rung: it drops the stored preset and every knob overriding it, and it is a launch argument rather than anything the settings scene can hold.

`--restage` pushes over what the device has regardless of what the byte counts say. it is rarely needed, since a size mismatch restages by itself; it is the escape hatch for the one case a byte count cannot see, which is two different dumps or builds of exactly the same length.

## building the pieces

each of these is one job, and `scripts/build.py` runs them in this order. **the order is real rather than editorial** — every link in it is an actual refusal by the step that comes after.

| | |
| --- | --- |
| `scripts/fetch-guest-libs.py` | the x86-64 glibc set the guest's own linker searches, built out of debian packages pinned on `snapshot.debian.org` by content hash and verified against it, plus the licenses they are redistributed under. `--keep-packages` keeps the downloads |
| `scripts/build-adrenotools.py` | the GPU driver loading library. the host project imports it as a static library at configure time and does not configure without it |
| `scripts/gen-thunks.py` | regenerates both halves of both thunks from the NDK's headers. **the output is committed**, so this is what you run when the NDK moves rather than on every build. `--check` reports what would change and writes nothing |
| `scripts/build-thunks.py` | assembles the guest halves into `libvulkan.so.1` and `libaaudio.so`, beside the glibc set |
| `scripts/build-host.py` | the host layer: the library the app loads, and the same thing as a shell binary. `--clean` wipes first, `--probe` builds the host vulkan probe instead |
| `scripts/build-guests.py` | the x86-64 test guests the regression set runs. `--only <name>` builds one |
| `scripts/build-apk.py` | the APK, with exactly one SharpEmu build and the guest's x86-64 libraries inside it. `--install` installs it afterwards, `--offline` makes gradle resolve everything from its cache or fail |

**the guest libraries always ship and no argument says otherwise.** a build is chosen because a person picks between several; the x86-64 set is the one right set for a given APK, so the only thing an argument could decide is whether the APK can run a game at all. missing, the APK step **refuses** and names the fetch — which is what makes the first link in the order above an actual refusal rather than an editorial one.

**it refuses just as hard without the licenses**, and that covers everything the APK redistributes rather than the guest set alone: the guest set's index and per-package statements, the notices for what is compiled into the host layer or shipped beside it, and an attribution list for whatever gradle resolved into the dex. all of it is asserted inside the finished archive, the index line by line, so a row that would open onto nothing is a refusal here instead. [`repo-structure.md`](repo-structure.md) has what each is and why.

**the dex half runs gradle once before the build**, to ask what it resolved rather than to trust what is declared. `--offline` reaches that invocation too — it is asked for to find out whether anything is being fetched that nobody declared, and an invocation exempt from it is one the question is not being asked of.

`py scripts/build.py --list` prints the whole sequence and says what each step does. it also reports when the committed thunk sources no longer match the NDK's headers, which is the only thing that would otherwise need someone to think of asking.

**a build step runs every time, and only the fetch is skipped when its output is already there.** the difference is who knows about the inputs: what a fetch produces either exists or does not, while what a build produces is stale the moment a source changes — and the driver cannot see that without knowing every source file, every header and the state of a submodule, which is the job of the cmake, ninja or compiler invocation the step already makes. so the step is entered and its own tool decides there is nothing to do. that costs about six seconds across the four build steps, against a stale library being packaged into an APK and a run whose log is missing the line the change added — which reads as the change not working rather than as the change never having been installed.

## putting things on a device

```
py scripts/stage.py --sharpemu build\builds\<a build>
py scripts/stage.py --game "Y:\games\Dreaming Sarah"
py scripts/stage.py --guest-libs
py scripts/stage.py --driver <a driver package>.zip --driver-name turnip
py scripts/stage.py --shell
```

more than one may be named in a single command. everything lands on the app's external storage, which is the volume `adb` can write and the app can read — except `--shell`, which goes to a directory belonging to no app and therefore takes no application id.

**`--guest-libs` is an override rather than a way of putting something there for the first time.** the APK carries the set and the app unpacks it to internal storage, which is what lets an install nobody has ever plugged in start a game; this writes a second copy on external storage that the app then prefers, and it exists for the fast loop after a rebuilt thunk stub. **it never expires** — a set staged weeks ago keeps winning after an app update ships newer stubs, and that failure is a guest resolving an old `libvulkan.so.1` against a new host thunk rather than anything that looks like a missing file. every launch logs which of the two tiers answered, and `--restage` is the fix.

**existence is not sameness.** what decides whether something is already there is the byte count of its payload, never its name: a rebuilt artefact keeps its directory name, so "the folder is already there" would silently run yesterday's bytes.

**the on-device name of a build comes from its own metadata**, never from what the directory or zip is called here. a build directory or a zip of one both work; the zip is unpacked here rather than on the device.

## producing a SharpEmu build

```
py scripts/package-build.py                                        whatever branch the fork has checked out
py scripts/package-build.py --branch android                       the timestamp stamps itself
py scripts/package-build.py --no-publish                           repackage what is already published
py scripts/package-build.py --from-archive <path or url> --id android
```

it produces a **directory and a zip** under `build/builds/` and stops. producing a build and putting one on a device are two jobs, which is what lets a build packaged last week — or one somebody else packaged — be staged without republishing anything.

**`--from-archive` needs no fork checkout, no .NET SDK and no git.** that is the path a third party takes, and the one any automated job would take. what it cannot do is record a commit, so the build's `commit` is empty and its `source` names the archive instead — and with no fork there is no remote to take an author from either, so `--author` is how one is set there.

**everything else in a build's metadata defaults from the fork**: its id and its `source` from the branch, its author from the owner of that branch's `origin` remote, and its display name from the branch where this repository knows one. [`build-format.md`](build-format.md) is where each of those is defined.

the fork checkout is resolved by **`SHARPDROID_SHARPEMU`**, falling back to the `external/sharpemu` submodule and to nothing else. the submodule is a pin, nothing develops in it, and a checkout beside this repository is deliberately not in the order — if it were, the pin would be the one path no machine ever took and would go stale with nothing to notice.

**set it for the whole sequence or for none of it.** packaging a build in a shell that has the variable and building the APK in one that does not gives the two steps different forks, and the second refuses a build the first was right to make. the APK step names the tree it compared against for that reason — and if the variable is missing everywhere, both steps resolve the pin, agree, and are wrong together, which is a thing to read in the paths rather than something any check can catch.

[`build-format.md`](build-format.md) is what a build is.

## shipping a build inside the APK

```
py scripts/build-apk.py                              bundles the newest under build\builds\
py scripts/build-apk.py --sharpemu <a build>         bundles that one
py scripts/build-apk.py --sharpemu none              no asset at all
py scripts/build-apk.py --release --sharpemu <dir>   the release identity
```

**bundling is the default and that is deliberate**: an APK without a build in it looks identical to one with it, right up to the moment you want to test the bundled build and find it is not installed. **nothing to bundle is a refusal, never a silent bundle-less APK.**

the asset is a plain directory tree rather than a zip — a zip inside an APK is compressed twice and the device pays to undo both. the first launch that resolves to it unpacks it, and a later one unpacks it again when the content hash written beside the tree differs from the stamp the last unpacking left — so **a rebuild of the same commit with different bytes in it is not a build the device keeps running** — which is what successive builds from a tree still being worked in are: one commit, different bytes each time.

three things a release APK refuses that a development one does not, each of them a build whose source nothing outside this machine could get back to:

- **a build the submodule pointer does not name.** that pointer is what makes an APK reproducible from a clone. the development identity prints the mismatch and builds anyway, because it installs under its own application id and there is no clone to reproduce it from
- **a build packaged from an archive**, since it records no commit to check
- **a build published from a checkout with uncommitted changes in it**, whose commit is recorded with `-dirty` after it and names a source nothing can reconstruct. the development identity says so and builds anyway, which is what makes it usable while the fork is being worked on

both identities refuse a build whose contract generation the app does not speak, and the range is read out of the app's own source so that a script cannot bless a build the app refuses.

**and both refuse a build that is not the fork checkout on this machine**, which is a different question to any of the above: those ask whether an APK could be rebuilt from a clone, and this asks whether the payload about to be sealed into one contains the work in the tree you are editing. the emulator is developed through the bundled build — edit the fork, package it, build the APK, run it — and dropping the packaging step from that loop otherwise produces a perfectly good APK carrying the payload from before the edit, with nothing anywhere saying so.

```
py scripts/package-build.py                      package the checkout, then build again
py scripts/build-apk.py --sharpemu <a build>     bundle that one, knowing what it is
```

**a build you name is reported and bundled anyway**, because naming one is a choice and it is the only way to bundle a branch that is not what is checked out. omitting it means the newest under `build\builds` answered and nobody chose, which is where the accident lives.

**an uncommitted change is compared rather than noticed.** the recorded commit carries a fingerprint of the working tree's changes after its marker — `8725891-dirty.925a11d6` — so a build published from *these* edits matches and one published from a different set does not. without that, the state a person developing the fork is in all day would be the one state this could not answer, and an answer nobody can act on is one they learn to skip. the app draws `8725891-dirty` and leaves the fingerprint off: it is there for a comparison, and a person reading a screen is not that comparison.

**the APK also records the commit of this repository it was built from**, which the app shows on its About screen beside its version and which a bug report is worth having: a version alone names a fortnight of commits. a working tree with uncommitted changes in it is marked as such, because an APK built from one is not the commit it names. **not knowing is a supported state and never a refusal** — a source archive carries no `.git` and a machine may have no `git` at all, and the app then shows the version by itself.

**it records the FEXCore it was built against the same way**, described out of the pinned submodule against FEX's own release tags. the same empty-string fallback applies, and the same dirty marker — which here would mean the rule that FEX is never modified had been broken, rather than an ordinary development tree.

## the shared package

`scripts/sharpdroid/` is the half every entry point shares, eight modules: `shell` is how a script talks, runs things and refuses; `paths` is where every artefact in this repository is; `toolchain` resolves the compilers and SDKs; `native` is the cmake build both native steps use; `vocabulary` is the argument scheme; `device` is `adb` and the app's identity; `builds` reads the build format; and `resolve` turns one of the vocabulary's values into a thing on a device.

**one rule lives in one place** is the whole organising idea — the app's identity, an artefact's path, a build's on-device name and what each argument accepts are each resolved by one function every caller shares.

## one rule worth knowing

**a script refuses; it never prompts.** stdin here is a pipe under anything driving these scripts, where a prompt either throws or reads EOF — and a naive prompt takes EOF for a yes. so a refusal names the words that resolve it, which is an instruction a person and a pipe can both act on. a refusal exits **2**; a tool one of these ran failing exits with whatever that was.

and **a step that returned zero is not a step that produced something**. every script asserts the artefact it was supposed to produce rather than trusting an exit code, because a tool exiting cleanly having done nothing is the most common failure shape here.
