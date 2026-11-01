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
| Gợi ý tìm kiếm, lịch sử tìm kiếm cục bộ | L, T | Gợi ý gửi không kèm tài khoản |
| Đồng bộ lịch sử tìm kiếm với tài khoản YouTube | — | Đã gỡ bỏ. Lịch sử tìm kiếm chỉ nằm trên thiết bị (theo từng tài khoản/khách); tìm kiếm và gợi ý luôn gửi không kèm tài khoản, nên YouTube không ghi từ khóa vào lịch sử tài khoản |
| Mix: chạm là phát | L, T | Player mở ngay từ thumbnail của thẻ, video đầu (từ `watchEndpoint` hoặc id `RD…`) được phân giải ngay; hàng chờ tải song song. Mix không ghi video đầu (vd. My Mix) chờ trang đầu ngay trong Player |
| Hàng chờ Mix / playlist trong Player | T | Không còn trang "Danh sách kết hợp" cho Mix (id `RD…` từ mọi nơi đều phát ngay). Thẻ Mix ngay dưới video (tên, vị trí, bài tiếp theo) mở thành panel nổi bằng container transform (chạm hoặc vuốt lên, kéo theo ngón tay, thả theo vận tốc, Back/Đóng/chạm ngoài để thu lại); panel có Trộn, Lặp, danh sách đầy đủ với bài đang phát được tô sáng và menu từng bài. Chạm bài phát ngay mà không tải lại trang tiếp tục của hàng chờ. "Xem tiếp" là đề xuất riêng của video, bỏ video trùng với Mix khi còn đủ đề xuất khác. Chưa chạy thử trên thiết bị thật |
| Trang xem: thông tin, liên quan | L | |
| Chapter | L, T | Ưu tiên chapter YouTube gắn trên thanh phát (`markersMap`: của người tạo hoặc tự động), sau đó bảng `macroMarkersListRenderer`, cuối cùng mới đọc từ mô tả (timestamp trước hoặc sau tiêu đề, bỏ qua timestamp lẻ, tối thiểu 3 mục, mỗi mục ≥ 10 giây, không vượt độ dài video). Phản hồi thật có chapter tự động chưa được thử trực tiếp |
| Âm thanh | T | Chọn luồng trung thực nhất: không dùng biến thể DRC khi có bản gốc, rồi theo hạng chất lượng của YouTube, bitrate, số kênh, sample rate; Opus và AAC so như nhau. Không bật EQ/bass/loudness mặc định. Không thể vượt chất lượng nguồn |
| Chế độ ambient | — | Đã gỡ bỏ (UI, cài đặt, xử lý khung hình); giá trị cũ được xóa khỏi DataStore ở lần lưu cài đặt kế tiếp |
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
| Thẻ "Tiếp tục xem" | T, U | Tiến độ ghi định kỳ (5 giây), khi tạm dừng, tua và khi ứng dụng xuống nền; thẻ chỉ hiện khi tiến trình mới khởi động, giữ nguyên khi chuyển màn hình/xuống nền/xoay màn hình; chỉ xóa khi video kết thúc, đóng thẻ hoặc xóa khỏi lịch sử. Chuỗi kill → mở lại chưa chạy trên thiết bị |
| Ghi hoạt động xem về YouTube | X | Cố ý không triển khai |
| Xóa lịch sử tài khoản YouTube | X | Chỉ xóa lịch sử cục bộ và ghi rõ phạm vi |
| Phân giải luồng, chọn chất lượng | L | Tải được byte thật (HTTP 206) cho 720p + AAC; kết quả phụ thuộc YouTube |
| Phát bằng ExoPlayer, tua, tốc độ, chất lượng, phụ đề, luồng âm thanh | U | Điều khiển UI có test; phát thật trên thiết bị chưa chạy |
| Mini-player, toàn màn hình, PiP, MediaSession, nền, thông báo | U, T | Giữa hai video (lấy luồng kế tiếp) MediaSession vẫn báo đang tải, giữ wake lock + Wi-Fi lock có giới hạn thời gian; luồng của video kế tiếp được lấy trước 30 giây trước khi hết video. Chưa thử phát nền dài trên máy thật |
| Thông báo dẫn đúng đích | T | Video (cả thời điểm, playlist/Mix; bình luận: mở bảng bình luận và cuộn tới khi đã tải), kênh, playlist; dùng chung cho thông báo hệ thống và danh sách trong app; giữ yêu cầu tới khi khởi động xong. Kênh chỉ có @handle (không có UC…) chưa mở được: báo lỗi |
| Kích thước hiển thị | T | Cài đặt › Kích thước hiển thị: kích thước giao diện 85–125% và cỡ chữ 85–130% (nhân với cỡ chữ hệ thống), xem trước Trang chủ trực tiếp khi kéo, áp dụng khi đóng bảng, có Đặt lại. Không đổi DPI/cỡ chữ của Android |
| Thanh Mix | T | Khi Mix đang phát và Player thu nhỏ: thanh Mix ngay trên thanh điều hướng, mini player nằm trên thanh Mix, cả hai dịch chuyển theo chiều cao đo thật; chạm mở Player ở bảng hàng chờ, X thoát Mix (video vẫn phát) |
| Live (HLS) | U | |
| Luồng `signatureCipher`, DRM | X | Báo lỗi đúng lý do |
| Quản lý tài khoản, chuyển tài khoản, kênh Brand | T, U | Chuyển kênh dựa `pageId` chưa kiểm chứng |
| Giao diện sáng/tối/AMOLED, Dynamic Color | T | Ảnh chụp Robolectric; Dynamic Color chỉ chạy được trên thiết bị API 31+ |
| Google Sans Flex ROND=100 | T | Test trục + ảnh chụp (dấu tiếng Việt, fallback) |
| Tiếng Việt/Anh, plurals, vùng nội dung | T | Test đối chiếu khóa/placeholder |
| Không quảng cáo | T | Parser bỏ qua đơn vị quảng cáo; không có SDK quảng cáo |
| Component Gallery, Diagnostics | T | Chỉ trong bản debug |
