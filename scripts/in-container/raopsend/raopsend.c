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
 *   raopsend <host> <port> [seconds]
 *
 * Generates its own 440 Hz tone, so there is no file to push.
 */

#include <arpa/inet.h>
#include <errno.h>
#include <math.h>
#include <netinet/in.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define FRAMES_PER_PACKET 352 /* what Shairport advertises in fmtp */
#define SAMPLE_RATE 44100
#define CHANNELS 2

static int rtsp_fd = -1;
static int cseq = 0;
static char local_ip[64] = "127.0.0.1";

static int rtsp_exchange(const char *method, const char *extra_headers, const char *body,
                         char *response, size_t response_len) {
  char request[4096];
  int n = snprintf(request, sizeof(request),
                   "%s rtsp://%s/1 RTSP/1.0\r\n"
                   "CSeq: %d\r\n"
                   "User-Agent: raopsend/1.0\r\n"
                   "Client-Instance: 0011223344556677\r\n",
                   method, local_ip, ++cseq);
  if (body != NULL)
    n += snprintf(request + n, sizeof(request) - n,
                  "Content-Type: application/sdp\r\nContent-Length: %zu\r\n", strlen(body));
  if (extra_headers != NULL)
    n += snprintf(request + n, sizeof(request) - n, "%s\r\n", extra_headers);
  n += snprintf(request + n, sizeof(request) - n, "\r\n");
  if (body != NULL)
    n += snprintf(request + n, sizeof(request) - n, "%s", body);

  if (write(rtsp_fd, request, n) != n) {
    fprintf(stderr, "%s: short write: %s\n", method, strerror(errno));
    return -1;
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
  printf("  %-8s -> %s\n", method, first);
  return strstr(response, "RTSP/1.0 200") == response ? 0 : -1;
}

/* Answer the receiver's timing requests. Without them the player waits for
   clock sync and never starts. */
static void service_timing(int fd) {
  uint8_t packet[256];
  struct sockaddr_in from;
  socklen_t from_len = sizeof(from);
  for (;;) {
    ssize_t n = recvfrom(fd, packet, sizeof(packet), MSG_DONTWAIT,
                         (struct sockaddr *)&from, &from_len);
    if (n < 8)
      return; /* EAGAIN, or nothing useful */
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

int main(int argc, char **argv) {
  if (argc < 3) {
    fprintf(stderr, "usage: %s <host> <port> [seconds]\n", argv[0]);
    return 2;
  }
  const char *host = argv[1];
  int port = atoi(argv[2]);
  int seconds = (argc > 3) ? atoi(argv[3]) : 6;

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
  struct sockaddr_in local;
  socklen_t local_len = sizeof(local);
  getsockname(rtsp_fd, (struct sockaddr *)&local, &local_len);
  inet_ntop(AF_INET, &local.sin_addr, local_ip, sizeof(local_ip));
  printf("  sender %s -> receiver %s:%d\n", local_ip, host, port);

  int control_port = 0, timing_port = 0;
  int control_fd = bind_udp(&control_port);
  int timing_fd = bind_udp(&timing_port);
  if (control_fd < 0 || timing_fd < 0) {
    fprintf(stderr, "could not bind the control/timing sockets\n");
    return 1;
  }

  char response[4096];
  if (rtsp_exchange("OPTIONS", NULL, NULL, response, sizeof(response)) != 0)
    return 1;

  char sdp[1024];
  /* No a=fmtp line. Shairport's ANNOUNCE handler does
     "if (pfmtp) { conn->stream.type = ast_apple_lossless; }" -- the mere
     presence of fmtp selects ALAC no matter what the rtpmap says, and the
     ALAC decoder then chokes on PCM ("unhandled prediction type") and takes
     the receiver down with it. The uncompressed branch sets frames per
     packet, rate, channels and depth itself, so fmtp has nothing to add. */
  snprintf(sdp, sizeof(sdp),
           "v=0\r\no=iTunes %ld 0 IN IP4 %s\r\ns=iTunes\r\nc=IN IP4 %s\r\nt=0 0\r\n"
           "m=audio 0 RTP/AVP 96\r\n"
           "a=rtpmap:96 L16/%d/%d\r\n",
           (long)time(NULL), local_ip, host, SAMPLE_RATE, CHANNELS);
  if (rtsp_exchange("ANNOUNCE", NULL, sdp, response, sizeof(response)) != 0)
    return 1;

  char transport[256];
  snprintf(transport, sizeof(transport),
           "Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;"
           "control_port=%d;timing_port=%d",
           control_port, timing_port);
  if (rtsp_exchange("SETUP", transport, NULL, response, sizeof(response)) != 0)
    return 1;

  const char *sp = strstr(response, "server_port=");
  if (sp == NULL) {
    fprintf(stderr, "no server_port in the SETUP response\n");
    return 1;
  }
  int audio_port = atoi(sp + strlen("server_port="));
  printf("  receiver audio port: %d\n", audio_port);

  if (rtsp_exchange("RECORD", "Range: npt=0-\r\nRTP-Info: seq=0;rtptime=0", NULL, response,
                    sizeof(response)) != 0)
    return 1;

  int audio_fd = socket(AF_INET, SOCK_DGRAM, 0);
  struct sockaddr_in audio_addr = server;
  audio_addr.sin_port = htons(audio_port);

  int total_packets = (SAMPLE_RATE * seconds) / FRAMES_PER_PACKET;
  printf("  streaming %d packets (%d s of 440 Hz)\n", total_packets, seconds);

  uint8_t packet[12 + FRAMES_PER_PACKET * CHANNELS * 2];
  uint32_t timestamp = 0;
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
    service_timing(timing_fd);

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
  rtsp_exchange("TEARDOWN", NULL, NULL, response, sizeof(response));
  return 0;
}
