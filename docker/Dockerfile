# LineageOS / AOSP build environment for NVIDIA Tegra (Jetson Nano, codename "porg")
#
# Base is deliberately Ubuntu 22.04, not 24.04. Several packages the LineageOS
# build guide asks for were dropped or renamed in noble (libsdl1.2-dev became
# libsdl1.2-compat-dev, lib32readline-dev disappeared). Pinning the container to
# jammy is the entire reason for containerising this in the first place — the
# host distro is then irrelevant.
FROM ubuntu:22.04

ARG HOST_UID=1000
ARG HOST_GID=1000
ARG USERNAME=builder

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

# ---------------------------------------------------------------------------
# Packages
#   Block 1 is the list from the LineageOS build guide, verbatim, plus the
#   ncurses packages that guide asks for on pre-23.10 Ubuntu (harmless here,
#   and needed if you ever build a lineage-20-or-older branch).
#   Block 2 is everything that list quietly assumes you already have.
# ---------------------------------------------------------------------------
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      bc bison build-essential ccache curl erofs-utils flex \
      g++-multilib gcc-multilib git git-lfs gnupg gperf imagemagick \
      protobuf-compiler python3-protobuf lib32readline-dev lib32z1-dev \
      libdw-dev libelf-dev libgnutls28-dev lz4 libsdl1.2-dev libssl-dev \
      libxml2 libxml2-utils lzop pngcrush rsync schedtool squashfs-tools \
      xsltproc xxd zip zlib1g-dev \
      lib32ncurses5-dev libncurses5 libncurses5-dev \
 && apt-get install -y --no-install-recommends \
      adb fastboot android-sdk-libsparse-utils \
      brotli bsdextrautils ca-certificates cpio file jq less nano \
      openssh-client p7zip-full procps python3 python3-pip python3-venv \
      python-is-python3 sudo tzdata unzip wget xz-utils \
 && rm -rf /var/lib/apt/lists/*

# ---------------------------------------------------------------------------
# repo
# ---------------------------------------------------------------------------
RUN curl -fsSL https://storage.googleapis.com/git-repo-downloads/repo \
      -o /usr/local/bin/repo \
 && chmod a+x /usr/local/bin/repo

# ---------------------------------------------------------------------------
# An unprivileged user whose uid/gid match yours on the host, so files written
# into the bind-mounted source tree come out owned by you. The guards handle
# both "uid 1000 already exists in the base image" and "it doesn't".
# ---------------------------------------------------------------------------
RUN set -eux; \
    if getent group "${HOST_GID}" >/dev/null; then \
        groupmod -n "${USERNAME}" "$(getent group "${HOST_GID}" | cut -d: -f1)"; \
    else \
        groupadd -g "${HOST_GID}" "${USERNAME}"; \
    fi; \
    if getent passwd "${HOST_UID}" >/dev/null; then \
        usermod -l "${USERNAME}" -d "/home/${USERNAME}" -m \
                "$(getent passwd "${HOST_UID}" | cut -d: -f1)"; \
        usermod -g "${HOST_GID}" "${USERNAME}"; \
    else \
        useradd -u "${HOST_UID}" -g "${HOST_GID}" -m -s /bin/bash "${USERNAME}"; \
    fi; \
    printf '%s ALL=(ALL) NOPASSWD:ALL\n' "${USERNAME}" > "/etc/sudoers.d/${USERNAME}"; \
    chmod 0440 "/etc/sudoers.d/${USERNAME}"

# ---------------------------------------------------------------------------
# ccache + mount points
#   /srv/lineage  source tree        (bind, big disk)
#   /ccache       compiler cache     (bind, big disk)
#   /dlcache      downloaded L4T tarballs and SHIELD OTA images (bind, big disk)
# ---------------------------------------------------------------------------
ENV USE_CCACHE=1 \
    CCACHE_EXEC=/usr/bin/ccache \
    CCACHE_DIR=/ccache

RUN mkdir -p /srv/lineage /ccache /dlcache \
 && chown "${HOST_UID}:${HOST_GID}" /srv/lineage /ccache /dlcache

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

USER ${USERNAME}
WORKDIR /srv/lineage

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["bash"]
