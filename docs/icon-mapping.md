# Mapping icon theo chức năng

ZIP tham chiếu có tên phần lớn bị rút gọn và XML ở dạng nhị phân. Không suy đoán tệp YouTube cụ thể hoặc sao chép path; bảng sau chỉ ghi icon Vibetube hiện dùng và trạng thái mapping.

| Khu vực | Registry hiện dùng | Trạng thái |
|---|---|---|
| Header / Search | VIcon.Search, VIcon.Settings | Giữ icon hiện tại |
| Navigation | VIcon.Home/HomeFilled, VIcon.Shorts/ShortsFilled | Giữ icon; Shorts tùy chỉnh cần kiểm toán nguồn riêng |
| Feed / Channel / Subscriptions / Library | VIcon và các drawable theo từng thao tác | Giữ icon hiện tại, chưa xác minh mapping từ ZIP |
| Player / Miniplayer | StateIcons.Play, Fullscreen, Subtitles | Các biến thể và nhãn dùng chung, giữ touch target |
| Chapters / Mix | VIcon và drawable hiện có | Giữ icon hiện tại |
| Comments | StateIcons.Like/Dislike, VIcon.Reply/HeartSmile | Giữ callback và trạng thái hiện có |
| Settings / Dialog / Bottom sheet | VIcon.Settings, Close và các drawable hiện có | Giữ icon hiện tại |

StateIconSpec quản lý drawable thường/được chọn, nhãn accessibility và kích thước; tint và touch target được cung cấp ở chỗ dùng. VIcon vẫn là registry cho các icon khác. Chưa có căn cứ gộp toàn bộ thành một registry hoặc tuyên bố chuẩn hóa/kiểm thử hết mọi theme từ ZIP này.
