# Release 1.0

Package `vibe.tube`, versionName `1.0`, versionCode `10`, tên ứng dụng `Vibetube`.

APK: `app/build/outputs/apk/release/Vibetube.apk`. Release sử dụng R8, resource shrinking và baseline profile.

Sao lưu riêng `.release-signing/Vibetube-release.jks` và `keystore.properties`. Không đăng các tệp này lên repository. Giữ nguyên khóa cho các bản cập nhật tiếp theo.

GitHub Actions cần secrets `VIBE_KEYSTORE_BASE64`, `VIBE_STORE_PASSWORD`, `VIBE_KEY_PASSWORD`; alias là `vibetube`. Các kiểm tra phải thành công trước khi phát hành.
