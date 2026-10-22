# Báo cáo kiểm thử

Lệnh: `./gradlew :app:testDebugUnitTest` — **109 test, 0 lỗi, 5 bỏ qua** (5 test live chỉ chạy khi đặt `VIBETUBE_LIVE=1`). Ngoài ra `assembleDebug`, `assembleRelease` (R8) và `lintDebug` chạy thành công; lint không còn lỗi, chỉ còn cảnh báo ghi nhận bên dưới.

| Lớp test | Số test | Nội dung |
| --- | --- | --- |
| `youtube.ParserTest` | 17 | Parser trên phản hồi thật đã cắt gọn: tìm kiếm video/playlist/kênh, playlist (continuation dạng view-model), trang xem, bình luận + trả lời, menu tài khoản khách, home khách rỗng; cấu trúc Home có tài khoản (bỏ quảng cáo, continuation); danh sách tài khoản/kênh Brand; gợi ý |
| `youtube.StreamResolverTest` | 8 | Phân loại bot-check/tuổi/vùng/riêng tư/đã gỡ/DRM/cipher; kế hoạch media (trần chất lượng, muxed, HLS, luồng âm thanh, tránh WebM) |
| `account.SessionPrimitivesTest` | 12 | SAPISIDHASH, cookie jar (domain, secure, hạn, xoay vòng, xóa), cách ly hai jar, mã hóa/giả mạo, vault mã hóa/hỏng, `toString` không lộ cookie |
| `data.FeedControllerTest` | 12 | Khách không gọi mạng; trang đầu → load more dùng đúng continuation; refresh gọi trang đầu mới; đổi tài khoản khi đang tải; phản hồi cũ; hết hạn phiên (không retry); cache theo tài khoản; offline hiển thị cache; lỗi load more có thể thử lại; đăng xuất xóa vault/cache/hủy request; đăng nhập với cookie không hợp lệ không lưu gì; cách ly cookie khi chuyển tài khoản |
| `data.ActionsTest` | 6 | Optimistic + rollback, hủy coroutine, khách bị chặn không gọi mạng, hết hạn phiên khi đang thao tác, dùng đúng thông tin của tài khoản đang chọn |
| `data.PersistenceTest` | 5 | Room cache (đa hình, cách ly tài khoản, TTL), lịch sử phát/tiếp tục/hoàn tất theo phạm vi, lịch sử tìm kiếm, DataStore thiết lập, locale nội dung tách ngôn ngữ/vùng |
| `network.InnerTubeProviderTest` | 19 | MockWebServer: header xác thực/cookie/visitor/PageId/`onBehalfOfUser`, nội dung request (browseId, continuation từ phản hồi trước), retry có giới hạn, 4xx không retry, 401 → hết hạn, logged-out → hết hạn, verify (từ chối menu khách dù có cookie), thao tác ghi chỉ thành công khi có xác nhận |
| `network.LiveProbeTest` | 5 (opt-in) | Chạy trực tiếp với YouTube ở chế độ khách (xem dưới) |
| `ui.TypographyTest` | 3 | Trục font nằm trong phạm vi font, ROND=100 ở mọi weight, không trùng trục |
| `ui.ThemeAndNavTest` | 5 | AMOLED #000000 + bề mặt phân tầng, độ tương phản chữ ở 3 theme, quyết định tối/sáng, route điều hướng type-safe |
| `ui.StringsParityTest` | 3 | Khóa/plurals/placeholder en = vi; mọi `R.string` được dùng đều tồn tại |
| `ui.FeedUiTest` | 6 | Thẻ video từ dữ liệu thật: bấm phát đúng video, font scale 2×, lời nhắc đăng nhập (không feed thay thế), hết hạn phiên, banner cache offline; ảnh chụp |
| `ui.PlayerSurfaceUiTest` | 5 | Điều khiển player có nhãn truy cập và gọi đúng hành động; lớp phủ lỗi nêu đúng lý do và nút phù hợp; nhãn LIVE; định dạng thời gian |
| `ui.ScreenshotTest` | 3 | Component Gallery ở sáng/tối/AMOLED (ảnh chụp trong `app/build/screenshots`) |

## Kiểm chứng trực tiếp (chế độ khách, từ mã Kotlin của ứng dụng)

Kết quả lần chạy gần nhất:

- Tìm kiếm "lofi hip hop": 20 video, continuation có, trang thứ hai có dữ liệu
- Trang xem: tiêu đề, kênh, 19 video liên quan, token bình luận → 20 bình luận + continuation
- Playlist công khai: 93 video + tiêu đề + continuation; kênh: tên, handle, số người đăng ký, avatar, 30 video + continuation
- Gợi ý tìm kiếm trả về danh sách
- `verify` với cookie `SAPISID` giả → `LoggedOut` (không bị đánh lừa bởi cookie)
- `player`: client `ANDROID_VR` trả luồng; kế hoạch 720p adaptive; tải 2048 byte đầu của video (itag 136) và audio (itag 140) → HTTP 206. Trong lúc phát triển, một số lần gọi từ cùng môi trường bị YouTube trả kiểm tra "xác nhận không phải bot" — kết quả này phụ thuộc thời điểm/mạng

## Chưa kiểm chứng trực tiếp

Không có tài khoản thử và không có thiết bị/emulator (không có KVM) nên **chưa** kiểm chứng:

- Đăng nhập WebView và việc Google có chấp nhận hay không
- Home cá nhân hóa, hai tài khoản có lịch sử khác nhau, feed Subscriptions, thư viện/playlist riêng tư
- Like, Save/Xem sau, tạo/sửa playlist, đăng bình luận có được YouTube chấp nhận và phản ánh trên tài khoản
- Đọc lịch sử YouTube; (ghi lịch sử không được triển khai)
- Đăng xuất xóa đúng phiên trên thiết bị thật (logic đã có test; Keystore và WebView chưa chạy)
- Phát bằng ExoPlayer, MediaSession/thông báo, nền, PiP, xoay màn hình, Dynamic Color
- Bản release R8: build thành công nhưng chưa chạy thử (các quy tắc giữ lớp serialization cho cache feed chưa được thực thi)

## Cảnh báo lint còn lại (không chặn)

`LocalContextResourcesRead`/`LocalContextGetResourceValueCall` (đọc tài nguyên raw và chuỗi trong hiệu ứng snackbar), `NonObservableLocale` (ngôn ngữ cho URL đăng nhập), `UseKtx`, `UnusedAttribute` (`localeConfig` chỉ từ API 33), `PictureInPictureIssue` (gợi ý `setSourceRectHint`), `OldTargetApi` (targetSdk 36 < 37), `ExportedService` (bắt buộc cho MediaSessionService), cảnh báo phiên bản dependency mới hơn.
