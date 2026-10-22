# 📺 Bubu Cast

Ứng dụng Android cast video từ web lên TV/máy chiếu qua mạng nội bộ —
lấy cảm hứng từ Web Video Caster, giao diện và mã nguồn riêng.

Gồm 2 app:
- **app** (`com.bubu.cast`): điện thoại — trình duyệt + phát hiện video + cast
- **tv** (`com.bubu.cast.tv`): TV/máy chiếu — receiver hiển thị mã kết nối + phát video

## Tính năng

### Điện thoại
- Trình duyệt WebView với **chặn quảng cáo 5 lớp** (port từ Q10 Browser v1.6)
- Multi-tab độc lập (mỗi tab 1 WebView riêng, không lẫn nội dung)
- Tự phát hiện video (.mp4/.m3u8/.webm/.mkv) từ network + thẻ `<video>`
- Dialog "Chọn thiết bị để chiếu tới" giống Web Video Caster:
  - Quét DLNA / Google Cast / Web Receiver
  - Nhập **mã TV 6 số** hoặc **IP thủ công**
  - Nút QUÉT LẠI / ĐÓNG
- **Route video through phone**: proxy nội bộ (port 8081) để vượt host chặn header/cookie
- Phát video nội bộ bằng Media3 ExoPlayer
- Lịch sử + dấu trang (Room), cài đặt (DataStore)
- Tiếng Việt, dark theme xanh đen + tím neon

### TV Receiver
- Màn hình chờ hiện **IP:port** lớn + **mã kết nối 6 số** ngẫu nhiên
- HTTP server port 8080: `/ping`, `/play`, `/control`, `/status`
- Phát bằng **Media3 ExoPlayer** (MP4/HLS/DASH/WebM/MKV, phụ đề SRT/VTT)
- Điều khiển bằng remote: OK = play/pause, trái/phải = tua ∓10s

## Build

### Yêu cầu
- Android Studio Hedgehog+
- JDK 17
- Android SDK 34

### Mở và build
```bash
# Mở thư mục bubu-cast trong Android Studio
# Chờ Gradle sync xong, rồi:
./gradlew :app:assembleDebug   # APK điện thoại
./gradlew :tv:assembleDebug    # APK TV receiver
```

APK nằm ở:
- `app/build/outputs/apk/debug/app-debug.apk`
- `tv/build/outputs/apk/debug/tv-debug.apk`

### Build tự động (GitHub Actions)
Push lên nhánh `main` → workflow build cả 2 APK, tải về từ Artifacts.

## Cấp quyền

App tự xin quyền khi cần. Các quyền trong Manifest:
- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` — quét mạng, cast
- `CHANGE_WIFI_MULTICAST_STATE` — DLNA discovery (multicast)
- `FOREGROUND_SERVICE` — DLNA service trên Android 14+

Không yêu cầu quyền đọc toàn bộ bộ nhớ — chọn video qua Storage Access Framework.

## Kiểm thử

### Thiết bị
- Điện thoại iQOO Neo 9 (Android 14)
- Máy chiếu Q10 (Android 9, 32-bit)
- TV Sony có Chromecast

### Checklist
- [ ] Cài APK điện thoại, mở không crash
- [ ] Mở TV receiver, màn hình hiện IP + mã 6 số
- [ ] Điện thoại quét thấy TV (Web Receiver)
- [ ] Nhập mã 6 số → kết nối thành công
- [ ] Nhập IP thủ công → kết nối thành công
- [ ] Mở web phim, phát hiện video, bấm Cast → TV phát ngay
- [ ] Bật "Route video through phone" → cast được video cần cookie
- [ ] Play/Pause/Seek từ điện thoại
- [ ] Tắt màn hình điện thoại → TV vẫn phát
- [ ] Mất WiFi → báo lỗi rõ ràng, kết nối lại được
- [ ] DLNA: TV DLNA hiện trong danh sách (nếu có)
- [ ] Cast: Chromecast hiện khi có GMS (nếu không có GMS → bỏ qua lặng lẽ)

### Giao thức đã kiểm thử
| Giao thức | Trạng thái |
|-----------|-----------|
| Web Receiver (HTTP) | ✅ Hoạt động (phone ↔ TV app) |
| DLNA/UPnP | ⚠️ Chưa test thiết bị thật (dùng Cling 2.1.1) |
| Google Cast | ⚠️ Cần GMS + Chromecast thật để test |

## Định dạng video (TV)

| Định dạng | Hỗ trợ |
|-----------|--------|
| MP4 (H.264/H.265) | ✅ |
| HLS (.m3u8, AES-128) | ✅ |
| DASH (.mpd) | ✅ |
| WebM (VP8/VP9) | ✅ |
| MKV (H.264+AAC) | ✅ |
| Phụ đề SRT/VTT | ✅ |

## Thư viện & giấy phép

| Thư viện | Giấy phép |
|----------|-----------|
| AndroidX, Compose, Media3 | Apache 2.0 |
| Cling (DLNA) | CDDL 1.0 |
| Play Services Cast | Proprietary (tùy chọn) |
| Kotlin Coroutines | Apache 2.0 |

## Bảo mật

- Không thu thập dữ liệu người dùng
- Không đăng nhập bắt buộc
- Không gửi lịch sử duyệt web ra ngoài
- Cookie/header chỉ gửi tới TV trong mạng nội bộ khi cast
- Không hardcode API key
