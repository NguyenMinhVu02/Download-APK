# Download APK (Aurora Web)

Web tải APK **bản mới nhất (hoặc bản cũ)** từ Google Play, **không cần tài khoản Google**.
Dùng thư viện [gplayapi](https://gitlab.com/AuroraOSS/gplayapi) của Aurora Store qua lane Anonymous.

## Tính năng

- Tìm app theo **tên**, hoặc dán **link Google Play** hay **package name**
- Trang chi tiết app: đánh giá, lượt tải, dung lượng, phiên bản, mô tả
- Tải **bản mới nhất** hoặc **bản cũ theo versionCode** (nếu Google Play còn giữ bản đó)
- App chỉ có 1 file thì trả `.apk` cài thẳng. App có split thì trả `.apks` (cài bằng SAI). File luôn **giữ chữ ký gốc**
- Tự gộp split của nhiều loại máy (arm64 / armeabi-v7a, xhdpi / xxhdpi…) để tránh lỗi `INSTALL_FAILED_MISSING_SPLIT`
- Kiểm tra SHA-256 / SHA-1 của từng file
- Thanh tiến trình khi tải, mã QR để tải về điện thoại cùng Wi-Fi
- Lịch sử file đã tải (tải lại / QR / xoá)
- Tải qua **proxy nước ngoài** cho app bị chặn ở Việt Nam, có chế độ "Tự động" thử lần lượt từng vùng
- Hiện IP và quốc gia mà Google nhìn thấy, nút làm mới phiên sau khi đổi VPN
- Giao diện font Nunito, có chế độ sáng/tối, hiển thị tốt trên điện thoại

## Chạy

Cần **JDK 21**. Gradle wrapper đi kèm repo, không cần cài Gradle.

```bash
./gradlew run                      # http://127.0.0.1:8080
HOST=0.0.0.0 ./gradlew run         # mở cho điện thoại trong cùng mạng LAN
```

Lần đầu chạy, Gradle tự tải `com.auroraoss:gplayapi:4.0.0` (dạng AAR) từ Maven Central rồi tách
`classes.jar` để dùng trên JVM.

Biến môi trường:

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `HOST` | `127.0.0.1` | Địa chỉ lắng nghe |
| `PORT` | `8080` | Cổng |
| `OUT_DIR` | `out` | Thư mục lưu file đã tải |
| `PROXIES_FILE` | `proxies.txt` | File danh sách proxy |
| `PROXIES` | | Proxy dạng `us=socks5://h:p,jp=http://u:p@h:p` |
| `EXTRA_DEVICES` | `sm_a13_5g,rm_7,sm_s25u` | Các máy giả lập dùng để lấy thêm split |

## Tải app bị chặn ở Việt Nam

Google Play xác định quốc gia theo **IP**. Có hai cách:

1. **Bật VPN trên cả máy chạy server**, chọn vùng "Máy này", rồi bấm nút làm mới phiên.
2. **Dùng proxy riêng**: chép `proxies.example.txt` thành `proxies.txt` rồi thêm proxy, ví dụ VPS ở Mỹ:
   ```bash
   ssh -N -D 1080 root@IP-VPS          # mở đường hầm SOCKS
   # proxies.txt:  us  Mỹ (VPS)  socks5://127.0.0.1:1080
   ```

Tor **không** dùng được: Google không nhận IP Tor là IP của nước đó.

## Cài lên điện thoại

- `.apk`: mở file để cài.
- `.apks`: cài bằng app [SAI](https://play.google.com/store/apps/details?id=com.aefyr.sai),
  hoặc giải nén rồi chạy `adb install-multiple *.apk`.

## API

| Method | Đường dẫn | Mô tả |
|---|---|---|
| GET | `/api/search?q=&region=` | Tìm app theo tên |
| GET | `/api/details?input=&region=` | Thông tin app (input là link hoặc package) |
| POST | `/api/download` | Body `{input, region, versionCode?}` |
| GET | `/api/progress?pkg=` | Tiến trình tải |
| GET | `/api/files` · DELETE `/api/files/{name}` | Lịch sử, xoá file |
| GET | `/files/{name}` | Tải file |
| GET | `/api/regions` · `/api/ip` · POST `/api/reset` | Vùng, IP hiện tại, làm mới phiên |

## Giới hạn

- Chỉ tải app **miễn phí**. Không tải file OBB và Play Asset Delivery.
- App có **PairIP** hoặc license check sẽ không chạy khi cài ngoài Play. Tool không vượt qua cơ chế này.
- Bản cũ chỉ tải được khi biết versionCode và Google Play còn giữ bản đó.
- Lane Anonymous có thể bị giới hạn tốc độ (rate limit).
- Dùng API không chính thức của Google Play có thể vi phạm điều khoản của Google. Chỉ nên dùng
  nội bộ, cho mục đích học tập và nghiên cứu.

## Giấy phép

Dùng thư viện gplayapi (GPL-3.0-or-later), nên project này cũng theo **GPL-3.0-or-later**.
