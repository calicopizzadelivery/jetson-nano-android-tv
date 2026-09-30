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
