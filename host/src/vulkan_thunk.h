// sharpdroid host layer -- guest x86-64 vulkan onto the host's arm64 vulkan, in-process.
//
// this is what hosting the guest in-process buys: the guest's vulkan calls do not cross a socket,
// a process boundary or a protocol. they arrive here, and here calls the real driver on the same
// thread, in the same address space, one host call later.
//
// the trap is a syscall with a magic number. the guest side is a generated shared object,
// host/thunks/vulkan/generated/vulkan_stubs.S built into libvulkan.so.1, where every entry point is the
// same 16 bytes but for one immediate:
//
//     movq %rcx, %r10           ; the syscall instruction destroys RCX, which holds C arg 3
//     movl $0x564B00nn, %eax    ; "VK" << 16 | command id
//     syscall
//     ret
//
// a syscall rather than an illegal instruction or a call gate because the host layer already
// owns that boundary: FEXCore spills the *entire* guest state, GPRs and FPRs alike, before every
// syscall (JIT/BranchOps.cpp DEF_OP(Syscall), GPRSpillMask = FPRSpillMask = ~0U), so CPUState is
// a complete and exact description of the guest's registers at the call. that is what lets one
// stub shape serve 623 entry points with different signatures: the *host* reads the arguments
// out of the spilled state according to the SysV classification, so no stub ever has to know
// anything about its own prototype.
//
// two things make this far smaller than a general FFI:
//
//   - **no pointer translation at all.** guest and host share one address space 1:1, so a guest
//     pointer is already a host pointer. every VkStructure the guest fills in is read by the
//     driver in place. this is the single biggest thing sharing a process buys, and it is why
//     the whole marshaller is one template rather than a code generator per struct.
//   - **vulkan passes nothing by value that is not a scalar.** every command takes handles,
//     enums, integers, floats and pointers, and returns void or an integer. so argument
//     classification collapses to "SSE if floating point, INTEGER otherwise" and the return
//     value is always RAX. ArgReader static_asserts this, so a future header that breaks it is
//     a compile error rather than silent corruption.
//
// what the host must *not* pass through is anything that would make the driver call back into
// guest code, since a guest function pointer is x86-64 and the driver would enter it as arm64:
//   - VkAllocationCallbacks* is forced to nullptr, by a type-level specialisation, so all ~90
//     commands taking one are covered without naming any of them.
//   - vkGetInstanceProcAddr/vkGetDeviceProcAddr return the *guest* stub address, not the host
//     function. SharpEmu's presenter casts what GetDeviceProcAddr returns straight to a
//     delegate* and calls it, so this is load-bearing rather than tidy.

#pragma once

#include <FEXCore/Core/CoreState.h>
#include <FEXCore/HLE/SyscallHandler.h>

#include <cstdint>

// forward-declared at global scope on purpose, so that everything including this header does not
// have to pull in <android/native_window.h>. this is exactly how the NDK declares it.
struct ANativeWindow;

namespace HostLayer::VulkanThunk {

// "VK" in the top 16 bits. real linux x86-64 syscall numbers are all below 1000 and this FEX
// has no table indexed by syscall number (no GetSyscallABI, no InlineSyscall op), so the whole
// upper range is free and an unrecognised number can never be mistaken for one of ours.
inline constexpr uint64_t Magic = 0x564B0000;
inline constexpr uint64_t MagicMask = 0xFFFF0000;
inline constexpr uint64_t AttachId = 0xFFFF;

inline bool IsThunkCall(uint64_t SyscallNumber) {
  return (SyscallNumber & MagicMask) == Magic;
}

// enabled by --vulkan. off by default: with the thunk unavailable the guest's dlopen of
// libvulkan.so.1 fails the way it did before the thunk existed, which is the behaviour every
// earlier measurement was taken against.
void SetEnabled(bool Enabled);
bool Enabled();
void SetTrace(bool Enabled);

// per-command wall time, dumped every 300 presented frames as a delta since the last dump.
//
// this is a different question from SetTrace's. a trace says which commands are *called*, which
// cannot find a stall -- 95 ms spread across 15 draws is not spread at all, it is one call waiting.
// the profile sorts by time and the answer is usually the first line.
//
// deltas rather than totals, because a total is dominated by start-up forever: shader compilation
// is seconds of `vkCreateGraphicsPipelines` that never repeat, and it would bury everything.
void SetProfile(bool Enabled);

// which half of CPUState's XMM union FEX is spilling to is GuestAbi::SetAvxRegisterFile in
// guest_abi.h, not here: both thunks read arguments out of the same register file, and only one
// place can own that answer.

// the host vulkan to load. defaults to the platform loader, "libvulkan.so", which is what
// gives WSI and the stock adreno driver.
//
// **this is not where a turnip .so goes**, and the reason is the one trap building this recorded: on android
// WSI lives in the *loader*, not the driver. naming turnip here would dlopen a driver with no
// vkCreateSwapchainKHR in it at all. the loader still has to be the loader; what has to change is
// which driver it loads, which is SetDriver below.
void SetLibraryPath(const char* Path);

// custom driver injection, via libadrenotools. both must be set or neither is used, and with
// neither set OpenLibrary() is byte-for-byte the dlopen every measurement up to here was taken
// against -- which is what keeps the stock-driver baseline reproducible.
//
//   Driver      absolute path to the driver .so, e.g. turnip's libvulkan_freedreno.so. it must
//               live on **internal** storage: adrenotools stats it and then dlopens it, and the
//               linker refuses a library any other app could have written.
//   HookLibDir  the app's nativeLibraryDir, and nothing else. libmain_hook.so and libhook_impl.so
//               are loaded from there by soname into an isolated linker namespace, so a path that
//               merely contains copies of them is not the same thing.
//
// this only works inside an app process. adrenotools drives the bionic linker's namespace API,
// which a shell binary has no classloader namespace to bypass -- so the shell binary and the whole
// regression set stay on the platform loader whatever these are set to.
void SetDriver(const char* Path);
void SetHookLibDir(const char* Dir);

// **whether the driver named above is the one this process will render through**, which is not the
// same question as whether the injection was accepted.
//
// adrenotools hands back the platform loader opened in an isolated namespace with a hook in front of
// the loader's own dlopen, and the hook decides later. one that cannot load the driver falls back to
// the system driver and returns a perfectly good handle, so every vulkan call works and the picture
// is right -- a run on the driver somebody picked and a run on the driver they did not are the same
// run but for a line in a log. what settles it is whether the library is mapped into this process.
//
// **it opens the host loader, and is the same open the guest's first call would have done** -- one
// `std::call_once`, so calling this early moves when the driver loads and not how often. that is
// what lets the app ask before it starts a guest, and refuse the launch rather than end one.
//
// false only for a definite failure. nothing asked for, a maps file that could not be read and a
// path that would not resolve all answer true, because none of them is evidence against the driver
// and the expensive mistake here is refusing one that works.
bool ChosenDriverLoads();

// NAME=VALUE for the driver's own environment, applied before it is loaded. repeatable.
//
// this is **not** --env, and the difference is the whole reason it exists. --env appends to the
// *guest's* environment, which is what CoreCLR and SharpEmu read; turnip is host arm64 code
// loaded by the host loader, so it reads the host process's environment and never sees a guest
// variable at all. TU_DEBUG and the rest of mesa's knobs are only reachable from here.
void AddDriverEnv(const char* Assignment);

// pin the GPU clocks, via libadrenotools' KGSL power-control ioctl.
//
// **this is not a driver feature and does not care which driver is loaded.** it is
// `IOCTL_KGSL_SETPROPERTY(KGSL_PROP_PWRCTRL)` straight to `/dev/kgsl-3d0`, so it works on the
// stock adreno driver and on turnip alike -- which is what makes it a fair lever to compare them
// with, and what makes it reachable at all on builds whose mesa options are compiled out.
//
// re-asserted on a timer rather than set once, because that is what Eden does
// (`video_core/renderer_vulkan/vk_turbo_mode.cpp`): its android path is a thread that calls
// `adrenotools_set_turbo(true)` in a loop for as long as submissions are recent, and releases it
// on shutdown. taking it at face value rather than assuming one call sticks.
//
// off by default. it is a thermal and battery decision, not a free win, and every measurement in
// this project so far was taken without it.
void SetTurbo(bool Enabled);
void StopTurbo();

// the dispatch entry, called from LinuxSyscallHandler::Dispatch for any magic number.
uint64_t Handle(FEXCore::Core::CpuStateFrame* Frame, FEXCore::HLE::SyscallArguments* Args);

// the extent the faked surface reports, and therefore the size of every swapchain image. without
// a window this is whatever the guest should think it has, set by --vulkan-size; with one it is
// the window's, set by SetAndroidWindow below and not overridable.
void SetSurfaceSize(uint32_t Width, uint32_t Height);
uint64_t PresentedFrameCount();

// which window system the guest gets.
//
//   Headless   the thunk invents the surface and owns the swapchain, and a present is a copy into
//              whatever the host wants. it is the only thing a shell binary with no window can do.
//   Android    a real VK_KHR_android_surface on the app's ANativeWindow, and a real swapchain from
//              the platform loader. the driver composites the guest's own images and nothing is
//              copied anywhere.
//   Auto       Android when there is a window, Headless when there is not. latched the first time
//              it is asked, because the answer must not change under a live instance.
//
// the flag exists rather than just switching, for the reason `--smc` and `--asyncsig` exist: a
// real swapchain negotiates format, extent and present mode against the *driver* instead of
// against our own answers, and being able to put the invented path back is what makes a graphics
// regression bisectable rather than guessable.
enum class WsiMode { Auto, Headless, Android };
void SetWsiMode(WsiMode Mode);

// the app's surface, or null when there is none -- called from the SurfaceHolder callback, which
// means it can arrive before the guest starts and vanish while it is running.
//
// a window changes two things and only two. it is **authoritative for the extent**, which is what
// removes any need to hand-match a size: a surface that disagrees with the client's drawable makes
// the presenter recreate its swapchain forever without ever erroring, so the size has to come from
// one place, and the only place that actually knows is here. and it gives Present() somewhere to
// put the frame -- with no window, a presented image is counted and dropped.
void SetAndroidWindow(::ANativeWindow* Window);

// where to write presented frames as PPMs, as "<prefix>-NNNNN.ppm". off unless asked for: it
// costs a full device-to-host copy and a stall on the frames it captures.
//
// **headless WSI only.** under android WSI the swapchain images belong to the driver, and reading
// one back would mean tracking handles the thunk does not own and forcing a usage bit onto a create
// info that is the guest's. it proves pixels exist when nothing reaches a screen; with a window,
// `adb shell screencap` answers the same question more cheaply.
void SetDumpPrefix(const char* Prefix);

// counters, for the run summary.
uint64_t CallCount();
uint64_t UnresolvedCount();
const char* LastUnresolved();

} // namespace HostLayer::VulkanThunk
