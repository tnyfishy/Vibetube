# Phiên đăng nhập

## Phân biệt khái niệm

| Khái niệm | Biểu diễn trong mã |
| --- | --- |
| Tài khoản Google | `AccountSession` (id cục bộ ngẫu nhiên) |
| Kênh YouTube / Brand Account | `AccountIdentity` (khóa = `pageId`, hoặc `primary`) |
| Cookie phiên web | `StoredCookie` trong `SessionCookieJar` của đúng tài khoản |
| Trạng thái do YouTube xác nhận | `AuthState.Verified` chỉ sau khi `account/account_menu` trả về `activeAccountHeaderRenderer` và không báo logged-out |

Tên/avatar chỉ hiển thị sau xác minh; không bao giờ coi là bằng chứng đăng nhập.

## Luồng

1. `LoginScreen` mở `accounts.google.com/ServiceLogin?service=youtube…` trong WebView. Chỉ cho phép điều hướng https tới miền Google/YouTube. User-agent mặc định của WebView, không giả mạo.
2. Khi WebView tới miền youtube.com và có cookie `SAPISID` hoặc `__Secure-3PAPISID`, `LoginViewModel` đọc `CookieManager.getCookie("https://www.youtube.com")`.
3. `SessionManager.loginWithCookies` dựng phiên nháp và gọi `YouTubeProvider.verify` (`account/account_menu`, sau đó `account/accounts_list` để lấy kênh). Chỉ khi `Verified` mới lưu; `LoggedOut` hoặc lỗi thì không lưu gì.
4. Cookie WebView bị xóa ngay sau đó (`WebSessionCleaner`). WebView chỉ là môi trường đăng nhập tạm; mọi request về sau đi qua OkHttp với cookie jar riêng.

Cookie lấy từ `CookieManager.getCookie` không kèm thuộc tính (expiry, path, secure). Vibetube ghi chúng với miền `youtube.com`, path `/`, secure, không hết hạn; mọi `Set-Cookie` sau đó từ máy chủ được `SessionCookieJar` lưu đầy đủ domain/path/expiry/secure và cập nhật (xoay vòng cookie, xóa cookie). Hạn dùng thực sự do YouTube quyết định và được phát hiện qua phản hồi logged-out.

## Xác thực request

`Authorization: SAPISIDHASH ts_sha1(ts SAPISID origin)` kèm `SAPISID1PHASH`/`SAPISID3PHASH` khi có cookie tương ứng; `X-Origin`, `Origin`, `X-Goog-AuthUser: 0`, `X-Goog-Visitor-Id`; kênh Brand dùng `X-Goog-PageId` và `context.user.onBehalfOfUser`. Phản hồi logged-out hoặc HTTP 401/UNAUTHENTICATED cho request có xác thực → `AppError.SessionExpired` → `AuthState.Expired`, không retry.

## Lưu trữ và bảo vệ

- Mỗi tài khoản một tệp trong `noBackupFilesDir/sessions/`, mã hóa AES-256-GCM với khóa trong Android Keystore (`KeystoreSecretCipher`). Ghi nguyên tử; tệp hỏng/không giải mã được bị loại bỏ.
- `allowBackup=false` và quy tắc `dataExtractionRules`/`fullBackupContent` loại trừ mọi miền (cloud backup và device transfer).
- Không lưu mật khẩu. `AccountSession.toString()` không chứa cookie. Không có log cookie/token/Authorization trong mã; `Redact` ẩn danh định danh trong chẩn đoán debug.
- Không gửi phiên tới backend riêng, analytics hay bên thứ ba: host duy nhất được dùng cho phiên là `www.youtube.com`; jar từ chối cookie ngoài `youtube.com`/`google.com`.
- Không có phiên thật trong repo, fixture, CI hay APK.

## Đăng xuất

`SessionManager.logout(id)`: hủy request thuộc tài khoản (tag `AccountTag`), xóa và hủy jar trong bộ nhớ, xóa tệp mã hóa, xóa cache feed + lịch sử cục bộ của tài khoản, xóa cookie/storage WebView của ứng dụng. Nếu là tài khoản đang dùng, ứng dụng về chế độ khách và **không** tự chuyển sang tài khoản khác. Không đụng tới trình duyệt/ứng dụng khác.

## Nhiều tài khoản

Mỗi tài khoản có `SessionCookieJar` riêng; `CookieManager` singleton chỉ dùng tạm khi đăng nhập (xóa trước và sau). Test chứng minh cách ly: hai jar không chia sẻ cookie, và hai request liên tiếp của hai tài khoản chỉ mang cookie của chính chúng (`InnerTubeProviderTest.twoAccountsNeverShareCookiesOnTheWire`). Chuyển tài khoản hủy request cũ và loại bỏ phản hồi muộn (`FeedControllerTest`). Chuyển kênh Brand dựa trên `pageId`; chưa kiểm chứng với tài khoản có Brand Account.

## Khi Google chặn WebView

Ứng dụng phát hiện (HTTP 400/403 từ `accounts.google.com`, URL `signin/rejected`/`disallowed_useragent`, hoặc cụm từ cảnh báo trên trang) và hiển thị lỗi thật. Không có cơ chế vượt qua, không đổi user-agent để lách, không vượt CAPTCHA/2FA, không đọc cookie của trình duyệt khác, không giả lập thành công. Chrome Custom Tabs không chuyển cookie sang WebView/OkHttp. Nếu Google chặn trên thiết bị của bạn, phần đăng nhập bị chặn và chỉ chế độ khách hoạt động; đây là điểm cần kiểm chứng trên thiết bị thật trước khi dùng cho tài khoản.
