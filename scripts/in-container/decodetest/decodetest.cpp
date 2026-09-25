// decodetest — decode a clip and report which codec component actually ran.
//
// A build with no video player gives you no way to tell a hardware decoder
// from a software fallback. This drives NDK MediaCodec directly and prints
// the component name the framework selected (AMediaCodec_getName), so
// "OMX.Nvidia.h264.decode" vs "c2.android.avc.decoder" is unambiguous.
//
//   decodetest <file> [component]     component forces a specific decoder
//
// SPDX-License-Identifier: Apache-2.0
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <ctime>

static double now_ms() {
    timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

int main(int argc, char** argv) {
    if (argc < 2) { fprintf(stderr, "usage: decodetest <file> [component]\n"); return 2; }
    const char* path = argv[1];
    const char* want = (argc > 2) ? argv[2] : nullptr;

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

    if (AMediaCodec_configure(codec, fmt, nullptr, nullptr, 0) != AMEDIA_OK) {
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
            AMediaCodec_releaseOutputBuffer(codec, ob, false);
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

    printf("RESULT %s mime=%s size=%dx%d component=%s frames=%d ms=%.0f fps=%.1f\n",
           path, mime, w, h, name ? name : "?", frames, dt,
           frames > 0 ? frames * 1000.0 / dt : 0.0);

    AMediaCodec_stop(codec); AMediaCodec_delete(codec);
    AMediaFormat_delete(fmt); AMediaExtractor_delete(ex); fclose(f);
    return frames > 0 ? 0 : 1;
}
