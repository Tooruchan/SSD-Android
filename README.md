# ShadowsocksD for Android

## Basic Project - 基础项目

[shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)

ShadowsocksD会跟随Shadowsocks更新代码。
```
  *  ShadowsocksD中的广告收入依然归Shadowsocks项目作者所有，ShadowsocksD不会分取此收入
```

## Shared Wiki - 共享Wiki

[ShadowsocksD项目共享Wiki](https://github.com/TheCGDF/SSD-Windows/wiki)

## Environment - 环境

Android 5.0+ (minSdkAPIVersion:21;targetSdkAPIVersion:28)

~~非\[华为/锤子\]设备~~

~~不建议使用\[小米（MIUI）\]系统~~

~~无\[360/2345/百度/腾讯\]全家桶~~

已经移除了这些检测，但是我们仍然不建议在这些设备和装有这些软件的手机上使用。

Modified by [Tooruchan](https://t.me/TooruchanNews)，要恢复这些检测，请去掉 `mobile\src\main\java\com\github\shadowsocks\MainActivity.kt` 中 355-361 行以及 251-261 行中的所有注释。
## Development - 开发

\[Windows/Linux/MacOS\]

需使用`git clone --recurse-submodules <repo>`或 `git submodule update --init --recursive`对仓库进行初始化

## Open Source References - 开源引用
```
shadowsocks-android (GPLv3) https://github.com/shadowsocks/shadowsocks-android
redsocks (APL 2.0)          https://github.com/shadowsocks/redsocks
mbed TLS (APL 2.0)          https://github.com/ARMmbed/mbedtls
libevent (BSD)              https://github.com/shadowsocks/libevent
tun2socks (BSD)             https://github.com/shadowsocks/badvpn
pcre (BSD)                  https://android.googlesource.com/platform/external/pcre/+/master/dist2
libancillary (BSD)          https://github.com/shadowsocks/libancillary
shadowsocks-libev (GPLv3)   https://github.com/shadowsocks/shadowsocks-libev
libev (GPLv2)               https://github.com/shadowsocks/libev
libsodium (ISC)             https://github.com/jedisct1/libsodium
```
