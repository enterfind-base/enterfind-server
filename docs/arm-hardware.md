# ARM Hardware Compatibility

The Docker image is published for **linux/amd64** and **linux/arm64** only. On ARM, the host must run a 64-bit OS — any board that is 32-bit-only, or any 64-bit-capable board running a 32-bit OS image, will not work.

The same goes for the [installer](installation.md#installer-no-docker)'s Linux arm64 build, which also needs glibc 2.28 or newer (Debian 10, Ubuntu 18.10 and later), so the lists below apply to it too.

## Compatible boards

All of these ship with or support a 64-bit Linux OS and run the image without modification.

| Board | SoC | CPU |
|---|---|---|
| Raspberry Pi 5 | BCM2712 | Cortex-A76 |
| Raspberry Pi 4 | BCM2711 | Cortex-A72 |
| Raspberry Pi Zero 2 W | RP3A0 | Cortex-A53 |
| Le Potato (AML-S905X-CC) | S905X | Cortex-A53 |
| ODROID-N2 / N2+ | S922X | Cortex-A73 + A53 |
| ODROID-C4 | S905X3 | Cortex-A55 |
| ODROID-HC4 | S905X3 | Cortex-A55 |
| ODROID-M1 / M1S | RK3568 | Cortex-A55 |
| Rock Pi 4 / ROCK 4 series | RK3399 | Cortex-A72 + A53 |
| Rock 5B / Radxa Rock 5 | RK3588 | Cortex-A76 + A55 |
| Radxa Rock 3A | RK3568 | Cortex-A55 |
| Orange Pi 5 / 5+ | RK3588S | Cortex-A76 + A55 |
| Orange Pi 4 | RK3399 | Cortex-A72 + A53 |
| Orange Pi Zero 2 / Zero 3 | H616 / H618 | Cortex-A53 |
| NanoPi R4S | RK3399 | Cortex-A72 + A53 |
| NanoPi R5S | RK3568 | Cortex-A55 |
| Pine64 / ROCKPro64 | RK3399 | Cortex-A72 + A53 |
| Khadas VIM3 | A311D | Cortex-A73 + A53 |

**Raspberry Pi Zero 2 W** has only 512 MB RAM. Spring Boot starts but leaves little headroom; not recommended for production use.

## OS-dependent: Raspberry Pi 3 / 3B+

The Cortex-A53 CPU is 64-bit capable, but Raspberry Pi OS shipped a 32-bit default image for years. With a **64-bit OS** (Raspberry Pi OS 64-bit, Ubuntu 22.04+, etc.) it is compatible. With a legacy 32-bit image it is not.

## Not compatible — 32-bit ARM only

These boards have no 64-bit mode. The image will not run on them regardless of OS.

| Board | SoC | CPU |
|---|---|---|
| ODROID-XU4 / HC1 / HC2 | Exynos 5422 | Cortex-A15 + A7 (ARMv7) |
| Raspberry Pi 2 | BCM2836 | Cortex-A7 (ARMv7) |
| Raspberry Pi 1 | BCM2835 | ARM1176 (ARMv6) |
| Raspberry Pi Zero / Zero W | BCM2835 | ARM1176 (ARMv6) |
| BeagleBone Black | AM335x | Cortex-A8 (ARMv7) |
| Banana Pi M2 / M2 Zero | H3 / H2+ | Cortex-A7 (ARMv7) |
| Orange Pi Zero (original) | H2+ | Cortex-A7 (ARMv7) |
| Orange Pi One / PC | H3 | Cortex-A7 (ARMv7) |

## Note on ARMv8.0 boards (Cortex-A53)

Boards built on Cortex-A53 — including the Raspberry Pi 3/4-era A53 clusters, Le Potato, Orange Pi Zero 2/3, and Pine64 — implement ARMv8.0, which predates the Large System Extensions (LSE) atomic instructions added in ARMv8.1. ONNX Runtime (used for object detection and face detection) targets ARMv8.0 and runs correctly on these boards. Code compiled specifically for ARMv8.1+ would SIGILL; no current dependency does this, but it is worth knowing if you build custom components.

---

**See also:** [Installation](installation.md) · [Getting Started](getting-started.md)

[← Back to documentation index](../README.md)
