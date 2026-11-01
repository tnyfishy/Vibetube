# Kiến trúc

Một module Gradle `:app`, tách lớp bằng package dưới `vibe.tube`. Luồng dữ liệu một chiều: provider → repository/controller → `StateFlow` → Compose. Không gọi mạng hay parse nặng trong composable.

| Lớp | Package | Vai trò |
| --- | --- | --- |
| Design system | `ui.theme`, `ui.components`, `ui.Icons` | Màu, chữ, shape, motion, spacing, icon, thẻ video, danh sách feed |
| Account/session | `account` | Cookie jar theo tài khoản, mã hóa Keystore, SAPISIDHASH, `SessionManager` |
| Network | `network` | `InnerTubeClient` (OkHttp), profile client, retry/backoff, hủy theo tài khoản |
| Provider/extractor | `youtube`, `youtube.parser`, `youtube.player` | Endpoint InnerTube, parser JSON, phân giải luồng phát, lập kế hoạch media |
| Domain/repository | `youtube.model`, `data.repo` | Model, `FeedController`, `FeedRepository`, `AccountActions`, lịch sử cục bộ |
| Dữ liệu cục bộ | `data.db`, `data.settings` | Room (cache feed, lịch sử tìm kiếm/phát), DataStore (thiết lập) |
| Tính năng | `feature.*` | Home, đăng ký, thư viện, playlist, kênh, tìm kiếm, tài khoản, cài đặt, watch |
| Player | `player`, `feature.watch` | ExoPlayer dùng chung, `MediaSessionService`, mini-player, PiP |
| Debug | `src/debug` | Component Gallery và Diagnostics, không có trong bản release |

## Feed theo tài khoản

`FeedController` là đơn vị trạng thái cho mọi danh sách (Home, subscriptions, playlist, tìm kiếm…).

- Khóa ngữ cảnh = `accountId/identityKey` + locale/region. Khi khóa đổi, `collectLatest` hủy mọi request đang chạy và xóa trạng thái.
- Mỗi lần tải ghi nhớ `(khóa, generation)`; kết quả cũ (đổi tài khoản, refresh chồng) bị loại bỏ trước khi ghi vào state.
- Trang đầu: hiển thị cache theo (tài khoản, locale, region, loại feed) nếu có, sau đó gọi mạng. Cache hết hạn sau 24 giờ, xóa theo tài khoản khi đăng xuất.
- Continuation chỉ lấy từ phản hồi của trang trước thuộc cùng ngữ cảnh; trang cache không có continuation.
- Phiên hết hạn: hiển thị lỗi đăng nhập, ẩn dữ liệu riêng tư, không thử lại vô hạn, không chuyển sang feed phổ biến.
- Khách: feed cần đăng nhập không gọi mạng, hiển thị yêu cầu đăng nhập.

## Player

`PlayerEngine` giữ một ExoPlayer duy nhất; `PlaybackService` bọc nó bằng `MediaSession`; UI (khung video mở rộng, mini-player) chỉ gắn `PlayerView` vào cùng player nên không bao giờ có hai player. `PlayerController` quản lý hàng chờ, phân giải luồng, lưu vị trí xem dở, thử lại khi URL hết hạn (một lần), tránh codec WebM khi lỗi giải mã.

### Vòng đời một yêu cầu phát

`Idle → Resolving → Preparing → Buffering → Ready` (đang phát = `isPlaying` trên `Ready`); `Recovering` thay cho các pha đang chờ khi cùng video được tải lại sau lỗi; `Error` luôn có lý do và nút Thử lại; `Ended`. Mỗi lần gọi `load()` nhận một số thứ tự (`requestSeq`): kết quả của yêu cầu cũ (đã bị thay hoặc đóng player) bị bỏ, nên video trước không thể ghi đè video sau.

- Phân giải: mỗi client InnerTube có ngân sách thời gian riêng (`StreamResolver.PROFILE_TIMEOUT_MS`, 7 s); client chậm, trả dữ liệu hỏng hoặc lỗi mạng bị bỏ qua để thử client kế tiếp. Toàn bộ phân giải có giới hạn 25 s.
- Watchdog khởi động sau `prepare()`: chưa tới khung hình đầu sau 15 s → tải lại qua client khác (tối đa 2 lần).
- Lỗi trình phát: URL hết hạn (403 khi đã quá `expiresInSeconds`, hoặc bấm phát sau thời gian tạm dừng dài) → lấy URL mới cùng client; 403/404/410, 429, 5xx → client khác; lỗi giải mã → bỏ WebM; live tụt khỏi cửa sổ → về điểm live. Mọi lần tự phục hồi bị giới hạn 5 lần mỗi video.
- `PlaybackFailure.Network`: lỗi khi thiết bị mất mạng được tự thử lại một lần khi có mạng đã xác thực trở lại (không chạy nền nếu tắt phát nền). Mã HTTP từ YouTube không bị báo là mất mạng.
- Chạm lại video đang lỗi, hoặc nút phát trên mini-player khi lỗi, đều tải lại thay vì chỉ mở lại màn hình lỗi.
- Vị trí xem chỉ được lưu cho video thực sự đang nằm trong ExoPlayer (`currentMediaItem.mediaId`); điểm tiếp tục vượt quá độ dài video bị bỏ qua.
- Nhật ký: thẻ `Playback`, một dòng mỗi bước dạng `#12 <videoId> resolved +840ms client=ANDROID_VR`; không ghi URL luồng, cookie hay dữ liệu tài khoản.
