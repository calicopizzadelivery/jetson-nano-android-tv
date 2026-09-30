/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

// A 440 Hz tone as raw AAC-ELD access units, 44.1 kHz stereo, 480 samples a
// frame: the stream AirPlay mirroring carries. Each unit is written as a
// two-byte big-endian length and the unit itself.

#include <math.h>
#include <stdio.h>
#include <stdlib.h>

#include <aacenc_lib.h>

static const int kRate = 44100;
static const int kFrame = 480;

int main(int argc, char** argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: %s <seconds> <out.bin>\n", argv[0]);
        return 2;
    }
    int seconds = atoi(argv[1]);
    FILE* out = fopen(argv[2], "wb");
    if (out == nullptr) {
        perror(argv[2]);
        return 1;
    }

    HANDLE_AACENCODER enc;
    if (aacEncOpen(&enc, 0, 2) != AACENC_OK ||
        aacEncoder_SetParam(enc, AACENC_AOT, AOT_ER_AAC_ELD) != AACENC_OK ||
        aacEncoder_SetParam(enc, AACENC_SAMPLERATE, kRate) != AACENC_OK ||
        aacEncoder_SetParam(enc, AACENC_CHANNELMODE, MODE_2) != AACENC_OK ||
        aacEncoder_SetParam(enc, AACENC_GRANULE_LENGTH, kFrame) != AACENC_OK ||
        aacEncoder_SetParam(enc, AACENC_BITRATE, 128000) != AACENC_OK ||
        aacEncoder_SetParam(enc, AACENC_TRANSMUX, TT_MP4_RAW) != AACENC_OK ||
        aacEncEncode(enc, nullptr, nullptr, nullptr, nullptr) != AACENC_OK) {
        fprintf(stderr, "could not configure the encoder\n");
        return 1;
    }
    AACENC_InfoStruct info;
    aacEncInfo(enc, &info);
    printf("config ");
    for (UINT i = 0; i < info.confSize; i++) printf("%02x", info.confBuf[i]);
    printf(", %u samples a frame\n", info.frameLength);

    INT_PCM pcm[kFrame * 2];
    UCHAR unit[2048];
    long position = 0;
    int units = 0;
    long total = (long)seconds * kRate;
    for (;;) {
        bool flushing = position >= total;
        for (int i = 0; i < kFrame; i++) {
            INT_PCM s = (INT_PCM)(18000 * sin(2 * M_PI * 440 * (position + i) / kRate));
            pcm[2 * i] = pcm[2 * i + 1] = s;
        }
        position += kFrame;

        void* inPtr = pcm;
        INT inId = IN_AUDIO_DATA, inSize = sizeof(pcm), inElSize = sizeof(INT_PCM);
        void* outPtr = unit;
        INT outId = OUT_BITSTREAM_DATA, outSize = sizeof(unit), outElSize = 1;
        AACENC_BufDesc inBuf = {1, &inPtr, &inId, &inSize, &inElSize};
        AACENC_BufDesc outBuf = {1, &outPtr, &outId, &outSize, &outElSize};
        AACENC_InArgs inArgs = {flushing ? -1 : kFrame * 2, 0};
        AACENC_OutArgs outArgs = {};
        AACENC_ERROR err = aacEncEncode(enc, &inBuf, &outBuf, &inArgs, &outArgs);
        if (err == AACENC_ENCODE_EOF) break;
        if (err != AACENC_OK) {
            fprintf(stderr, "encode failed: %d\n", err);
            return 1;
        }
        if (outArgs.numOutBytes > 0) {
            fputc(outArgs.numOutBytes >> 8, out);
            fputc(outArgs.numOutBytes & 0xff, out);
            fwrite(unit, 1, outArgs.numOutBytes, out);
            units++;
        }
    }
    aacEncClose(&enc);
    fclose(out);
    printf("%d units, %.1f s\n", units, units * (double)kFrame / kRate);
    return 0;
}
