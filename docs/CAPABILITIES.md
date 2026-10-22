# Ma trận chức năng

Mức kiểm chứng:

- **L** — chạy trực tiếp với YouTube (chế độ khách) từ mã Kotlin của ứng dụng
- **T** — kiểm thử tự động (fixture từ phản hồi thật, cấu trúc dựng từ renderer thật, MockWebServer, Robolectric); chưa với tài khoản thật
- **U** — đã triển khai, **chưa kiểm chứng trực tiếp** (cần tài khoản và/hoặc thiết bị)
- **X** — chưa hỗ trợ

| Chức năng | Mức | Ghi chú |
| --- | --- | --- |
| Đăng nhập bằng WebView Google chính thức | U | Chưa chạy trên thiết bị; Google có thể chặn WebView (ứng dụng báo lỗi thật) |
| Xác minh phiên qua `account_menu` | T, L | Cookie giả bị từ chối trực tiếp (`LoggedOut`); phiên thật chưa thử |
| Cookie jar theo tài khoản, cách ly | T | `SessionPrimitivesTest`, `InnerTubeProviderTest` |
| Mã hóa phiên Keystore | T, U | AES-GCM kiểm thử với khóa bộ nhớ; `AndroidKeyStore` chưa chạy trên thiết bị |
| Đăng xuất xóa đúng phiên | T | `FeedControllerTest.logout…` (vault, cache, hủy request, về chế độ khách) |
| Home cá nhân hóa | T, U | Parser, continuation, refresh, đổi tài khoản, phản hồi cũ, hết hạn phiên có test; phản hồi có tài khoản chưa thử |
| Chế độ khách: feed thịnh hành | T | Khách xem video nhiều lượt xem nhất trong tuần (tìm kiếm công khai); chưa chạy thử trực tiếp trong môi trường này |
| Cache theo tài khoản/locale/region | T | Room + test cách ly, TTL 24h |
| Tìm kiếm video/kênh/playlist, bộ lọc, phân trang | L | |
| Gợi ý tìm kiếm, lịch sử tìm kiếm cục bộ | L, T | |
| Trang xem: thông tin, liên quan | L | |
| Bình luận: đọc, phân trang, trả lời (đọc) | L | |
| Bình luận: sắp xếp | T | Token lấy từ `sortFilterSubMenuRenderer` |
| Bình luận: đăng, trả lời | U | Tham số tạo lấy từ phản hồi có tài khoản; chỉ báo thành công khi nhận `createCommentAction` |
| Bình luận: sửa, xóa, like | X | |
| Playlist công khai, kênh, video kênh | L | |
| Playlist của tôi, Đã thích, Xem sau, lịch sử YouTube (đọc) | U | Dùng chung parser đã chạy live; phản hồi riêng tư chưa thử |
| Kênh đã đăng ký, feed subscriptions | U | |
| Subscribe/unsubscribe | T, U | Optimistic + rollback có test; máy chủ thật chưa thử |
| Like/unlike, đối chiếu trạng thái | T, U | |
| Lưu/bỏ lưu video vào playlist, Xem sau | T, U | |
| Tạo/đổi tên/xóa playlist | T, U | |
| Đổi quyền riêng tư playlist sau khi tạo, sắp xếp lại | X | |
| Lịch sử cục bộ, vị trí xem dở, chế độ riêng tư | T | Phạm vi ghi rõ "trên thiết bị này" |
| Ghi hoạt động xem về YouTube | X | Cố ý không triển khai |
| Xóa lịch sử tài khoản YouTube | X | Chỉ xóa lịch sử cục bộ và ghi rõ phạm vi |
| Phân giải luồng, chọn chất lượng | L | Tải được byte thật (HTTP 206) cho 720p + AAC; kết quả phụ thuộc YouTube |
| Phát bằng ExoPlayer, tua, tốc độ, chất lượng, phụ đề, luồng âm thanh | U | Điều khiển UI có test; phát thật trên thiết bị chưa chạy |
| Mini-player, toàn màn hình, PiP, MediaSession, nền, thông báo | U | |
| Live (HLS) | U | |
| Luồng `signatureCipher`, DRM | X | Báo lỗi đúng lý do |
| Quản lý tài khoản, chuyển tài khoản, kênh Brand | T, U | Chuyển kênh dựa `pageId` chưa kiểm chứng |
| Giao diện sáng/tối/AMOLED, Dynamic Color | T | Ảnh chụp Robolectric; Dynamic Color chỉ chạy được trên thiết bị API 31+ |
| Google Sans Flex ROND=100 | T | Test trục + ảnh chụp (dấu tiếng Việt, fallback) |
| Tiếng Việt/Anh, plurals, vùng nội dung | T | Test đối chiếu khóa/placeholder |
| Không quảng cáo | T | Parser bỏ qua đơn vị quảng cáo; không có SDK quảng cáo |
| Component Gallery, Diagnostics | T | Chỉ trong bản debug |
