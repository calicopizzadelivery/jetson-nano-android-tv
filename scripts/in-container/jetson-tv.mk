# SPDX-FileCopyrightText: 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
# Packages this project adds that are not part of any upstream tree. Inherited
# by device/nvidia/porg/device.mk through inherit-product-if-exists, so a tree
# without vendor/jetson-tv still configures and builds.

PRODUCT_PACKAGES += \
    AmbientDream
