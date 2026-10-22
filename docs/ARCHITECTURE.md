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
