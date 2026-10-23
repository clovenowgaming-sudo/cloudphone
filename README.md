# Cloud Phone - 2 APK
- server/     : APK utama di HP yang mau diakses. Jalankan screen capture + Accessibility + HTTP :8080 + tunnel ngrok (binary dibundel).
- controller/ : APK untuk HP lain, tampil layar + kontrol sentuh jarak jauh.

## Build
Push ke GitHub -> Actions -> download artifact `CloudPhone-Server-APK` dan `CloudPhone-Controller-APK`.
(workflow otomatis mengunduh ngrok arm64 ke server/src/main/jniLibs/arm64-v8a/libngrok.so)

## Pakai
1. HP server: buka Cloud Phone Server, isi ngrok authtoken + domain static, START SERVER, izinkan screen capture, aktifkan Accessibility.
2. HP lain: Cloud Phone Control, isi https://domain + token yang tampil di Server, Connect.
