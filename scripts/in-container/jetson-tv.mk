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

# JetsonTV's own identity, from vendor/jetson-tv/Branding. SHIELD's tree
# names the box "SHIELD Android TV" in two places, and both are overridden
# here: the device name by a product overlay (which outranks foster's device
# overlay), the Bluetooth name by a product property (product build.prop is
# loaded after foster's vendor one). AirPlay uses the device name too.
#
# The wallpaper is NASA's Artemis II Earthset (Branding/wallpaper/NOTICE).
# ro.config.wallpaper makes it the default, which the home screen shows
# under a scrim.
PRODUCT_PACKAGE_OVERLAYS += vendor/jetson-tv/Branding/overlay
PRODUCT_PACKAGES += \
    JetsonTVWallpaper
PRODUCT_PRODUCT_PROPERTIES += \
    bluetooth.device.default_name=JetsonTV \
    ro.config.wallpaper=/product/media/wallpaper/earthset.jpg

# Key layouts for 8BitDo pads in X-input mode (USB or their 2.4 GHz
# receiver), which xpad drives once kernel/0002 lets it claim 8BitDo's vendor
# ID. Android has none for those IDs, and its generic layout swaps the right
# stick and the triggers on an xpad device. /product/usr is searched first.
PRODUCT_COPY_FILES += \
    $(foreach f,$(wildcard vendor/jetson-tv/Input/keylayout/*.kl),\
        $(f):$(TARGET_COPY_OUT_PRODUCT)/usr/keylayout/$(notdir $(f)))

# A game controller's Guide button goes Home, unless the user turns that off.
# The key layout keeps the honest BUTTON_MODE so the button stays available to
# apps; the Home behaviour is this handler, which Branding/overlay points
# PhoneWindowManager at. See KeyHandler/.
PRODUCT_PACKAGES += \
    JetsonTVKeyHandler

# Third-party apps, from PrebuiltApps/apps.json via prebuilt_apps.py, which
# tree-local-changes.sh runs before every build. A plain include, not an
# -include: a build that skipped that step must fail, not quietly ship
# without them.
include vendor/jetson-tv/PrebuiltApps/prebuilt-apps.mk
