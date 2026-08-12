package com.mircowuffwuff.sharpemu;

import android.app.Activity;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.Log;
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
 * One activity, one SurfaceView, one guest — <b>and one process, which ends with the run</b>.
 *
 * <p>The manifest puts this activity in {@code :guest}, and the reason is the guest's own exit: a
 * guest calling {@code exit_group} ends the process it is in, and the host layer answers with
 * {@code _exit} because the other guest threads are inside translated code and cannot be unwound.
 * Sharing a process with the game list would make that the app dying with the game.
 *
 * <p><b>The other half is that this process is ended deliberately when a run finishes any other
 * way</b> — see {@link #endRun}. A guest run leaves a loaded payload, a guest address space and a
 * JIT behind it, and none of that may reach the next run; a process that answered a second launch
 * would also be answering it from whatever it had cached of the settings store before the user
 * changed a row in the other process. So the invariant is one process per guest run, and it is what
 * makes two launches of one intent the same launch twice.
 *
 * <p>The payload lives in the app's own external files directory rather than in
 * {@code /data/local/tmp}, which is {@code shell_data_file} and which SELinux denies an app. That
 * costs nothing: the host layer maps guest images into anonymous memory and reads into them rather
 * than mapping them from a file, so a {@code noexec} volume is not a problem and never was.
 */
public final class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "sharpemu";
    /**
     * The game directory under {@code <external files>/games/}, without the {@code eboot.bin}.
     *
     * <p>Overridden per launch by {@code --es game <name>}, for the same reason the driver is an
     * extra: comparing titles should not cost an APK rebuild.
     */
    private static final String GAME = "Dreaming Sarah [PPSA02929]";

    /**
     * {@code --es game}: a directory name under {@code games/}, <b>or an absolute path to one</b>.
     *
     * <p><b>A leading slash is the whole distinction, and it cannot be ambiguous</b> — a directory
     * name under {@code games/} does not begin with one. A path is what the game list sends for a
     * game in a granted folder while all-files access is held, and what a script sends to reach a
     * library outside the app's own directory. Either way it is one code path, and it is the one a
     * staged game has always taken: an ordinary directory the guest opens with ordinary syscalls,
     * with no interception registered anywhere.
     */
    private String gameName;

    /**
     * {@code --es safgame <directory name>}, a game inside the granted tree instead of a staged one.
     *
     * <p>Null means the staged path, which is the mode every script uses and every number was
     * measured on. The two are deliberately reachable side by side, on the same build, so the cost of
     * the file layer stays something that can be measured rather than argued about.
     */
    private String safGameName;

    /**
     * {@code --es saftree <tree uri>}, which of the granted trees {@link #safGameName} is in.
     *
     * <p><b>Absent means the first persisted read grant this app holds</b>, which is what
     * {@code am start --es safgame} means from a script. With one grant that is the same answer as
     * naming it; with two it is not, and the list is the only thing that knows which the user
     * tapped.
     */
    private String safTreeUri;

    /**
     * Which staged GPU driver to inject, or null for the stock Adreno one.
     *
     * <p>A folder name: a package the driver manager imported, or one staged under
     * {@code <external files>/gpu-drivers/} by {@code scripts/stage.py}. Both spellings
     * resolve through {@link GpuDriver}, so a driver a script names and one a user chose are the
     * same code path.
     *
     * <p>This constant is the last of the three answers rather than the only one: the launch intent
     * wins, then Settings → Graphics → Custom driver, then this.
     *
     * <p><b>Null on purpose.</b> Turnip injection works, and the stock driver is nonetheless the
     * default, because it is the configuration every measurement in this project is taken at — a
     * baseline that moves with the driver is not a baseline.
     *
     * <p>Overridden per launch by {@code --es driver <name>}, so comparing drivers is a loop over
     * {@code am start} rather than a rebuild each time. {@code stock} means the same as null.
     */
    private static final String DRIVER = null;

    // Which SharpEmu build to run is deliberately not a constant here. It is
    // `--es sharpemu <absolute path to a build directory>`, or, with no extra, whatever the build
    // manager settled on — see chosenBuild. Exactly one build ships inside the APK, so a launch that
    // names nothing has a concrete artefact to answer with rather than a rule to apply.

    /** Resolved once in {@link #onCreate}, because the intent is not readable from a worker. */
    private String driverName;
    private String[] driverEnv = {};
    private boolean profile;
    private boolean turbo;
    private boolean audioWatchdog;
    /** {@code --ez tracefiles}, counting the guest's file access under the game directory. */
    private boolean traceFiles;
    /** The host layer's SMC tracking mode. mtrack is the default every measurement was taken on. */
    private String smcMode = "mtrack";
    private String[] guestEnv = {};
    /** {@code --es sharpemu}, an absolute path to a build directory, or null for the latest staged. */
    private String buildPath;
    /** The selected build's own environment defaults. The lowest-precedence source there is. */
    private Map<String, String> buildEnv = new LinkedHashMap<>();

    /**
     * {@code --strict} on the payload's own command line, or null for "the user did not say".
     *
     * <p><b>Three-valued rather than boolean, and that is the whole precedence rule in one field.</b>
     * A launch that names {@code --ez strict} wins; a launch that does not falls back to what the
     * settings scene stored; a setting the user never touched leaves this null and the flag is not
     * passed at all. Collapsing it to a boolean would make "off" and "unsaid" the same answer, and
     * the day a default changes, every script that relied on omission would silently be pinned to
     * the old one.
     */
    private Boolean strictDynlib;

    /**
     * The FEXCore JIT preset, or null for "the user did not say".
     *
     * <p>Three-valued for the same reason {@link #strictDynlib} is, and null passes no {@code --fex}
     * at all — which is the argument vector every measurement in this project was taken on, and is
     * also what {@link FexPreset#COMPATIBILITY} would produce, since every value in that rung is
     * FEXCore's own default.
     *
     * <p><b>It is a host-layer flag and not a guest environment variable.</b> See {@link FexPreset}:
     * the {@code FEX_} environment spelling other FEX frontends use is read by machinery this
     * project does not build, so it would reach nothing here while looking like it had.
     */
    private String fexPreset;

    /**
     * What the settings scene contributes to the guest environment. Empty when nothing was chosen.
     *
     * <p>It sits above the build's own {@code env} and below the launcher's five, which is the order
     * {@code docs/build-format.md} documents — and below {@code --es guestenv}, so a script naming a
     * variable still wins over a row that set it.
     */
    private Map<String, String> settingsEnv = new LinkedHashMap<>();

    private boolean started;
    /** Set once the run is over, so {@code onDestroy} can tell an ending from an ordinary one. */
    private boolean ending;
    private int surfaceWidth;
    private int surfaceHeight;

    /**
     * The panel the back button opens, and the only way out of a run that is not the guest's own.
     *
     * <p>It is the only thing this activity draws, and it exists from the moment the activity does
     * rather than from the moment a guest starts — a launch is several seconds of black screen
     * before the first frame, and being unable to leave during it is exactly the state the overlay
     * is for.
     */
    private GuestOverlay overlay;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

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
        gameName = getIntent().getStringExtra("game");
        if (gameName == null || gameName.isEmpty()) {
            gameName = GAME;
        }
        // --es safgame <directory name>, naming a game inside the tree the user granted rather than
        // one staged into the app's own directory. **absent is the whole point**: without it nothing
        // here changes, the game is a path, no interception is registered, and the run is exactly the
        // one every measurement so far was taken on. That is what keeps a frame rate measured through
        // the scripts free of any alibi.
        safGameName = getIntent().getStringExtra("safgame");
        // --es saftree <tree uri>, naming which grant that directory is in. the game list sends it
        // because it knows which folder the row came out of; a script omits it and gets the first
        // grant, which is what a device with one granted folder means either way.
        safTreeUri = getIntent().getStringExtra("saftree");
        // --es sharpemu <absolute path>, in the shape --es driver and --es game already have:
        // comparing two builds should be a loop over `am start`, not an APK rebuild per candidate.
        // **A path, never an id** — see resolvePayload for why an id is refused. Null here means
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
        Settings settings = Settings.of(this);
        // **the intent wins over the driver manager, and the manager over the constant.** an
        // untouched row leaves the store empty and the constant is null, so a launch that names
        // nothing loads the driver this device shipped with — which is the configuration every
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
        String preset = FexPreset.normalise(getIntent().getStringExtra("fexpreset"));
        fexPreset = preset != null ? preset : settings.getFexPreset();
        // the environment the rows contribute -- the internal resolution, the .NET switch and any
        // custom assignments. there is no extra of its own for these: --es guestenv already reaches
        // the same map and already wins, so a second spelling would be a second thing to keep in
        // step with the first for no new capability.
        settingsEnv = settings.guestEnvironment();

        // a game boot is minutes of work with no touch input, and the screen going off takes the
        // surface with it.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        SurfaceView view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        setContentView(withOverlays(view));
        goFullscreen();
    }

    /**
     * What a back press does during a run.
     *
     * <p><b>It never leaves.</b> A run holds nothing that survives being left — no pause, no save of
     * ours — so finishing on a back press would end a game at the depth of one accidental gesture,
     * and this activity is a full-screen surface where a gesture is easy to make by mistake. So back
     * opens the overlay, back again closes it, and leaving is the labelled button inside it.
     *
     * <p>{@code super} is deliberately never called, which is what makes that a rule rather than a
     * default. The framework's answer to a back press on the last activity of a task is to finish
     * it, and the process would then be left alive and warm — the state {@link #endRun} exists to
     * prevent.
     *
     * <p>This is the legacy dispatch rather than an {@code OnBackInvokedCallback}, and it stays that
     * way while the manifest does not opt into the predictive gesture. Opting in is a change to
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

    /** The surface, with the back overlay over it. */
    private View withOverlays(SurfaceView view) {
        FrameLayout root = new FrameLayout(this);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // it is invisible until a back press and consumes nothing until then — see GuestOverlay,
        // which is INVISIBLE rather than GONE so that the panel has a width to slide in from on the
        // first open.
        //
        // **it is built from a themed context rather than from this activity**, which is how the
        // scheme chosen in the settings scene reaches something drawn over a guest: this window
        // wears the framework fullscreen theme and has no colour roles to offer.
        overlay = new GuestOverlay(Theme.overlayContext(this), () -> {
            Log.i(TAG, "[app] exit game");
            endRun();
        });
        root.addView(overlay.view(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    /**
     * Says why nothing is going to start. The going back is {@link #endRun}'s, which follows.
     *
     * <p><b>Back rather than black.</b> A tap from the game list returns to it; an
     * {@code am start} that names nothing else simply ends, and the message is in the log either
     * way. The alternative is this activity sitting on a black surface forever with the reason
     * visible only to somebody running {@code logcat}.
     */
    private void abort(String message) {
        Log.e(TAG, "[app] " + message);
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    /**
     * Ends the run, the activity and this process, in that order.
     *
     * <p><b>The process is the point.</b> Finishing alone would leave {@code :guest} alive and
     * warm, and a warm one is a process that has a payload mapped, a guest address space reserved,
     * a JIT populated and a settings store cached from before whatever the user changed in the
     * other process — so the second launch of one intent would not be the first launch again.
     * Killing it is also the only way to reach a state {@code exit_group} reaches for free, which
     * is what makes the two endings one behaviour rather than two.
     *
     * <p>The kill is in {@code onDestroy} rather than here so that the activity record is gone
     * before the process hosting it is. Killed first, the task is left resuming an activity whose
     * process died, which is a state android recovers from by restarting it — the one outcome a
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
        if (ending) {
            Log.i(TAG, "[app] the run is over, and so is this process");
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    /**
     * Every event this window is handed, before any view sees it.
     *
     * The panel's own trace says what reached the button; this says what reached the window, so a
     * cancel that the hierarchy invented can be told from one the input system delivered.
     */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        Log.i(TAG, "[window] action " + event.getActionMasked()
                + " at " + (int) event.getX() + "," + (int) event.getY()
                + " raw " + (int) event.getRawX() + "," + (int) event.getRawY()
                + " since " + (event.getEventTime() - event.getDownTime()) + "ms"
                + " pointers " + event.getPointerCount()
                + " flags " + event.getFlags());
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        Log.i(TAG, "[window] focus " + hasFocus);
        if (hasFocus) {
            goFullscreen();
        }
    }

    /**
     * The system bars are not decoration here. They shrink the surface, and the surface is what
     * decides the extent the guest renders at — so a visible navigation bar would mean the guest
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
        Log.i(TAG, "[app] surface " + width + "x" + height + " format " + format);
        surfaceWidth = width;
        surfaceHeight = height;
        HostLayer.nativeSetSurface(holder.getSurface());

        if (!started) {
            started = true;
            // endRun runs however runGuest leaves — a payload that did not resolve, a game that is
            // not there, or a guest that returned. the one exit it never sees is exit_group, which
            // does not come back through nativeRun at all and does not need to: it has already
            // ended this process, which is the same thing endRun arranges.
            new Thread(() -> {
                runGuest();
                endRun();
            }, "sharpemu-host-layer").start();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // the guest keeps running and its presents become no-ops. it has no idea, which is the
        // point of the host layer owning the swapchain.
        HostLayer.nativeSetSurface(null);
    }

    /**
     * Resolves the chosen adrenotools driver package to a {@code .so} the loader will accept, or
     * null — in which case the run is the driver this device shipped with and the flags below are
     * simply not passed.
     *
     * <p><b>An imported package is loaded where it is and a staged one is copied first</b>, and the
     * difference is the linker's rather than a preference. adrenotools stats the driver and then
     * {@code dlopen}s it, and the linker refuses a library from anywhere another app could have
     * written it — which is what {@code /storage/emulated/0} is. So a package the driver manager
     * imported already lives on internal storage and needs nothing, while one staged by
     * {@code scripts/stage.py} is copied to where the linker will take it. External storage
     * is also FUSE-backed and this is 15 MB, which is the second reason not to load one in place.
     *
     * <p><b>A package that is gone falls back to the system driver rather than failing the launch.</b>
     * It is a state a user reaches without doing anything wrong — deleted from a PC, or the volume
     * wiped — and there is a driver that always works. What it must not do is happen quietly, so the
     * name that was wanted is logged.
     */
    private String installDriver(File externalRoot) {
        if (driverName == null) {
            return null;
        }
        File internalRoot = AppStorage.installedDrivers(getFilesDir());
        File stagedRoot = AppStorage.stagedDrivers(externalRoot);
        GpuDriver driver = GpuDriver.resolve(driverName, internalRoot, stagedRoot);
        if (driver == null) {
            Log.e(TAG, "[app] the chosen GPU driver '" + driverName + "' is in neither "
                    + internalRoot + " nor " + stagedRoot + " — using the system driver");
            return null;
        }

        try {
            File source = driver.library();
            if (!source.isFile()) {
                Log.e(TAG, "[app] meta.json names " + driver.getLibraryName()
                        + " and it is not in " + driver.getDir());
                return null;
            }

            // already on internal storage, which is the only requirement there is. copying it a
            // second time would be 15 MB per launch to arrive at the same path.
            if (driver.isInstalled(internalRoot)) {
                Log.i(TAG, "[app] driver: " + driver.identity() + " at " + source);
                return source.getAbsolutePath();
            }

            // per driver, so switching between two packages cannot leave the previous one's
            // library sitting in the directory being pointed at.
            File installDir = AppStorage.installedDriver(getFilesDir(), driverName);
            if (!installDir.isDirectory() && !installDir.mkdirs()) {
                Log.e(TAG, "[app] could not create " + installDir);
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
                Log.i(TAG, "[app] installed " + driver.getLibraryName() + " ("
                        + installed.length() + " bytes) to " + installDir);
            }

            Log.i(TAG, "[app] driver: " + driver.identity() + " at " + installed);
            return installed.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "[app] could not install the driver", e);
            return null;
        }
    }

    /**
     * Resolves {@link #buildPath} to a payload, or null if it does not resolve.
     *
     * <p><b>A build is named by path, and never by id.</b> An id names a family and not a build, so
     * resolving one means answering with the newest of it — and a freshly staged build then loses
     * silently to a later-stamped one still lying around. That is a plausible number attributed to
     * the wrong artefact with nothing erroring, which is this project's most expensive failure
     * shape. A path cannot be ambiguous about which directory it meant.
     *
     * <p><b>An id here is refused outright rather than resolved</b>, because offering both forms is
     * what would keep the ambiguous one reachable. {@code hostContract} does not gate it: the
     * contract gates the <i>payload</i>, and this is a rule on the launcher's side of the line that
     * leaves every build byte-for-byte compatible either way. Bumping it would refuse working builds
     * by name, which is a false negative in the mechanism built to prevent false negatives.
     *
     * <p><b>Nothing at all means whatever the build manager settled on</b>, which is what the
     * scripts mean by omitting the flag. Naming nothing is a real answer; naming something ambiguous
     * is not.
     */
    private File resolvePayload(File root) {
        File staged = AppStorage.stagedBuilds(root);
        File internal = AppStorage.installedBuilds(getFilesDir());
        SharpEmuBuild build;
        if (buildPath == null || buildPath.isEmpty()) {
            build = chosenBuild(internal, staged);
        } else if (!buildPath.startsWith("/")) {
            Log.e(TAG, "[app] --es sharpemu wants an absolute path to a build directory, and '"
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
        Log.i(TAG, "[app] build: " + build.identity() + " at " + build.dir);
        if (!build.notes.isEmpty()) {
            Log.i(TAG, "[app]   " + build.notes);
        }
        buildEnv = build.env;
        return build.payloadFile();
    }

    /**
     * Which build a launch that named none runs.
     *
     * <p><b>Three answers, and the first is the one that shipping exactly one build buys.</b> Nothing
     * stored means the bundled build — a concrete artefact, the same on every device, with no
     * per-release constant behind it and no toggle in front of it. A stored folder means that build.
     * Neither present means the most recently staged one, which is a debug app's normal state and the
     * behaviour the deploy loop has always had.
     *
     * <p><b>A chosen build that is gone falls back loudly rather than refusing.</b> It is a state a
     * user reaches without doing anything wrong — deleted from a PC, or the external volume wiped —
     * and with no error UI the alternative is a game that does not start with the reason only in a
     * log. The line names the build that was wanted <i>and</i> the one that ran, and the stored
     * choice is left alone: this is a launch working around a problem, not resolving it.
     */
    private SharpEmuBuild chosenBuild(File internal, File staged) {
        String folder = Settings.of(this).getBuild();
        // **the reserved folder goes down the bundled path rather than being resolved as a folder**,
        // because before the first launch it is not a folder at all — it is 76 MB of APK. resolving
        // it would find nothing and fall back to a staged build, which is a launch quietly running
        // something other than what the build manager's radio says it will.
        if (folder != null && !SharpEmuBuild.BUNDLED.equals(folder)) {
            SharpEmuBuild build = SharpEmuBuild.resolveFolder(folder, internal, staged);
            if (build != null) {
                return build;
            }
            Log.w(TAG, "[app] the chosen build '" + folder + "' could not be resolved, so falling"
                    + " back. the choice in Settings is unchanged");
        }
        return bundledBuild(internal, staged);
    }

    /**
     * The bundled build, unpacked out of the APK first if this is the launch that needs it.
     *
     * <p><b>Nothing is extracted until now, which is what makes an app-only update free.</b> The
     * asset is a directory tree in the APK; this is the first launch that resolves to it, so this is
     * where it becomes a build directory. An update that carries a different fork commit
     * re-extracts, and one that carries the same build does not.
     *
     * <p><b>Nothing is drawn while it happens.</b> The extraction is a fraction of a second against
     * the several seconds of black screen a launch already is, so a screen for this one alone would
     * dress the shortest wait in a launch and leave the longest bare. What is waited on is the same
     * question for both, and it is answered for both at once or not at all.
     *
     * <p><b>A failure ends the launch instead of falling back.</b> Running the most recently staged
     * build because the bundled one could not be written would be a plausible run attributed to the
     * wrong artefact — this project's oldest and most expensive failure — and on a release install
     * there would usually be no staged build to fall back to anyway. Having nothing bundled is a
     * different answer entirely, and is the one a development build gives.
     */
    private SharpEmuBuild bundledBuild(File internal, File staged) {
        // the progress this discards is real and is reported per 64 KB. nothing consumes it while a
        // launch draws nothing; see the note above.
        BundledBuild.Outcome outcome = BundledBuild.ensure(this, internal, (done, total) -> { });
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
     * Points the guest file layer at a game inside a tree the user has already granted us.
     *
     * <p><b>The tree is named by the launch, and only falls back to a guess when it is not.</b> The
     * game list sends {@code --es saftree} because it knows which granted folder the row came out of.
     * A launch that says nothing takes the first persisted read permission this app holds: exact
     * with one granted folder, arbitrary with two, and it stays because it is what
     * {@code am start --es safgame} means from a script.
     *
     * <p><b>A named tree is checked against what we actually hold rather than trusted.</b> A grant
     * revoked in android's own settings, or a volume that is not mounted, would otherwise reach the
     * guest as a game whose every file is missing — which reads as a corrupt dump rather than as
     * access that is gone.
     */
    private boolean mountSafGame() {
        Uri tree = null;
        Uri named = safTreeUri != null && !safTreeUri.isEmpty() ? Uri.parse(safTreeUri) : null;
        for (UriPermission held : getContentResolver().getPersistedUriPermissions()) {
            if (!held.isReadPermission()) {
                continue;
            }
            if (named == null) {
                tree = held.getUri();
                break;
            }
            if (named.equals(held.getUri())) {
                tree = named;
                break;
            }
        }
        if (tree == null) {
            if (named != null) {
                Log.e(TAG, "[app] --es saftree named " + named + " and this app does not hold a read"
                        + " grant on it. it was revoked, or the volume is not mounted");
            } else {
                Log.e(TAG, "[app] --es safgame needs a granted directory and this app holds none."
                        + " add one from Settings > Data > Game folders first");
            }
            return false;
        }
        Log.i(TAG, "[app] the game is in the granted tree " + tree
                + (named == null ? " (the first one held, since the launch named none)" : ""));
        return GuestFiles.mount(this, tree, safGameName);
    }

    private void runGuest() {
        File root = getExternalFilesDir(null);
        if (root == null) {
            Log.e(TAG, "[app] no external files directory");
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
        String guestGame;
        File staged = new File(gameName.startsWith("/")
                ? new File(gameName) : new File(AppStorage.games(root), gameName), "eboot.bin");
        if (safGameName != null && !safGameName.isEmpty()) {
            if (!mountSafGame()) {
                return;
            }
            guestGame = GuestFiles.MOUNT + "/eboot.bin";
        } else {
            guestGame = staged.getAbsolutePath();
            if (!staged.exists()) {
                // named by what would fix it, and the two forms fail for different reasons. a name
                // that is not there was never staged; a path that is not there is one this app is
                // not allowed to read, which is what all-files access being revoked between the tap
                // and the launch looks like from in here.
                Log.e(TAG, "[app] missing: " + staged.getAbsolutePath()
                        + (gameName.startsWith("/")
                        ? " — that path is not readable. is all-files access still on?"
                        : " — stage it with scripts/stage.py"));
                return;
            }
        }
        for (File needed : new File[] {payload, AppStorage.guestLibs(root)}) {
            if (!needed.exists()) {
                Log.e(TAG, "[app] missing: " + needed.getAbsolutePath()
                        + " — stage it with scripts/stage.py or scripts/stage.py");
                return;
            }
        }

        List<String> args = new ArrayList<>();
        args.add("--timestamps");
        args.add("--vulkan");
        // the audio thunk, in the shape --vulkan has. nothing at all is needed from this side
        // besides the flag: AAudio is a pure NDK C API, so there is no JNI, no looper and no
        // permission — RECORD_AUDIO gates input and this only ever plays.
        args.add("--audio");
        if (audioWatchdog) {
            args.add("--audio-watchdog");
        }
        // the custom driver, if one is staged. both flags or neither: with neither, the host layer
        // opens the platform loader exactly as every measurement up to here did, so the stock
        // baseline stays reproducible from the same build.
        String driver = installDriver(root);
        if (driver != null) {
            args.add("--vulkan-driver");
            args.add(driver);
            // and the hooks, which adrenotools loads by soname from this directory and nowhere
            // else. it must be nativeLibraryDir itself — a directory that merely contains copies
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
        args.addAll(FexPreset.arguments(fexPreset));

        // guest environment, in precedence order: **build defaults < app settings < intent
        // extras**, last wins. it is a map rather than a list of --env flags so a variable a build
        // defaults on and a launch overrides reaches the guest once, with the override's value —
        // two --env flags naming the same variable would be a coin toss over which the guest reads.
        //
        // the missing tier is explicit --env on the shell binary's command line, which is above all
        // three and is not reachable from here. a build may set *only* this: --smc, --asyncsig and
        // the --vulkan-* family are properties of the host layer's correctness, and a payload able
        // to ask for --smc none is a payload able to break the thing running it.
        Map<String, String> env = new LinkedHashMap<>(buildEnv);
        // the settings scene's contribution, between the build's defaults and the four below. it is
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
        // and this is the one that stops the extent being a coincidence: the host has the window,
        // the guest does not, so the size travels from here rather than being agreed by two
        // separately hand-set defaults.
        env.put("SHARPEMU_HOST_WINDOW_SIZE", surfaceWidth + "x" + surfaceHeight);
        // and a fifth that asks nothing of a payload, the way DOTNET_EnableWriteXorExecute does.
        // SharpEmu puts save data in user/savedata/ next to its own executable unless this names
        // somewhere else — so without it, re-staging a build destroys that build's saves, and the
        // bundled build would lose them on every app update. a payload too old to know the variable
        // simply keeps the old behaviour, which is why the contract number does not move for it.
        //
        // **one set for the app, not one per build.** a save belongs to the game rather than to the
        // binary that wrote it: keying it by build would make trying another build look like losing
        // a save, and switching back look like getting it returned.
        env.put("SHARPEMU_SAVEDATA_DIR", AppStorage.saveData(root).getAbsolutePath());
        for (String assignment : guestEnv) {
            int eq = assignment.indexOf('=');
            if (eq < 1) {
                Log.e(TAG, "[app] --es guestenv wants NAME=VALUE, ignoring '" + assignment + "'");
                continue;
            }
            env.put(assignment.substring(0, eq), assignment.substring(eq + 1));
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            args.add("--env");
            args.add(e.getKey() + "=" + e.getValue());
        }

        args.add("--libs");
        args.add(AppStorage.guestLibs(root).getAbsolutePath());
        // internal storage, not the external one the payload sits on: .NET reaches for TMPDIR far
        // more than for its own bundle, and the external volume is FUSE-backed on Android 11+, so
        // every file operation there is a userspace round trip.
        args.add("--tmp");
        args.add(getCacheDir().getAbsolutePath());
        // the mount, and only when a granted game asked for one. the flag being absent is what keeps
        // an ordinary run on exactly the code path it has always been on.
        if (safGameName != null && !safGameName.isEmpty()) {
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

        // named the way the run reaches it, because the two are different enough that a log which
        // said only "game: X" would not tell you which of the two arms produced the numbers under it.
        Log.i(TAG, "[app] game: " + (safGameName != null && !safGameName.isEmpty()
                ? safGameName + " (through a grant)"
                : gameName + (gameName.startsWith("/") ? " (a path)" : " (staged)")));
        Log.i(TAG, "[app] starting: " + String.join(" ", args));
        int status = HostLayer.nativeRun(args.toArray(new String[0]));
        Log.i(TAG, "[app] host layer returned " + status);
        // the lookups that came back empty, counted rather than each one reported. it prints only
        // when the guest returns rather than calling exit_group, which is the same limitation the
        // line above it has always had.
        if (safGameName != null && !safGameName.isEmpty()) {
            Log.i(TAG, "[app] " + GuestFiles.missCount() + " lookups came back empty");
        }
    }
}
