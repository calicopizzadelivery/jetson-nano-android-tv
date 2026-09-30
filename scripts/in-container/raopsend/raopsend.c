/*
 * A minimal classic-RAOP (AirPlay 1) sender, for testing a receiver on-device.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Why this exists rather than an off-the-shelf sender:
 *
 *   - pyatv probes "GET /info" first, which is AirPlay 2. A Classic receiver
 *     never answers it, so pyatv times out before it sends anything useful.
 *   - It has to run on the device. RAOP carries audio over UDP; adb forward is
 *     TCP only, and the emulator console's redir targets eth0 while the
 *     emulator's IPv4 address lands on wlan0.
 *
 * It is short because Shairport Sync accepts two things that remove all the
 * hard parts (see rtsp.c): uncompressed L16/44100/2, so no ALAC encoder is
 * needed, and a stream carrying neither a=aesiv nor a=rsaaeskey is treated as
 * unencrypted, so there is no RSA key exchange.
 *
 *   raopsend [options] <host> <port> [seconds]
 *
 * Generates its own 440 Hz tone and carries its own cover art, so there is
 * nothing to push alongside it.
 *
 * Metadata is the reason most of this file is not just the audio loop. A
 * receiver with CONFIG_METADATA writes to its metadata pipe only what a sender
 * sends it, so a tone-only sender leaves the pipe silent and there is no way
 * to tell a broken pipe from an idle one. What Shairport recognises, and what
 * this therefore emits (rtsp.c, handle_set_parameter):
 *
 *   X-Apple-Client-Name: header on ANNOUNCE  -> ssnc/snam, the sender's name
 *   application/x-dmap-tagged                -> core items, the DAAP track tags
 *   image/jpeg                               -> ssnc/PICT, the cover art
 *   text/parameters "progress: a/b/c"        -> ssnc/prgr, position in frames
 *   text/parameters "volume: d"              -> the AirPlay volume
 *
 * The DAAP and picture items are bracketed by the receiver in ssnc/mdst and
 * ssnc/mden (and pcst/pcen) using the rtptime from RTP-Info, which is how a
 * reader knows a group of items describes one track. Sending that header is
 * optional -- Shairport only logs its absence -- but without it every item
 * arrives ungrouped, so this always sends it.
 */

#include <arpa/inet.h>
#include <errno.h>
#include <math.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#include "artwork.h"

#define FRAMES_PER_PACKET 352 /* what Shairport advertises in fmtp */
#define SAMPLE_RATE 44100
#define CHANNELS 2

/* Enough for the largest thing sent inline; artwork is written separately. */
#define REQUEST_MAX 8192

static int rtsp_fd = -1;
static int cseq = 0;
static char local_ip[64] = "127.0.0.1";
static const char *client_name = "raopsend";

/*
 * One RTSP request/response. The body is a length-counted blob rather than a
 * string because cover art is JPEG, and the content type is a parameter
 * because the same method carries SDP, DAAP, an image and plain text.
 */
static int rtsp_exchange(const char *method, const char *extra_headers, const char *content_type,
                         const void *body, size_t body_len, char *response, size_t response_len) {
  char header[REQUEST_MAX];
  int n = snprintf(header, sizeof(header),
                   "%s rtsp://%s/1 RTSP/1.0\r\n"
                   "CSeq: %d\r\n"
                   "User-Agent: raopsend/1.0\r\n"
                   "Client-Instance: 0011223344556677\r\n"
                   "X-Apple-Client-Name: %s\r\n",
                   method, local_ip, ++cseq, client_name);
  if (body != NULL)
    n += snprintf(header + n, sizeof(header) - n, "Content-Type: %s\r\nContent-Length: %zu\r\n",
                  content_type, body_len);
  if (extra_headers != NULL)
    n += snprintf(header + n, sizeof(header) - n, "%s\r\n", extra_headers);
  n += snprintf(header + n, sizeof(header) - n, "\r\n");
  if (n < 0 || (size_t)n >= sizeof(header)) {
    fprintf(stderr, "%s: headers too long\n", method);
    return -1;
  }

  /* Two writes rather than one buffer: the artwork alone is bigger than any
     sane request buffer, and Nagle coalesces them anyway. */
  if (write(rtsp_fd, header, n) != n) {
    fprintf(stderr, "%s: short write: %s\n", method, strerror(errno));
    return -1;
  }
  if (body != NULL && body_len > 0) {
    const uint8_t *p = body;
    size_t sent = 0;
    while (sent < body_len) {
      ssize_t w = write(rtsp_fd, p + sent, body_len - sent);
      if (w <= 0) {
        fprintf(stderr, "%s: short body write: %s\n", method, strerror(errno));
        return -1;
      }
      sent += w;
    }
  }

  size_t total = 0;
  while (total < response_len - 1) {
    ssize_t got = read(rtsp_fd, response + total, response_len - 1 - total);
    if (got <= 0) {
      fprintf(stderr, "%s: no response: %s\n", method, strerror(errno));
      return -1;
    }
    total += got;
    response[total] = 0;
    if (strstr(response, "\r\n\r\n") != NULL)
      break;
  }
  char first[128] = {0};
  const char *eol = strstr(response, "\r\n");
  if (eol != NULL && (size_t)(eol - response) < sizeof(first))
    memcpy(first, response, eol - response);
  printf("  %-13s -> %s\n", method, first);
  return strstr(response, "RTSP/1.0 200") == response ? 0 : -1;
}

/* ------------------------------------------------------------------ DAAP -- */

/*
 * DMAP is a flat run of (4-char code, 4-byte big-endian length, payload).
 * Containers nest the same shape. Shairport's parser starts at offset 8 --
 * it assumes and discards exactly one container header -- so the tags have
 * to sit inside one wrapper, which iTunes sends as 'mlit', a listing item.
 */
static size_t daap_put(uint8_t *buf, size_t off, size_t cap, const char *code, const void *data,
                       size_t len) {
  if (off + 8 + len > cap)
    return off; /* silently drop rather than corrupt the run */
  memcpy(buf + off, code, 4);
  uint32_t belen = htonl((uint32_t)len);
  memcpy(buf + off + 4, &belen, 4);
  if (len > 0)
    memcpy(buf + off + 8, data, len);
  return off + 8 + len;
}

static size_t daap_put_str(uint8_t *buf, size_t off, size_t cap, const char *code,
                           const char *value) {
  if (value == NULL || *value == 0)
    return off;
  return daap_put(buf, off, cap, code, value, strlen(value));
}

static size_t daap_put_u32(uint8_t *buf, size_t off, size_t cap, const char *code, uint32_t value) {
  uint32_t be = htonl(value);
  return daap_put(buf, off, cap, code, &be, sizeof(be));
}

/*
 * Build one 'mlit' listing. astm is the track length in milliseconds, which
 * is what gives a now-playing surface a duration to draw a progress bar
 * against; the progress items that follow only carry a position.
 */
static size_t build_daap(uint8_t *buf, size_t cap, const char *title, const char *artist,
                         const char *album, uint32_t duration_ms) {
  size_t off = 8; /* leave room for the container header */
  off = daap_put_str(buf, off, cap, "minm", title);
  off = daap_put_str(buf, off, cap, "asar", artist);
  off = daap_put_str(buf, off, cap, "asal", album);
  if (duration_ms > 0)
    off = daap_put_u32(buf, off, cap, "astm", duration_ms);
  memcpy(buf, "mlit", 4);
  uint32_t belen = htonl((uint32_t)(off - 8));
  memcpy(buf + 4, &belen, 4);
  return off;
}

static int send_track_metadata(uint32_t rtptime, const char *title, const char *artist,
                               const char *album, uint32_t duration_ms, char *response,
                               size_t response_len) {
  uint8_t daap[2048];
  size_t len = build_daap(daap, sizeof(daap), title, artist, album, duration_ms);
  char rtp_info[64];
  snprintf(rtp_info, sizeof(rtp_info), "RTP-Info: rtptime=%u", rtptime);
  printf("  metadata: \"%s\" / \"%s\" / \"%s\" (%u ms, %zu B)\n", title, artist, album, duration_ms,
         len);
  return rtsp_exchange("SET_PARAMETER", rtp_info, "application/x-dmap-tagged", daap, len, response,
                       response_len);
}

static int send_artwork(uint32_t rtptime, const uint8_t *jpeg, size_t len, char *response,
                        size_t response_len) {
  char rtp_info[64];
  snprintf(rtp_info, sizeof(rtp_info), "RTP-Info: rtptime=%u", rtptime);
  printf("  artwork: %zu B of JPEG\n", len);
  return rtsp_exchange("SET_PARAMETER", rtp_info, "image/jpeg", jpeg, len, response, response_len);
}

/*
 * Progress is three RTP timestamps: where the track started, where the player
 * is now, and where it ends. Shairport passes the string through untouched as
 * ssnc/prgr, so the units are whatever the sender's clock uses -- frames at
 * the stream's sample rate.
 */
static int send_progress(uint32_t start, uint32_t now, uint32_t end, char *response,
                         size_t response_len) {
  char body[96];
  int n = snprintf(body, sizeof(body), "progress: %u/%u/%u\r\n", start, now, end);
  return rtsp_exchange("SET_PARAMETER", NULL, "text/parameters", body, n, response, response_len);
}

/* ----------------------------------------------------------------- audio -- */

/*
 * Answer the receiver's timing requests. Without them the player waits for
 * clock sync and never starts.
 *
 * This has to be its own thread, and that is not a tidiness point. Polling the
 * timing socket from inside the audio loop replies only at the next packet
 * boundary, so the round trip a receiver measures is not the real one: it is
 * however long is left of the current 8 ms packet. Shairport-sync estimates
 * the clock offset by *filtering on round-trip time*, so a reply delay that
 * sawtooths from 0 to 8 ms makes every sample look different and it rejects
 * them all -- "not enough samples to estimate drift -- remaining at 0.00 ppm",
 * forever. The player then never learns when to play, sits in
 * buffer_get_frame() and emits nothing at all, with any backend.
 *
 * The symptom is silence with a healthy-looking session: RTSP succeeds, the
 * packets arrive, the output stream opens and starts, and not one frame is
 * ever written to it.
 */
static void *timing_thread(void *arg) {
  const int fd = *(int *)arg;
  uint8_t packet[256];
  struct sockaddr_in from;
  socklen_t from_len = sizeof(from);
  for (;;) {
    /* Blocking, so the reply goes out as soon as the request lands. */
    ssize_t n = recvfrom(fd, packet, sizeof(packet), 0,
                         (struct sockaddr *)&from, &from_len);
    if (n < 0)
      return NULL;
    if (n < 8)
      continue;
    if ((packet[1] & 0x7F) != 82) /* 82: timing request */
      continue;

    struct timeval tv;
    gettimeofday(&tv, NULL);
    uint32_t secs = htonl((uint32_t)tv.tv_sec + 0x83AA7E80u); /* NTP epoch */
    uint32_t frac = htonl((uint32_t)((double)tv.tv_usec * 4294.967296));

    uint8_t reply[32];
    memset(reply, 0, sizeof(reply));
    reply[0] = 0x80;
    reply[1] = 0xD3; /* timing response */
    reply[2] = 0x00;
    reply[3] = 0x07;
    if (n >= 32)
      memcpy(reply + 8, packet + 24, 8); /* echo the request's transmit time */
    memcpy(reply + 16, &secs, 4);
    memcpy(reply + 20, &frac, 4);
    memcpy(reply + 24, &secs, 4);
    memcpy(reply + 28, &frac, 4);
    sendto(fd, reply, sizeof(reply), 0, (struct sockaddr *)&from, from_len);
  }
  return NULL;
}

static int bind_udp(int *port) {
  int fd = socket(AF_INET, SOCK_DGRAM, 0);
  if (fd < 0)
    return -1;
  struct sockaddr_in addr;
  memset(&addr, 0, sizeof(addr));
  addr.sin_family = AF_INET;
  addr.sin_addr.s_addr = htonl(INADDR_ANY);
  if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
    close(fd);
    return -1;
  }
  socklen_t len = sizeof(addr);
  getsockname(fd, (struct sockaddr *)&addr, &len);
  *port = ntohs(addr.sin_port);
  return fd;
}

static uint8_t *read_file(const char *path, size_t *len) {
  FILE *f = fopen(path, "rb");
  if (f == NULL)
    return NULL;
  fseek(f, 0, SEEK_END);
  long size = ftell(f);
  fseek(f, 0, SEEK_SET);
  if (size <= 0) {
    fclose(f);
    return NULL;
  }
  uint8_t *buf = malloc(size);
  if (buf == NULL || fread(buf, 1, size, f) != (size_t)size) {
    free(buf);
    fclose(f);
    return NULL;
  }
  fclose(f);
  *len = size;
  return buf;
}

static void usage(const char *argv0) {
  fprintf(stderr,
          "usage: %s [options] <host> <port> [seconds]\n"
          "\n"
          "  --name NAME       sender name, as X-Apple-Client-Name (default raopsend)\n"
          "  --title TEXT      track title\n"
          "  --artist TEXT     track artist\n"
          "  --album TEXT      track album\n"
          "  --artwork FILE    cover art JPEG (default: a small embedded image)\n"
          "  --no-metadata     send audio only, no DAAP tags or art\n"
          "  --no-artwork      send the tags but no cover art\n"
          "  --second-track    send a different track at the halfway point, so a\n"
          "                    reader's update path is exercised as well as its first\n"
          "                    read\n",
          argv0);
}

int main(int argc, char **argv) {
  const char *title = "Test Tone";
  const char *artist = "raopsend";
  const char *album = "Jetson TV bench";
  const char *artwork_path = NULL;
  int want_metadata = 1, want_artwork = 1, second_track = 0;

  int a = 1;
  for (; a < argc && strncmp(argv[a], "--", 2) == 0; a++) {
    const char *opt = argv[a];
    const char **target = NULL;
    if (strcmp(opt, "--name") == 0)
      target = &client_name;
    else if (strcmp(opt, "--title") == 0)
      target = &title;
    else if (strcmp(opt, "--artist") == 0)
      target = &artist;
    else if (strcmp(opt, "--album") == 0)
      target = &album;
    else if (strcmp(opt, "--artwork") == 0)
      target = &artwork_path;
    else if (strcmp(opt, "--no-metadata") == 0)
      want_metadata = 0;
    else if (strcmp(opt, "--no-artwork") == 0)
      want_artwork = 0;
    else if (strcmp(opt, "--second-track") == 0)
      second_track = 1;
    else {
      usage(argv[0]);
      return 2;
    }
    if (target != NULL) {
      if (a + 1 >= argc) {
        fprintf(stderr, "%s needs a value\n", opt);
        return 2;
      }
      *target = argv[++a];
    }
  }

  if (argc - a < 2) {
    usage(argv[0]);
    return 2;
  }
  const char *host = argv[a];
  int port = atoi(argv[a + 1]);
  int seconds = (argc - a > 2) ? atoi(argv[a + 2]) : 6;

  const uint8_t *artwork = default_artwork;
  size_t artwork_len = default_artwork_len;
  if (artwork_path != NULL) {
    size_t len = 0;
    uint8_t *loaded = read_file(artwork_path, &len);
    if (loaded == NULL) {
      fprintf(stderr, "could not read %s: %s\n", artwork_path, strerror(errno));
      return 1;
    }
    artwork = loaded;
    artwork_len = len;
  }

  struct sockaddr_in server;
  memset(&server, 0, sizeof(server));
  server.sin_family = AF_INET;
  server.sin_port = htons(port);
  if (inet_pton(AF_INET, host, &server.sin_addr) != 1) {
    fprintf(stderr, "bad host %s\n", host);
    return 2;
  }

  rtsp_fd = socket(AF_INET, SOCK_STREAM, 0);
  if (connect(rtsp_fd, (struct sockaddr *)&server, sizeof(server)) < 0) {
    fprintf(stderr, "connect: %s\n", strerror(errno));
    return 1;
  }
  /* Mid-stream SET_PARAMETERs block the audio loop while they wait for a
     reply. A receiver that stops answering should cost a couple of seconds of
     jitter, not a hang. */
  struct timeval rcv_timeout = {2, 0};
  setsockopt(rtsp_fd, SOL_SOCKET, SO_RCVTIMEO, &rcv_timeout, sizeof(rcv_timeout));

  struct sockaddr_in local;
  socklen_t local_len = sizeof(local);
  getsockname(rtsp_fd, (struct sockaddr *)&local, &local_len);
  inet_ntop(AF_INET, &local.sin_addr, local_ip, sizeof(local_ip));
  printf("  sender %s (\"%s\") -> receiver %s:%d\n", local_ip, client_name, host, port);

  int control_port = 0, timing_port = 0;
  int control_fd = bind_udp(&control_port);
  int timing_fd = bind_udp(&timing_port);
  if (control_fd < 0 || timing_fd < 0) {
    fprintf(stderr, "could not bind the control/timing sockets\n");
    return 1;
  }

  pthread_t timing_tid;
  if (pthread_create(&timing_tid, NULL, timing_thread, &timing_fd) != 0) {
    fprintf(stderr, "could not start the timing thread: %s\n", strerror(errno));
    return 1;
  }
  pthread_detach(timing_tid);

  char response[4096];
  if (rtsp_exchange("OPTIONS", NULL, NULL, NULL, 0, response, sizeof(response)) != 0)
    return 1;

  char sdp[1024];
  /* No a=fmtp line. Shairport's ANNOUNCE handler does
     "if (pfmtp) { conn->stream.type = ast_apple_lossless; }" -- the mere
     presence of fmtp selects ALAC no matter what the rtpmap says, and the
     ALAC decoder then chokes on PCM ("unhandled prediction type") and takes
     the receiver down with it. The uncompressed branch sets frames per
     packet, rate, channels and depth itself, so fmtp has nothing to add. */
  int sdp_len = snprintf(sdp, sizeof(sdp),
                         "v=0\r\no=iTunes %ld 0 IN IP4 %s\r\ns=iTunes\r\nc=IN IP4 %s\r\nt=0 0\r\n"
                         "m=audio 0 RTP/AVP 96\r\n"
                         "a=rtpmap:96 L16/%d/%d\r\n",
                         (long)time(NULL), local_ip, host, SAMPLE_RATE, CHANNELS);
  /* ANNOUNCE is where X-Apple-Client-Name is read, so ssnc/snam arrives here
     -- before any track tags, which is the order a reader wants it in. */
  if (rtsp_exchange("ANNOUNCE", NULL, "application/sdp", sdp, sdp_len, response,
                    sizeof(response)) != 0)
    return 1;

  char transport[256];
  snprintf(transport, sizeof(transport),
           "Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;"
           "control_port=%d;timing_port=%d",
           control_port, timing_port);
  if (rtsp_exchange("SETUP", transport, NULL, NULL, 0, response, sizeof(response)) != 0)
    return 1;

  const char *sp = strstr(response, "server_port=");
  if (sp == NULL) {
    fprintf(stderr, "no server_port in the SETUP response\n");
    return 1;
  }
  int audio_port = atoi(sp + strlen("server_port="));
  printf("  receiver audio port: %d\n", audio_port);

  if (rtsp_exchange("RECORD", "Range: npt=0-\r\nRTP-Info: seq=0;rtptime=0", NULL, NULL, 0, response,
                    sizeof(response)) != 0)
    return 1;

  uint32_t total_frames = (uint32_t)SAMPLE_RATE * seconds;

  /* After RECORD, so the receiver has a player to attach the metadata to. */
  if (want_metadata) {
    send_track_metadata(0, title, artist, album, (uint32_t)seconds * 1000, response,
                        sizeof(response));
    if (want_artwork)
      send_artwork(0, artwork, artwork_len, response, sizeof(response));
    send_progress(0, 0, total_frames, response, sizeof(response));
  }

  int audio_fd = socket(AF_INET, SOCK_DGRAM, 0);
  struct sockaddr_in audio_addr = server;
  audio_addr.sin_port = htons(audio_port);

  int total_packets = (SAMPLE_RATE * seconds) / FRAMES_PER_PACKET;
  printf("  streaming %d packets (%d s of 440 Hz)\n", total_packets, seconds);

  uint8_t packet[12 + FRAMES_PER_PACKET * CHANNELS * 2];
  uint32_t timestamp = 0;
  uint32_t next_progress = SAMPLE_RATE; /* one update a second */
  int swapped_track = 0;
  struct timespec start;
  clock_gettime(CLOCK_MONOTONIC, &start);

  for (int i = 0; i < total_packets; i++) {
    packet[0] = 0x80;
    packet[1] = (i == 0) ? 0xE0 : 0x60; /* marker on the first packet */
    packet[2] = (i >> 8) & 0xFF;
    packet[3] = i & 0xFF;
    uint32_t ts = htonl(timestamp);
    memcpy(packet + 4, &ts, 4);
    memset(packet + 8, 0, 4); /* ssrc */

    /* L16 is network byte order, so write the samples big-endian. */
    uint8_t *payload = packet + 12;
    for (int f = 0; f < FRAMES_PER_PACKET; f++) {
      double t = (double)(timestamp + f) / SAMPLE_RATE;
      int16_t sample = (int16_t)(18000.0 * sin(2.0 * M_PI * 440.0 * t));
      for (int c = 0; c < CHANNELS; c++) {
        *payload++ = (sample >> 8) & 0xFF;
        *payload++ = sample & 0xFF;
      }
    }

    sendto(audio_fd, packet, sizeof(packet), 0, (struct sockaddr *)&audio_addr,
           sizeof(audio_addr));
    timestamp += FRAMES_PER_PACKET;

    if (want_metadata && second_track && !swapped_track && timestamp >= total_frames / 2) {
      swapped_track = 1;
      send_track_metadata(timestamp, "Second Tone", artist, album,
                          (uint32_t)seconds * 1000 / 2, response, sizeof(response));
    }
    if (want_metadata && timestamp >= next_progress) {
      next_progress += SAMPLE_RATE;
      send_progress(0, timestamp, total_frames, response, sizeof(response));
    }

    /* Pace it: the receiver expects audio to arrive about as fast as it plays. */
    struct timespec now, target;
    double elapsed = (double)(timestamp) / SAMPLE_RATE;
    target.tv_sec = start.tv_sec + (time_t)elapsed;
    target.tv_nsec = start.tv_nsec + (long)((elapsed - (long)elapsed) * 1e9);
    if (target.tv_nsec >= 1000000000L) {
      target.tv_sec++;
      target.tv_nsec -= 1000000000L;
    }
    clock_gettime(CLOCK_MONOTONIC, &now);
    long sleep_ns = (target.tv_sec - now.tv_sec) * 1000000000L + (target.tv_nsec - now.tv_nsec);
    if (sleep_ns > 0) {
      struct timespec d = {sleep_ns / 1000000000L, sleep_ns % 1000000000L};
      nanosleep(&d, NULL);
    }
  }

  printf("  sent %d packets\n", total_packets);
  rtsp_exchange("TEARDOWN", NULL, NULL, NULL, 0, response, sizeof(response));
  return 0;
}
