# SPDX-FileCopyrightText: 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
# Packages this project adds that are not part of any upstream tree. Inherited
# by device/nvidia/porg/device.mk through inherit-product-if-exists, so a tree
# without vendor/jetson-tv still configures and builds.

# AirPlay is UxPlay, hosted in AirPlayReceiver (which pulls in libuxplay_jni).
# shairport-sync is no longer shipped: it would advertise a second AirPlay
# receiver under the same name, and its init service would start on the same
# enable property. Its fork stays in the tree for if it is ever wanted back.
PRODUCT_PACKAGES += \
    AirPlayReceiver \
    AmbientDream

# The system document and folder picker. TV builds leave it out and install
# TvFrameworkPackageStubs, whose DocumentsStub claims OPEN_DOCUMENT(_TREE)
# and GET_CONTENT and then does nothing, so an app asking the user for a
# folder gets nothing back: Lemuroid cannot be pointed at a games folder.
# The stub's filters are priority 99, DocumentsUI's 100, so with DocumentsUI
# present it wins. It brings its own privapp allowlist.
PRODUCT_PACKAGES += \
    DocumentsUI

# Third-party apps, from PrebuiltApps/apps.json via prebuilt_apps.py, which
# tree-local-changes.sh runs before every build. A plain include, not an
# -include: a build that skipped that step must fail, not quietly ship
# without them.
include vendor/jetson-tv/PrebuiltApps/prebuilt-apps.mk
