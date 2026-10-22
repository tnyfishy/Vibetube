# Provider / extractor

Vibetube tự triển khai một client InnerTube (`InnerTubeClient` + `InnerTubeProvider`) thay vì dùng thư viện bên thứ ba. NewPipeExtractor (GPL-3.0) không được dùng để tránh ràng buộc giấy phép; không phụ thuộc backend nào ngoài YouTube.

## Nghiên cứu Pure Tuber

APK/mã trích xuất của Pure Tuber **không được cung cấp** trong môi trường này nên không có phần mã nào của Pure Tuber được đọc, và không có kết luận nào dựa trên nó. Các dấu vết nêu trong yêu cầu (CookieManager, `LOGIN_INFO`, `SAPISID`, `SAPISIDHASH`, `/youtubei/v1/browse`, `FEwhat_to_watch`, `FEhistory`, `VLLL`, `VLWL`) chỉ được dùng làm gợi ý; từng thứ đã được kiểm tra lại độc lập:

| Gợi ý | Kiểm tra |
| --- | --- |
| `/youtubei/v1/browse`, `FEwhat_to_watch` | Phản hồi khách thật có `richGridRenderer` rỗng + `feedNudgeRenderer` và `logged_in=0` (fixture `home_guest.json`). Cấu trúc có tài khoản được dựng từ các renderer thật (`home_logged_in_structure.json`), chưa lấy được phản hồi có tài khoản |
| `SAPISIDHASH` | Công thức `SHA1(ts + " " + SAPISID + " " + origin)` có test; request mang header đúng (`InnerTubeProviderTest`) |
| `VLLL`, `VLWL`, `FEhistory` | Dùng qua `browse`; parser dùng chung với playlist công khai (chạy live ở chế độ khách với playlist công khai); phản hồi cần đăng nhập chưa kiểm chứng |
| `LOGIN_INFO` | Không dùng riêng; trạng thái đăng nhập do YouTube xác nhận qua `account_menu` và `logged_in`/`loggedOut` trong `responseContext` |

## Endpoint

| Chức năng | Endpoint | Ghi chú |
| --- | --- | --- |
| Xác minh phiên | `account/account_menu`, `account/accounts_list` | Cần `activeAccountHeaderRenderer` |
| Home | `browse` `FEwhat_to_watch` (+`continuation`) | Cần đăng nhập |
| Đăng ký | `browse` `FEsubscriptions`, `FEchannels` | Cần đăng nhập |
| Thư viện | `FEplaylist_aggregation`, `VLLL`, `VLWL`, `FEhistory`, `VL<playlistId>` | Playlist công khai chạy ở chế độ khách |
| Tìm kiếm | `search` (+`params` loại/sắp xếp, `continuation`) | Live, khách |
| Gợi ý | `suggestqueries-clients6.youtube.com/complete/search` | Live, khách |
| Kênh | `browse` `<channelId>` (+ tab Videos) | Live, khách |
| Xem video | `next` (+`continuation` cho liên quan/bình luận/trả lời) | Live, khách |
| Phát | `player` (profile `ANDROID_VR`, `IOS`; WEB có xác thực làm phương án cuối) | Xem bên dưới |
| Like | `like/like`, `like/removelike` | Cần đăng nhập |
| Đăng ký kênh | `subscription/subscribe`, `subscription/unsubscribe` | Cần đăng nhập |
| Playlist | `playlist/get_add_to_playlist`, `browse/edit_playlist`, `playlist/create`, `playlist/delete` | Cần đăng nhập |
| Bình luận | `comment/create_comment`, `comment/create_comment_reply` | Cần đăng nhập |

Mỗi thao tác ghi chỉ được coi là thành công khi máy chủ trả về xác nhận hợp lệ (không có `error`; `STATUS_SUCCEEDED` cho `edit_playlist`; `createCommentAction` cho bình luận); sau thao tác like, trạng thái được đọc lại từ `next` và đối chiếu.

## Parser

- `FeedParser` duyệt cây JSON theo thứ tự tài liệu, nhận các renderer item (`videoRenderer`, `lockupViewModel`, `channelRenderer`, `playlistRenderer`, `shortsLockupViewModel`…), khử trùng lặp, và lấy continuation từ `continuationItemRenderer`/`continuationItemViewModel`/`nextContinuationData`.
- Đơn vị quảng cáo (`adSlotRenderer`, `promotedSparklesWebRenderer`, `inFeedAdLayoutRenderer`, … và mọi khóa khớp mẫu quảng cáo) bị bỏ qua cùng toàn bộ cây con của chúng; số lượng bỏ qua được ghi vào chẩn đoán debug (số thật, không phải số liệu tiếp thị).
- `CommentsParser` ghép `commentViewModel` với `commentEntityPayload` trong `frameworkUpdates`, hỗ trợ trả lời, sắp xếp, trạng thái tắt bình luận.
- `WatchParser`: tiêu đề, kênh, trạng thái like/subscribe từ `entityBatchUpdate`, liên quan, hàng chờ playlist, token bình luận.

## Phát video

`StreamResolver` thử lần lượt các profile client không cookie; phân loại `playabilityStatus` thành `PlaybackFailure` (BotCheck, LoginRequired, AgeRestricted, RegionBlocked, Private, Removed, Unavailable, DrmUnsupported, CipherUnsupported, NoCompatibleStream, Expired). `MediaPlanner` chọn: HLS cho live, adaptive (video + audio ghép bằng `MergingMediaSource`) theo trần chất lượng Wi-Fi/di động, nếu không có thì luồng muxed. URL luôn là URL luồng đã phân giải (không bao giờ là URL trang YouTube); ExoPlayer nhận URL 403/410 sẽ phân giải lại đúng một lần.

Giới hạn đã biết:

- Format chỉ có `signatureCipher` bị bỏ qua và báo `CipherUnsupported`; Vibetube không có bộ giải mã chữ ký/`n`.
- YouTube có thể trả "xác nhận không phải bot" tùy mạng/thời điểm (đã gặp trong lúc phát triển với một số client; lần chạy live gần nhất trả luồng hợp lệ). Ứng dụng báo đúng lý do và không cố vượt qua.
- Không có đơn vị quảng cáo nào được dựng vào luồng; các client dùng ở đây trả luồng nội dung. Chưa thể khẳng định mọi video đều không kèm quảng cáo trong nguồn phát.
- Phụ đề lấy từ `captionTracks` (định dạng vtt); có thể cần token bổ sung tùy video — chưa kiểm chứng.
