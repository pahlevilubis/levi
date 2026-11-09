# Levi — asisten AI offline di HP

Aplikasi Android buatan sendiri (Pahlevi Lubis bersama Claude) untuk bantu hal-hal kecil **tanpa internet**:

- **Chat AI** — model bahasa kecil (Qwen3.5 / Gemma 4, format GGUF) berjalan langsung di HP lewat llama.cpp
- **Terjemah** — offline dengan Google ML Kit (paket bahasa ±30 MB, unduh sekali)
- **Rekam & Dikte** — dikte langsung jadi teks, rekam rapat berjam-jam, lalu transkrip offline dengan Whisper
- **Dokumen** — foto / PDF jadi teks (OCR offline), lalu ringkas, terjemah, atau tanya isinya
- **Ringkas** & **Bantu Tulis** — surat, email, pesan, notulen
- **Cari Web** — hanya kalau kamu izinkan, memakai paket data
- **Memori** — Levi mengingat hal yang kamu setujui, tersimpan di HP saja

## Izin & privasi

Levi tidak bisa mengubah pengaturan HP, mengirim pesan, atau membuka aplikasi lain.
Mikrofon dan internet hanya dipakai setelah kamu izinkan. Semua data tersimpan di HP.

## Cara dapat APK

Setiap ada perubahan di branch `main`, GitHub Actions otomatis membangun APK dan
menerbitkannya di halaman **Releases** → unduh `Levi.apk` dari HP lalu pasang.

Pertama kali buka: **Pengaturan → Model AI → Unduh** (sekali saja, sebaiknya pakai WiFi).

## Struktur

- `app/` — aplikasi (Kotlin + Jetpack Compose)
- `llm/` — jembatan JNI ke llama.cpp
- `whisper/` — jembatan JNI ke whisper.cpp
- `third_party/` — diunduh otomatis oleh workflow (llama.cpp & whisper.cpp versi terkunci)
