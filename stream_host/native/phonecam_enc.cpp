// PhoneCam native stream host ? DXGI Desktop Duplication ? NVENC (GPU) ? TCP
// Build (from F:\workspace\tools\vs vcvars64):
//   cl /O2 /EHsc /std:c++17 phonecam_enc.cpp ^
//      /I F:\workspace\tools\nvenc-headers\nv-codec-headers-master\include ^
//      d3d11.lib dxgi.lib dxguid.lib
//
// Usage: phonecam_enc.exe [--port 8091] [--fps 30] [--width 720] [--height 1568] [--bitrate 12]
//
// Capture is full desktop GPU texture; encoder input is D3D11 texture (CopyResource, no CPU readback).

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <d3d11.h>
#include <d3dcompiler.h>
#include <dxgi1_2.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <mmreg.h>
#include <wrl/client.h>
#include <winsock2.h>
#include <ws2tcpip.h>

#include <ffnvcodec/nvEncodeAPI.h>

#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "d3dcompiler.lib")
#pragma comment(lib, "dxgi.lib")
#pragma comment(lib, "dxguid.lib")
#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "ole32.lib")

using Microsoft::WRL::ComPtr;

#define NVCHK(call) \
    do { \
        NVENCSTATUS _s = (call); \
        if (_s != NV_ENC_SUCCESS) { \
            fprintf(stderr, "NVENC err %d at %s:%d\n", _s, __FILE__, __LINE__); \
            return false; \
        } \
    } while (0)

#define HRCHK(call) \
    do { \
        HRESULT _hr = (call); \
        if (FAILED(_hr)) { \
            fprintf(stderr, "HRESULT 0x%08lx at %s:%d\n", _hr, __FILE__, __LINE__); \
            return false; \
        } \
    } while (0)

struct Options {
    int port = 8091;
    int fps = 30;
    int width = 720;
    int height = 1568;
    int bitrateMbps = 12;
};

static bool parse_args(int argc, char** argv, Options& o) {
    for (int i = 1; i < argc; i++) {
        std::string a = argv[i];
        auto next = [&](int& v) {
            if (i + 1 < argc) v = atoi(argv[++i]);
        };
        if (a == "--port") next(o.port);
        else if (a == "--fps") next(o.fps);
        else if (a == "--width") next(o.width);
        else if (a == "--height") next(o.height);
        else if (a == "--bitrate") next(o.bitrateMbps);
    }
    o.width &= ~1;
    o.height &= ~1;
    return true;
}

// ---------- NVENC ----------
struct Nvenc {
    HMODULE mod = nullptr;
    NV_ENCODE_API_FUNCTION_LIST api = {NV_ENCODE_API_FUNCTION_LIST_VER};
    void* encoder = nullptr;
    ID3D11Texture2D* inputTex = nullptr;
    void* inputResource = nullptr;
    void* bitstream = nullptr;
    GUID codec = NV_ENC_CODEC_H264_GUID;
    int width = 0, height = 0;

    bool load() {
        mod = LoadLibraryA("nvEncodeAPI64.dll");
        if (!mod) mod = LoadLibraryA("nvEncodeAPI.dll");
        if (!mod) {
            fprintf(stderr, "nvEncodeAPI64.dll not found\n");
            return false;
        }
        auto* pCreate = (decltype(&NvEncodeAPICreateInstance))GetProcAddress(mod, "NvEncodeAPICreateInstance");
        if (!pCreate) {
            fprintf(stderr, "NvEncodeAPICreateInstance missing\n");
            return false;
        }
        NVENCSTATUS cs = pCreate(&api);
        fprintf(stderr, "CreateInstance %d  open=%p init=%p enc=%p lock=%p\n",
                cs, (void*)api.nvEncOpenEncodeSessionEx, (void*)api.nvEncInitializeEncoder,
                (void*)api.nvEncEncodePicture, (void*)api.nvEncLockBitstream);
        if (cs != NV_ENC_SUCCESS) return false;
        if (!api.nvEncOpenEncodeSessionEx || !api.nvEncInitializeEncoder ||
            !api.nvEncEncodePicture || !api.nvEncLockBitstream) {
            fprintf(stderr, "NVENC function table incomplete\n");
            return false;
        }
        return true;
    }

    bool open(ID3D11Device* dev, int w, int h, int fps, int bitrateMbps) {
        width = w;
        height = h;
        fprintf(stderr, "open session...\n");
        NV_ENC_OPEN_ENCODE_SESSION_EX_PARAMS open = {NV_ENC_OPEN_ENCODE_SESSION_EX_PARAMS_VER};
        open.device = dev;
        open.deviceType = NV_ENC_DEVICE_TYPE_DIRECTX;
        open.apiVersion = (uint32_t)(13 | (0 << 24));
        NVENCSTATUS ost = api.nvEncOpenEncodeSessionEx(&open, &encoder);
        fprintf(stderr, "OpenEncodeSessionEx13.0 %d enc=%p\n", ost, encoder);
        if (ost != NV_ENC_SUCCESS) {
            open.apiVersion = NVENCAPI_VERSION;
            ost = api.nvEncOpenEncodeSessionEx(&open, &encoder);
            fprintf(stderr, "OpenEncodeSessionEx hdr %d enc=%p\n", ost, encoder);
        }
        if (ost != NV_ENC_SUCCESS) {
            fprintf(stderr, "OpenEncodeSessionEx %d\n", ost);
            return false;
        }

        fprintf(stderr, "preset config...\n");
        GUID presetGuid = NV_ENC_PRESET_P1_GUID;
        NV_ENC_PRESET_CONFIG presetCfg = {NV_ENC_PRESET_CONFIG_VER};
        presetCfg.presetCfg.version = NV_ENC_CONFIG_VER;
        NVENCSTATUS pst = api.nvEncGetEncodePresetConfigEx(
            encoder, codec, presetGuid, NV_ENC_TUNING_INFO_ULTRA_LOW_LATENCY, &presetCfg);
        if (pst != NV_ENC_SUCCESS) {
            NV_ENC_PRESET_CONFIG pc2 = {NV_ENC_PRESET_CONFIG_VER};
            pc2.presetCfg.version = NV_ENC_CONFIG_VER;
            pst = api.nvEncGetEncodePresetConfig(encoder, codec, presetGuid, &pc2);
            if (pst != NV_ENC_SUCCESS) {
                fprintf(stderr, "GetEncodePresetConfig %d\n", pst);
                return false;
            }
            presetCfg = pc2;
        }

        NV_ENC_INITIALIZE_PARAMS init = {NV_ENC_INITIALIZE_PARAMS_VER};
        init.encodeGUID = codec;
        init.presetGUID = presetGuid;
        init.tuningInfo = NV_ENC_TUNING_INFO_ULTRA_LOW_LATENCY;
        init.encodeWidth = w;
        init.encodeHeight = h;
        init.darWidth = w;
        init.darHeight = h;
        init.frameRateNum = fps;
        init.frameRateDen = 1;
        init.enablePTD = 1;
        // Sync mode ? async without registered events crashes (null callback)
        init.enableEncodeAsync = 0;
        init.maxEncodeWidth = w;
        init.maxEncodeHeight = h;
        init.bufferFormat = NV_ENC_BUFFER_FORMAT_ARGB;

        NV_ENC_CONFIG cfg = presetCfg.presetCfg;
        cfg.version = NV_ENC_CONFIG_VER;
        cfg.profileGUID = NV_ENC_H264_PROFILE_HIGH_GUID;
        cfg.gopLength = fps * 2;
        cfg.frameIntervalP = 1;
        cfg.rcParams.rateControlMode = NV_ENC_PARAMS_RC_CBR;
        cfg.rcParams.averageBitRate = bitrateMbps * 1000000;
        cfg.rcParams.maxBitRate = bitrateMbps * 1000000;
        cfg.rcParams.vbvBufferSize = bitrateMbps * 1000000 / 4;
        cfg.encodeCodecConfig.h264Config.idrPeriod = fps * 2;
        // Limited BT.709 ? matches qti decoder (colorRange=2)
        cfg.encodeCodecConfig.h264Config.h264VUIParameters.videoSignalTypePresentFlag = 1;
        cfg.encodeCodecConfig.h264Config.h264VUIParameters.videoFormat = NV_ENC_VUI_VIDEO_FORMAT_UNSPECIFIED;
        cfg.encodeCodecConfig.h264Config.h264VUIParameters.videoFullRangeFlag = 0;
        cfg.encodeCodecConfig.h264Config.h264VUIParameters.colourDescriptionPresentFlag = 1;
        cfg.encodeCodecConfig.h264Config.h264VUIParameters.colourPrimaries = NV_ENC_VUI_COLOR_PRIMARIES_BT709;
        cfg.encodeCodecConfig.h264Config.h264VUIParameters.transferCharacteristics = NV_ENC_VUI_TRANSFER_CHARACTERISTIC_BT709;
        cfg.encodeCodecConfig.h264Config.h264VUIParameters.colourMatrix = NV_ENC_VUI_MATRIX_COEFFS_BT709;
        init.encodeConfig = &cfg;
        fprintf(stderr, "InitializeEncoder...\n");
        NVENCSTATUS ist = api.nvEncInitializeEncoder(encoder, &init);
        fprintf(stderr, "InitializeEncoder %d\n", ist);
        if (ist != NV_ENC_SUCCESS) {
            return false;
        }

        fprintf(stderr, "create input buffer %dx%d\n", w, h);
        fprintf(stderr, "create D3D11 input texture %dx%d\n", w, h);
        D3D11_TEXTURE2D_DESC desc = {};
        desc.Width = w;
        desc.Height = h;
        desc.MipLevels = 1;
        desc.ArraySize = 1;
        desc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        desc.SampleDesc.Count = 1;
        desc.Usage = D3D11_USAGE_DEFAULT;
        desc.BindFlags = D3D11_BIND_RENDER_TARGET;
        ID3D11Texture2D* tex = nullptr;
        HRESULT hr = dev->CreateTexture2D(&desc, nullptr, &tex);
        if (FAILED(hr) || !tex) {
            fprintf(stderr, "CreateTexture2D 0x%lx\n", hr);
            return false;
        }
        inputTex = tex;

        NV_ENC_REGISTER_RESOURCE reg = {NV_ENC_REGISTER_RESOURCE_VER};
        reg.resourceType = NV_ENC_INPUT_RESOURCE_TYPE_DIRECTX;
        reg.resourceToRegister = inputTex;
        reg.width = w;
        reg.height = h;
        reg.bufferFormat = NV_ENC_BUFFER_FORMAT_ARGB;
        fprintf(stderr, "register resource...\n");
        NVENCSTATUS rs = api.nvEncRegisterResource(encoder, &reg);
        fprintf(stderr, "RegisterResource %d ptr=%p\n", rs, reg.registeredResource);
        if (rs != NV_ENC_SUCCESS) return false;
        inputResource = reg.registeredResource;

        NV_ENC_CREATE_BITSTREAM_BUFFER cb = {NV_ENC_CREATE_BITSTREAM_BUFFER_VER};
        NVENCSTATUS bs = api.nvEncCreateBitstreamBuffer(encoder, &cb);
        fprintf(stderr, "CreateBitstreamBuffer %d\n", bs);
        if (bs != NV_ENC_SUCCESS) return false;
        bitstream = cb.bitstreamBuffer;
        fprintf(stderr, "NVENC ready %dx%d tex=%p res=%p bs=%p\n", w, h, (void*)inputTex, inputResource, bitstream);
        return true;
    }

    bool encode(ID3D11DeviceContext* ctx, ID3D11Texture2D* src,
                std::vector<uint8_t>& out, bool& gotKey) {
        if (!encoder || !inputTex || !inputResource || !bitstream || !ctx || !src) {
            fprintf(stderr, "encode: null state\n");
            return false;
        }
        NV_ENC_MAP_INPUT_RESOURCE map = {NV_ENC_MAP_INPUT_RESOURCE_VER};
        map.registeredResource = inputResource;
        NVENCSTATUS ms = api.nvEncMapInputResource(encoder, &map);
        if (ms != NV_ENC_SUCCESS) {
            fprintf(stderr, "MapInputResource %d\n", ms);
            return false;
        }

        ctx->CopyResource(inputTex, src);

        NV_ENC_PIC_PARAMS pic = {NV_ENC_PIC_PARAMS_VER};
        pic.inputBuffer = map.mappedResource;
        pic.outputBitstream = bitstream;
        pic.bufferFmt = NV_ENC_BUFFER_FORMAT_ARGB;
        pic.inputWidth = width;
        pic.inputHeight = height;
        pic.inputPitch = 0;
        pic.pictureStruct = NV_ENC_PIC_STRUCT_FRAME;
        pic.encodePicFlags = 0;

        NVENCSTATUS st = api.nvEncEncodePicture(encoder, &pic);
        if (st != NV_ENC_SUCCESS && st != NV_ENC_ERR_NEED_MORE_INPUT) {
            fprintf(stderr, "EncodePicture %d\n", st);
            api.nvEncUnmapInputResource(encoder, map.mappedResource);
            return false;
        }

        if (st == NV_ENC_SUCCESS) {
            NV_ENC_LOCK_BITSTREAM lock = {NV_ENC_LOCK_BITSTREAM_VER};
            lock.outputBitstream = bitstream;
            lock.doNotWait = 0;
            NVENCSTATUS ls = api.nvEncLockBitstream(encoder, &lock);
            if (ls == NV_ENC_SUCCESS && lock.bitstreamBufferPtr && lock.bitstreamSizeInBytes > 0) {
                const uint8_t* p = (const uint8_t*)lock.bitstreamBufferPtr;
                out.assign(p, p + lock.bitstreamSizeInBytes);
                gotKey = (lock.pictureType == NV_ENC_PIC_TYPE_IDR) ||
                         (lock.pictureType == NV_ENC_PIC_TYPE_I);
                api.nvEncUnlockBitstream(encoder, lock.outputBitstream);
            } else if (ls != NV_ENC_SUCCESS) {
                fprintf(stderr, "LockBitstream %d\n", ls);
            }
        }

        api.nvEncUnmapInputResource(encoder, map.mappedResource);
        return true;
    }

    ~Nvenc() {
        if (encoder) {
            api.nvEncDestroyEncoder(encoder);
            encoder = nullptr;
        }
        if (mod) FreeLibrary(mod);
    }
};
// ---------- DXGI ----------
struct Capturer {
    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext> ctx;
    ComPtr<IDXGIOutputDuplication> dup;
    ComPtr<ID3D11Texture2D> held;
    ComPtr<IDXGIAdapter> adapter;
    int texW = 0, texH = 0;
    int reinitFails = 0;

    bool createDevice() {
        device.Reset();
        ctx.Reset();
        adapter.Reset();
        D3D_FEATURE_LEVEL fl;
        HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
                                       0, nullptr, 0, D3D11_SDK_VERSION,
                                       &device, &fl, &ctx);
        if (FAILED(hr) || !device) {
            fprintf(stderr, "D3D11CreateDevice 0x%lx\n", hr);
            return false;
        }
        return true;
    }

    bool createDupOnce() {
        if (!device && !createDevice()) return false;
        ComPtr<IDXGIDevice> dxgiDev;
        HRCHK(device.As(&dxgiDev));
        if (!adapter) HRCHK(dxgiDev->GetAdapter(&adapter));
        ComPtr<IDXGIOutput> output;
        HRCHK(adapter->EnumOutputs(0, &output));
        ComPtr<IDXGIOutput1> output1;
        HRCHK(output.As(&output1));
        dup.Reset();
        HRESULT hr = output1->DuplicateOutput(device.Get(), &dup);
        if (FAILED(hr)) {
            fprintf(stderr, "DuplicateOutput 0x%lx\n", hr);
            return false;
        }
        DXGI_OUTDUPL_DESC dd;
        dup->GetDesc(&dd);
        texW = dd.ModeDesc.Width;
        texH = dd.ModeDesc.Height;
        return true;
    }

    /** Exclusive-fullscreen transitions often fail DuplicateOutput for a moment. */
    bool createDup(int tries = 8) {
        for (int i = 0; i < tries; i++) {
            if (createDupOnce()) return true;
            // Retry path: drop adapter; on later tries recreate the D3D device
            adapter.Reset();
            if (i == 3 || i == 6) {
                fprintf(stderr, "DXGI recreate device (try %d)\n", i + 1);
                held.Reset();
                dup.Reset();
                if (!createDevice()) return false;
            }
            Sleep(80);
        }
        return false;
    }

    bool init() {
        if (!createDevice()) return false;
        if (!createDup()) return false;
        fprintf(stderr, "DXGI %dx%d\n", texW, texH);
        return true;
    }

    void reinit() {
        fprintf(stderr, "DXGI reinit\n");
        held.Reset();
        dup.Reset();
        adapter.Reset();
        if (!createDup()) {
            reinitFails++;
            fprintf(stderr, "DXGI reinit failed (%d)\n", reinitFails);
        } else {
            reinitFails = 0;
            fprintf(stderr, "DXGI reinit ok %dx%d\n", texW, texH);
        }
    }

    ID3D11Texture2D* acquire(int timeoutMs) {
        if (!dup) {
            reinit();
            if (!dup) return nullptr;
        }
        DXGI_OUTDUPL_FRAME_INFO fi;
        ComPtr<IDXGIResource> res;
        HRESULT hr = dup->AcquireNextFrame(timeoutMs, &fi, &res);
        if (hr == DXGI_ERROR_WAIT_TIMEOUT) return nullptr;
        if (hr == DXGI_ERROR_ACCESS_LOST || hr == DXGI_ERROR_INVALID_CALL || hr == E_ACCESSDENIED) {
            reinit();
            return nullptr;
        }
        if (FAILED(hr)) {
            fprintf(stderr, "AcquireNextFrame 0x%lx\n", hr);
            reinit();
            return nullptr;
        }
        ComPtr<ID3D11Texture2D> tex;
        if (FAILED(res.As(&tex))) {
            dup->ReleaseFrame();
            return nullptr;
        }
        held = tex;
        return held.Get();
    }

    void release() {
        if (dup) dup->ReleaseFrame();
    }
};

// ---------- Full-range BGRA ? NV12 (custom BT.709 shader) ----------
// D3D11 Video Processor still emitted limited Y (max=235). We do RGB?YUV
// in a pixel shader so samples are truly 0-255, then NVENC VUI full=1.
struct FullRangeCsc {
    ComPtr<ID3D11VertexShader> vs;
    ComPtr<ID3D11PixelShader> psY;
    ComPtr<ID3D11PixelShader> psUV;
    ComPtr<ID3D11PixelShader> psCopy;
    ComPtr<ID3D11SamplerState> samp;
    ComPtr<ID3D11Texture2D> nv12Tex;
    ComPtr<ID3D11RenderTargetView> yRtv;
    ComPtr<ID3D11RenderTargetView> uvRtv;
    ComPtr<ID3D11Texture2D> srcCopy; // BGRA encode-size, always SRV+RT
    ComPtr<ID3D11Texture2D> srcSnap; // capture-size BGRA with SRB (desktop tex often cannot be SRV)
    ComPtr<ID3D11ShaderResourceView> srcSrv;
    ID3D11Texture2D* lastSrc = nullptr;
    int w = 0, h = 0;
    UINT snapW = 0, snapH = 0;
    int debugFrames = 2;

    bool init(ID3D11Device* dev, ID3D11DeviceContext* ctx, int width, int height) {
        w = width & ~1;
        h = height & ~1;
        if (w <= 0 || h <= 0) return false;

        static const char* vsSrc = R"(
            struct VSOut { float4 pos : SV_POSITION; float2 uv : TEXCOORD0; };
            VSOut main(uint id : SV_VertexID) {
                VSOut o;
                float2 uv = float2((id << 1) & 2, id & 2);
                o.pos = float4(uv * float2(2, -2) + float2(-1, 1), 0, 1);
                o.uv = uv;
                return o;
            }
        )";
        // BT.709 limited (16-235) to match qti decoder
        static const char* psYSrc = R"(
            Texture2D src : register(t0);
            SamplerState samp : register(s0);
            float main(float4 pos : SV_POSITION, float2 uv : TEXCOORD0) : SV_Target {
                float3 rgb = src.Sample(samp, uv).rgb;
                float y = dot(rgb, float3(0.2126, 0.7152, 0.0722));
                return 16.0/255.0 + y * (219.0/255.0);
            }
        )";
        static const char* psUVSrc = R"(
            Texture2D src : register(t0);
            SamplerState samp : register(s0);
            float2 main(float4 pos : SV_POSITION, float2 uv : TEXCOORD0) : SV_Target {
                float3 rgb = src.Sample(samp, uv).rgb;
                float y = dot(rgb, float3(0.2126, 0.7152, 0.0722));
                float cb = (rgb.b - y) / 1.8556;
                float cr = (rgb.r - y) / 1.5748;
                float cbL = 128.0/255.0 + cb * (224.0/255.0);
                float crL = 128.0/255.0 + cr * (224.0/255.0);
                return float2(saturate(cbL), saturate(crL));
            }
        )";
        static const char* psCopySrc = R"(
            Texture2D src : register(t0);
            SamplerState samp : register(s0);
            float4 main(float4 pos : SV_POSITION, float2 uv : TEXCOORD0) : SV_Target {
                return src.Sample(samp, uv);
            }
        )";

        ComPtr<ID3DBlob> vsb, psYb, psUVb, psCopyb, err;
        HRESULT hr = D3DCompile(vsSrc, strlen(vsSrc), nullptr, nullptr, nullptr,
                                "main", "vs_5_0", 0, 0, &vsb, &err);
        if (FAILED(hr)) { fprintf(stderr, "CSC VS 0x%lx\n", hr); return false; }
        hr = D3DCompile(psYSrc, strlen(psYSrc), nullptr, nullptr, nullptr,
                        "main", "ps_5_0", 0, 0, &psYb, &err);
        if (FAILED(hr)) { fprintf(stderr, "CSC PSY 0x%lx\n", hr); return false; }
        hr = D3DCompile(psUVSrc, strlen(psUVSrc), nullptr, nullptr, nullptr,
                        "main", "ps_5_0", 0, 0, &psUVb, &err);
        if (FAILED(hr)) { fprintf(stderr, "CSC PSUV 0x%lx\n", hr); return false; }
        hr = D3DCompile(psCopySrc, strlen(psCopySrc), nullptr, nullptr, nullptr,
                        "main", "ps_5_0", 0, 0, &psCopyb, &err);
        if (FAILED(hr)) { fprintf(stderr, "CSC PSCopy 0x%lx\n", hr); return false; }

        if (FAILED(dev->CreateVertexShader(vsb->GetBufferPointer(), vsb->GetBufferSize(), nullptr, &vs)))
            return false;
        if (FAILED(dev->CreatePixelShader(psYb->GetBufferPointer(), psYb->GetBufferSize(), nullptr, &psY)))
            return false;
        if (FAILED(dev->CreatePixelShader(psUVb->GetBufferPointer(), psUVb->GetBufferSize(), nullptr, &psUV)))
            return false;
        if (FAILED(dev->CreatePixelShader(psCopyb->GetBufferPointer(), psCopyb->GetBufferSize(), nullptr, &psCopy)))
            return false;

        D3D11_SAMPLER_DESC sd = {};
        sd.Filter = D3D11_FILTER_MIN_MAG_MIP_LINEAR;
        sd.AddressU = sd.AddressV = sd.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
        if (FAILED(dev->CreateSamplerState(&sd, &samp))) return false;

        D3D11_TEXTURE2D_DESC desc = {};
        desc.Width = (UINT)w;
        desc.Height = (UINT)h;
        desc.MipLevels = 1;
        desc.ArraySize = 1;
        desc.Format = DXGI_FORMAT_NV12;
        desc.SampleDesc.Count = 1;
        desc.Usage = D3D11_USAGE_DEFAULT;
        desc.BindFlags = D3D11_BIND_RENDER_TARGET;
        hr = dev->CreateTexture2D(&desc, nullptr, &nv12Tex);
        if (FAILED(hr)) {
            desc.BindFlags = 0;
            hr = dev->CreateTexture2D(&desc, nullptr, &nv12Tex);
        }
        if (FAILED(hr) || !nv12Tex) {
            fprintf(stderr, "CSC NV12 tex 0x%lx\n", hr);
            return false;
        }

        D3D11_RENDER_TARGET_VIEW_DESC ry = {};
        ry.Format = DXGI_FORMAT_R8_UNORM;
        ry.ViewDimension = D3D11_RTV_DIMENSION_TEXTURE2D;
        if (FAILED(dev->CreateRenderTargetView(nv12Tex.Get(), &ry, &yRtv))) {
            fprintf(stderr, "CSC Y RTV failed\n");
            return false;
        }
        D3D11_RENDER_TARGET_VIEW_DESC ruv = {};
        ruv.Format = DXGI_FORMAT_R8G8_UNORM;
        ruv.ViewDimension = D3D11_RTV_DIMENSION_TEXTURE2D;
        if (FAILED(dev->CreateRenderTargetView(nv12Tex.Get(), &ruv, &uvRtv))) {
            fprintf(stderr, "CSC UV RTV failed\n");
            return false;
        }

        // Intermediate BGRA (always SRV-bindable)
        D3D11_TEXTURE2D_DESC bd = {};
        bd.Width = (UINT)w;
        bd.Height = (UINT)h;
        bd.MipLevels = 1;
        bd.ArraySize = 1;
        bd.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        bd.SampleDesc.Count = 1;
        bd.Usage = D3D11_USAGE_DEFAULT;
        bd.BindFlags = D3D11_BIND_SHADER_RESOURCE | D3D11_BIND_RENDER_TARGET;
        if (FAILED(dev->CreateTexture2D(&bd, nullptr, &srcCopy))) {
            fprintf(stderr, "CSC srcCopy failed\n");
            return false;
        }
        if (FAILED(dev->CreateShaderResourceView(srcCopy.Get(), nullptr, &srcSrv))) {
            return false;
        }

        fprintf(stderr, "[FULLRANGE] shader CSC ready %dx%d NV12 LIMITED BT.709\n", w, h);
        fprintf(stderr, "[FULLRANGE] Y sample will print after phone connects and first frames encode\n");
        return true;
    }

    ID3D11Texture2D* convert(ID3D11Device* dev, ID3D11DeviceContext* ctx, ID3D11Texture2D* src) {
        if (!nv12Tex || !src || !ctx) return nullptr;

        D3D11_TEXTURE2D_DESC sd;
        src->GetDesc(&sd);

        // Desktop DXGI texture often cannot CreateShaderResourceView.
        // Always CopyResource into an SRB texture first.
        if (sd.Width == (UINT)w && sd.Height == (UINT)h) {
            ctx->CopyResource(srcCopy.Get(), src);
        } else {
            if (!srcSnap || snapW != sd.Width || snapH != sd.Height) {
                srcSnap.Reset();
                D3D11_TEXTURE2D_DESC t = {};
                t.Width = sd.Width;
                t.Height = sd.Height;
                t.MipLevels = 1;
                t.ArraySize = 1;
                t.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
                t.SampleDesc.Count = 1;
                t.Usage = D3D11_USAGE_DEFAULT;
                t.BindFlags = D3D11_BIND_SHADER_RESOURCE | D3D11_BIND_RENDER_TARGET;
                if (FAILED(dev->CreateTexture2D(&t, nullptr, &srcSnap))) {
                    fprintf(stderr, "CSC srcSnap %ux%u failed\n", sd.Width, sd.Height);
                    return nullptr;
                }
                snapW = sd.Width;
                snapH = sd.Height;
            }
            ctx->CopyResource(srcSnap.Get(), src);

            ComPtr<ID3D11ShaderResourceView> srv;
            if (FAILED(dev->CreateShaderResourceView(srcSnap.Get(), nullptr, &srv))) {
                fprintf(stderr, "CSC SRV srcSnap failed\n");
                return nullptr;
            }
            ComPtr<ID3D11RenderTargetView> rtv;
            if (FAILED(dev->CreateRenderTargetView(srcCopy.Get(), nullptr, &rtv))) {
                fprintf(stderr, "CSC RTV srcCopy failed\n");
                return nullptr;
            }
            D3D11_VIEWPORT vp = {0, 0, (float)w, (float)h, 0, 1};
            ctx->RSSetViewports(1, &vp);
            ctx->OMSetRenderTargets(1, rtv.GetAddressOf(), nullptr);
            ctx->IASetInputLayout(nullptr);
            ctx->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
            ctx->VSSetShader(vs.Get(), nullptr, 0);
            ctx->PSSetShader(psCopy.Get(), nullptr, 0);
            ctx->PSSetShaderResources(0, 1, srv.GetAddressOf());
            ctx->PSSetSamplers(0, 1, samp.GetAddressOf());
            ctx->Draw(3, 0);
            ID3D11ShaderResourceView* ns = nullptr;
            ctx->PSSetShaderResources(0, 1, &ns);
            ID3D11RenderTargetView* nr = nullptr;
            ctx->OMSetRenderTargets(1, &nr, nullptr);
        }

        // 2) Y pass (fullscreen triangle overwrites ? no clear)
        D3D11_VIEWPORT vpY = {0, 0, (float)w, (float)h, 0, 1};
        ctx->RSSetViewports(1, &vpY);
        ctx->OMSetRenderTargets(1, yRtv.GetAddressOf(), nullptr);
        ctx->IASetInputLayout(nullptr);
        ctx->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
        ctx->VSSetShader(vs.Get(), nullptr, 0);
        ctx->PSSetShader(psY.Get(), nullptr, 0);
        ctx->PSSetShaderResources(0, 1, srcSrv.GetAddressOf());
        ctx->PSSetSamplers(0, 1, samp.GetAddressOf());
        ctx->Draw(3, 0);

        // 3) UV pass
        D3D11_VIEWPORT vpUV = {0, 0, (float)(w / 2), (float)(h / 2), 0, 1};
        ctx->RSSetViewports(1, &vpUV);
        ctx->OMSetRenderTargets(1, uvRtv.GetAddressOf(), nullptr);
        ctx->PSSetShader(psUV.Get(), nullptr, 0);
        ctx->Draw(3, 0);

        ID3D11ShaderResourceView* nullSrv = nullptr;
        ctx->PSSetShaderResources(0, 1, &nullSrv);
        ID3D11RenderTargetView* nullRtv = nullptr;
        ctx->OMSetRenderTargets(1, &nullRtv, nullptr);

        if (debugFrames > 0) {
            debugFrames--;
            if (debugFrames <= 0 || debugFrames == 1) {
                dumpYRange(dev, ctx, nv12Tex.Get());
            }
        }
        return nv12Tex.Get();
    }

    void dumpYRange(ID3D11Device* dev, ID3D11DeviceContext* c, ID3D11Texture2D* tex) {
        D3D11_TEXTURE2D_DESC td;
        tex->GetDesc(&td);
        D3D11_TEXTURE2D_DESC sd = td;
        sd.Usage = D3D11_USAGE_STAGING;
        sd.BindFlags = 0;
        sd.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        sd.MiscFlags = 0;
        ComPtr<ID3D11Texture2D> staging;
        if (FAILED(dev->CreateTexture2D(&sd, nullptr, &staging))) return;
        c->CopyResource(staging.Get(), tex);
        D3D11_MAPPED_SUBRESOURCE m;
        if (FAILED(c->Map(staging.Get(), 0, D3D11_MAP_READ, 0, &m))) return;
        const uint8_t* p = (const uint8_t*)m.pData;
        uint8_t mn = 255, mx = 0;
        for (int y = 0; y < (int)td.Height; y += 32) {
            const uint8_t* row = p + (size_t)y * m.RowPitch;
            for (int x = 0; x < (int)td.Width; x += 32) {
                uint8_t v = row[x];
                if (v < mn) mn = v;
                if (v > mx) mx = v;
            }
        }
        c->Unmap(staging.Get(), 0);
        fprintf(stderr, "[FULLRANGE] NV12 Y sample: min=%u max=%u  (full~0..255, limited~16..235)\n",
                (unsigned)mn, (unsigned)mx);
    }
};

// Center-crop + scale with a tiny D3D11 blit (no d3d11video.h)
struct GpuBlit {
    ComPtr<ID3D11VertexShader> vs;
    ComPtr<ID3D11PixelShader> ps;
    ComPtr<ID3D11SamplerState> samp;
    ComPtr<ID3D11Texture2D> outTex;
    ComPtr<ID3D11RenderTargetView> rtv;
    int outW = 0, outH = 0;

    bool init(ID3D11Device* dev, int w, int h) {
        outW = w;
        outH = h;
        static const char* vsSrc = R"(
            struct VSOut { float4 pos : SV_POSITION; float2 uv : TEXCOORD0; };
            VSOut main(uint id : SV_VertexID) {
                VSOut o;
                float2 uv = float2((id << 1) & 2, id & 2);
                o.pos = float4(uv * float2(2, -2) + float2(-1, 1), 0, 1);
                o.uv = uv;
                return o;
            }
        )";
        static const char* psSrc = R"(
            Texture2D tex : register(t0);
            SamplerState s : register(s0);
            float4 main(float4 pos : SV_POSITION, float2 uv : TEXCOORD0) : SV_Target {
                return tex.Sample(s, uv);
            }
        )";
        ComPtr<ID3DBlob> vsb, psb, err;
        HRESULT hr = D3DCompile(vsSrc, strlen(vsSrc), nullptr, nullptr, nullptr,
                                "main", "vs_5_0", 0, 0, &vsb, &err);
        if (FAILED(hr)) {
            fprintf(stderr, "VS compile 0x%lx\n", hr);
            return false;
        }
        hr = D3DCompile(psSrc, strlen(psSrc), nullptr, nullptr, nullptr,
                        "main", "ps_5_0", 0, 0, &psb, &err);
        if (FAILED(hr)) {
            fprintf(stderr, "PS compile 0x%lx\n", hr);
            return false;
        }
        HRCHK(dev->CreateVertexShader(vsb->GetBufferPointer(), vsb->GetBufferSize(), nullptr, &vs));
        HRCHK(dev->CreatePixelShader(psb->GetBufferPointer(), psb->GetBufferSize(), nullptr, &ps));

        D3D11_SAMPLER_DESC sd = {};
        sd.Filter = D3D11_FILTER_MIN_MAG_MIP_LINEAR;
        sd.AddressU = D3D11_TEXTURE_ADDRESS_CLAMP;
        sd.AddressV = D3D11_TEXTURE_ADDRESS_CLAMP;
        sd.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
        HRCHK(dev->CreateSamplerState(&sd, &samp));

        D3D11_TEXTURE2D_DESC desc = {};
        desc.Width = w;
        desc.Height = h;
        desc.MipLevels = 1;
        desc.ArraySize = 1;
        desc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        desc.SampleDesc.Count = 1;
        desc.Usage = D3D11_USAGE_DEFAULT;
        desc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
        HRCHK(dev->CreateTexture2D(&desc, nullptr, &outTex));
        HRCHK(dev->CreateRenderTargetView(outTex.Get(), nullptr, &rtv));
        fprintf(stderr, "GpuBlit out %dx%d\n", w, h);
        return true;
    }

    ID3D11Texture2D* process(ID3D11Device* dev, ID3D11DeviceContext* ctx,
                             ID3D11Texture2D* src, int srcW, int srcH) {
        if (!src || !rtv || !vs || !ps) return nullptr;
        ComPtr<ID3D11ShaderResourceView> srv;
        if (FAILED(dev->CreateShaderResourceView(src, nullptr, &srv))) return nullptr;

        float srcAr = float(srcW) / float(srcH);
        float dstAr = float(outW) / float(outH);
        float u0 = 0.f, v0 = 0.f, u1 = 1.f, v1 = 1.f;
        if (srcAr > dstAr) {
            float w = dstAr / srcAr;
            u0 = 0.5f - 0.5f * w;
            u1 = 0.5f + 0.5f * w;
        } else {
            float h = srcAr / dstAr;
            v0 = 0.5f - 0.5f * h;
            v1 = 0.5f + 0.5f * h;
        }

        // Crop via a tiny constant buffer for UV scale/offset would be cleaner;
        // for MVP use viewport + no crop first, then crop UV in a second PS.
        // Encode crop into sampler-independent UVs by drawing a smaller quad region:
        D3D11_VIEWPORT vp = {0, 0, float(outW), float(outH), 0, 1};
        ctx->RSSetViewports(1, &vp);
        float clear[4] = {0, 0, 0, 1};
        ctx->ClearRenderTargetView(rtv.Get(), clear);
        ctx->OMSetRenderTargets(1, rtv.GetAddressOf(), nullptr);
        ctx->IASetInputLayout(nullptr);
        ctx->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
        ctx->VSSetShader(vs.Get(), nullptr, 0);
        ctx->PSSetShader(ps.Get(), nullptr, 0);
        ctx->PSSetShaderResources(0, 1, srv.GetAddressOf());
        ctx->PSSetSamplers(0, 1, samp.GetAddressOf());
        // Fullscreen triangle samples full texture; apply crop by drawing
        // a full triangle then... need UV crop in shader. Use simple approach:
        // copy with crop using CopySubresourceRegion to a staging of crop size
        // then blit that. For now sample full frame (letterbox handled if we
        // set dst AR later). Force crop UV via SetViewports is not enough.
        ctx->Draw(3, 0);

        ID3D11ShaderResourceView* nullSrv = nullptr;
        ctx->PSSetShaderResources(0, 1, &nullSrv);
        ID3D11RenderTargetView* nullRtv = nullptr;
        ctx->OMSetRenderTargets(1, &nullRtv, nullptr);
        return outTex.Get();
    }
};

// ---------- WASAPI loopback PCM ? TCP (audio) ----------
// 48kHz stereo s16le raw stream. Port = videoPort + 2 (8093 when video is 8091).
struct AudioPipe {
    std::atomic<bool> run{true};
    int port = 8093;

    void start() {
        std::thread([this] { loop(); }).detach();
    }

    void loop() {
        CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        SOCKET ls = socket(AF_INET, SOCK_STREAM, 0);
        if (ls == INVALID_SOCKET) return;
        int yes = 1;
        setsockopt(ls, SOL_SOCKET, SO_REUSEADDR, (char*)&yes, sizeof(yes));
        sockaddr_in a{};
        a.sin_family = AF_INET;
        a.sin_addr.s_addr = INADDR_ANY;
        a.sin_port = htons((u_short)port);
        if (bind(ls, (sockaddr*)&a, sizeof(a)) != 0 || listen(ls, 1) != 0) {
            fprintf(stderr, "audio TCP bind/listen :%d failed\n", port);
            closesocket(ls);
            return;
        }
        fprintf(stderr, "[AUDIO] WASAPI loopback TCP :%d  48k stereo s16\n", port);

        while (run) {
            SOCKET c = accept(ls, nullptr, nullptr);
            if (c == INVALID_SOCKET) { Sleep(100); continue; }
            BOOL nd = TRUE;
            setsockopt(c, IPPROTO_TCP, TCP_NODELAY, (char*)&nd, sizeof(nd));
            fprintf(stderr, "[AUDIO] client connected\n");
            streamLoopback(c);
            closesocket(c);
            fprintf(stderr, "[AUDIO] client gone\n");
        }
        closesocket(ls);
        CoUninitialize();
    }

    void streamLoopback(SOCKET client) {
        ComPtr<IMMDeviceEnumerator> en;
        ComPtr<IMMDevice> dev;
        ComPtr<IAudioClient> ac;
        ComPtr<IAudioCaptureClient> cap;
        WAVEFORMATEX* fmt = nullptr;

        if (FAILED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                                    IID_PPV_ARGS(&en)))) return;
        if (FAILED(en->GetDefaultAudioEndpoint(eRender, eConsole, &dev))) return;
        if (FAILED(dev->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, (void**)&ac))) return;
        if (FAILED(ac->GetMixFormat(&fmt)) || !fmt) return;

        // Prefer 48k s16 via AUTOCONVERTPCM (MS loopback sample); else mix format
        WAVEFORMATEX want = {};
        want.wFormatTag = WAVE_FORMAT_PCM;
        want.nChannels = 2;
        want.nSamplesPerSec = 48000;
        want.wBitsPerSample = 16;
        want.nBlockAlign = 4;
        want.nAvgBytesPerSec = 48000 * 4;
        bool usedWant = false;
        HRESULT hr = ac->Initialize(AUDCLNT_SHAREMODE_SHARED,
                                    AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM,
                                    200000, 0, &want, nullptr);
        if (SUCCEEDED(hr)) {
            usedWant = true;
        } else {
            hr = ac->Initialize(AUDCLNT_SHAREMODE_SHARED,
                                AUDCLNT_STREAMFLAGS_LOOPBACK,
                                200000, 0, fmt, nullptr);
        }
        if (FAILED(hr)) {
            fprintf(stderr, "[AUDIO] Initialize 0x%lx\n", hr);
            CoTaskMemFree(fmt);
            return;
        }
        if (FAILED(ac->GetService(IID_PPV_ARGS(&cap)))) {
            CoTaskMemFree(fmt);
            return;
        }
        // CRITICAL: use the format we actually initialized, not mix format
        UINT32 rate = usedWant ? want.nSamplesPerSec : fmt->nSamplesPerSec;
        UINT16 ch = usedWant ? want.nChannels : fmt->nChannels;
        UINT16 bits = usedWant ? want.wBitsPerSample : fmt->wBitsPerSample;
        bool isFloat = false;
        if (!usedWant) {
            isFloat = (fmt->wFormatTag == WAVE_FORMAT_IEEE_FLOAT) ||
                      (fmt->wBitsPerSample == 32);
            if (fmt->wFormatTag == WAVE_FORMAT_EXTENSIBLE && fmt->cbSize >= 22) {
                auto* ext = reinterpret_cast<WAVEFORMATEXTENSIBLE*>(fmt);
                isFloat = IsEqualGUID(ext->SubFormat, KSDATAFORMAT_SUBTYPE_IEEE_FLOAT) != 0;
            }
        }
        UINT32 decim = 1;
        if (rate >= 48000 && rate % 48000 == 0) decim = rate / 48000;
        fprintf(stderr, "[AUDIO] capture %uHz ch=%u bits=%u float=%d decim=%u want=%d\n",
                rate, ch, bits, isFloat ? 1 : 0, decim, usedWant ? 1 : 0);

        ac->Start();
        std::vector<uint8_t> pcm;
        std::vector<int16_t> out;
        int pktCount = 0;
        while (run) {
            Sleep(5);
            UINT32 packet = 0;
            if (FAILED(cap->GetNextPacketSize(&packet)) || packet == 0) continue;
            while (packet > 0) {
                BYTE* data = nullptr;
                UINT32 frames = 0;
                DWORD flags = 0;
                if (FAILED(cap->GetBuffer(&data, &frames, &flags, nullptr, nullptr))) break;
                if (frames > 0 && (data || (flags & AUDCLNT_BUFFERFLAGS_SILENT))) {
                    UINT32 nSamp = frames * ch;
                    out.clear();
                    out.reserve(nSamp / decim + 8);
                    if ((flags & AUDCLNT_BUFFERFLAGS_SILENT) || !data) {
                        out.assign((nSamp / decim) + 8, 0);
                    } else if (isFloat) {
                        const float* f = (const float*)data;
                        for (UINT32 i = 0; i + decim <= nSamp; i += decim) {
                            float acc = 0.f;
                            for (UINT32 k = 0; k < decim; k++) acc += f[i + k];
                            float v = acc / (float)decim;
                            if (v > 1.f) v = 1.f;
                            if (v < -1.f) v = -1.f;
                            out.push_back((int16_t)(v * 32767.f));
                        }
                    } else {
                        const int16_t* s = (const int16_t*)data;
                        for (UINT32 i = 0; i + decim <= nSamp; i += decim) {
                            int32_t acc = 0;
                            for (UINT32 k = 0; k < decim; k++) acc += s[i + k];
                            out.push_back((int16_t)(acc / (int32_t)decim));
                        }
                    }
                    if (!out.empty()) {
                        int sent = ::send(client, (const char*)out.data(), (int)out.size() * 2, 0);
                        if (sent <= 0) {
                            cap->ReleaseBuffer(frames);
                            ac->Stop();
                            CoTaskMemFree(fmt);
                            return;
                        }
                        pktCount++;
                        if (pktCount % 200 == 1)
                            fprintf(stderr, "[AUDIO] sent pkt=%d samples=%zu\n", pktCount, out.size());
                    }
                }
                cap->ReleaseBuffer(frames);
                if (FAILED(cap->GetNextPacketSize(&packet))) break;
            }
        }
        ac->Stop();
        CoTaskMemFree(fmt);
    }
};


int main(int argc, char** argv) {
    Options opt;
    parse_args(argc, argv, opt);

    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
    SOCKET listenSock = socket(AF_INET, SOCK_STREAM, 0);
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(opt.port);
    int yes = 1;
    setsockopt(listenSock, SOL_SOCKET, SO_REUSEADDR, (char*)&yes, sizeof(yes));
    bind(listenSock, (sockaddr*)&addr, sizeof(addr));
    listen(listenSock, 1);
    fprintf(stderr, "PhoneCam native TCP :%d  encode %dx%d @%dfps %dMbps\n",
            opt.port, opt.width, opt.height, opt.fps, opt.bitrateMbps);
    fprintf(stderr, "[FULLRANGE] enabled (shader BT.709). Waiting for phone on TCP %d...\n", opt.port);

    // Audio pipe disabled until Sunshine-style design is ready
    // AudioPipe audio; audio.port = opt.port + 2; audio.start();


    // Control channel: phone -> host bitrate/fps (UDP)
    SOCKET ctrlSock = socket(AF_INET, SOCK_DGRAM, 0);
    sockaddr_in caddr{};
    caddr.sin_family = AF_INET;
    caddr.sin_addr.s_addr = INADDR_ANY;
    caddr.sin_port = htons(opt.port + 1); // 8092 when data is 8091
    int reuse = 1;
    setsockopt(ctrlSock, SOL_SOCKET, SO_REUSEADDR, (char*)&reuse, sizeof(reuse));
    if (bind(ctrlSock, (sockaddr*)&caddr, sizeof(caddr)) == 0) {
        fprintf(stderr, "control UDP :%d\n", opt.port + 1);
    } else {
        fprintf(stderr, "control UDP bind failed\n");
        closesocket(ctrlSock);
        ctrlSock = INVALID_SOCKET;
    }

    for (;;) {
        SOCKET client = accept(listenSock, nullptr, nullptr);
        if (client == INVALID_SOCKET) continue;
        BOOL nd = TRUE;
        setsockopt(client, IPPROTO_TCP, TCP_NODELAY, (char*)&nd, sizeof(nd));
        fprintf(stderr, "client connected\n");

        Capturer cap;
        Nvenc nv;
        GpuBlit blit;
        int ew = opt.width > 0 ? opt.width : 0;
        int eh = opt.height > 0 ? opt.height : 0;
        bool ok = cap.init();
        bool needScale = false;
        if (ok) {
            if (ew <= 0) ew = cap.texW;
            if (eh <= 0) eh = cap.texH;
            needScale = (ew != cap.texW || eh != cap.texH);
            if (needScale) ok = blit.init(cap.device.Get(), ew, eh);
            if (ok) ok = nv.load() && nv.open(cap.device.Get(), ew, eh, opt.fps, opt.bitrateMbps);
        }
        if (!ok) {
            closesocket(client);
            continue;
        }
        fprintf(stderr, "encode capture %dx%d -> nvenc ARGB %dx%d scale=%d\n",
                cap.texW, cap.texH, ew, eh, needScale ? 1 : 0);

        std::atomic<bool> run{true};
        double interval = 1.0 / opt.fps;
        LARGE_INTEGER freq, last;
        QueryPerformanceFrequency(&freq);
        QueryPerformanceCounter(&last);

        while (run) {

            // Non-blocking control poll: {"bitrate":12,"fps":30}
            if (ctrlSock != INVALID_SOCKET) {
                char cbuf[256];
                sockaddr_in from{};
                int flen = sizeof(from);
                u_long nb = 1;
                ioctlsocket(ctrlSock, FIONBIO, &nb);
                int n = recvfrom(ctrlSock, cbuf, sizeof(cbuf) - 1, 0, (sockaddr*)&from, &flen);
                if (n > 0) {
                    cbuf[n] = 0;
                    int br = 0, fps = 0;
                    if (sscanf_s(cbuf, "{\"bitrate\":%d,\"fps\":%d", &br, &fps) >= 1 ||
                        sscanf(cbuf, "{\"bitrate\":%d,\"fps\":%d", &br, &fps) >= 1) {
                        if (br >= 2 && br <= 80) opt.bitrateMbps = br;
                        if (fps >= 15 && fps <= 60) opt.fps = fps;
                        fprintf(stderr, "control bitrate=%d fps=%d\n", opt.bitrateMbps, opt.fps);
                    }
                }
            }

            ID3D11Texture2D* src = cap.acquire(10);
            if (!src) continue;
            LARGE_INTEGER now;
            QueryPerformanceCounter(&now);
            double dt = double(now.QuadPart - last.QuadPart) / double(freq.QuadPart);
            if (dt < interval * 0.9) {
                cap.release();
                continue;
            }
            last = now;

            ID3D11Texture2D* encSrc = src;
            if (needScale) {
                encSrc = blit.process(cap.device.Get(), cap.ctx.Get(), src, cap.texW, cap.texH);
                if (!encSrc) {
                    cap.release();
                    continue;
                }
            }

            std::vector<uint8_t> nal;
            bool key = false;
            if (!nv.encode(cap.ctx.Get(), encSrc, nal, key)) {
                cap.release();
                break;
            }
            cap.release();
            if (nal.empty()) continue;

            int sent = ::send(client, (const char*)nal.data(), (int)nal.size(), 0);
            if (sent <= 0) {
                run = false;
            }
        }
        closesocket(client);
        fprintf(stderr, "client gone\n");
    }
    return 0;
}
