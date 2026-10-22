# Vibetube

Ứng dụng Android xem YouTube với giao diện Material 3 riêng, không quảng cáo, đăng nhập bằng phiên cookie của trang Google/YouTube chính thức (không dùng OAuth hay YouTube Data API v3).

- Application ID / namespace: `vibe.tube`
- Kotlin, Jetpack Compose, Material 3 (Expressive qua `material3 1.5.0-beta02`), Hilt, OkHttp, Room, DataStore, Media3, Coil
- minSdk 26, compileSdk 37, targetSdk 36
- Font Google Sans Flex variable (ROND=100) đóng gói trong ứng dụng

> Trạng thái trung thực: phần đọc dữ liệu công khai (tìm kiếm, video, bình luận, playlist, kênh, gợi ý) và phân giải luồng phát đã được chạy thử trực tiếp với YouTube ở chế độ khách. Mọi chức năng cần tài khoản (đăng nhập WebView, Home cá nhân hóa, thư viện, thao tác ghi) đã được triển khai và kiểm thử bằng test, nhưng **chưa được chạy với tài khoản thật trên thiết bị**. Xem [docs/CAPABILITIES.md](docs/CAPABILITIES.md).

## Build

Yêu cầu: JDK 21, Android SDK (platform `android-37.0`, build-tools 36.0.0).

```
export ANDROID_HOME=/đường/dẫn/android-sdk
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Chạy test:

```
./gradlew :app:testDebugUnitTest
```

Các test dùng Robolectric tải `android-all` từ Maven Central. Nếu mạng chặn, đặt biến môi trường `ROBOLECTRIC_DEPENDENCY_REPO_URL` trỏ tới mirror Maven.

Kiểm chứng trực tiếp (tùy chọn, cần Internet, không cần tài khoản):

```
VIBETUBE_LIVE=1 ./gradlew :app:testDebugUnitTest --tests 'vibe.tube.network.LiveProbeTest' -i
```

Bản release (mẫu cấu hình ký, không kèm khóa):

```
cp keystore.properties.sample keystore.properties
./gradlew :app:assembleRelease
```

`keystore.properties` và `*.jks` đã nằm trong `.gitignore`.

## Sử dụng

1. Mở ứng dụng. Chưa đăng nhập, Trang chủ hiển thị rõ chế độ khách; ứng dụng không thay bằng feed thịnh hành hay dữ liệu mẫu.
2. Tab Tài khoản (hoặc nút Đăng nhập) mở trang đăng nhập Google chính thức trong WebView. Bạn tự nhập thông tin và xác minh. Vibetube chỉ giữ phiên YouTube sau khi YouTube xác nhận bằng một yêu cầu `account_menu`.
3. Nếu Google chặn đăng nhập trong WebView, ứng dụng hiển thị lỗi thật và không giả lập thành công. Xem [docs/SESSION.md](docs/SESSION.md).
4. Tìm kiếm ở thanh ứng dụng; chạm video để phát; thu nhỏ thành mini-player; Hình trong hình khi rời ứng dụng.

## Tài liệu

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): kiến trúc và phân lớp
- [docs/SESSION.md](docs/SESSION.md): đăng nhập, cookie, bảo vệ phiên, đa tài khoản
- [docs/PROVIDER.md](docs/PROVIDER.md): provider/extractor InnerTube, parser, player
- [docs/CAPABILITIES.md](docs/CAPABILITIES.md): ma trận chức năng và mức kiểm chứng
- [docs/M3_COMPONENTS.md](docs/M3_COMPONENTS.md): rà soát component Material 3
- [docs/TEST_REPORT.md](docs/TEST_REPORT.md): báo cáo kiểm thử
- [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md): dependency và giấy phép
- [licenses/](licenses): giấy phép font và icon

## Giới hạn quan trọng

- Chưa chạy trên thiết bị/emulator thật (môi trường build không có KVM). Giao diện được kiểm bằng Robolectric native graphics và ảnh chụp.
- Đăng nhập WebView, Home cá nhân hóa, subscriptions, thư viện, thao tác ghi: chưa kiểm chứng trực tiếp với tài khoản.
- Vibetube không ghi hoạt động xem về lịch sử YouTube và không xóa lịch sử YouTube.
- Luồng bị bảo vệ bằng chữ ký (signatureCipher), DRM: chưa hỗ trợ.
- Việc YouTube có đưa ra kiểm tra "xác nhận không phải bot" hay không phụ thuộc mạng/thời điểm; ứng dụng báo đúng lý do và không cố vượt qua.
