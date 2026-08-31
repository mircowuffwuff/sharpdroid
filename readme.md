<p align="center">
    <img width=27% height=27% src=".github/images/sharpemu.png" />
</p>

# sharpdroid

this is an android app that runs [SharpEmu](https://github.com/sharpemu/sharpemu), a PS5 emulator for Windows and Linux on x86-64. sharpdroid focuses on low overhead, mergeability, and modularity.

<p align="center">
	<a target="_blank" rel="noopener noreferrer" href="#agent-usage"><img height="33" src=".github/images/agent-usage-button.png" /></a> <a target="_blank" rel="noopener noreferrer" href="https://dl-sharpdroid.mircowuffwuff.com"><img height="33" src=".github/images/download-button.png" /></a> <a target="_blank" rel="noopener noreferrer" href="https://github.com/mircowuffwuff/sharpemu/releases"><img height="33" src=".github/images/sharpemu-builds-button.png" /></a>
</p>

like the fruit of my latest software engineering obsession? click the dumb dog below to support development!

<p align="center">
    <a target="_blank" rel="noopener noreferrer" href="https://support.mircowuffwuff.com">
        <img width=30% height=30% src=".github/images/mirco.png" />
    </a>
</p>

## requirements

an arm64-v8a processor, android 9 or later, and a GPU with vulkan support. thats it.

## how it works

tl;dr: sharpdroid ships a **compatibility layer** purpose-built to run SharpEmu's x64 Linux build on arm64 android.

```
sharpdroid
├── user interface
└── host layer
    ├── ELF loader
    ├── syscall dispatch
    ├── thunk dispatch
    │   └── vulkan and aaudio
    ├── signal delegation
    ├── VMA and SMC tracking
    └── FEXCore
------------x86-64-below------------
        └── SharpEmu (linux-x64)
            └── the PS5 game
```

**SharpEmu** has no CPU emulator. instead, it executes x86-64 PS5 guest code natively on an x86-64 host CPU. there is simply nothing there, to port to arm64 android.

thats where [FEX](https://github.com/FEX-Emu/FEX) comes in. we use the x86-64 to arm64 translator it ships, [**FEXCore**](https://github.com/FEX-Emu/FEX/tree/main/FEXCore), to read x86-64 and emit arm64, as SharpEmu runs.

on arm64 Linux, which is FEX's intended usage environment, FEX also ships what it calls its frontend; a Linux program that loads the guest, answers its syscalls, and delivers its signals. FEX's frontend is a program. we need a library. which is why we implement an analogue that works as such: our **host layer**.

our **host layer** routes most of what SharpEmu asks for through to the Linux kernel at android's heart. files, memory, threads, and futexes are all answered by the real thing. though this does not work for all types of question; for instance, when SharpEmu asks which program it is, the host layer answers *SharpEmu*, instead of *an android app*, which is what the kernel would have said.

graphics and audio are solved without serialisation or copying, since guest and host share one address space.

a decoy `libvulkan.so` driver reroutes graphics API calls from SharpEmu to the host layer's **vulkan** thunk, and as a result of that to the system driver or a loaded mesa turnip driver, directly. each frame is drawn straight from SharpEmu into android's **ANativeWindow**.

similarly, a decoy `libaaudio.so` driver reroutes audio API calls from SharpEmu to the host layer's **aaudio** thunk. all audio is played straight from SharpEmu through android's **AAudio**.

### versus Windows emulation

on paper, sharpdroid carries much less overhead than Windows emulation on android does. though that isnt worth much, until upstream SharpEmu matures enough. but once it does, sharpdroid has serious potential to become the most performant and efficient way to play games on android, that are available on both PS5 and Windows.

| layers of Windows emulation on android, running a Windows game | layers of sharpdroid, running a PS5 game |
| --- | --- |
| a bionic container | our host layer |
| wine | |
| FEXCore | FEXCore |
| X server | |
| PulseAudio server | |
| DXVK | SharpEmu graphics |
| | SharpEmu otherwise |
| the Windows game | the PS5 game |

Windows emulation's **bionic container** is replaced by our more lightweight **host layer**. similarly to a bionic container, it manages to provide SharpEmu with everything it needs, mostly natively, from the Linux kernel at android's heart. but unlike a bionic container, it loads SharpEmu itself, in-process instead of as a second process, with no root filesystem and nothing to unpack per game.

Windows emulation's **wine**/Proton falls away entirely, because we are building our SharpEmu payloads for Linux. thus, wine's whole virtual desktop, the Windows API, and everything else Windows-specific are completely out of the picture.

likewise, Windows emulation's **X server** and **PulseAudio server** are not necessary at all on sharpdroid, which is an immediate benefit of SharpEmu sharing an address space with our app.

both Windows emulation on android and sharpdroid translate graphics to vulkan. the former through **DXVK**, the latter through **SharpEmu's graphics** translation.

the one honest cost of sharpdroid's architecture over Windows emulation on android's is **the rest of SharpEmu**, of course. both translate x86-64 to arm64 using FEX, but only sharpdroid also emulates a PS5 underneath.

### upstream mergeability

very handily, our architecture allows for minimal modification to upstream code. as of writing, [our sharpemu fork](https://github.com/mircowuffwuff/sharpemu/)'s android branch needs just [a few commits](https://github.com/mircowuffwuff/sharpemu/compare/main...android) to fully support the android platform.

this means, there is almost nothing in the way of sharpdroid receiving very frequent upstream updates, as evidenced by the fact that merging upstream `v0.0.3-release.2` into our `v0.0.3-hotfix-2` based fork, resulted in *one* merge conflict. thats `124` changed upstream files, `+13,871` and `-2,445` lines of code, that nearly entirely automerged into our fork.

### modular SharpEmu builds

perhaps the most exciting benefit of this architecture has to be, that it is extremely cheap, to design the app to allow for modular SharpEmu builds. so thats what we have done! sharpdroid sports a SharpEmu build manager, that allows importing different SharpEmu payloads, similar to how turnip drivers work on other emulators.

> [!CAUTION]
> **do not** import and run any SharpEmu builds whose authors you dont trust. if their sharpemu fork is open source, it might be worth cloning, reading through the source, and finally building and packaging yourself, to avoid harm.

theres a fork of upstream SharpEmu that specialises in compatibility with a specific game? merge [the few necessary android platform support commits](https://github.com/mircowuffwuff/sharpemu/compare/main...android) into it, package it as a SharpEmu build for sharpdroid, import it in the app's build manager, and set it as the SharpEmu build to use for that specific game in its per-game settings!

## history

a few weeks ago, i woke up, caked in sweat. "what if... PS5 on android?", i asked myself, at the dawn of night. the moonlight shone through my window, as i heard the crickets chirp. it was a beautiful summer night. i uttered "maybe with F-FEX...", before i fell back asleep.

<p float=left>
	<img src=".github/images/caked-in-sweat.png" />
</p>

when i woke up the next morning, i was infatuated with the idea of a PS5 emulator on android. i tried suppressing my burning curiosity for the topic, but i was hardly able to. later that fateful day, i gave in. together with claude, i might just be able to produce a proof of concept, i thought.

hours of discussion later, sharpdroid's architecture was decided upon.

two days later, Dreaming Sarah successfully booted to the main menu headlessly.

another day later, Dreaming Sarah rendered on an Adreno 830 and its video was presented to the screen.

just one more day after that, Dreaming Sarah played its audio through the Odin 3's speakers.

"thats it", i said with a calm voice. "the proof of concept is done."
i could not believe it. both; how quickly claude and i were able to realize this proof of concept, but also that it was possible *at all*. it took a second, before my emotions had caught up with the implications of my observation. my mind started racing, flooded with the possibilities that were unlocked before me.

i remained awestruck for what felt like an eternity. stunned by dozens of ideas, barely able to escape the allure of what could be. once i finally snapped out of it, i got to work.

## agent usage

> [!NOTE]
> this entire project makes it unambiguous, what is written by a human or an agent, by design.
>
> - both this readme and all release notes are hand written.
> - issues or comments on issues, that are written by claude, are posted by [@claudeclaudewuff](https://github.com/claudeclaudewuff).
> - every commit, that is co-authored by claude, is credited as such.

hai!! my name is mirco <img width="24" align="top" alt="mircomoji" src=".github/images/mircomoji-flat.png" /> and i am a software engineer.

i have a decade of programming experience myself, but in this project, claude is my programmer. and *barely more*. i plan the project, i tell him what to do, i monitor his progress and only accept his contributions, if they satisfy me.

i regularly spend *entire sessions* just discussing implementations out with claude, to ensure we can build the best app possible together. in the event that claude suggests something, i never blindly accept it.

and as a result, i understand how every moving part of the project works on paper.

...including our compatibility layer, sharpdroid's [host layer](https://github.com/mircowuffwuff/sharpdroid/tree/main/host). that being said, i can hardly scrutinize claude's host layer *code*. the concept is sound, but i am neither a low level programmer, nor an emulator developer. so, i have entrusted claude with the implementation of said host layer. i invite anyone with the appropriate experience to properly review it. i am sure, there are improvements to be made!

the claude model used until release was exclusively Opus 5.

## documentation

besides this hand written readme, a bunch of more in-depth documents on the separate moving parts of this project exist. do note, that these are written by claude:

- [app](docs/app.md)
- [audio](docs/audio.md)
- [build format](docs/build-format.md)
- [frontends](docs/frontends.md)
- [guest files](docs/guest-files.md)
- [host layer](docs/host-layer.md)
- [pad](docs/pad.md)
- [repo structure](docs/repo-structure.md)
- [scripts](docs/scripts.md)
- [vulkan](docs/vulkan.md)

## how to contribute

im happy you want to help! below are the steps to get a development environment, just like my own.

### how to fork

1. log in to your GitHub account
2. press the fork button on the top right of [this page](https://github.com/mircowuffwuff/sharpdroid)
3. keep the predefined name

### how to clone

1. open a terminal, i recommend a powershell run from [the new Windows Terminal](https://github.com/microsoft/terminal)
2. [install git](https://git-scm.com/downloads), if you havent yet
3. [install Python](https://www.python.org/downloads/), if you havent yet
4. navigate to your projects folder using `cd`
5. replace `YOUR_NAME` with your GitHub handle and clone your fork via `git clone --recurse-submodules https://github.com/YOUR_NAME/sharpdroid`
6. run `cd sharpdroid` to navigate into your fresh clone
7. run `py scripts/fetch-toolchain.py --install` to download the remaining dependencies into `toolchain/`; `android-sdk`, `dotnet-sdk`, `jdk-21-temurin`.

### how to build and run

1. plug an android device into your pc via usb
2. enable developer mode on the android device. this depends on the manufacturer, but generally:
   1. open settings
   2. select `Info`, `About this phone` or `Device`
   3. hit the android version number a couple of times. on Xiaomi devices, hit the MIUI or HyperOS version number instead
3. enable usb debugging on the android device
   1. open settings
   2. scroll down to `Additional settings`
   3. scroll down to `Developer options`
   4. find `USB Debugging` and toggle it on
4. if this is the first time building, since cloning, run `py scripts/run.py --sharpemu build`.
5. on any subsequent build, `py scripts/run.py` is sufficient. this will skip recompiling the bundled SharpEmu build.
- to quickly stage and launch a game, pass a PC path to said game: `py scripts/run.py --game "D:/games/Dreaming Sarah"`
- to quickly launch a game that is already on the android device's internal storage, pass its absolute device path: `py scripts/run.py --game "/storage/emulated/0/roms/ps5/Dreaming Sarah"`

### how to work on a SharpEmu build

most contributors will probably want to work on sharpdroid, not the SharpEmu payload itself. but if you want to work on the SharpEmu build, that sharpdroid runs, this is how:

1. **do not** develop in `external/sharpemu`. the submodule is pinned at one commit and only exists for easy cloning + building of sharpdroid.
2. fork [mircowuffwuff/sharpemu](https://github.com/mircowuffwuff/sharpemu)
3. clone your fork into your projects folder
4. run `$Env:SHARPDROID_SHARPEMU = "C:/projects/sharpemu"` in a powershell to point the environment variable `SHARPDROID_SHARPEMU` at your local sharpemu fork clone
5. make changes to sharpemu
6. run `py scripts/run.py --sharpemu build` to build sharpemu with your recent changes, bundle it in the apk, and run it on your device

### how to package a SharpEmu build

for other users, to be able to import your unique SharpEmu payload, the whole build has to be thrown into a zip, which needs a meta.json in its root.

1. make sure `SHARPDROID_SHARPEMU` points at the local sharpemu fork clone, that you intend to package. run `$Env:SHARPDROID_SHARPEMU` in a powershell to test that.
2. run `py scripts/package-build.py` to build the fork and package it into `build/builds/`. to set an id, author, display name, notes, etcetera, either:
   - edit the meta.json inside the generated zip archive
   - or pass the appropriate arguments to the Python script. see `py scripts/package-build.py --help`

### how to commit and push

> [!WARNING]
> **AI assisted** contributions are strictly required to carry a functional **`Co-Authored-By: ...` trailer** as the very last line of their commit messages, so that commits correctly display involvement by claude or Gemini in GitHub's user interface.<br/>
> <img height="38" src=".github/images/co-authored-by-example.png" />


1. add all files, that are relevant to your changes, to git via `git add path/to/file`.
   1. to see a list of unstaged files with changes run `git status`
   2. to simply add all unstaged files with changes listed there, enter `git add -u`
2. run `git commit -m "<type>(<scope>): <subject>"`. [example commit messages](https://github.com/mircowuffwuff/sharpdroid/commits/main/):
   - `fix(app): redraw a manager's cards in place so a press still ripples`
   - `feat(host): track a guest boot's progress through a checkpoint table`
3. make sure nothing unintended has been committed via `git show`. exit the interface by hitting `q`
4. push your locally committed changes to your fork's remote via `git push`

### how to open a pull request

1. make sure you have thoroughly tested all of your changes and that they are ready to be merged into the main sharpdroid repository!
2. navigate to your forked sharpdroid repository on GitHub
3. hit the contribute button above source
4. hit the open pull request button
5. follow the prompts

## for frontend developers

unlike Dolphin and Eden, a `roms/ps5/` directory has to have been added in sharpdroid's user interface, before any game inside `roms/ps5/` can be launched by it.

apparently, PS5 game folders always contain an `eboot.bin`, as well as an `sce_module/` and an `sce_sys/` directory.

```
Dreaming Sarah
├── eboot.bin
├── sce_module/
└── sce_sys/
	├── param.json
	├── icon0.png
	└── pic0.png
```

to scan a `roms/ps5/` directory, it is recommended to do roughly [what sharpdroid does](app/src/main/java/com/mircowuffwuff/sharpdroid/GameLibrary.kt), for decent performance.

`sce_sys/` contains a `param.json` with the game's display name in e.g. `localizedParameters.en-US.titleName`, depending on `localizedParameters.defaultLanguage`. `icon0.png` is a developer-supplied 512x square cover artwork and `pic0.png` is a 4K 16:9 game background wallpaper. i imagine, these could be used to replace scraping entirely, for PS5 games!

to launch a game, sharpdroid takes a **tree-based document URI** to the game folder *or* the `eboot.bin` inside it. attached as data of a `com.mircowuffwuff.sharpdroid/com.mircowuffwuff.sharpdroid.MainActivity` intent.

```java
Uri gameFolder = DocumentsContract.buildDocumentUriUsingTree(
	romsTree, DocumentsContract.getTreeDocumentId(romsTree) + "/Dreaming Sarah");

Intent intent = new Intent()
	.setClassName(
		"com.mircowuffwuff.sharpdroid",
		"com.mircowuffwuff.sharpdroid.MainActivity")
	.setData(gameFolder);

startActivity(intent);
```

alternatively to a Java implementation, a shell command also works.

```shell
adb shell am start -n com.mircowuffwuff.sharpdroid/com.mircowuffwuff.sharpdroid.MainActivity -d "content://com.android.externalstorage.documents/tree/primary%3Aroms%2Fps5/document/primary%3Aroms%2Fps5%2FDreaming%20Sarah"
```

## credits

thanks to [SharpEmu](https://github.com/sharpemu/sharpemu), all its contributors on GitHub, its references [ShadPS4](https://shadps4.net/), [Kyty](https://github.com/InoriRus/Kyty), and Ryujinx, and everyone who has helped support its development.

thanks to [FEX](https://github.com/FEX-Emu/FEX) for providing [FEXCore](https://github.com/FEX-Emu/FEX/tree/main/FEXCore), an x86-64 to arm64 translation layer, and thanks to [Valve](https://www.valvesoftware.com/en/) for funding it.

thanks to [libadrenotools](https://github.com/bylaws/libadrenotools) for making it possible to inject custom vulkan drivers with relative ease.

thanks to [Eden](https://eden-emu.dev/) for providing an elegant user interface design reference.

thanks to [GameNative](https://github.com/utkarshdalal/GameNative) for providing a functional x86 on arm64 android reference.

thanks to [Dolphin](https://github.com/dolphin-emu/dolphin) for providing a functional SAF file loading reference.

thanks to Ömer for donating 3 weeks' worth of claude code pro trials, and for composing his amazing handheld themed [sharpdroid render](https://github.com/user-attachments/assets/6bf16388-f0f9-4a91-aa36-2c10acdd1d8d).

thanks to Milana for hand drawing the SharpEmu logo at the top, the buttons, my OC, and the history illustration.

## license

[GPL-2.0-or-later](LICENSE)

## legal

sharpdroid does not:

- ship any games, firmware, or other proprietary PlayStation assets
- implement any circumvention of copy protection or digital rights management
- exist for any purposes but research