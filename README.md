# Remote Sony

App Android điều khiển TV Sony (Google TV / Android TV), có **bàn phím gõ chữ**: dùng bàn phím của điện thoại, gõ được tiếng Việt, chữ hiện ngay trên ô tìm kiếm của TV.

## Tính năng
- Remote đầy đủ: nguồn, Input, 4 mũi tên + OK, Quay lại, Home, Tuỳ chọn, âm lượng, kênh, tắt tiếng, bàn số, phát/tạm dừng, mở YouTube/Netflix. Giữ nút âm lượng/kênh/mũi tên để lặp.
- **Gõ chữ**: bấm *Gõ chữ* → bàn phím điện thoại bật lên, mỗi chữ gõ hiện ngay trên TV. Nút *Tìm* = Enter. Có nút Dán, Xoá, điều hướng nhanh.
- **Tự bật bàn phím**: khi trên TV mở ô tìm kiếm (YouTube, Netflix, Cài đặt…), app tự chuyển sang màn hình gõ chữ.
- **Hồng ngoại dự phòng**: điện thoại có mắt IR (OPPO, OnePlus, Xiaomi…) thì các nút cơ bản vẫn bấm được khi chưa có Wi‑Fi, bật được TV đang tắt hẳn. Gõ chữ thì bắt buộc Wi‑Fi.

## Cách hoạt động
- **Wi‑Fi**: giao thức *Android TV Remote v2* (cổng 6466/6467) – cùng giao thức app Google TV dùng. Ghép nối 1 lần bằng mã 6 ký tự hiện trên TV, không cần bật gì trong cài đặt TV.
- **Hồng ngoại**: mã Sony SIRC 12/15 bit, sóng mang 40 kHz, qua `ConsumerIrManager`.
- Ưu tiên Wi‑Fi; mất Wi‑Fi thì tự chuyển sang hồng ngoại.

Mã chính: `tv/AndroidTvRemote.kt` (điều khiển + gõ chữ), `tv/Pairing.kt` (ghép nối), `tv/Tls.kt` (chứng chỉ máy khách), `tv/TvDiscovery.kt` (dò TV), `ir/SonyIr.kt` (mã IR Sony), `MainViewModel.kt`, `ui/Screens.kt`.

## Build APK bằng GitHub Actions
1. Tạo repo mới (VD `quatrang300-oss/RemoteSony`), tải toàn bộ thư mục này lên nhánh `main` (nhớ có thư mục `.github`).
2. Tab **Actions** → workflow **Build APK** tự chạy (hoặc bấm *Run workflow*).
3. Tải artifact **RemoteSony-apk** → giải nén → cài `RemoteSony.apk`.

Bản build được ký bằng khoá cố định `app/remotesony.jks` nên bản mới cài đè được lên bản cũ. (AGP 8.11, Kotlin 2.2, Compose, minSdk 26 – Android 8.0.)

## Sử dụng lần đầu
1. Điện thoại và TV dùng chung một mạng Wi‑Fi, TV đang bật.
2. Mở app → chọn TV trong danh sách (hoặc nhập IP của TV).
3. TV hiện mã 6 ký tự → nhập vào app → *Xác nhận*.

Nếu báo không ghép nối được: trên TV vào *Cài đặt → Ứng dụng → Xem tất cả ứng dụng → Hiện ứng dụng hệ thống → Android TV Remote Service → Xoá dữ liệu*, rồi thử lại.
