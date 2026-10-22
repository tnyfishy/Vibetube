# Rà soát component Material 3

Nguồn: danh mục tại m3.material.io/components. Thư viện: `androidx.compose.material3` 1.5.0-beta02 (stable 1.4.0 giữ các API Expressive ở mức `internal`; xem DEPENDENCIES).

| Component | Vị trí sử dụng | API Compose | Trạng thái |
| --- | --- | --- | --- |
| App bars (top) | Mọi màn hình | `TopAppBar`, `CenterAlignedTopAppBar`, `TopAppBarDefaults.enterAlwaysScrollBehavior` | Dùng |
| Badges | Gallery | `Badge`, `BadgedBox` | Chỉ gallery; không có luồng cần huy hiệu số |
| Bottom app bar | — | — | Không áp dụng: điều hướng chính dùng navigation suite |
| Buttons (common) | Toàn ứng dụng | `Button`, `FilledTonalButton`, `OutlinedButton`, `TextButton`, `ElevatedButton` (gallery) | Dùng |
| Icon buttons | App bar, player, thẻ | `IconButton` + `TooltipBox` (`TooltipIconButton`), `FilledIconButton`, `FilledTonalIconButton` (gallery) | Dùng |
| FAB / Extended FAB | Danh sách phát | `ExtendedFloatingActionButton`; `FloatingActionButton` (gallery) | Dùng |
| Button groups / Segmented / Split buttons | Gallery | `SegmentedButton` | Chỉ gallery; cài đặt dùng hộp thoại lựa chọn |
| Cards | Thẻ tài khoản; gallery | `Card`, `ElevatedCard`, `OutlinedCard` | Dùng (thẻ video là bề mặt tùy biến theo shape token) |
| Carousel | — | — | Không áp dụng: không có luồng cuộn ngang |
| Checkbox | Thêm vào playlist | `Checkbox` | Dùng |
| Chips | Lọc tìm kiếm, tùy chọn phát, hành động xem | `FilterChip`, `AssistChip`; `InputChip`, `SuggestionChip` (gallery) | Dùng |
| Date / Time pickers | — | — | Không áp dụng |
| Dialogs | Xác nhận, tạo/đổi tên playlist, lựa chọn cài đặt | `AlertDialog` | Dùng |
| Divider | Cài đặt, tài khoản | `HorizontalDivider` | Dùng |
| Lists | Thư viện, cài đặt, tài khoản | `ListItem` | Dùng |
| Loading indicator | Tải trang đầu, load more, player | `LoadingIndicator` | Dùng |
| Menus | Menu video, sắp xếp, đổi tên/xóa | `DropdownMenu`, `DropdownMenuItem` | Dùng |
| Navigation bar / rail / drawer | Điều hướng chính thích ứng | `NavigationSuiteScaffold` (bar, rail, drawer theo kích thước) | Dùng |
| Progress indicators | Tiến độ xem dở, mini-player, WebView | `LinearProgressIndicator` (`CircularProgressIndicator` ở gallery) | Dùng |
| Radio button | Kênh Brand, lựa chọn | `RadioButton` | Dùng |
| Search | Màn hình tìm kiếm | `SearchBarDefaults.InputField` | Dùng |
| Sheets (bottom) | Lưu vào playlist, tùy chọn phát | `ModalBottomSheet` | Dùng |
| Sheets (side) | — | — | Không áp dụng: màn hình lớn dùng bố cục hai cột trong player |
| Sliders | Thanh tua | `Slider` | Dùng |
| Snackbar | Phản hồi thao tác | `SnackbarHost`, `SnackbarHostState` | Dùng |
| Switch | Cài đặt | `Switch` | Dùng |
| Tabs | Đăng ký, player | `PrimaryTabRow`, `Tab` | Dùng |
| Text fields | Playlist, bình luận, tìm kiếm | `OutlinedTextField` | Dùng |
| Toolbars (floating/docked) | — | — | Không áp dụng: điều khiển player là lớp phủ tùy biến |
| Tooltips | Nút biểu tượng | `TooltipBox`, `PlainTooltip` | Dùng |

Hệ thống kèm theo: `MaterialExpressiveTheme` + `MotionScheme.expressive()` (spring cho mini-player, kéo thu nhỏ, chuyển trang), màu `ColorScheme` đầy đủ vai trò (kể cả `*Fixed*`, `surfaceContainer*`), `Shapes` kể cả `largeIncreased`/`extraLargeIncreased`, `Typography` kể cả biến thể Emphasized, tôn trọng `ANIMATOR_DURATION_SCALE = 0` (`LocalReducedMotion`).
