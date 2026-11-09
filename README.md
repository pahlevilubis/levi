# Levi — asisten AI offline di HP

Aplikasi Android buatan Pahlevi Lubis bersama Claude untuk bantu hal-hal kecil, sebagian besar **tanpa internet**:

- **Chat AI** — model bahasa kecil (Qwen3.5 / Gemma 4, format GGUF) berjalan langsung di HP lewat llama.cpp
- **Terjemah** — offline dengan Google ML Kit (paket bahasa ±30 MB, unduh sekali)
- **Rekam & Dikte** — dikte langsung jadi teks, rekam rapat berjam-jam, lalu transkrip offline dengan Whisper
- **Dokumen** — foto / PDF jadi teks (OCR offline), lalu ringkas, terjemah, atau tanya isinya
- **Ringkas** & **Bantu Tulis** — surat, email, pesan, notulen
- **Email** — baca & ringkas Gmail (sandi aplikasi Google, disimpan terenkripsi), balas dengan bantuan AI
- **Kirim Pesan** — WhatsApp, SMS, email; Levi selalu menampilkan tujuan & isi lalu minta izin dulu
- **Cari Web & Pengetahuan** — mencari di web hanya dengan izin; Levi menanyakan apakah situsnya bisa dipercaya,
  dan hanya belajar (menyimpan pengetahuan) dari situs yang kamu percayai
- **Memori** — Levi mengingat hal yang kamu setujui, tersimpan di HP saja

## Batasan

- Tidak bisa mengubah pengaturan HP atau membuka/mengendalikan aplikasi lain.
- Pesan hanya dikirim setelah kamu melihat isinya dan menekan **Ya, kirim**.
- Mikrofon, internet, dan Gmail hanya dipakai setelah kamu izinkan. Semua data tersimpan di HP.

## Cara dapat APK

Setiap ada perubahan di branch `main`, GitHub Actions otomatis membangun APK dan
menerbitkannya di halaman **Releases** → unduh `Levi.apk` dari HP lalu pasang.

Pertama kali buka: **Pengaturan → Model AI → Unduh** (sekali saja, sebaiknya pakai WiFi).

## Struktur

- `app/` — aplikasi (Kotlin + Jetpack Compose)
- `llm/` — jembatan JNI ke llama.cpp
- `whisper/` — jembatan JNI ke whisper.cpp
- `third_party/` — diunduh otomatis oleh workflow (llama.cpp & whisper.cpp versi terkunci)
