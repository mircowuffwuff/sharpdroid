package com.mircowuffwuff.sharpdroid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * one activity, one SurfaceView, one guest -- <b>and one process, which ends with the run</b>.
 *
 * <p>the manifest puts this activity in {@code :guest}, and the reason is the guest's own exit: a
 * guest calling {@code exit_group} ends the process it is in, and the host layer answers with
 * {@code _exit} because the other guest threads are inside translated code and cannot be unwound.
 * sharing a process with the game list would make that the app dying with the game.
 *
 * <p><b>the other half is that this process is ended deliberately when a run finishes any other
 * way</b> -- see {@link #endRun}. a guest run leaves a loaded payload, a guest address space and a
 * JIT behind it, and none of that may reach the next run; a process that answered a second launch
 * would also be answering it from whatever it had cached of the settings store before the user
 * changed a row in the other process. so the invariant is one process per guest run, and it is what
 * makes two launches of one intent the same launch twice.
 *
 * <p>the payload lives in the app's own external files directory rather than in
 * {@code /data/local/tmp}, which is {@code shell_data_file} and which SELinux denies an app. that
 * costs nothing: the host layer maps guest images into anonymous memory and reads into them rather
 * than mapping them from a file, so a {@code noexec} volume is not a problem and never was.
 */
public final class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "sharpdroid";

    /**
     * which game this launch runs, and where its files are.
     *
     * <p><b>resolved once, in {@code onCreate}, by {@link LaunchGame}</b> -- which is the only thing
     * in the app that reads the extras naming a game. everything here asks the source instead of
     * reading them again, and the one question it is ever asked is which kind it is: a staged
     * directory the guest opens with ordinary syscalls, or a directory inside a granted tree that is
     * mounted and answered through a content provider.
     *
     * <p>null when the launch could not name a game it can reach, in which case {@link #refusal}
     * says why.
     */
    private GameSource source;

    /**
     * why nothing is going to run, or null when something is.
     *
     * <p><b>held rather than said immediately.</b> the resolution happens in {@code onCreate}, which
     * has no window to say anything in yet -- the loading screen is built with the surface, and the
     * surface is what {@link #runGuest} waits for. so the sentence waits with it.
     */
    private String refusal;

    /**
     * which staged GPU driver to inject, or null for the stock Adreno one.
     *
     * <p>a folder name: a package the driver manager imported, or one staged under
     * {@code <external files>/gpu-drivers/} by {@code scripts/stage.py}. both spellings
     * resolve through {@link GpuDriver}, so a driver a script names and one a user chose are the
     * same code path.
     *
     * <p>this constant is the last of the three answers rather than the only one: the launch intent
     * wins, then Settings → Graphics → Custom driver, then this.
     *
     * <p><b>null on purpose.</b> turnip injection works, and the stock driver is nonetheless the
     * default, because it is the configuration every measurement in this project is taken at -- a
     * baseline that moves with the driver is not a baseline.
     *
     * <p>overridden per launch by {@code --es driver <name>}, so comparing drivers is a loop over
     * {@code am start} rather than a rebuild each time. {@code stock} means the same as null.
     */
    private static final String DRIVER = null;

    // which SharpEmu build to run is deliberately not a constant here. it is
    // `--es sharpemu <absolute path to a build directory>`, or, with no extra, whatever the build
    // manager settled on -- see chosenBuild. exactly one build ships inside the APK, so a launch that
    // names nothing has a concrete artefact to answer with rather than a rule to apply.

    /** resolved once in {@link #onCreate}, because the intent is not readable from a worker. */
    /**
     * the extra a refused launch sends back, carrying the message a person is shown.
     *
     * <p>present only on a launch that gave up; a run that ends normally sends nothing at all, which
     * is what lets the receiving side treat absence as "nothing to say" rather than as a state.
     */
    public static final String ABORT_MESSAGE = "abort";

    private String driverName;
    private String[] driverEnv = {};
    /**
     * why the chosen driver could not be used, as the string resource a person is shown, or zero when
     * there is nothing wrong with it. set by {@link #installDriver} for the four things this side can
     * see, and by the host layer's answer for the one it cannot; read immediately after both.
     */
    private int driverFailure;
    private boolean profile;
    private boolean turbo;
    private boolean audioWatchdog;
    /** {@code --ez tracefiles}, counting the guest's file access under the game directory. */
    private boolean traceFiles;
    /**
     * {@code --ez tracepad}, printing every pad poll and every rumble request.
     *
     * <p>chatty by the standards of the others: the emulator samples the pad up to a thousand times a
     * second per polling thread, so this is for one question at a time and not for a whole run.
     */
    private boolean tracePad;
    /**
     * {@code --ez padselftest}, one fabricated rumble when the guest first polls.
     *
     * <p>it exists because the two directions of the pad bridge fail independently and an ordinary run
     * exercises only one: a game that polls proves the read path continuously, and rumble is proven by
     * nothing unless the title happens to vibrate. this fires the real delivery path and says so in the
     * log, so a buzz can never be mistaken for the game's own.
     */
    private boolean padSelfTest;
    // widen the guest's log stamps to name the host thread that wrote each line. off unless a run
    // asks, since every scanner outside the app matches a guest line by its text.
    private boolean logTids;
    /** the host layer's SMC tracking mode. mtrack is the default every measurement was taken on. */
    private String smcMode = "mtrack";
    private String[] guestEnv = {};
    /** {@code --es sharpemu}, an absolute path to a build directory, or null for the latest staged. */
    private String buildPath;
    /** the selected build's own environment defaults. the lowest-precedence source there is. */
    private Map<String, String> buildEnv = new LinkedHashMap<>();

    /**
     * {@code --strict} on the payload's own command line, or null for "the user did not say".
     *
     * <p><b>three-valued rather than boolean, and that is the whole precedence rule in one field.</b>
     * a launch that names {@code --ez strict} wins; a launch that does not falls back to what the
     * settings scene stored; a setting the user never touched leaves this null and the flag is not
     * passed at all. collapsing it to a boolean would make "off" and "unsaid" the same answer, and
     * the day a default changes, every script that relied on omission would silently be pinned to
     * the old one.
     */
    private Boolean strictDynlib;

    /**
     * the FEXCore JIT preset, or null for "the user did not say".
     *
     * <p>three-valued for the same reason {@link #strictDynlib} is, and null passes no {@code --fex}
     * at all -- which is the argument vector every measurement in this project was taken on, and is
     * also what {@link FexPreset#COMPATIBILITY} would produce, since every value in that rung is
     * FEXCore's own default.
     *
     * <p><b>it is a host-layer flag and not a guest environment variable.</b> see {@link FexPreset}:
     * the {@code FEX_} environment spelling other FEX frontends use is read by machinery this
     * project does not build, so it would reach nothing here while looking like it had.
     */
    private String fexPreset;

    /**
     * the FEXCore knobs the settings scene overrides on top of {@link #fexPreset}, and empty where
     * nobody has opened one.
     *
     * <p><b>a rung and a sparse map are one answer between them</b>, so this is read from the same
     * store and in the same breath -- see {@link Settings#fexOverrides()} for the rule that joins a
     * game's level to the app's, and for why the pair is stored rather than the values alone.
     *
     * <p><b>there is no intent extra for it and there is not meant to be.</b> {@code --es fex}
     * already names a knob for one run and is emitted after this, so a launch measuring one still
     * beats a row somebody set -- and a second extra would be two ways to say one thing with an
     * ordering between them that nobody could see.
     */
    private Map<String, String> fexOverrides = new LinkedHashMap<>();

    /**
     * whether this launch carries no JIT configuration at all.
     *
     * <p><b>{@code --es fexpreset none}, and it is not a rung.</b> every rung names every knob, so a
     * launch normally spells out the whole configuration; this is the one way back to the argument
     * vector that names none of it and lets FEXCore and the host layer decide between them. it is
     * what every figure recorded before the rungs were complete was taken on, so a run comparable
     * with those has to still be expressible.
     *
     * <p>{@code --es fex} is unaffected: a knob named on the command line was asked for by name.
     */
    private boolean noJitConfiguration;

    private boolean hostFeatureProbe;

    /**
     * extra FEXCore options for one run, appended after whatever the preset contributes.
     *
     * <p><b>an instrument, and absent unless a launch names it.</b> the preset ladder is a fixed
     * table, so measuring a knob that is not on it would otherwise mean an APK per candidate. with
     * no {@code --es fex} the argument vector is exactly the one the preset alone produces.
     *
     * <p>nothing validates the names here. the host layer resolves each against FEXCore's own option
     * table and refuses one it does not know, so a typo ends the run with a message naming it rather
     * than becoming a knob that silently did nothing.
     */
    private String[] fexOptions = new String[0];

    /**
     * what the settings scene contributes to the guest environment. empty when nothing was chosen.
     *
     * <p>it sits above the build's own {@code env} and below the launcher's five, which is the order
     * {@code docs/build-format.md} documents -- and below {@code --es guestenv}, so a script naming a
     * variable still wins over a row that set it.
     */
    private Map<String, String> settingsEnv = new LinkedHashMap<>();

    /**
     * whether compiled graphics pipelines survive the run that built them.
     *
     * <p><b>two-valued and defaulting to off, unlike every other row here.</b> the emulator's own
     * default is on. keeping the cache is derived state written throughout a run and held per
     * title, so it is a thing to opt into rather than something an install starts doing unasked --
     * and the app is where that call belongs, since the app is what owns the directory it lands in.
     *
     * <p><b>it travels as {@code SHARPEMU_VK_PIPELINE_CACHE=0}, the emulator's own opt-out</b>, which
     * keeps the cache in memory for the run and writes nothing. leaving
     * {@code SHARPEMU_VK_PIPELINE_CACHE_PATH} unset would not have done this: unset means the
     * emulator resolves its portable default, which is a path inside the build directory, so the
     * cache would persist and land somewhere a re-stage destroys.
     */
    private boolean diskShaderCache;

    /**
     * the title id the emulator will resolve for this launch's game -- see {@link #game}.
     *
     * <p>read once, in {@code onCreate}, and used twice: to find the game's own settings store, and
     * to name the per-title pipeline cache the emulator is handed.
     */
    private String titleId;

    /**
     * what this launch's game is filed under -- its title id, or its directory name when it has none.
     *
     * <p><b>not {@link #titleId}, and the difference is a dump that names no title of its own.</b> the
     * emulator files every such dump under one id, so two of them share a save directory; this app
     * does not, and keys them by their folders instead. anything of the app's own that belongs to one
     * game is keyed by this -- the settings store, and the record of how long this game took to boot.
     */
    private String configKey;

    /**
     * which build this launch resolved, for the boot record to be keyed by.
     *
     * <p>the commit where there is one, since that is what tells two builds of one version apart, and
     * the folder otherwise. set in {@link #resolvePayload}, which is the only place that sees a build
     * rather than the path to its payload.
     */
    private String buildKey = "";

    /**
     * this launch's settings: the game's own store, with the app's behind it.
     *
     * <p>built in {@code onCreate} and kept, because the build is resolved much later -- in
     * {@code runGuest} -- and resolving it against the app's store while every other row came from
     * this one is exactly the split-brain the precedence rule exists to prevent.
     */
    private Settings settings;

    private boolean started;
    /** set once the run is over, so {@code onDestroy} can tell an ending from an ordinary one. */
    private boolean ending;
    private int surfaceWidth;
    private int surfaceHeight;

    /**
     * the panel the back button opens, and the only way out of a run that is not the guest's own.
     *
     * <p>it is the only thing this activity draws, and it exists from the moment the activity does
     * rather than from the moment a guest starts -- a launch is several seconds of black screen
     * before the first frame, and being unable to leave during it is exactly the state the overlay
     * is for.
     */
    private GuestOverlay overlay;

    /**
     * what fills the window from the tap until the guest presents its first frame.
     *
     * <p><b>it exists from {@code onCreate}, not from the moment a guest starts.</b> the black begins
     * when the activity does, and the thread that can report anything about a boot is not started
     * until the surface exists -- so a screen inflated any later would leave the first stretch of a
     * launch unaccounted for, which is the part that looks most like a tap having done nothing.
     */
    private GuestLoading loading;

    /**
     * this launch's game as the dump describes itself: its title, its identity and its artwork.
     *
     * <p><b>read here rather than handed down, and that is one extra fewer on every launch.</b> the
     * activity has always had to open {@code sce_sys/param.json} anyway -- the settings this run
     * merges are keyed by the title id inside it -- so a name and a cover sent alongside would be
     * two extras describing a file that is being parsed regardless.
     *
     * <p><b>and it is what makes a launch from outside this app look like a tap on the list.</b> an
     * app that starts a game has the game and nothing else: it cannot know this dump's display name
     * and it has no cover to send. reading both from the dump means it does not have to.
     *
     * <p>null when the launch could not name a game -- see {@link #source}.
     */
    private Game game;

    /**
     * whether the loading screen draws its bar against a prediction, or simply says a boot is
     * happening.
     *
     * <p><b>off is the indeterminate bar on every launch</b>, with the cover, the name and the phase
     * line unchanged and the screen still coming down at the first frame -- the record is never read.
     * it is still <i>written</i>: the host layer stamps every checkpoint whether or not anything
     * predicts from them, because the position reaching the end of that table is the only signal this
     * activity has that the guest has drawn. so recording costs one store commit against data that
     * exists either way, and it keeps switching this back on immediate.
     */
    private boolean loadingEstimate;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        // **first, so that everything below is in the window as well as in logcat.** this is the
        // process that loads the host layer, so it is the process whose java lines can be kept
        // beside the emulator's -- and the lines above the first frame are exactly the ones a report
        // about a launch is made of. see AppLog.
        AppLog.attach();

        // driver selection off the launch intent, so a comparison across driver builds is a loop
        // over `am start --es driver <name>` instead of an APK rebuild per candidate. "stock" and
        // absence both mean the platform's own driver.
        //
        // the driver manager's choice is merged in below, with the rest of what the user chose.
        driverName = getIntent().getStringExtra("driver");
        // and mesa's own knobs, comma-separated: --es driverenv TU_DEBUG=sysmem,TU_DEBUG=noubwc
        String env = getIntent().getStringExtra("driverenv");
        if (env != null && !env.isEmpty()) {
            driverEnv = env.split(",");
        }
        // --ez profile true, for the same reason the driver is an extra: finding a stall should not
        // cost an APK rebuild.
        profile = getIntent().getBooleanExtra("profile", false);
        // --ez turbo true pins the GPU clocks through KGSL. off by default: a thermal and battery
        // trade rather than a free win, and every number recorded before it was taken without it.
        turbo = getIntent().getBooleanExtra("turbo", false);
        // --ez audiowatchdog true reports the stream's state once a second whether or not the guest
        // is submitting. the periodic report on the write path cannot see the guest stopping.
        audioWatchdog = getIntent().getBooleanExtra("audiowatchdog", false);
        // --ez tracefiles true counts what the guest asks of the game directory: opens, stats,
        // directory listings, and what it then does with the descriptors. it costs a predictable
        // branch per file syscall when off, and it is what makes two ways of reaching the same game
        // comparable rather than a matter of opinion.
        traceFiles = getIntent().getBooleanExtra("tracefiles", false);
        // --ez tracepad true prints every poll and every rumble request. the counters print without
        // it: the first read, the first read that finds a pad and the first rumble each say so once,
        // which is what separates "the pad does nothing" from "the payload never asked".
        tracePad = getIntent().getBooleanExtra("tracepad", false);
        padSelfTest = getIntent().getBooleanExtra("padselftest", false);
        logTids = getIntent().getBooleanExtra("logtids", false);
        // --es smc full, because chasing the audio stall needs the two SMC modes compared on the
        // same build. this
        // is a *launch* extra and still not a build one: the comment below about a payload that can
        // ask for --smc none stands, and nothing here lets it. mtrack stays the default, so a run
        // that does not say otherwise is the configuration every published number was taken on.
        String smc = getIntent().getStringExtra("smc");
        if (smc != null && ("none".equals(smc) || "mtrack".equals(smc) || "full".equals(smc))) {
            smcMode = smc;
        }
        // extra guest environment, comma-separated: --es guestenv DOTNET_EnableAVX=0
        // these reach SharpEmu itself, unlike driverenv which reaches the GPU driver.
        String genv = getIntent().getStringExtra("guestenv");
        if (genv != null && !genv.isEmpty()) {
            guestEnv = genv.split(",");
        }
        // **which game, resolved once and for all here.** the three extras that can name one --
        // `game`, `safgame` and `saftree` -- are read by LaunchGame and by nothing else, and what
        // comes back is a GameSource: the same type the game list scans a library into. a launch
        // that cannot name a game it can reach comes back refused, carrying the sentence to show.
        File externalRoot = getExternalFilesDir(null);
        LaunchGame.Resolved resolved = LaunchGame.of(this, getIntent(),
                externalRoot == null ? null : AppStorage.games(externalRoot));
        if (resolved instanceof LaunchGame.Resolved.Refused) {
            refusal = ((LaunchGame.Resolved.Refused) resolved).getWhy();
        } else {
            source = ((LaunchGame.Resolved.Found) resolved).getSource();
        }
        // --es sharpemu <absolute path>, in the shape --es driver and --es game already have:
        // comparing two builds should be a loop over `am start`, not an APK rebuild per candidate.
        // **a path, never an id** -- see resolvePayload for why an id is refused. null here means
        // none was given, which is a real answer rather than a missing one.
        buildPath = getIntent().getStringExtra("sharpemu");

        // ------------------------------------------------------------------------------------
        // what the user chose, merged with what this launch said.
        //
        // **the intent wins, and an untouched row contributes nothing.** that second half is the
        // one worth guarding: if a row that has never been touched reported its default as a
        // choice, this activity would start passing that value on every launch, and `am start`
        // omitting the extra would no longer reach the default. a default that cannot be reached
        // by saying nothing is not a default, and every script in this repository launches by
        // saying nothing.
        //
        // **the store is this game's, with the app's own behind it**, so the precedence a launch
        // resolves is four deep: a build's env, then the app's settings, then this game's, then the
        // extras. a game that overrides nothing reads exactly as the app's own store does, so a
        // launch of a game nobody has configured is the argument vector it has always been.
        //
        // it is keyed by the title id the emulator itself resolves, which is also what names this
        // game's save data and its pipeline cache -- one game, one name, in all three places.
        game = source == null ? null : Game.read(source);
        titleId = game != null ? game.getEmulatorTitleId() : Game.UNKNOWN_TITLE_ID;
        configKey = game != null ? game.getConfigKey() : "";
        settings = Settings.forGame(this, configKey);
        // **the intent wins over the driver manager, and the manager over the constant.** an
        // untouched row leaves the store empty and the constant is null, so a launch that names
        // nothing loads the driver this device shipped with -- which is the configuration every
        // measurement in this project is taken on, and a baseline that moved with a stored setting
        // would not be one.
        if (driverName == null) {
            driverName = settings.getDriver();
        }
        if (driverName == null) {
            driverName = DRIVER;
        }
        // "stock", "system" and the empty string all mean the platform's own, so a script and the
        // manager say the same thing in the two spellings each already had.
        if (GpuDriver.isSystem(driverName)) {
            driverName = null;
        }
        // --ez strict true. hasExtra rather than a sentinel: getBooleanExtra cannot distinguish
        // "false" from "absent" and the difference between them is the whole point here.
        if (getIntent().hasExtra("strict")) {
            strictDynlib = getIntent().getBooleanExtra("strict", false);
        } else {
            strictDynlib = settings.getStrictDynlib();
        }
        // --es fexpreset performance, validated against the ladder this build knows rather than
        // passed through. it is a launch extra and still not a build one, exactly as --es smc is:
        // the FEXCore knobs behind it change how guest code is translated, and a payload able to
        // choose them is a payload able to break the thing running it.
        //
        // an unknown name is dropped and the settings row answers instead, which is what --es smc
        // does with a mode it does not recognise. a name that survives here is one the host layer
        // will also accept, since both sides read the same table.
        String asked = getIntent().getStringExtra("fexpreset");
        // "none" is not a rung and does not fall back to the store: it is the launch that names no
        // JIT configuration at all. see FexPreset.NONE.
        noJitConfiguration = FexPreset.isNone(asked);
        String preset = FexPreset.normalise(asked);
        // **a run naming nothing is recorded under the same key an untouched install is**, which is
        // right because it is the same translation: BootRecord.DEFAULT_PRESET is what a null means
        // where the boot is stamped.
        fexPreset = noJitConfiguration ? null : (preset != null ? preset : settings.getFexPreset());
        // **the rung named by an extra takes its own overrides with it, which is to say none.** the
        // rule the store already carries is that a level naming a preset owns the whole
        // configuration, and a launch is a level: a script asking for one rung and silently getting
        // it plus whatever rows are set would be a measurement of something nobody wrote down.
        fexOverrides = (preset != null || noJitConfiguration)
                ? new LinkedHashMap<>()
                : settings.fexOverrides();
        // --es fex "MaxInst=20000", comma-separated and appended after the preset and the rows
        // overriding it, so a knob the screen does not draw can be measured without an APK of its
        // own. the nine it does draw are reachable from the settings scene instead; what this is
        // for is the rest of FEXCore's option table, which is most of it. a launch that names
        // nothing here contributes nothing.
        // --ez hostprobe true, hasExtra for the reason --ez strict is: absent and false are
        // different answers. on is the default and says nothing on the command line, so this row
        // adds to the vector only when it is turned off.
        if (getIntent().hasExtra("hostprobe")) {
            hostFeatureProbe = getIntent().getBooleanExtra("hostprobe", true);
        } else {
            hostFeatureProbe = !Boolean.FALSE.equals(settings.getHostFeatureProbe());
        }
        String fex = getIntent().getStringExtra("fex");
        if (fex != null && !fex.isEmpty()) {
            fexOptions = fex.split(",");
        }
        // --ez shadercache true, hasExtra for the same reason --ez strict is: absent and false are
        // different answers here. **this is the one row whose "nobody said" is not the payload's own
        // default** -- Boolean.TRUE.equals leaves both an untouched row and an empty store off, which
        // is what makes the switch's default reachable by saying nothing.
        if (getIntent().hasExtra("shadercache")) {
            diskShaderCache = getIntent().getBooleanExtra("shadercache", false);
        } else {
            diskShaderCache = Boolean.TRUE.equals(settings.getDiskShaderCache());
        }
        // the environment the rows contribute -- the internal resolution, the .NET switch and any
        // custom assignments. there is no extra of its own for these: --es guestenv already reaches
        // the same map and already wins, so a second spelling would be a second thing to keep in
        // step with the first for no new capability.
        settingsEnv = settings.guestEnvironment();

        // a game boot is minutes of work with no touch input, and the screen going off takes the
        // surface with it.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // **the two Controls switches, read before anything can act on them.** neither becomes a
        // launch argument: they govern what this process does with events it receives and with a
        // request it is handed, so the argument vector is untouched by either. an untouched row is
        // null and both default to on.
        //
        // this process is given to one run and ended with it, so reading them once here is reading
        // them for the whole run -- there is no later launch to inherit a stale value.
        PadState.setEnabled(!Boolean.FALSE.equals(settings.getAutomaticControllerMapping()));
        PadRumble.setEnabled(!Boolean.FALSE.equals(settings.getVibrateHandheld()));
        // and the loading screen's estimate, read here for the same reason and defaulting the same
        // way: an untouched row leaves it on. it is not a launch argument either -- the host layer is
        // asked for boot progress on every launch regardless, since the position reaching the end of
        // its table is what tells this activity the guest has drawn and is what takes the loading
        // screen down. what this governs is only whether the bar is drawn against a prediction.
        //
        // **out of the app's own store rather than out of `settings`, which is the one row here that
        // is asked that way.** `settings` is this game's store with the app's behind it, and an App
        // section row does not fall back -- so asking it would find nothing in the game's file, get
        // null, and read as untouched however the switch is actually set. every other row this
        // activity reads is one a game may override; this one is the app's, like the theme.
        loadingEstimate = !Boolean.FALSE.equals(Settings.of(this).getLoadingEstimate());

        // the vibrator, before the guest starts, because the host layer's rumble path resolves its
        // java side at library load and would otherwise have somewhere to call and nothing behind it.
        // the application context rather than this activity: it outlives any one screen.
        PadRumble.attach(getApplicationContext());
        // and the pads already attached. android reports one arriving as an event and one that was
        // already there as nothing at all, so the initial sweep is the only thing that finds a
        // controller the device booted with -- which on this hardware is the built-in one.
        PadState.onDeviceChanged();

        SurfaceView view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        setContentView(withOverlays(view));
        goFullscreen();
    }

    /**
     * the gamepad, before the view hierarchy sees it.
     *
     * <p><b>{@code dispatchKeyEvent} rather than {@code onKeyDown}, and that is what makes the d-pad
     * work.</b> an unconsumed {@code DPAD_DOWN} reaching the views moves focus to whatever is
     * focusable, and the in-game panel has a button on it -- so the d-pad would walk the panel's focus
     * instead of reaching the game. taking the event here means the hierarchy never gets the chance.
     *
     * <p>{@code KEYCODE_BACK} is deliberately not one of the pad's keys, so a controller's own back
     * button still opens the panel like the software one does. everything else the pad claims goes no
     * further.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (PadState.onKey(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * the sticks and the analogue triggers, before the view hierarchy sees them, for the same reason.
     *
     * <p>a joystick motion event would otherwise be offered to the focused view first, and a focused
     * button treats a stick deflection as a focus move.
     */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (PadState.onMotion(event)) {
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    /**
     * a controller arriving or leaving mid-run.
     *
     * <p><b>removal is what this is for.</b> a pad that is used registers itself on its first event, so
     * one plugged in during a game is picked up without help. one unplugged is not an event at all, and
     * without this a stick that was held over when the cable came out stays held as far as the guest is
     * concerned, forever.
     */
    private final android.hardware.input.InputManager.InputDeviceListener padListener =
            new android.hardware.input.InputManager.InputDeviceListener() {
                @Override
                public void onInputDeviceAdded(int deviceId) {
                    PadState.onDeviceChanged();
                }

                @Override
                public void onInputDeviceRemoved(int deviceId) {
                    PadState.onDeviceChanged();
                }

                @Override
                public void onInputDeviceChanged(int deviceId) {
                    PadState.onDeviceChanged();
                }
            };

    @Override
    protected void onResume() {
        super.onResume();
        android.hardware.input.InputManager input =
                getSystemService(android.hardware.input.InputManager.class);
        if (input != null) {
            input.registerInputDeviceListener(padListener, null);
        }
        // and a sweep, because anything that changed while this activity was not listening produced no
        // callback to catch up on.
        PadState.onDeviceChanged();
    }

    /**
     * releases every control on the way out of the foreground.
     *
     * <p><b>the run keeps going and that is deliberate</b> -- nothing here pauses emulation. what must
     * not keep going is a button: this activity stops receiving key events when it loses focus, so the
     * release for a button held as the app went away would never arrive and the guest would see it held
     * for the rest of the run.
     */
    @Override
    protected void onPause() {
        super.onPause();
        android.hardware.input.InputManager input =
                getSystemService(android.hardware.input.InputManager.class);
        if (input != null) {
            input.unregisterInputDeviceListener(padListener);
        }
        PadState.clear();
    }

    /**
     * what a back press does during a run.
     *
     * <p><b>it never leaves.</b> a run holds nothing that survives being left -- no pause, no save of
     * ours -- so finishing on a back press would end a game at the depth of one accidental gesture,
     * and this activity is a full-screen surface where a gesture is easy to make by mistake. so back
     * opens the overlay, back again closes it, and leaving is the labelled button inside it.
     *
     * <p>{@code super} is deliberately never called, which is what makes that a rule rather than a
     * default. the framework's answer to a back press on the last activity of a task is to finish
     * it, and the process would then be left alive and warm -- the state {@link #endRun} exists to
     * prevent.
     *
     * <p>this is the legacy dispatch rather than an {@code OnBackInvokedCallback}, and it stays that
     * way while the manifest does not opt into the predictive gesture. opting in is a change to
     * every screen in the app at once and it wants its own piece of work.
     */
    @Override
    public void onBackPressed() {
        if (overlay.isOpen()) {
            overlay.close();
        } else {
            overlay.open();
        }
    }

    /** the surface, with the loading screen and then the back overlay over it. */
    private View withOverlays(SurfaceView view) {
        FrameLayout root = new FrameLayout(this);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // **both of these are built from a themed context rather than from this activity**, which is
        // how the scheme chosen in the settings scene reaches something drawn over a guest: this
        // window wears the framework fullscreen theme and has no colour roles to offer.
        Context themed = Theme.overlayContext(this);

        // under the back overlay, because the panel is how a person leaves a launch that is still
        // loading -- a screen that covered it would be the one moment in a run with no way out.
        loading = new GuestLoading(themed, this::onFirstFrame);
        // **the dump's own name and cover, from the read onCreate already did.** the icon is a File
        // for a staged game and a content uri for a granted one -- the split GameSource makes, and
        // the only two things coil is ever handed here.
        loading.describe(
                game != null ? game.getName() : "",
                source != null ? source.getIcon() : null);
        root.addView(loading.view(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // it is invisible until a back press and consumes nothing until then -- see GuestOverlay,
        // which is INVISIBLE rather than GONE so that the panel has a width to slide in from on the
        // first open.
        overlay = new GuestOverlay(themed, () -> {
            AppLog.i(TAG, "[app] exit game");
            endRun();
        });
        root.addView(overlay.view(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    /**
     * the guest has presented its first frame: the loading screen is gone and the boot is on record.
     *
     * <p><b>written here rather than when the run ends, because the run does not end anywhere this
     * process can see.</b> a guest calling {@code exit_group} ends the process from inside itself --
     * {@code nativeRun} never returns, {@code onDestroy} never runs -- so a record deferred to teardown
     * would never be written on the ending nearly every run takes. this is the same reason the
     * emulator's own pipeline cache is written by a periodic save.
     *
     * <p><b>it is keyed the way this launch was configured</b>, since that is what makes the last
     * boot a prediction of the next one rather than of a different run: the build and the JIT preset
     * for the half of a boot that is the emulator starting itself, the game and the GPU driver for
     * the half that is the game's own.
     */
    private void onFirstFrame(long[] times) {
        BootRecord.of(this).record(
                buildKey,
                fexPreset != null ? fexPreset : BootRecord.DEFAULT_PRESET,
                configKey,
                driverName != null ? driverName : BootRecord.STOCK_DRIVER,
                HostLayer.nativeBootCheckpointIds(),
                times);
    }

    /**
     * says why nothing is going to start. the going back is {@link #endRun}'s, which follows.
     *
     * <p><b>back rather than black.</b> a tap from the game list returns to it; an
     * {@code am start} that names nothing else simply ends, and the message is in the log either
     * way. the alternative is this activity sitting on a black surface forever with the reason
     * visible only to somebody running {@code logcat}.
     */
    private void abort(String message) {
        AppLog.e(TAG, "[app] " + message);
        runOnUiThread(() -> {
            // **handed to whoever started this rather than said here, when there is somebody to hand
            // it to.** a toast belongs to the process that posted it, and this process is about to
            // end: the platform cancels it along with us a few hundred milliseconds later, and what
            // a person sees is a flicker too short to read. the game list is in the process that
            // survives, so it says this instead -- see its own launcher.
            setResult(RESULT_FIRST_USER, new Intent().putExtra(ABORT_MESSAGE, message));
            // and when nothing started us for a result -- `am start`, every script -- there is nobody
            // to hand it to, so it is said here for what that is worth. the log is the real answer
            // on that path and it is the line above.
            if (getCallingActivity() == null) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        });
    }


    /**
     * ends the run, the activity and this process, in that order.
     *
     * <p><b>the process is the point.</b> finishing alone would leave {@code :guest} alive and
     * warm, and a warm one is a process that has a payload mapped, a guest address space reserved,
     * a JIT populated and a settings store cached from before whatever the user changed in the
     * other process -- so the second launch of one intent would not be the first launch again.
     * killing it is also the only way to reach a state {@code exit_group} reaches for free, which
     * is what makes the two endings one behaviour rather than two.
     *
     * <p>the kill is in {@code onDestroy} rather than here so that the activity record is gone
     * before the process hosting it is. killed first, the task is left resuming an activity whose
     * process died, which is a state android recovers from by restarting it -- the one outcome a
     * run that has ended must not have.
     */
    private void endRun() {
        runOnUiThread(() -> {
            ending = true;
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // so that a request already in flight cannot buzz after the run it belonged to is over.
        PadRumble.detach();
        if (ending) {
            AppLog.i(TAG, "[app] the run is over, and so is this process");
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            goFullscreen();
        }
    }

    /**
     * the system bars are not decoration here. they shrink the surface, and the surface is what
     * decides the extent the guest renders at -- so a visible navigation bar would mean the guest
     * presenting 1920x1005 into a panel that is 1920x1080.
     */
    private void goFullscreen() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // deliberately nothing. surfaceChanged always follows and is the first point with a size.
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        AppLog.i(TAG, "[app] surface " + width + "x" + height + " format " + format);
        surfaceWidth = width;
        surfaceHeight = height;
        HostLayer.nativeSetSurface(holder.getSurface());

        if (!started) {
            started = true;
            // endRun runs however runGuest leaves -- a payload that did not resolve, a game that is
            // not there, or a guest that returned. the one exit it never sees is exit_group, which
            // does not come back through nativeRun at all and does not need to: it has already
            // ended this process, which is the same thing endRun arranges.
            new Thread(() -> {
                runGuest();
                endRun();
            }, "sharpdroid-host-layer").start();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // the guest keeps running and its presents become no-ops. it has no idea, which is the
        // point of the host layer owning the swapchain.
        HostLayer.nativeSetSurface(null);
    }

    /**
     * resolves the chosen adrenotools driver package to a {@code .so} the loader will accept, or
     * null -- in which case the run is the driver this device shipped with and the flags below are
     * simply not passed.
     *
     * <p><b>an imported package is loaded where it is and a staged one is copied first</b>, and the
     * difference is the platform's rather than a preference. adrenotools stats the driver and then
     * {@code dlopen}s it, and {@code /storage/emulated/0} is mounted {@code noexec} -- so mapping the
     * library's executable segment off it fails with {@code EPERM}, the loader reports
     * {@code couldn't map … segment 2}, and adrenotools' hook falls back to the system driver while
     * still returning a usable handle. that is the quiet failure, not a loud one. so a package the
     * driver manager imported already lives on internal storage and needs nothing, while one staged
     * by {@code scripts/stage.py} is copied to where it can be mapped. External storage is also
     * FUSE-backed and this is 15 MB, which is the second reason not to load one in place.
     *
     * <p><b>the copy is a cache entry rather than app data</b>, because it is derived from bytes that
     * are still on external storage and is remade whenever it is missing. {@link AppStorage} says
     * what that buys.
     *
     * <p><b>a package that is gone ends the launch rather than falling back to the system driver.</b>
     * it is a state a user reaches without doing anything wrong -- deleted from a PC, or the volume
     * wiped -- and there is a driver that always works, which is what once made falling back look like
     * the kind thing to do. it is not: the game starts, the picture is right, and the only evidence
     * that the driver somebody chose did nothing is a line in a log. somebody comparing two drivers
     * then compares one of them with itself. the choice is stored, so a fallback is also not a
     * one-launch problem -- it is every launch from then on, silently. refusing says it once, on the
     * screen, and names where the choice is changed.
     *
     * <p>{@link #driverFailure} carries which message that is, because the difference between a
     * package that is gone and one that cannot be used is a difference the person can act on.
     */
    private String installDriver(File externalRoot) {
        driverFailure = 0;
        if (driverName == null) {
            return null;
        }
        File internalRoot = AppStorage.installedDrivers(getFilesDir());
        File stagedRoot = AppStorage.stagedDrivers(externalRoot);
        GpuDriver driver = GpuDriver.resolve(driverName, internalRoot, stagedRoot);
        if (driver == null) {
            AppLog.e(TAG, "[app] the chosen GPU driver '" + driverName + "' is in neither "
                    + internalRoot + " nor " + stagedRoot);
            driverFailure = R.string.driver_failed_missing;
            return null;
        }

        try {
            File source = driver.library();
            if (!source.isFile()) {
                AppLog.e(TAG, "[app] meta.json names " + driver.getLibraryName()
                        + " and it is not in " + driver.getDir());
                driverFailure = R.string.driver_failed;
                return null;
            }

            // already on internal storage, which is the only requirement there is. copying it a
            // second time would be 15 MB per launch to arrive at the same path.
            if (driver.isInstalled(internalRoot)) {
                AppLog.i(TAG, "[app] driver: " + driver.identity() + " at " + source);
                return source.getAbsolutePath();
            }

            // per driver, so switching between two packages cannot leave the previous one's
            // library sitting in the directory being pointed at.
            File installDir = AppStorage.installedDriver(getCacheDir(), driverName);
            if (!installDir.isDirectory() && !installDir.mkdirs()) {
                AppLog.e(TAG, "[app] could not create " + installDir);
                driverFailure = R.string.driver_failed;
                return null;
            }
            File installed = new File(installDir, driver.getLibraryName());

            // length is enough to notice a re-staged driver and cheap enough to check every
            // launch. a 15 MB copy off a FUSE volume is not something to do per run for nothing.
            if (installed.length() != source.length()) {
                try (InputStream in = new FileInputStream(source);
                     OutputStream out = new FileOutputStream(installed)) {
                    byte[] buffer = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                }
                AppLog.i(TAG, "[app] installed " + driver.getLibraryName() + " ("
                        + installed.length() + " bytes) to " + installDir);
            }

            AppLog.i(TAG, "[app] driver: " + driver.identity() + " at " + installed);
            return installed.getAbsolutePath();
        } catch (Exception e) {
            AppLog.e(TAG, "[app] could not install the driver", e);
            driverFailure = R.string.driver_failed;
            return null;
        }
    }

    /**
     * resolves {@link #buildPath} to a payload, or null if it does not resolve.
     *
     * <p><b>a build is named by path, and never by id.</b> an id names a family and not a build, so
     * resolving one means answering with the newest of it -- and a freshly staged build then loses
     * silently to a later-stamped one still lying around. that is a plausible number attributed to
     * the wrong artefact with nothing erroring, which is this project's most expensive failure
     * shape. a path cannot be ambiguous about which directory it meant.
     *
     * <p><b>an id here is refused outright rather than resolved</b>, because offering both forms is
     * what would keep the ambiguous one reachable. {@code hostContract} does not gate it: the
     * contract gates the <i>payload</i>, and this is a rule on the launcher's side of the line that
     * leaves every build byte-for-byte compatible either way. bumping it would refuse working builds
     * by name, which is a false negative in the mechanism built to prevent false negatives.
     *
     * <p><b>nothing at all means whatever the build manager settled on</b>, which is what the
     * scripts mean by omitting the flag. naming nothing is a real answer; naming something ambiguous
     * is not.
     */
    private File resolvePayload(File root) {
        File staged = AppStorage.stagedBuilds(root);
        File internal = AppStorage.installedBuilds(getFilesDir());
        SharpEmuBuild build;
        if (buildPath == null || buildPath.isEmpty()) {
            build = chosenBuild(internal, staged);
        } else if (!buildPath.startsWith("/")) {
            AppLog.e(TAG, "[app] --es sharpemu wants an absolute path to a build directory, and '"
                    + buildPath + "' is a name. an id is not accepted: it names a family rather than"
                    + " a build, so resolving one answers with the highest packagedAt of it and can"
                    + " run a different build to the one that was just staged. stage one with"
                    + " scripts/stage.py and pass the path it prints, or pass nothing for the"
                    + " build the manager settled on");
            return null;
        } else {
            build = SharpEmuBuild.resolvePath(new File(buildPath));
        }
        if (build == null) {
            return null;
        }
        // the launch log names the build, and that is not decoration: a third-party build
        // misbehaving arrives as "your emulator is broken", so a run has to be traceable to the
        // artefact that produced it without asking the person who ran it.
        AppLog.i(TAG, "[app] build: " + build.identity() + " at " + build.dir);
        if (!build.notes.isEmpty()) {
            AppLog.i(TAG, "[app]   " + build.notes);
        }
        buildEnv = build.env;
        // and what the boot record files this launch's timings under. the commit is what tells two
        // builds of one version apart, so it is the key wherever there is one; the folder is what a
        // build carrying no commit has, and it is unique per import.
        buildKey = build.shortCommit().isEmpty() ? build.folder : build.shortCommit();
        return build.payloadFile();
    }

    /**
     * which build a launch that named none runs.
     *
     * <p><b>three answers, and the first is the one that shipping exactly one build buys.</b> nothing
     * stored means the bundled build -- a concrete artefact, the same on every device, with no
     * per-release constant behind it and no toggle in front of it. a stored folder means that build.
     * neither present means the most recently staged one, which is a debug app's normal state and the
     * behaviour the deploy loop has always had.
     *
     * <p><b>a chosen build that is gone falls back loudly rather than refusing.</b> it is a state a
     * user reaches without doing anything wrong -- deleted from a PC, or the external volume wiped --
     * and with no error UI the alternative is a game that does not start with the reason only in a
     * log. the line names the build that was wanted <i>and</i> the one that ran, and the stored
     * choice is left alone: this is a launch working around a problem, not resolving it.
     */
    private SharpEmuBuild chosenBuild(File internal, File staged) {
        String folder = settings.getBuild();
        // **the reserved folder goes down the bundled path rather than being resolved as a folder**,
        // because before the first launch it is not a folder at all -- it is 76 MB of APK. resolving
        // it would find nothing and fall back to a staged build, which is a launch quietly running
        // something other than what the build manager's radio says it will.
        if (folder != null && !SharpEmuBuild.BUNDLED.equals(folder)) {
            SharpEmuBuild build = SharpEmuBuild.resolveFolder(folder, internal, staged);
            if (build != null) {
                return build;
            }
            AppLog.w(TAG, "[app] the chosen build '" + folder + "' could not be resolved, so falling"
                    + " back. the choice in Settings is unchanged");
        }
        return bundledBuild(internal, staged);
    }

    /**
     * the bundled build, unpacked out of the APK first if this is the launch that needs it.
     *
     * <p><b>nothing is extracted until now, which is what makes an app-only update free.</b> the
     * asset is a directory tree in the APK; this is the first launch that resolves to it, so this is
     * where it becomes a build directory. an update that carries a different fork commit
     * re-extracts, and one that carries the same build does not.
     *
     * <p><b>it is drawn as a phase of the launch rather than on a screen of its own.</b> the
     * extraction is a fraction of a second against the several seconds a boot takes, so a screen for
     * this one alone would dress the shortest wait in a launch and leave the longest bare. it is one
     * wait, on one screen, naming whichever part of itself it is in -- see {@link GuestLoading}.
     *
     * <p><b>a failure ends the launch instead of falling back.</b> running the most recently staged
     * build because the bundled one could not be written would be a plausible run attributed to the
     * wrong artefact -- this project's oldest and most expensive failure -- and on a release install
     * there would usually be no staged build to fall back to anyway. having nothing bundled is a
     * different answer entirely, and is the one a development build gives.
     */
    private SharpEmuBuild bundledBuild(File internal, File staged) {
        // **the progress this reports is deliberately not drawn, and the callback stays because it is
        // what says an unpack is happening at all.** the screen names the phase and leaves its bar
        // indeterminate for the whole launch -- see GuestLoading, where the reason is that an unpack
        // is a few hundred milliseconds of a launch of several seconds and cannot be a segment of a
        // bar the boot owns.
        BundledBuild.Outcome outcome = BundledBuild.ensure(this, internal,
                (done, total) -> loading.unpacking(R.string.loading_unpacking_build));
        if (outcome instanceof BundledBuild.Outcome.Ready ready) {
            return ready.getBuild();
        }
        if (outcome instanceof BundledBuild.Outcome.OutOfSpace out) {
            abort(getString(R.string.bundled_out_of_space,
                    Formatter.formatShortFileSize(this, out.getNeeded()),
                    Formatter.formatShortFileSize(this, out.getFree())));
            return null;
        }
        if (outcome instanceof BundledBuild.Outcome.Failed failed) {
            abort(failed.getWhy());
            return null;
        }
        // nothing ships in this APK, which is the normal state of a development build of the app.
        return SharpEmuBuild.mostRecent(staged, internal);
    }

    /**
     * the directory the guest's own linker searches, unpacked out of the APK first if this is the
     * launch that needs it.
     *
     * <p><b>it is not staged content, and that is what this pays for.</b> a set reaching a device only
     * over {@code adb} leaves a release install unable to start a game at all, and puts a working
     * install in that same state after a data wipe -- {@code clearApplicationUserData} takes the
     * external files directory, which is where a staged set lives. the APK carries the set instead;
     * {@link GuestLibraries} is where the two tiers are decided.
     *
     * <p><b>a failure ends the launch rather than falling back</b>, for {@link #bundledBuild}'s
     * reason and one of its own: there is nothing to fall back <i>to</i>. without an interpreter the
     * guest does not start, and the honest thing is to say so on the screen instead of leaving the
     * reason in {@code logcat}, which is exactly how this arrived as "tapping a game does nothing".
     */
    private File resolveGuestLibs(File root, File internal) {
        // the second of the two trees, named separately on the same line -- the same arrangement
        // bundledBuild has, and the same reason. **this one is the case that is not a fresh install**:
        // the set carries a packaging-time content hash, so an update bringing new libraries
        // re-extracts them beside a build that is already unpacked and a boot record that is already
        // good -- a launch with everything it needs to draw an estimate, which is exactly the one
        // that must not start drawing one halfway through.
        GuestLibraries.Outcome outcome = GuestLibraries.ensure(this, root, internal,
                (done, total) -> loading.unpacking(R.string.loading_unpacking_libs));
        GuestLibraries.report(outcome);
        if (outcome instanceof GuestLibraries.Outcome.Staged staged) {
            return staged.getDir();
        }
        if (outcome instanceof GuestLibraries.Outcome.Ready ready) {
            return ready.getDir();
        }
        if (outcome instanceof GuestLibraries.Outcome.OutOfSpace out) {
            abort(getString(R.string.guest_libs_out_of_space,
                    Formatter.formatShortFileSize(this, out.getNeeded()),
                    Formatter.formatShortFileSize(this, out.getFree())));
            return null;
        }
        if (outcome instanceof GuestLibraries.Outcome.Failed failed) {
            abort(failed.getWhy());
            return null;
        }
        // nothing in the APK and nothing staged. an APK built before the set was bundled is the only
        // way here, so the message names the command that fixes one.
        abort(getString(R.string.guest_libs_missing));
        return null;
    }

    /**
     * the title id the emulator will resolve for this launch's game, read the way it reads one.
     *
     * <p><b>in {@code onCreate}, because the settings this run merges are keyed by it.</b> a game's
     * own store is found by {@link Game#configKeyFor}, and the merge happens before anything is
     * resolved or installed -- so this is read early rather than beside the pipeline cache line it
     * also names. it is read <b>once</b> and both uses take the field.
     *
     * <p><b>both kinds of source answer it the same way, and the granted one needs no mount.</b>
     * {@link GameSource} opens {@code param.json} through whichever mechanism its kind implies, and
     * for a granted game that is a document read, which the grant alone is enough for. the host
     * layer's mount is for the <i>guest's</i> reads and stays where it is, in {@link #runGuest},
     * behind the driver check that has to come first.
     *
     * <p><b>every failure answers {@code UNKNOWN}</b> rather than refusing: a dump with no
     * {@code param.json} is a game that boots perfectly well, and a game that is not there at all is
     * a case {@link #runGuest} reports properly a moment later.
     */
    /**
     * makes this launch's game readable by the guest, and answers the path the guest will open.
     *
     * <p><b>this is the only place the two kinds of source mean anything different to a run</b>, and
     * the difference is one question: is there a path, or is there not. a staged directory is a real
     * one and the guest opens {@code eboot.bin} inside it with an ordinary {@code openat}; a granted
     * directory is not a path at all, so the file layer is pointed at it and the guest is handed an
     * invented path under {@link GuestFiles#MOUNT}. everything after this point is the same argument
     * vector either way.
     *
     * <p><b>each kind is checked before it is handed over, and for the same reason:</b> a game that
     * is not there would otherwise become a guest whose every file is missing, which reads as a
     * corrupt dump rather than as a game that was never found.
     *
     * @return the guest's path to {@code eboot.bin}, or null when the run has been refused
     */
    private String openGame() {
        if (source instanceof GameSource.Granted) {
            GameSource.Granted granted = (GameSource.Granted) source;
            if (!GuestFiles.mount(this, granted.getTree(), granted.getDocumentId())) {
                abort(getString(R.string.launch_game_missing, granted.getFolder()));
                return null;
            }
            return GuestFiles.MOUNT + "/" + Game.EBOOT;
        }
        File directory = ((GameSource.Staged) source).getDirectory();
        File eboot = new File(directory, Game.EBOOT);
        if (!eboot.exists()) {
            // **the log says which of the two ways this could have happened**, since they are fixed
            // differently: a directory under the app's own games/ that is not there was never staged,
            // and one anywhere else is a path this app is not allowed to read -- which is what
            // all-files access being revoked between the tap and the launch looks like from in here.
            AppLog.e(TAG, "[app] missing: " + eboot.getAbsolutePath()
                    + " -- either it was never staged, or this app cannot read that path");
            abort(getString(R.string.launch_game_missing, directory.getName()));
            return null;
        }
        return eboot.getAbsolutePath();
    }

    private void runGuest() {
        // **the game this launch named, settled in onCreate, and the first thing asked about.**
        // nothing below is worth doing for a launch that never worked out what to run -- and this
        // refusal is the only one already in hand when the surface arrives, so it is also the fastest
        // one there is.
        if (source == null) {
            abort(refusal != null ? refusal : getString(R.string.launch_no_game));
            return;
        }
        File root = getExternalFilesDir(null);
        if (root == null) {
            abort(getString(R.string.launch_no_storage));
            return;
        }
        // where everything the emulator writes for the person using it goes. never null, unlike the
        // external root, which is why it is not checked.
        File files = getFilesDir();

        // **the driver first, before a payload is resolved or a byte of a game is touched.** it is
        // the only thing here that can refuse a launch on grounds the person can do something about,
        // and it is settled in milliseconds -- so settling it first is the difference between a
        // refusal that looks like the tap did nothing and one that arrives after several seconds of
        // black screen.
        String driver = installDriver(root);
        // and then the one question this side cannot answer: adrenotools falls back to the system
        // driver and returns a handle that is good in every way, so a package that resolves, exists
        // and copies correctly still may not be the driver a guest renders through. the host layer
        // opens it -- the same one-time open the guest's first Vulkan call would have done -- and says.
        if (driverFailure == 0 && driver != null
                && !HostLayer.nativeDriverLoads(driver, getApplicationInfo().nativeLibraryDir)) {
            driverFailure = R.string.driver_failed;
        }
        if (driverFailure != 0) {
            abort(getString(driverFailure));
            return;
        }

        File payload = resolvePayload(root);
        if (payload == null) {
            return;
        }

        // the game, one of two ways. a directory that is a real path is opened with an ordinary
        // openat; a granted one is not a path at all, so the host layer is told to mount the
        // provider and the guest is handed an invented path under it. everything after this point is
        // the same argument vector either way.
        //
        // the path form has two sources and one implementation: a name resolved under the app's own
        // games/, which is what the tooling stages and what every measurement was taken on, and an
        // absolute path, which is a game in a folder the user granted, reached directly because
        // all-files access is on. the second is deliberately not a mode of its own -- it is this one,
        // pointed somewhere else.
        // the title id was resolved in onCreate, because the settings this run merges are keyed by
        // it -- see the game field. it names the pipeline cache's directory below, which is the
        // emulator's to name everywhere except here.
        String guestGame = openGame();
        if (guestGame == null) {
            return;
        }
        if (!payload.exists()) {
            AppLog.e(TAG, "[app] missing: " + payload.getAbsolutePath()
                    + " -- stage it with scripts/stage.py");
            return;
        }
        File guestLibs = resolveGuestLibs(root, files);
        if (guestLibs == null) {
            return;
        }

        List<String> args = new ArrayList<>();
        args.add("--timestamps");
        if (logTids) {
            // the stamp gains the writing thread's id. what it is for: logcat stamps every line
            // with the log pump's thread rather than its author's, so a guest thread named in the
            // emulator's own output cannot otherwise be matched against a counter that names a
            // thread id.
            args.add("--log-tids");
        }
        // where the boot has got to, which only something with a screen in front of a booting guest
        // has any use for. always, rather than behind a setting: a run that turned it off would be a
        // run whose loading screen could not say anything, and it costs a substring search over the
        // guest's log lines until the first frame appears and nothing at all afterwards.
        args.add("--boot-progress");
        args.add("--vulkan");
        // the audio thunk, in the shape --vulkan has. nothing at all is needed from this side
        // besides the flag: AAudio is a pure NDK C API, so there is no JNI, no looper and no
        // permission -- RECORD_AUDIO gates input and this only ever plays.
        args.add("--audio");
        if (audioWatchdog) {
            args.add("--audio-watchdog");
        }
        // the pad bridge, in the shape the two above have. it needs nothing else from this side beyond
        // the flag and the pushes PadState makes: the state travels down and the guest polls for it, so
        // there is no thread and no callback anywhere in it.
        args.add("--pad");
        if (tracePad) {
            args.add("--trace-pad");
        }
        if (padSelfTest) {
            args.add("--pad-selftest");
        }
        // the custom driver, if one is staged. both flags or neither: with neither, the host layer
        // opens the platform loader exactly as every measurement up to here did, so the stock
        // baseline stays reproducible from the same build.
        if (driver != null) {
            args.add("--vulkan-driver");
            args.add(driver);
            // and the hooks, which adrenotools loads by soname from this directory and nowhere
            // else. it must be nativeLibraryDir itself -- a directory that merely contains copies
            // of them is not the same thing, and getting it wrong fails by quietly falling back
            // to the stock driver rather than by erroring.
            args.add("--vulkan-hooks");
            args.add(getApplicationInfo().nativeLibraryDir);
            for (String assignment : driverEnv) {
                args.add("--vulkan-driver-env");
                args.add(assignment);
            }
        }
        if (profile) {
            args.add("--vulkan-profile");
        }
        if (turbo) {
            args.add("--vulkan-turbo");
        }
        args.add("--smc");
        args.add(smcMode);
        // the JIT preset, as one --fex per knob. here rather than in the environment map below on
        // purpose: this governs how guest code is translated, so it belongs with --smc and the
        // --vulkan-* family rather than among things a build may express.
        //
        // null contributes nothing, so a launch that names no preset and a settings row nobody
        // touched both produce the argument vector every measurement was taken on.
        // the conservative feature set, named only when it is asked for. the host layer probes by
        // default, so on contributes nothing and off is one flag -- which keeps a launch that says
        // nothing identical to what it always was.
        if (!hostFeatureProbe) {
            args.add("--host-features");
            args.add("minimal");
        }
        // **the whole configuration, or none of it.** a rung names every knob this app draws, so
        // what a row shows is what the run got -- and the one launch that names nothing is the
        // measurement vector, which has to stay expressible rather than merely unreachable.
        if (!noJitConfiguration) {
            args.addAll(FexPreset.arguments(fexPreset));
            // the rows the user set on top of that rung, after it for the same reason --es fex is
            // after both: the host layer applies these in order and keeps the last assignment to a
            // name, so a knob emitted after a rung replaces what the rung said about it.
            args.addAll(FexPreset.overrideArguments(fexOverrides));
        }
        // after the preset and after the rows, so a launch measuring one knob overrides both the
        // rung it is measured against and anything stored, rather than fighting either: the host
        // layer applies these in order and the last assignment to a name is the one FEXCore keeps.
        for (String option : fexOptions) {
            String trimmed = option.trim();
            if (!trimmed.isEmpty()) {
                args.add("--fex");
                args.add(trimmed);
            }
        }

        // guest environment, in precedence order: **build defaults < app settings < intent
        // extras**, last wins. it is a map rather than a list of --env flags so a variable a build
        // defaults on and a launch overrides reaches the guest once, with the override's value --
        // two --env flags naming the same variable would be a coin toss over which the guest reads.
        //
        // the missing tier is explicit --env on the shell binary's command line, which is above all
        // three and is not reachable from here. a build may set *only* this: --smc, --asyncsig and
        // the --vulkan-* family are properties of the host layer's correctness, and a payload able
        // to ask for --smc none is a payload able to break the thing running it.
        Map<String, String> env = new LinkedHashMap<>(buildEnv);
        // the settings scene's contribution, between the build's defaults and the eight below. it is
        // empty unless a row was actually touched.
        env.putAll(settingsEnv);
        // the first is load-bearing: without it the SMC tracker cannot see CoreCLR's JIT writes and
        // a boot costs 65x. a launch may override it with --es guestenv and nothing else can.
        env.put("DOTNET_EnableWriteXorExecute", "0");
        // without this one the fork constructs an SDL window and dies on "No available video
        // device". the three below are the contract a payload is run under rather than a
        // preference, and they are written after the settings map on purpose: nothing a user or a
        // build can choose may reach them.
        env.put("SHARPEMU_HOST_WINDOW", "android");
        // and the third: without it the fork asks SDL for an audio device, SDL names four backends
        // android does not have, and the port degrades to backend=silent with nothing erroring.
        // that is the failure hostContract 2 exists to refuse, so this and the contract move
        // together.
        env.put("SHARPEMU_HOST_AUDIO", "android");
        // and the fourth of the same family: without it the payload registers no input source at all,
        // and its pad exports report a controller that is permanently connected and permanently
        // neutral -- a game that responds to nothing, with nothing anywhere returning an error. that is
        // the same shape of failure the audio selector exists to prevent, so it is written here beside
        // it and above anything a build or a settings row can reach.
        env.put("SHARPEMU_HOST_INPUT", "android");
        // and this is the one that stops the extent being a coincidence: the host has the window,
        // the guest does not, so the size travels from here rather than being agreed by two
        // separately hand-set defaults.
        env.put("SHARPEMU_HOST_WINDOW_SIZE", surfaceWidth + "x" + surfaceHeight);
        // and then the ones that move the emulator's own user directory out of the build it is
        // running. SharpEmu is portable software and resolves all four of these next to its own
        // executable; on android that executable sits in a directory the app re-stages, re-unpacks
        // and deletes, so anything written beside it has a lifetime nobody chose. each variable is
        // upstream's own and each is read only when it is set, so a payload too old to know one
        // keeps the portable behaviour for that one -- which is why the contract number does not
        // move for any of them. AppStorage.user is where they point and why.
        //
        // the layout under them is the emulator's own in every case. it keys save data itself, from
        // the title id it reads out of the dump; the pipeline cache's variable takes a file rather
        // than a root, so that one key is read here instead -- the same field, the same sanitising.
        //
        // these ask nothing of a payload, the way DOTNET_EnableWriteXorExecute does, and they are
        // written after the settings map for the same reason the three above are: a build's env or
        // a settings row may not quietly relocate somebody's saves.
        env.put("SHARPEMU_SAVEDATA_DIR", AppStorage.saveData(files).getAbsolutePath());
        env.put("SHARPEMU_VK_PIPELINE_CACHE_PATH",
                AppStorage.pipelineCache(files, titleId).getAbsolutePath());
        // and the switch above it, which decides whether that path is ever read or written.
        //
        // **the path is written either way, and only the mode moves.** the emulator reads the path
        // only while persistence is on, so naming it costs a disabled run nothing -- and the
        // alternative, leaving the variable out, is not an off switch at all: unset means the
        // emulator resolves its own portable default, which is a directory inside the build. that
        // would keep caching and put the bytes somewhere a re-stage deletes.
        //
        // it is written here beside the other four rather than in the settings map because the
        // app's answer when nobody has said anything is *off*, and the settings map is where a row
        // that was touched speaks. a launch naming no extras still gets this line.
        if (!diskShaderCache) {
            env.put("SHARPEMU_VK_PIPELINE_CACHE", "0");
        }
        env.put("SHARPEMU_HOSTAPP_DIR", AppStorage.hostApp(files).getAbsolutePath());
        env.put("SHARPEMU_DEVLOG_APP_DIR", AppStorage.devLogApp(files).getAbsolutePath());
        for (String assignment : guestEnv) {
            int eq = assignment.indexOf('=');
            if (eq < 1) {
                AppLog.e(TAG, "[app] --es guestenv wants NAME=VALUE, ignoring '" + assignment + "'");
                continue;
            }
            env.put(assignment.substring(0, eq), assignment.substring(eq + 1));
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            args.add("--env");
            args.add(e.getKey() + "=" + e.getValue());
        }

        args.add("--libs");
        args.add(guestLibs.getAbsolutePath());
        // internal storage, not the external one the payload sits on: .NET reaches for TMPDIR far
        // more than for its own bundle, and the external volume is FUSE-backed on Android 11+, so
        // every file operation there is a userspace round trip.
        args.add("--tmp");
        args.add(getCacheDir().getAbsolutePath());
        // the mount, and only when a granted game asked for one. the flag being absent is what keeps
        // an ordinary run on exactly the code path it has always been on.
        if (source instanceof GameSource.Granted) {
            args.add("--saf-mount");
            args.add(GuestFiles.MOUNT);
        }
        if (traceFiles) {
            // the game's own directory rather than the one above it, so a second staged game cannot
            // land in the counts, and so the numbers stay comparable between two runs of different
            // titles -- what the guest asks of *a* game is the measurement, not what it asks of the
            // directory games happen to share. under a mount that is the mount itself, which is the
            // same directory named the other way, so the two ways of reaching one game produce two
            // counts that can be put side by side.
            args.add("--trace-files");
            args.add(new File(guestGame).getParent());
        }
        args.add(payload.getAbsolutePath());
        args.add(guestGame);
        // **everything past the payload is the guest's own command line**, which the host layer
        // passes through without reading -- its usage string has said `<x86-64-elf> [guest args...]`
        // since it took its first argument, and this is the first thing to use it. --strict is
        // SharpEmu.CLI's own flag and nothing below the payload knows what it means.
        //
        // null here is "the user did not say", and it passes nothing at all, which is the argument
        // vector every measurement in this project was taken on.
        if (Boolean.TRUE.equals(strictDynlib)) {
            args.add("--strict");
        }

        // **named the way the run reaches it**, because the two are different enough that a log
        // saying only "game: X" would not tell you which of the two arms produced the numbers under
        // it: one opens files with ordinary syscalls and the other answers every one of them through
        // a content provider. what is printed is what that arm actually addresses the game by -- a
        // directory on a volume, or a document id inside a grant.
        AppLog.i(TAG, "[app] game: " + (source instanceof GameSource.Granted
                ? ((GameSource.Granted) source).getDocumentId() + " (through a grant)"
                : ((GameSource.Staged) source).getDirectory().getAbsolutePath() + " (a path)"));
        AppLog.i(TAG, "[app] starting: " + String.join(" ", args));
        // **the last thing before the host layer starts, because that is where its clock starts.**
        // everything a boot reports is measured from its own entry, and the app's wait began at the
        // tap with the driver check and any unpacking in between -- so the two halves of the screen's
        // timeline only join if this moment is taken here.
        //
        // **a null here is the indeterminate bar, and it is also what the switch in Settings hands
        // over** -- the store is not opened at all when the estimate is off, rather than opened and
        // its answer discarded, so off is the state a device with no record is already in and not a
        // second way of reaching it.
        loading.booting(!loadingEstimate ? null : BootRecord.of(this).expected(
                buildKey,
                fexPreset != null ? fexPreset : BootRecord.DEFAULT_PRESET,
                configKey,
                driverName != null ? driverName : BootRecord.STOCK_DRIVER));
        int status = HostLayer.nativeRun(args.toArray(new String[0]));
        AppLog.i(TAG, "[app] host layer returned " + status);
        // the lookups that came back empty, counted rather than each one reported. it prints only
        // when the guest returns rather than calling exit_group, which is the same limitation the
        // line above it has always had.
        if (source instanceof GameSource.Granted) {
            AppLog.i(TAG, "[app] " + GuestFiles.missCount() + " lookups came back empty");
        }
    }
}
