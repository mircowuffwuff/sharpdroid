// the APK.
//
// **drive this through scripts/build-apk.py rather than calling gradle directly.** the script resolves
// the SDK and JDK through the toolchain resolver, writes local.properties from what it found, and
// passes the identity this build should carry. gradle on its own would find its own SDK through
// ANDROID_HOME, which is exactly the disagreement the resolver exists to prevent.
//
// the native libraries are NOT built here. scripts/build-host.py and scripts/build-adrenotools.py produce
// them into build/, and stageJniLibs below collects the four that go in the APK.

// imported rather than written out where it is used: inside this file `java` resolves to the java
// extension the plugins install, so a fully qualified java.util.Properties does not compile.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.aboutlibraries)
}

// the identity, as scripts/build-apk.py resolved it. absent means the manifest's own -- which is what
// asking for the release identity gets -- so these are properties rather than defaults with a value.
val identityApplicationId: String? = (findProperty("sharpdroidApplicationId") as String?)?.takeIf { it.isNotBlank() }
val identityAppLabel: String = (findProperty("sharpdroidAppLabel") as String?)?.takeIf { it.isNotBlank() } ?: "SharpDroid"

// where the APK's asset trees were staged: the guest's x86-64 libraries, and the bundled SharpEmu
// build when one is being bundled at all.
//
// **it is a generated directory and never a source one.** scripts/build-apk.py populates it under
// build/ and empties it first, so "what is in this APK" is answered by one command rather than by
// whatever happens to be sitting in app/src/main/assets.
val bundleAssets: String? = (findProperty("sharpdroidBundleAssets") as String?)?.takeIf { it.isNotBlank() }

// the commit this APK is built from, as scripts/build-apk.py resolved it, short and with a marker
// when the tree it was built from had uncommitted changes in it.
//
// **empty is a state the About screen is written for.** a build from a source archive or from a
// checkout with no git in it cannot know its commit, and there is no answer to invent: a placeholder
// on that line is a string somebody then quotes into a bug report and tries to resolve.
val identityCommit: String = (findProperty("sharpdroidCommit") as String?)?.takeIf { it.isNotBlank() } ?: ""

// the FEXCore the host layer is linked against, as scripts/build-apk.py described it out of the
// pinned submodule. empty is a supported state for the same reason it is above, and the About screen
// simply omits the line.
val identityFexVersion: String =
    (findProperty("sharpdroidFexVersion") as String?)?.takeIf { it.isNotBlank() } ?: ""

// the build-tools revision, as scripts/build-apk.py resolved it out of toolchain.json.
//
// **saying nothing here does not mean "whatever is installed".** AGP has a default of its own, and
// it is a revision older than the pinned one -- so leaving this unset makes gradle download a second
// build-tools that nothing in this repository declares, package the APK with it, and leave the
// declared one fetched and unused. that also breaks --offline for anyone whose only build-tools is
// the one fetch-toolchain.py installed.
val buildTools: String? = (findProperty("sharpdroidBuildTools") as String?)?.takeIf { it.isNotBlank() }

// the release signing key, read here rather than handed over by scripts/build-apk.py: a password
// passed as a project property is a password in the command line of a process anybody on the machine
// can list. the script asserts this file instead, so the refusal still happens before gradle starts.
//
// **absent is the ordinary state and never a failure.** a clone has no release key and does not need
// one -- it builds the debug identity, which signs itself. only asking for the release identity
// requires this, and that request is refused by name when the file is not here.
val releaseSigning: Properties? = file("release-signing.properties")
    .takeIf { it.isFile }
    ?.let { source -> Properties().apply { source.inputStream().use { stream -> load(stream) } } }

android {
    // **the java package, and it does not move.** the JNI entry points are named
    // Java_com_mircowuffwuff_sharpdroid_HostLayer_*, host/CMakeLists.txt has a -Wl,-u keeping them
    // from being garbage-collected, and every launch command spells it out in full. renaming this
    // breaks the native link and every am start at once.
    namespace = "com.mircowuffwuff.sharpdroid"
    compileSdk = 35
    buildTools?.let { buildToolsVersion = it }

    defaultConfig {
        // **the application id is a different thing from the namespace above, and only this one
        // moves.** a renamed id installs beside the release app as a separate app to android, with
        // its own internal storage, its own external files directory and its own save data -- which
        // is what keeps a deploy loop away from a personal install. the activity is then
        // <application id>/com.mircowuffwuff.sharpdroid.MainActivity, because only half of it moved.
        applicationId = identityApplicationId ?: "com.mircowuffwuff.sharpdroid"
        minSdk = 28
        targetSdk = 35
        // **the version is the release counter and nothing else.** a release is tagged sharpdroid-1,
        // sharpdroid-2, sharpdroid-3, and the prefix lives in the tag alone -- it is a namespace for
        // a git ref and the first half of the published asset's filename, and repeating it here
        // would put a third spelling of one release beside those two. bare is what the About screen
        // shows and what a bug report is worth having.
        //
        // **the code is ten times the version, and the gap between them is the point.** android
        // refuses an install whose code is not above the installed one, so the nine values between
        // two releases are what a diagnostic build published under this identity takes, and
        // somebody running one can still take the next release afterwards. widening the multiplier
        // later is safe, since the sequence only has to rise; narrowing it is not, so it starts at
        // ten rather than at something harder to leave.
        versionCode = 30
        versionName = "3"

        // the label, which a renamed build has to change too: two entries in the launcher both
        // called "SharpDroid" and no way to tell which is which is the failure this avoids.
        resValue("string", "app_name", identityAppLabel)

        // what the About screen puts beside the version, and what a bug report is worth having.
        // **a resource rather than a BuildConfig field**, so that reading it needs neither the
        // buildConfig feature nor a generated class -- it is a string on a screen and nothing else
        // consumes it.
        resValue("string", "app_commit", identityCommit)
        resValue("string", "fex_version", identityFexVersion)

        ndk {
            // arm64 only, and that is the architecture rather than a packaging choice: FEXCore's
            // backend emits arm64 and there is no other target.
            abiFilters += "arm64-v8a"
        }
    }

    // **a throwaway debug key, and it must be this one.** the installed debug app on a development
    // device was signed with it; a different key -- including gradle's own ~/.android/debug.keystore
    // -- makes adb install -r fail with INSTALL_FAILED_UPDATE_INCOMPATIBLE and costs an uninstall,
    // which takes the app's save data with it. scripts/build-apk.py generates it on demand.
    //
    // **the release key is a different key and it is never generated.** a published APK is the one
    // artefact whose signature has to be the same next time: android refuses an update signed by
    // another key, and the recovery is an uninstall, which takes the save data of everybody who
    // installed the last one. so a missing release key is a refusal that names how to make one,
    // where a missing debug key is made on the spot -- generating on demand is exactly the failure
    // here, because the key that quietly appears is a new one and nothing says so until an upgrade
    // fails on somebody else's phone.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "sharpdroid"
            keyPassword = "android"
        }

        // the values come from release-signing.properties, which is not in git: a signing password
        // in a public build file signs nothing, since anybody can then produce an APK that upgrades
        // over a published one. scripts/build-apk.py asserts the file and the keystore before
        // gradle is asked for anything, so the refusal arrives with the instruction rather than as
        // a null store file three tasks deep.
        releaseSigning?.let { properties ->
            create("release") {
                storeFile = file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")

                // **v3 is what leaves a way out.** v2 alone pins the app to this key forever; v3
                // carries a rotation proof, so a key that is lost or has to be retired can be
                // succeeded rather than stranding every install. v1 is the JAR signature and is
                // only read below API 24, which is under this app's own minimum.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        // **the two build types are the two audiences, and the difference is one attribute.**
        // neither is minified: the frontend reaches native entry points by name and the emulator
        // payload is not ours to shrink, so R8 would be trading a real risk against megabytes that
        // the bundled build dwarfs anyway.
        getByName("debug") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }

        // **what a stranger installs.** debuggable is off here and on above, which is the whole of
        // the difference: a debuggable APK lets anything on the device attach to the process and
        // read the app's private directory, and that directory is where save data lives. nothing in
        // this repository reads the flag and no script uses run-as, so switching it off costs the
        // development loop nothing -- that loop runs under the debug identity, which is still
        // debuggable.
        //
        // a person who wants their own data out of a shipped install has the User data screen,
        // which exports it as an archive and needs no cable.
        getByName("release") {
            isMinifyEnabled = false
            isDebuggable = false
            releaseSigning?.let { signingConfig = signingConfigs.getByName("release") }
        }
    }

    buildFeatures {
        // Eden's frontend is view-based with viewBinding, and this one follows it. its Compose
        // rework is a separate work in progress that does not replace the emulator activity.
        viewBinding = true
    }

    packaging {
        jniLibs {
            // **extractNativeLibs, under its AGP name.** the host layer is a 33 MB .so and leaving
            // it compressed in the zip is simpler than the uncompressed page-aligned layout an
            // in-place load wants -- install time once rather than launch time every time. and
            // adrenotools needs its hooks to exist as real files in nativeLibraryDir, which is
            // exactly what extraction produces. one flag, a packaging convenience and a hard
            // requirement of the driver path.
            useLegacyPackaging = true

            // **the native libraries ship unstripped, on purpose.** AGP strips by default, and the
            // host layer is where this project's open crash investigations live -- a stripped .so
            // turns a native backtrace into a list of addresses. it costs about 7 MB against an APK
            // whose bundled emulator build is an order of magnitude larger, and the crashes worth
            // reading a backtrace for are the ones that happen on somebody else's phone, so a
            // published APK is the one that needs the symbols most rather than least.
            //
            // it is written down rather than left alone because AGP is *already* failing to strip
            // these ("Unable to strip the following libraries, packaging them as they are") and
            // that is an accident of it not finding a usable llvm-strip. without this line, the day
            // it does find one is the day symbols quietly disappear.
            keepDebugSymbols += "**/*.so"
        }
    }

    sourceSets {
        getByName("main") {
            // the four .so files are produced by other steps into build/ and collected by
            // stageJniLibs below, rather than living in the source tree.
            jniLibs.srcDir(layout.buildDirectory.dir("jniLibs"))
            // and the asset trees. a directory that does not exist contributes nothing, which is
            // what opening this project in Android Studio and hitting build gets -- an APK with no
            // assets, which installs and cannot start a game.
            bundleAssets?.let { assets.srcDir(it) }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.coil)
}

// ---------------------------------------------------------------------------------------------
// the native libraries
//
// four .so files from three different places, none of them a source directory: the host layer and
// the adrenotools hooks are build output, and the STL comes out of the NDK. they are copied into one
// staging directory because that is the shape jniLibs.srcDir wants.
//
// **each is asserted rather than globbed hopefully.** an APK missing the host layer installs and
// then dies at UnsatisfiedLinkError, and one missing a hook does something worse: adrenotools falls
// back to the stock driver quietly, so a driver comparison would silently measure the same driver
// twice.

val hostLayerSo = rootProject.file("build/host/libsharpdroid-host-layer.so")
val adrenotoolsHookSos = listOf(
    rootProject.file("build/adrenotools/src/hook/libmain_hook.so"),
    rootProject.file("build/adrenotools/src/hook/libhook_impl.so"),
)

// the STL.
//
// scripts/build-apk.py passes the path it resolved, because the toolchain resolver is what decides
// which NDK this repository builds against and the answer must not differ between the native step
// and this one. the search below is the fallback for opening the project in Android Studio and
// hitting build, and it deliberately globs every installed NDK rather than asking for
// android.ndkVersion -- that property answers with AGP's own default when nothing set it, which is
// an NDK that is not installed here.
val stlSo: File? = (findProperty("sharpdroidStlSo") as String?)
    ?.takeIf { it.isNotBlank() }
    ?.let { File(it) }
    ?.takeIf { it.isFile }
    ?: File(android.sdkDirectory, "ndk")
        .listFiles()
        ?.sortedByDescending { it.name }
        ?.asSequence()
        ?.flatMap { ndk ->
            (File(ndk, "toolchains/llvm/prebuilt").listFiles() ?: emptyArray()).asSequence()
        }
        ?.map { File(it, "sysroot/usr/lib/aarch64-linux-android/libc++_shared.so") }
        ?.firstOrNull { it.isFile }

val stageJniLibs by tasks.registering(Sync::class) {
    description = "collects the host layer, the STL and the adrenotools hooks into one jniLibs tree"

    // Sync rather than Copy: it deletes what is no longer produced, so a .so removed from this list
    // does not linger in the staging directory and keep being packaged.
    into(layout.buildDirectory.dir("jniLibs/arm64-v8a"))

    from(hostLayerSo)
    // the other two hooks adrenotools builds, file_redirect and gsl_alloc, are deliberately absent:
    // they back feature flags this app does not pass, and an unused hook in nativeLibraryDir is one
    // more thing that could be loaded by accident.
    from(adrenotoolsHookSos)
    // guarded rather than wrapped in a provider: a provider with no value fails while gradle is
    // still working out the task graph, which reports as "cannot query the value of this provider"
    // and names neither the STL nor the NDK. missing, it is doFirst below that says so.
    stlSo?.let { from(it) }

    doFirst {
        if (!hostLayerSo.isFile) {
            throw GradleException("$hostLayerSo not found. run: py scripts/build-host.py")
        }
        adrenotoolsHookSos.firstOrNull { !it.isFile }?.let {
            throw GradleException("$it not found. run: py scripts/build-adrenotools.py")
        }
        if (stlSo == null) {
            throw GradleException(
                "libc++_shared.so not found under ${android.sdkDirectory}/ndk. the host layer links" +
                    " c++_shared, and the copy in the APK has to be the one it was linked against."
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn(stageJniLibs)
}
