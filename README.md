# mst2-carplay-vc

This patch brings CarPlay song information including title, artist, album, playback progress, shuffle view and cover art to the Virtual Cockpit instead of just showing the generic "Apple CarPlay" text.

It also enables a "Share to External Maps" button within navigation apps to directly import the addresses into the internal MIB2 Navi (only EU for now).

(True turn-by-turn directions are coming in the next update)

Works with both wired connection and wireless connection via third-party dongles. 

Should work on all firmwares.

For a more detailed explanation of how it works, see [technical-readme.md](/technical-readme.md)

<img src="https://github.com/xPaiiN/mst2-carplay-vc/blob/cf1db33f421d6441804630c6a78a10ffa9ca6a3e/pics/mst2-cpvc-full.jpeg" width="1240">


## Compatibility

> [!CAUTION]
>
> - Only works with MIB2Std TechniSat/Preh ZR/PQ (MIB2 Standard)
>
> - Does NOT work with:
>   - MIB2Std Delphi (MIB2 Standard)
>   - MHI2 Harman (MIB2 High) (works so far, but not yet released)

> [!IMPORTANT]
> Completely vibecoded project. Not responsible for any damages. Use at your own risk. More info at the bottom.

## Screenshots

Screenshots of every variant are in the [pics](pics/) folder.

## Prerequisites

> [!NOTE]
> Requirements
>
> - [mib-std2-toolbox](https://github.com/olli991/mib-std2-pq-zr-toolbox) installed
> - mib2std-toolbox files on your sd card
> - SD-card formatted to FAT32, cluster size 4096 bytes
> - D-Link DUB-100 Ver.D1 Ethernet to USB adapter (optional)
>   - or a compatible converted adapter
> - Laptop (optional)

> [!NOTE]
> In this guide, I'm going to refer the sd-card as /mp001 or slot-2, the script works on both slots.
>
> Path of sd slot-1: /media/mp000/
>
> Path of sd slot-2: /media/mp001/
>
> Also I'm going to use the EU Full Patch here as the example

> [!TIP]
> After reboot, there is a very high chance that your sd-card slots swap numbers, if you have 2 sd-cards inserted.
>
> So sd-slot2 becomes /mp000 and vice-versa.

## Variants

- Only place 1 jar file in folder
- Every variant except lite also needs the native `cpvc-cover-bridge` helper
- The jars for each variant are in `required-files/region-[eu|cn]/variant[x]/`.
- Region is auto-detected from the HU

### Region EU (4 variants)

| Variant | Song info | Cover | Progress | Shuffle | Navi |
|---|:-:|:-:|:-:|:-:|:-:|
|**full**|✅|✅|✅|✅|✅|
| **no-navi** | ✅ | ✅ | ✅ | ✅ |❌|
|**no-navi-no-prog**| ✅ |✅|❌| ✅ |❌|
| **lite** |✅|❌ |❌|❌| ❌ |

> **Playback Progress**
>
> shows accurate playback progression under the song
>
> replaces the fourth row which is normally empty and used to display longer song titles
>
> Looks like this (`m:ss / -m:ss`)
>
> Supports up to (`9:59:59 / -9:59:59`)

> **Navigation**
>
> Within Map Apps in CarPlay, it enables a hidden button, which is used to import addresses or destinations directly into MIB2 internal Navi
>
> There are some pictures in the [pics](pics/) folder
>
> ~~True turn-by-turn route guidance is still not researched enough on MST2 :(~~
>
> True turn-by-turn implementation is done and is coming soon
>
> also not yet available on cn-region as it causes some problems, like carplay blackscreen.

### Region CN (3 variants)

CN has no navi variant (yet).

| Variant | Song info | Cover | Progress | Shuffle |
|---|:-:|:-:|:-:|:-:|
| **full** |✅|✅| ✅ |✅|
|**no-prog**| ✅ |✅|❌|✅|
| **lite** | ✅ |❌|❌ | ❌  |

## How to enable

2 ways to enable the patch:

1. **`method1-GEM/`** - custom menu entry in the MIB-STD2-Toolbox. Enable / disable / status / debug via Green Engineering Menu, no laptop required.
2. **`method2-console/`** - run the scripts from terminal via telnet, laptop + ethernet adapter required.

copy the files from your chosen `required-files/region-[eu|cn]/variant[x]/` folder into sd/custom/java

### Method 1: via custom carplay-vc GEM menu entry in mib2std-toolbox

> [!NOTE]
> sd-card should look like this

```text
sd/
└── custom/
    ├── greenmenu/
    │   ├── carplay-vc.esd
    │   └── scripts/
    │       ├── cpvc_activate.sh
    │       ├── cpvc_activate_debug.sh
    │       ├── cpvc_common.sh
    │       ├── cpvc_copy_logs.sh
    │       ├── cpvc_deactivate.sh
    │       └── cpvc_status.sh
    └── java/
        ├── mst2-carplay-vc-full-eu-v1.jar
        └── cpvc-cover-bridge
```

- Open GEM
- browse to /mibstd2-toolbox/customization/greenmenu
- `Copy custom GEM screens and scripts to unit`
- Re-open GEM when done
- browse to /mibstd2-toolbox/customization/carplay-vc
- `Enable Patch`
- Reboot if everything ok
- Enjoy :)
- Feel free to share your results in this [discussion](https://github.com/xPaiiN/mst2-carplay-vc/discussions/1) <3

> [!IMPORTANT]
> If there are any errors with patching, do NOT reboot.
>
> Check first, if any files have been modified.
>
> if the runHMI.sh is damaged, and it couldnt be restored, you need to restore it manually with the backups.
>
> Try to run the disable script to restore the backups and avoid bootlooping the HMI

### Method 2: connect via telnet and activate the patch on the console

You need to have (permanent) access to terminal enabled in mibstd2-toolbox to be able to connect and run the scripts.

> [!NOTE]
> sd-card should look like this

```text
sd/
└── mst2-carplay-vc-patch-v1/
    ├── mst2-carplay-vc-full-eu-v1.jar
    ├── cpvc-cover-bridge
    ├── cpvc_common.sh
    ├── enable_patch.sh
    ├── disable_patch.sh
    └── collect_logs.sh
```

Set following IP address config on your laptop:

```text
own: 192.168.1.10
subnet: 255.255.255.0
gateway: 192.168.1.4 (optional)
```

Connect to MST2 terminal via Putty (or any other tool):

```text
connection type: telnet
ip: 192.168.1.4
port: 23
```

Login:

```text
user: root
password: root
```

Run the script:

```bash
sh /media/mp001/mst2-carplay-vc-patch-v1/enable_patch.sh
# reboot to apply
```

Enjoy :)

Feel free to share your results in this [discussion](https://github.com/xPaiiN/mst2-carplay-vc/discussions/1) <3

> [!IMPORTANT]
> If there are any errors with patching, do NOT reboot.
>
> Check first, if any files have been modified.
>
> if the runHMI.sh is damaged, and it couldnt be restored, you need to restore it manually with the backups.
>
> Try to run the disable script to restore the backups and avoid bootlooping the HMI



## How to disable

### Method 1: via the carplay-vc GEM menu entry

- Open GEM
- browse to /mibstd2-toolbox/customization/carplay-vc
- `Disable + Cleanup`
- Reboot if everything ok
- Back to stock, all backups restored



### Method 2: connect via telnet and run the disable script

```bash
sh /media/mp001/mst2-carplay-vc-patch-v1/disable_patch.sh
# reboot to apply
```



## How to debug

**Method 1 GEM:**

You need to enable the --debug version of the patch to allow logging.

- Open GEM
- browse to /mibstd2-toolbox/customization/carplay-vc
- `Enable Patch with --debug`
- Reboot if everything ok
- Then reproduce the errors and log them
- browse to /mibstd2-toolbox/customization/carplay-vc
- `Copy logs to SD (if --debug enabled)`
- Create an issue here and maybe I'll look into it if I find some spare time.

**Method 2: via console access**

disable patch, then enable again with the `--debug` parameter:

```bash
sh /media/mp001/mst2-carplay-vc-patch-v1/disable_patch.sh

# reboot

sh /media/mp001/mst2-carplay-vc-patch-v1/enable_patch.sh --debug
# reboot to apply
```

then run this after reproducing errors to copy logs to sd /media/mp001/logs_<date+time>/:

```bash
sh /media/mp001/mst2-carplay-vc-patch-v1/collect_logs.sh
```



## Successfully tested with

Main Unit:

- MIB2Std TechniSat Preh ZR
- Part Number: 5F0 035 877 (only patchable with solderless method)
- Region: EU
- SW Version: 0516
- HW Version: H50x
- Seat Leon 5F

VC:

- Software Version: 1701
- Part Number 5F0 920 790 A

Devices:

- iPhone 14 Pro iOS 18.7.2 (main)
- iPhone 15 Pro iOS 26.5
- iPhone 11 Pro iOS 16.6.1

Music sources:

- Apple Music (main)
- Spotify (I need more testers to debug and verify as I barely use Spotify anymore, I think its buggy as it first shows a blank spotify cover, then the real cover)
- Youtube Sideloaded
- Local mp3 files (files apps)

Wireless adapter:

- Carlinkit 3.0

---

Shoutout to [@omonob](https://github.com/omonob) for helping out and testing cn-region <3

Tests were done with:

**MST2**
- MIB2Std TechniSat Preh PQ
- Part Number 5C0 035 682 G
- Region: CN
- Software Version: 0478
- Hardware Version: H32

**MHI2.5**
- MIB2High Harman
- Part Number 3EB 035 048
- Region: CN
- Software Version: 0841
- Hardware Version: H55
- Note: MHI2 Version is in the works. all MHI2 testing was done by him, not me.
---




## To-Do

- [ ] Cover art even more reliable. Currently this version can fail sometimes.
- [x] Full telephone integration
  - [x] recents list
    - [x] date and time stamps
    - [x] callable from vc with buttons
  - ~~[ ] favorite list~~ not possible to show on vc
  - [x] call duration (not possible natively, only as text behind name) 
  - [x] mute
    - [ ] fix mute state on vc persists through hang up to next call
  - [x] hold
  - ~~[ ] caller picture~~ not possible to get from iphone
  - [x] removed call bypass implementation from main patch as it really serves no purpose right now
- [x] Full turn by turn route guidance
  - [x] true turn by turn implementation
  - [x] eta
  - [x] lane guidance
  - [x] sidestreets
  - [ ] highway fixes
  - [ ] localization
- [ ] MHI2 support (almost done)
- [x] Display shuffle icon if on (no interest in repeat icon as I don't use this ever and it also collides with shuffle on Seat Leon HU)
- [x] Cover art faster than 7 sec frequency (15 sec max?)
- [x] make songtext almost instant with 0,5sec delay
- [x] fix that songtext gets empty after 120 seconds but covers stay, while calling
- [x] fix that songtext doesnt get cleared sometimes
- [x] fix that scripts sometimes freeze
- [x] fix that scripts wont accept jars
- [x] fix that scripts chose the wrong region
- [x] fix that covers get stuck indefinetly rarely
- [ ] maybe ambiente light in VC in the color of the cover art, like a dynamic album color background





## Sources

- [olli991/mib-std2-pq-zr-toolbox](https://github.com/olli991/mib-std2-pq-zr-toolbox)
  - scripts to dump mst2 filesystem
  - java patch architecture and script implementation
  - custom GEM menu entry
  - console access
  - +much more
- [adi961/mib2-android-auto-vc](https://github.com/adi961/mib2-android-auto-vc)
  - bridge architecture
- [luka-dev/mib2q-carplay-rgi](https://github.com/luka-dev/mib2q-carplay-rgi)
  - java build toolchain `zulu8.78.0.19-ca-jdk8.0.412` with `jclFoundation11.jar` and `j9jce.jar` bootclasspath stubs
  - build pattern: `javac -source 1.4 -target 1.4 -bootclasspath jclFoundation11.jar`
- [luka-dev/jxe2jar](https://github.com/luka-dev/jxe2jar)
  - for converting MIBHMI.jxe to .jar
- [Mr-MIBonk/M.I.B._More-Incredible-Bash (discussion #93)](https://github.com/Mr-MIBonk/M.I.B._More-Incredible-Bash/discussions/93)
  - for confirming the NavActiveIgnore patch approach
- [grajen3/mib2-lsd-patching](https://github.com/grajen3/mib2-lsd-patching)
  - MHI2 research
- [drive2.ru write-up](https://www.drive2.ru/l/647383926293153054/)
  - for MIBHMI.jxe patch approach through tsd.mibstd2.hmi.ifs



## Stuff

I originally made this patch because it annoyed me that there was no stock implementation. I also wanted to see how far I could push AI without writing a single LOC myself.

It turned into a bit more iterations than I expected. Somewhere around 300 patch builds and about 120 trips to the car to test them, since theres no safely way to work on these units and every test means copying it over, rebooting and checking

Bootlooped the HU twice along the way, first one was because I was messing with cpu.conf, and second one was because of emtpy runHMI.sh on reboot

Also I wrote this readme by myself tho, no AI used
