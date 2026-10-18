// decodetest — decode a clip and report which codec component actually ran.
//
// A build with no video player gives you no way to tell a hardware decoder
// from a software fallback. This drives NDK MediaCodec directly and prints
// the component name the framework selected (AMediaCodec_getName), so
// "OMX.Nvidia.h264.decode" vs "c2.android.avc.decoder" is unambiguous.
//
//   decodetest [-s] <file> [component]
//
//     -s          decode to a Surface instead of ByteBuffers
//     component   force a specific decoder
//
// WHY -s EXISTS. Decoding to ByteBuffers requires the component to support a
// colour format it can hand back in main memory. OMX.Nvidia.vp9.decode and
// OMX.Nvidia.mpeg2v.decode both instantiate and then fail with
// ERROR(0x80001020) and zero frames that way, which looked like "the decoder
// is broken" and was recorded as such for three weeks. They appear to require
// an output Surface, as tunnelled and some secure decoders do. -s supplies
// one through AImageReader, which yields a real ANativeWindow with no display
// and no SurfaceFlinger client, and then reads frames back off it -- so a
// frame count under -s is evidence the decoder produced pictures, not merely
// that it accepted input. RAIL 13.
//
// AND THE ANSWER WAS NO. Built to test that theory, -s disproved it: on real
// VP9 content OMX.Nvidia.vp9.decode works in BOTH modes, at 320x240 and
// 854x480 (18 October). The September failure was clip-specific, most likely
// a VP8 file forced through the VP9 component. The flag is kept because it is
// the only way to exercise the Surface path at all, and because a decoder
// that accepts input and renders nothing is otherwise indistinguishable from
// a working one.
//
// "rendered" is normally lower than "frames" -- 64 of 83, 236 of 300. That is
// AImageReader backpressure at maxImages=8, not dropped decode: one image is
// acquired per output buffer and released immediately, and when the reader is
// full the window drops. Do not read it as a fault.
//
// SPDX-License-Identifier: Apache-2.0
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <media/NdkImageReader.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <ctime>

static double now_ms() {
    timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

int main(int argc, char** argv) {
    bool use_surface = false;
    int a = 1;
    if (a < argc && !strcmp(argv[a], "-s")) { use_surface = true; a++; }
    if (argc - a < 1) {
        fprintf(stderr, "usage: decodetest [-s] <file> [component]\n"); return 2;
    }
    const char* path = argv[a++];
    const char* want = (a < argc) ? argv[a] : nullptr;

    FILE* f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "cannot open %s\n", path); return 2; }
    int fd = fileno(f);
    fseek(f, 0, SEEK_END); off_t sz = ftello(f); fseek(f, 0, SEEK_SET);

    AMediaExtractor* ex = AMediaExtractor_new();
    if (AMediaExtractor_setDataSourceFd(ex, fd, 0, sz) != AMEDIA_OK) {
        fprintf(stderr, "setDataSource failed\n"); return 2;
    }

    int track = -1; const char* mime = nullptr; AMediaFormat* fmt = nullptr;
    size_t n = AMediaExtractor_getTrackCount(ex);
    for (size_t i = 0; i < n; i++) {
        AMediaFormat* tf = AMediaExtractor_getTrackFormat(ex, i);
        const char* m = nullptr;
        if (AMediaFormat_getString(tf, AMEDIAFORMAT_KEY_MIME, &m) && strncmp(m, "video/", 6) == 0) {
            track = (int)i; mime = strdup(m); fmt = tf; break;
        }
        AMediaFormat_delete(tf);
    }
    if (track < 0) { fprintf(stderr, "no video track\n"); return 2; }
    AMediaExtractor_selectTrack(ex, track);

    int32_t w = 0, h = 0;
    AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, &w);
    AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, &h);

    AMediaCodec* codec = want ? AMediaCodec_createCodecByName(want)
                              : AMediaCodec_createDecoderByType(mime);
    if (!codec) { fprintf(stderr, "RESULT %s %s FAILED could-not-create %s\n",
                          path, mime, want ? want : "(by type)"); return 1; }

    /* A Surface for the codec to render into. AImageReader gives a real
       ANativeWindow without a display: nothing is composited, and the frames
       can be acquired back here to prove pictures were actually produced.
       The reader's dimensions do not have to match the clip -- the codec
       reconfigures the window -- but using the format's values avoids a
       needless reallocation on the first frame. */
    AImageReader* reader = nullptr;
    ANativeWindow* window = nullptr;
    if (use_surface) {
        int32_t w = 0, h = 0;
        AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, &w);
        AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, &h);
        if (w <= 0 || h <= 0) { w = 1920; h = 1080; }
        media_status_t rs = AImageReader_new(w, h, AIMAGE_FORMAT_YUV_420_888, 8, &reader);
        if (rs != AMEDIA_OK || !reader) {
            fprintf(stderr, "RESULT %s %s FAILED AImageReader_new=%d\n", path, mime, (int) rs);
            return 1;
        }
        if (AImageReader_getWindow(reader, &window) != AMEDIA_OK || !window) {
            fprintf(stderr, "RESULT %s %s FAILED AImageReader_getWindow\n", path, mime);
            return 1;
        }
    }

    if (AMediaCodec_configure(codec, fmt, window, nullptr, 0) != AMEDIA_OK) {
        char* nm = nullptr; AMediaCodec_getName(codec, &nm);
        fprintf(stderr, "RESULT %s %s FAILED configure component=%s\n",
                path, mime, nm ? nm : "?");
        return 1;
    }
    if (AMediaCodec_start(codec) != AMEDIA_OK) {
        fprintf(stderr, "RESULT %s %s FAILED start\n", path, mime); return 1;
    }

    char* name = nullptr;
    AMediaCodec_getName(codec, &name);

    bool eos_in = false; int frames = 0;
    int rendered = 0; int32_t last_w = 0, last_h = 0;
    double t0 = now_ms();
    const double deadline = t0 + 20000.0;   // never spin forever
    while (frames < 300 && now_ms() < deadline) {
        if (!eos_in) {
            ssize_t ib = AMediaCodec_dequeueInputBuffer(codec, 20000);
            if (ib >= 0) {
                size_t cap = 0; uint8_t* buf = AMediaCodec_getInputBuffer(codec, ib, &cap);
                ssize_t rd = AMediaExtractor_readSampleData(ex, buf, cap);
                if (rd < 0) {
                    AMediaCodec_queueInputBuffer(codec, ib, 0, 0, 0,
                                                 AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    eos_in = true;
                } else {
                    int64_t pts = AMediaExtractor_getSampleTime(ex);
                    AMediaCodec_queueInputBuffer(codec, ib, 0, rd, pts, 0);
                    AMediaExtractor_advance(ex);
                }
            }
        }
        AMediaCodecBufferInfo info;
        ssize_t ob = AMediaCodec_dequeueOutputBuffer(codec, &info, 20000);
        if (ob >= 0) {
            frames++;
            /* render=true on a Surface: this is what hands the picture to the
               window, and without it the reader never sees anything. */
            AMediaCodec_releaseOutputBuffer(codec, ob, use_surface);
            if (use_surface && reader) {
                AImage* img = nullptr;
                if (AImageReader_acquireNextImage(reader, &img) == AMEDIA_OK && img) {
                    int32_t iw = 0, ih = 0;
                    AImage_getWidth(img, &iw);
                    AImage_getHeight(img, &ih);
                    if (iw > 0 && ih > 0) { rendered++; last_w = iw; last_h = ih; }
                    AImage_delete(img);
                }
            }
            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) break;
        } else if (ob == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
            if (eos_in) break;
        } else if (ob != AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED &&
                   ob != AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            // Anything else is a real error. Bailing here matters: the codec
            // moves to Released and every further call just logs
            // "Invalid to call at Released state" in a tight loop.
            char* nm = nullptr; AMediaCodec_getName(codec, &nm);
            fprintf(stderr, "RESULT %s mime=%s component=%s FAILED dequeueOutput=%zd after %d frames\n",
                    path, mime, nm ? nm : "?", (ssize_t)ob, frames);
            return 1;
        }
    }
    double dt = now_ms() - t0;

    /* under -s, say how many frames actually reached the window: a decoder
       that accepts input and renders nothing is the failure this flag exists
       to tell apart from a working one. */
    char surface_note[96] = "";
    if (use_surface) {
        snprintf(surface_note, sizeof(surface_note),
                 " surface=yes rendered=%d last=%dx%d", rendered, last_w, last_h);
    }
    printf("RESULT %s mime=%s size=%dx%d component=%s frames=%d ms=%.0f fps=%.1f%s\n",
           path, mime, w, h, name ? name : "?", frames, dt,
           frames > 0 ? frames * 1000.0 / dt : 0.0,
           use_surface ? surface_note : "");

    AMediaCodec_stop(codec); AMediaCodec_delete(codec);
    AMediaFormat_delete(fmt); AMediaExtractor_delete(ex); fclose(f);
    return frames > 0 ? 0 : 1;
}
