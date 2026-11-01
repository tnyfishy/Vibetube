# Vibetube

YouTube client cho Android với giao diện Material 3, không quảng cáo, phát nền, Shorts và tùy chỉnh trải nghiệm xem.

- Package: `vibe.tube`
- Phiên bản: `1.0`
- Android 8.0 trở lên
- Ambient trong fullscreen, bật mặc định và có thể tắt trong tùy chọn phát.

## Build

Cần JDK 21 và Android SDK 37. Cấu hình khóa ký bằng `keystore.properties.sample`; không đưa keystore hoặc mật khẩu lên Git.

```sh
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

APK nằm trong `app/build/outputs/apk/release/`. GitHub Actions hỗ trợ build thủ công và phát hành từ tag `v*` khi đã cấu hình signing secrets.

Giấy phép của các thành phần bên thứ ba: [licenses](licenses/) và [thông báo bản quyền](docs/third-party/).
