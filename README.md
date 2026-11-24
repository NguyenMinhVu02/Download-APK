# Download APK (Aurora Web)

Web tải APK từ **Google Play** về máy, **không cần tài khoản Google**. Tải được bản mới nhất,
hoặc bản cũ theo versionCode.

Dùng thư viện [gplayapi](https://gitlab.com/AuroraOSS/gplayapi) của Aurora Store, qua lane
Anonymous (ẩn danh).

> Chỉ dùng cho học tập và nghiên cứu, dùng nội bộ. Gọi API không chính thức của Google Play có
> thể vi phạm điều khoản của Google.

---

## Mục lục

1. [Tính năng](#1-tính-năng)
2. [Web hoạt động thế nào](#2-web-hoạt-động-thế-nào)
3. [Cài đặt cho người mới](#3-cài-đặt-cho-người-mới)
4. [Cách dùng](#4-cách-dùng)
5. [Cài APK lên điện thoại](#5-cài-apk-lên-điện-thoại)
6. [Tải app bị chặn ở Việt Nam](#6-tải-app-bị-chặn-ở-việt-nam)
7. [Cấu hình](#7-cấu-hình)
8. [Đưa lên VPS (chạy 24/7)](#8-đưa-lên-vps-chạy-247)
9. [Cấu trúc code](#9-cấu-trúc-code)
10. [API](#10-api)
11. [Lỗi thường gặp](#11-lỗi-thường-gặp)
12. [Giới hạn](#12-giới-hạn)

---

## 1. Tính năng

| Nhóm | Tính năng |
|---|---|
| **Tìm app** | Gõ tên app, dán link Google Play, hoặc dán package name (`org.videolan.vlc`) |
| **Chi tiết app** | Icon, nhà phát triển, đánh giá, lượt tải, dung lượng, phiên bản, mô tả, thể loại, ngày cập nhật |
| **Tải** | Bản mới nhất, hoặc **bản cũ theo versionCode** (nếu Google Play còn giữ bản đó) |
| **File** | App chỉ có 1 file → `.apk` cài thẳng. App có split → `.apks`. Luôn **giữ chữ ký gốc** |
| **Đủ split** | Hỏi Google bằng nhiều máy giả lập (arm64 / armeabi-v7a, xhdpi / xxhdpi / xxxhdpi) rồi gộp split, để tránh lỗi `INSTALL_FAILED_MISSING_SPLIT` |
| **An toàn** | Kiểm tra SHA-256 / SHA-1 từng file. Không ký lại, không sửa APK |
| **Tiện ích** | Thanh tiến trình (%, MB, tốc độ), mã QR tải về điện thoại, lịch sử file (tải lại / QR / xoá), sao chép package |
| **Vùng / quốc gia** | Tải qua proxy nước ngoài, chế độ **Tự động** thử lần lượt từng vùng, hiện IP + quốc gia, nút làm mới phiên |
| **Giao diện** | Font Nunito (đủ dấu tiếng Việt), chế độ sáng / tối, hiển thị tốt trên điện thoại |

---

## 2. Web hoạt động thế nào

### Các thành phần

```
┌─────────────────────────── Máy chạy server ───────────────────────────┐
│                                                                       │
│   Backend = chương trình Kotlin (src/main/kotlin/Main.kt)             │
│     ├─ Ktor + Netty  → WEB SERVER, mở cổng 8080                       │
│     ├─ gplayapi      → nói chuyện với Google Play (ẩn danh)           │
│     └─ OkHttp        → tải file APK                                   │
│                                                                       │
│   Java 21 (JVM)      → "động cơ" chạy chương trình ở trên             │
│                                                                       │
│   out/               → nơi lưu các file APK đã tải                    │
└───────────────────────────────────────────────────────────────────────┘
        ▲                                             │
        │ http://<máy>:8080                           ▼
  Trình duyệt / điện thoại                      Google Play
  (frontend = index.html)
```

| Thành phần | Là gì | Khi nào cần |
|---|---|---|
| **Java 21** | Môi trường chạy (JVM). Kotlin được biên dịch ra cùng loại mã với Java | Luôn cần |
| **Ktor + Netty** | Web server, nằm **sẵn trong code**. Không cần cài thêm Apache, Nginx hay Tomcat | Đã có sẵn |
| **Gradle** (`./gradlew`) | **Công cụ build**: tải thư viện, biên dịch code. **Không phải server** | Chỉ khi build hoặc sửa code |
| **index.html** | Giao diện (HTML + CSS + JS), được đóng gói vào chương trình | Đã có sẵn |

Ví dụ cho dễ hiểu: **Gradle là thợ xây**, **chương trình là cái quán**, **Java là điện nước**.
Lệnh `./gradlew run` nghĩa là "xây xong thì mở cửa quán luôn". Còn lệnh
`./gradlew installDist` chỉ xây quán; sau đó mở quán bằng `bin/aurora-web`, không cần thợ nữa.

### Một lượt tải diễn ra như sau

1. Trình duyệt gửi link hoặc package lên server.
2. Server hỏi Google Play thông tin app, giả làm điện thoại **Pixel 9a**, bằng phiên ẩn danh.
3. Server hỏi danh sách file (base + split). Sau đó hỏi thêm bằng 3 máy giả lập khác để lấy
   split cho các loại chip và màn hình khác. Chỉ gộp split khi `base.apk` trùng khớp tuyệt đối.
4. Tải từng file, kiểm tra SHA.
5. Đóng gói thành `.apk` hoặc `.apks`, lưu vào `out/`.
6. Trình duyệt nhận nút tải và mã QR.

Google xác định quốc gia theo **IP của máy chạy server**, không theo IP của điện thoại.

---

## 3. Cài đặt cho người mới

### Bước 1: Cài Java 21 (chỉ làm một lần)

| Hệ điều hành | Cách cài |
|---|---|
| **macOS** | `brew install openjdk@21` (cần [Homebrew](https://brew.sh)), hoặc tải bản Temurin 21 từ https://adoptium.net |
| **Windows** | Tải **Temurin 21 (.msi)** từ https://adoptium.net. Khi cài, tick *Set JAVA_HOME* |
| **Ubuntu / Debian** | `sudo apt update && sudo apt install -y openjdk-21-jdk` |

Kiểm tra:
```bash
java -version      # phải hiện 21.x
```
**Không cần cài Gradle**, vì repo đã có sẵn Gradle wrapper (`gradlew`).

### Bước 2: Tải code

```bash
git clone https://github.com/NguyenMinhVu02/Download-APK.git
cd Download-APK
```
Không có Git thì vào trang repo, bấm **Code → Download ZIP**, rồi giải nén.

### Bước 3: Chạy

**Cách nhanh** (build và chạy luôn, hợp khi đang thử hoặc sửa code):
```bash
./gradlew run                 # macOS / Linux
gradlew.bat run               # Windows
```

**Cách chuẩn** (build một lần, sau đó chạy không cần Gradle, nhẹ và nhanh hơn):
```bash
./gradlew installDist                         # build ra build/install/aurora-web/
./build/install/aurora-web/bin/aurora-web     # macOS / Linux
build\install\aurora-web\bin\aurora-web.bat   # Windows
```

Lần build đầu mất vài phút để tải thư viện, trong đó có gplayapi từ Maven Central. Khi thấy dòng
`Responding at http://127.0.0.1:8080` là chạy xong. Mở **http://127.0.0.1:8080** trong trình duyệt.

### Bước 4: Mở trên điện thoại (cùng Wi-Fi)

Mặc định server chỉ nhận kết nối từ chính máy đó. Muốn điện thoại vào được thì chạy với `HOST=0.0.0.0`:
```bash
HOST=0.0.0.0 ./gradlew run                    # macOS / Linux
set HOST=0.0.0.0 && gradlew.bat run           # Windows (cmd)
$env:HOST="0.0.0.0"; .\gradlew.bat run        # Windows (PowerShell)
```
Trên web, bấm biểu tượng 📱 ở góc trên để lấy mã QR, rồi dùng điện thoại quét.
Lần đầu chạy, nếu hệ điều hành hỏi có cho Java nhận kết nối mạng không, chọn **Cho phép**.

### Sau khi sửa code

Mỗi lần sửa `Main.kt` hoặc `index.html` thì phải **build lại** mới thấy thay đổi. Tắt server
(`Ctrl + C`) rồi chạy lại `./gradlew run`, hoặc chạy `./gradlew installDist` rồi bật lại.

---

## 4. Cách dùng

1. Gõ **tên app** (ví dụ `VLC`) rồi bấm **Tìm**, rồi chọn app trong danh sách.
   Hoặc dán **link Google Play** hay **package name** rồi bấm **Xem app**.
2. Xem thông tin app, bấm **Tải APK** và chờ thanh tiến trình chạy xong.
3. Bấm nút xanh để tải file, hoặc **quét QR** bằng điện thoại.
4. Các file đã tải nằm ở tab **Đã tải**, có thể tải lại, lấy QR hoặc xoá.

**Tải bản cũ:** trong trang chi tiết app, mở mục **"Tải phiên bản khác (bản cũ)"**, nhập
**versionCode** rồi bấm tải. Google không cho xem danh sách bản cũ, nên cần biết trước versionCode
(có thể tra trên APKMirror). Chỉ tải được những bản Google Play còn giữ.

**Chọn vùng:** ô chọn cạnh nút Tìm.
- **Tự động**: thử IP của máy trước, nếu bị chặn thì lần lượt thử từng proxy.
- **Máy này**: dùng IP hiện tại của máy chạy server.
- Các vùng khác: lấy từ file `proxies.txt`.

**Thanh trên cùng:**

| Nút | Ý nghĩa |
|---|---|
| `VN · 1.2.3.4` | IP và quốc gia mà Google đang nhìn thấy |
| 🔄 | Làm mới phiên. Bấm sau khi bật hoặc đổi VPN |
| 📱 | QR mở web trên điện thoại |
| 🌙 | Đổi sáng / tối |

---

## 5. Cài APK lên điện thoại

| File | Cách cài |
|---|---|
| `.apk` | Mở file là cài. Cho phép *Cài từ nguồn không xác định* nếu được hỏi |
| `.apks` | Cài app [**SAI** (Split APKs Installer)](https://play.google.com/store/apps/details?id=com.aefyr.sai) một lần, rồi mở SAI → **Install APKs** → chọn file. Để SAI **tự chọn split** |
| Qua máy tính | Giải nén `.apks` rồi chạy `adb install-multiple *.apk` |

- Máy đã có app từ Google Play vẫn **cài đè được**, vì chữ ký gốc khớp.
- Web không gộp `.apks` thành một `.apk`. Gộp như vậy bắt buộc phải ký lại bằng khoá khác, khi đó
  không cài đè hay cập nhật được nữa, và nhiều app sẽ không chạy.

---

## 6. Tải app bị chặn ở Việt Nam

Google Play xác định quốc gia theo **IP của máy chạy server**. Có 3 cách:

### Cách 1: VPN trên máy chạy server (dễ nhất)
1. Bật VPN sang nước cần tải, ví dụ Proton VPN Free hoặc Windscribe Free (bản Free chọn được nước).
2. Trên web bấm **🔄 Làm mới phiên**. Dòng IP phải đổi từ `VN` sang nước mới.
3. Chọn vùng **Máy này** hoặc **Tự động**, rồi tải.

Khi bật VPN cho cả máy, điện thoại có thể không vào được web qua Wi-Fi nữa. Lúc đó hãy dùng trình
duyệt trên chính máy chạy server.

### Cách 2: Proxy hoặc VPS riêng (ổn định nhất)
Chép file mẫu rồi thêm proxy:
```bash
cp proxies.example.txt proxies.txt
```
Mỗi dòng trong `proxies.txt` có dạng `mã  Tên hiển thị  proxy-url`:
```
us   Mỹ (VPS)    socks5://127.0.0.1:1080
jp   Nhật        http://user:pass@1.2.3.4:8080
```
Có VPS ở nước ngoài thì mở đường hầm SOCKS từ máy chạy server:
```bash
ssh -N -D 1080 root@IP-VPS
```
Sau đó chạy lại server. Các vùng mới sẽ hiện trong ô chọn vùng.

### Cách 3: Chạy cả web trên VPS ở nước ngoài
Xem [mục 8](#8-đưa-lên-vps-chạy-247).

**Tor không dùng được.** Đã thử: Google không nhận IP Tor là IP của nước đó.

---

## 7. Cấu hình

Đặt bằng biến môi trường khi chạy, ví dụ `PORT=9000 ./gradlew run`.

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `HOST` | `127.0.0.1` | Địa chỉ lắng nghe. Dùng `0.0.0.0` để máy khác vào được |
| `PORT` | `8080` | Cổng web |
| `OUT_DIR` | `out` | Thư mục lưu file đã tải |
| `PROXIES_FILE` | `proxies.txt` | File danh sách proxy |
| `PROXIES` | (trống) | Proxy dạng `us=socks5://h:p,jp=http://u:p@h:p` |
| `EXTRA_DEVICES` | `sm_a13_5g,rm_7,sm_s25u` | Các máy giả lập dùng để lấy thêm split. Để trống thì chỉ dùng Pixel 9a |

Danh sách máy giả lập có sẵn: `px_9a` (mặc định), `sm_s25u`, `sm_s20_plus`, `sm_a13_5g`,
`sm_f34_5g`, `sm_j5_prime`, `rm_7`, `rm_5i`, `rm_note_12_4g`, `xm_11a`, `mi_a1`, `poco_f1`,
`op_8_pro`, `oppo_r17`, `hw_mate20`, `nothing_p1`, `nk_drx`, `xp_5_dual`, `px_9_fold`,
`px_tablet`…

---

## 8. Đưa lên VPS (chạy 24/7)

Ví dụ trên Ubuntu:

```bash
# 1. Cài Java và lấy code
sudo apt update && sudo apt install -y openjdk-21-jdk git
git clone https://github.com/NguyenMinhVu02/Download-APK.git /opt/download-apk
cd /opt/download-apk
./gradlew installDist

# 2. Tạo dịch vụ systemd để server tự chạy khi VPS khởi động
sudo tee /etc/systemd/system/download-apk.service > /dev/null <<'EOF'
[Unit]
Description=Download APK (Aurora Web)
After=network-online.target

[Service]
WorkingDirectory=/opt/download-apk
Environment=HOST=127.0.0.1
Environment=PORT=8080
ExecStart=/opt/download-apk/build/install/aurora-web/bin/aurora-web
Restart=always

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now download-apk
sudo systemctl status download-apk      # xem trạng thái
journalctl -u download-apk -f           # xem log
```

**Web hiện chưa có đăng nhập**, ai có link cũng dùng được. Khi đưa ra Internet nên:
- Đặt **Caddy** hoặc **Nginx** phía trước để có HTTPS và mật khẩu (basic auth), hoặc
- Chỉ cho truy cập qua **Tailscale** hay VPN riêng, hoặc giới hạn IP bằng tường lửa.

Ví dụ `Caddyfile` (HTTPS tự động + mật khẩu):
```
apk.ten-mien-cua-ban.com {
    basic_auth {
        admin <mật-khẩu-đã-hash-bằng: caddy hash-password>
    }
    reverse_proxy 127.0.0.1:8080
}
```

Cập nhật code mới trên VPS:
```bash
cd /opt/download-apk && git pull && ./gradlew installDist && sudo systemctl restart download-apk
```

---

## 9. Cấu trúc code

```
Download-APK/
├── src/main/kotlin/Main.kt          # Backend: server Ktor, gọi Google Play, tải, đóng gói, API
├── src/main/resources/index.html    # Frontend: giao diện web (HTML + CSS + JS)
├── build.gradle.kts                 # Cấu hình build, thư viện, tự tách gplayapi từ AAR
├── settings.gradle.kts
├── gradlew, gradlew.bat, gradle/    # Gradle wrapper (không cần cài Gradle)
├── proxies.example.txt              # File proxy mẫu → chép thành proxies.txt
├── LICENSE                          # GPL-3.0-or-later
└── README.md
```
Các thư mục `out/` (file đã tải), `build/` (kết quả build) và file `proxies.txt` (có thể chứa
mật khẩu) đều nằm trong `.gitignore`, nên không bị đưa lên GitHub.

**Vì sao phải tách AAR?** gplayapi 4.0.0 chỉ phát hành dạng AAR (thư viện Android). Phần lõi là
Kotlin/JVM thuần, nên `build.gradle.kts` tải AAR từ Maven Central, tách `classes.jar` ra rồi
dùng như một thư viện JVM bình thường (task `extractGplayApi`).

---

## 10. API

| Method | Đường dẫn | Mô tả |
|---|---|---|
| GET | `/api/search?q=&region=` | Tìm app theo tên (tối đa 30 kết quả) |
| GET | `/api/details?input=&region=` | Thông tin app. `input` là link hoặc package |
| POST | `/api/download` | Body `{"input": "...", "region": "auto", "versionCode": null}` |
| GET | `/api/progress?pkg=` | Tiến trình tải: `resolve`, `resolve-extra`, `download`, `pack`, `done`, `error` |
| GET | `/api/files` | Danh sách file đã tải |
| DELETE | `/api/files/{name}` | Xoá file |
| GET | `/files/{name}` | Tải file |
| GET | `/api/regions` | Danh sách vùng (máy này + proxy) |
| GET | `/api/ip?region=` | IP và quốc gia mà Google thấy |
| POST | `/api/reset` | Làm mới phiên ẩn danh (sau khi đổi VPN) |
| GET | `/api/lan` | Địa chỉ LAN của server (dùng cho mã QR) |

Ví dụ:
```bash
curl -X POST http://127.0.0.1:8080/api/download \
  -H 'Content-Type: application/json' \
  -d '{"input":"https://play.google.com/store/apps/details?id=org.videolan.vlc"}'
```

---

## 11. Lỗi thường gặp

| Lỗi | Nguyên nhân / cách xử lý |
|---|---|
| `java: command not found`, sai version | Chưa cài Java 21. Cài xong thì mở lại Terminal |
| `Permission denied: ./gradlew` | Chạy `chmod +x gradlew` |
| `Port 8080 already in use` | Server đang chạy rồi, hoặc app khác dùng cổng này. Đổi cổng: `PORT=9000 ./gradlew run` |
| Điện thoại không mở được web | Chạy với `HOST=0.0.0.0`, dùng cùng Wi-Fi, tắt VPN trên máy chạy server, cho phép Java qua tường lửa |
| "App bị chặn ở…" | App không phát hành ở nước của IP hiện tại. Xem [mục 6](#6-tải-app-bị-chặn-ở-việt-nam) |
| "Google đang giới hạn tốc độ" | Phiên ẩn danh bị rate limit. Chờ vài phút rồi thử lại |
| "Phiên ẩn danh bị từ chối" | Web đã tự tạo phiên mới, bấm tải lại |
| "Google Play không còn trả bản versionCode…" | Google không còn giữ bản cũ đó, hoặc bản đó không dành cho máy giả lập |
| `INSTALL_FAILED_MISSING_SPLIT` | Tải lại bằng bản web mới (đã gộp split). Trong SAI để tự chọn hoặc chọn tất cả split |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Máy đang có bản ký bằng khoá khác. Gỡ bản cũ rồi cài lại |
| Cài xong app bị đẩy về Play / không mở được | App có **PairIP** hoặc kiểm tra bản quyền. Tool không vượt qua được |

---

## 12. Giới hạn

- Chỉ tải app **miễn phí**. Không tải file **OBB** và **Play Asset Delivery**.
- App có **PairIP** hoặc license check sẽ không chạy khi cài ngoài Play.
- Bản cũ chỉ tải được khi biết versionCode và Google Play còn giữ bản đó.
- Lane ẩn danh có thể bị **rate limit** khi tải nhiều.
- Web **chưa có đăng nhập**. Khi đưa ra Internet phải tự thêm lớp bảo vệ (mục 8).

## Giấy phép

Project dùng thư viện gplayapi (GPL-3.0-or-later), nên cũng theo **GPL-3.0-or-later**.
Xem file [LICENSE](LICENSE).
