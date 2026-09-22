# src/native

| File | What |
|---|---|
| `pzopt_los.cpp` | the C++ LOS pass experiment (`playerLosNative`, `PZOPT_NATIVE=1`) |
| `pzopt_ngx.cpp` | the DLSS shim (`upscaler=dlss`, docs/plan-upscalers.md): a Vulkan device on the GL context's GPU, NGX init / optimal size / feature / evaluate, the four images and two semaphores exported for GL, every entry point on a 64 MB-stack worker thread; a ring of three command buffers so the CPU only waits for the evaluation three frames back |

## Building the shim

Linux (`scripts/build.sh` does this when `~/.local/share/nvidia-dlss-sdk` holds a checkout of
https://github.com/NVIDIA/DLSS and the Vulkan headers are installed):

    g++ -O2 -shared -fPIC -std=c++17 -fvisibility=hidden -I$SDK/include -o natives/libpzopt_ngx64.so \
        src/native/pzopt_ngx.cpp $SDK/lib/Linux_x86_64/libnvsdk_ngx.a -ldl -lpthread

and `libnvidia-ngx-dlss.so.<ver>` from `$SDK/lib/Linux_x86_64/rel/` next to it under `natives/`.

Windows (untested here, 2026-09-22): the source carries the Win32 handle paths
(`VK_KHR_external_memory_win32` / `GL_EXT_memory_object_win32`, `pzopt.Dlss` imports handles instead of fds
and looks for `natives/pzopt_ngx64.dll`), but the SDK's `nvsdk_ngx_s.lib` is an MSVC static library that
mingw cannot link (`__security_cookie`, `StringCch*`, MSVC-mangled internals), so the DLL needs Visual Studio:

    cl /O2 /std:c++17 /LD /I%SDK%\include /I<vulkan headers> src\native\pzopt_ngx.cpp ^
       %SDK%\lib\Windows_x86_64\x64\nvsdk_ngx_s.lib /Fe:natives\pzopt_ngx64.dll

with `nvngx_dlss.dll` from `%SDK%\lib\Windows_x86_64\rel\` next to it. Until someone builds and runs that,
`upscaler=dlss` on Windows logs "natives/pzopt_ngx64.dll not found" and falls back to the bicubic path.
Intel XeSS (Windows only, `libxess.dll`, a Vulkan API since XeSS 2) would take the same shape — its own
`pzngx_*`-style backend behind the same images and semaphores — and is not written yet.
