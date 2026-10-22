# Dependency và giấy phép

Phiên bản cố định trong `gradle/libs.versions.toml`, Gradle 9.6.0, AGP 9.4.1 (Kotlin tích hợp, plugin Kotlin 2.4.21), KSP 2.3.12.

| Thành phần | Phiên bản | Giấy phép |
| --- | --- | --- |
| Compose BOM | 2026.09.00 | Apache-2.0 |
| `androidx.compose.material3` (+ navigation-suite, window-size) | 1.5.0-beta02 / 1.5.0-beta02 | Apache-2.0 |
| Material3 Adaptive (core) | 1.3.0 | Apache-2.0 |
| Navigation Compose | 2.10.2 | Apache-2.0 |
| Lifecycle, Activity, Core KTX, AppCompat | 2.11.0, 1.13.0, 1.19.1, 1.7.1 | Apache-2.0 |
| Hilt / Dagger, hilt-navigation-compose | 2.60.1, 1.4.0 | Apache-2.0 |
| Room | 2.8.5 | Apache-2.0 |
| DataStore Preferences | 1.3.0 | Apache-2.0 |
| Media3 (exoplayer, hls, okhttp datasource, session, ui) | 1.11.1 | Apache-2.0 |
| OkHttp | 5.5.0 | Apache-2.0 |
| Coil 3 (compose, network-okhttp) | 3.6.3 | Apache-2.0 |
| kotlinx.serialization, kotlinx.coroutines | 1.12.0, 1.11.0 | Apache-2.0 |
| Test: JUnit 4.13.2, Robolectric 4.17, MockWebServer3 5.5.0, coroutines-test, Compose UI test | — | EPL-1.0 / MIT / Apache-2.0 |
| Google Sans Flex (font biến thiên) | từ google/fonts, `ofl/googlesansflex` | SIL OFL 1.1 — [licenses/OFL-GoogleSansFlex.txt](../licenses/OFL-GoogleSansFlex.txt) |
| Material Symbols Rounded (vector icon, chỉ những icon dùng) | từ google/material-design-icons | Apache-2.0 — [licenses/Apache-2.0-MaterialSymbols.txt](../licenses/Apache-2.0-MaterialSymbols.txt) |

Ghi chú:

- `material3` dùng bản beta vì stable 1.4.0 trong BOM giữ `MotionScheme`, `MaterialExpressiveTheme`, các biến thể `Shapes`/`Typography` Expressive ở mức `internal`; yêu cầu sản phẩm cần MotionScheme/Expressive. Các thư viện còn lại là bản ổn định.
- Hilt 2.60.1 yêu cầu AGP 9+, vì vậy dự án dùng AGP 9.4.1 (yêu cầu Gradle ≥ 9.6.0).
- `compileSdk 37` do `core-ktx 1.19.1` và OkHttp 5.5.0 yêu cầu; `targetSdk` giữ 36.
- Không có SDK quảng cáo, analytics hay crash-reporting.
