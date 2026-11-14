# Audit gerakan dan transisi Droide — 9 November 2026

## Batas perubahan

Permintaan pengguna: audit gerakan, animasi dan transisi agar tidak tersendat,
berkedip atau melompat. **Desain dan layout harus tetap sama.**

Basis: `Droide-Full-Source-2026-11-09-cli-panel-recovery.zip`, SHA-256
`7b606b7a6b73166a67a05c145921d24e2a3976980d421f1c6bcfebfd77bb1669`.
Seluruh perbaikan sebelumnya tetap disertakan. Tidak ada perubahan warna,
tipografi, ikon, jarak, ukuran, urutan kontrol, resource atau struktur navigasi.
Tidak ada tombol/pengaturan baru.

## Masalah yang diperbaiki

### Panel hilang sebelum animasi keluar

Sebagian tombol Close mengubah boolean visibilitas langsung. Composable panel
terhapus sebelum `SheetState.hide()` sempat menjalankan animasi Material.

`DroideModalBottomSheet` sekarang mempertahankan satu panel Material sampai
animasi hide selesai, lalu menjalankan callback penutupan satu kali. Tidak ada
timer atau durasi buatan. Permintaan tutup berulang digabung; pembatalan scope
saat pemilik UI dibuang mencegah callback lama menutup layar berikutnya.
Hide yang dibatalkan oleh gesture atau ditolak state dapat dicoba kembali.

Penerapan pada Close biasa: project drawer, Problems, model picker, history,
Android Development, Billing, output build, provider connection, panel
professional, serta Close/Minimize export. Proses export tetap berjalan sesuai
perilaku sebelumnya; cancel export dan pemilihan tujuan tidak ditunda.

Wrapper meneruskan `sheetState`, warna dan isi yang sama ke Material3 tanpa
menambah node layout. Uji membandingkan bounds panel terbuka dengan Material
langsung dan memastikan hasilnya identik. Dengan animation scale Android nol,
tidak ada tambahan waktu tunggu.

### Kedip pada perubahan status agent yang beruntun

Sebelumnya setiap perubahan fase membatalkan animasi lama dan mengembalikan
opacity ke 0.82. Perubahan Working → Thinking → Preparing → Reading yang cepat
mengulang efek redup tersebut.

Pengendali gerakan yang sudah digunakan workbench digeneralisasi menjadi
`DroideKeyedMotion`. Status langsung menampilkan label terbaru sambil
melanjutkan animasi yang sedang berjalan. Burst pembaruan tidak mengulang atau
memperpanjang animasi pertama. Durasi dan rentang opacity tetap sama; tampilan
awal status tetap langsung penuh. Status selesai/berhenti/menunggu persetujuan
dan reduced motion langsung mencapai keadaan akhir. Mengaktifkan animasi lagi
tidak mengulang perubahan yang sudah ditampilkan.

Uji pembanding menggunakan source lama benar-benar gagal pada pengukuran
opacity untuk burst status; source perbaikan lulus uji yang sama.

## Cakupan audit source

Inventaris dilakukan pada kode UI Kotlin, pemanggilan gerakan/scroll/dialog,
resource Android dan assets. Berikut jalur yang diperiksa; audit source bukan
benchmark seluruh runtime pada perangkat.

| Jalur | Hasil/keputusan |
| --- | --- |
| Perpindahan editor, terminal, chat, settings | Pertahankan satu surface dan animasi workbench yang sudah ada; navigasi cepat tidak me-reset lintasan atau memasang renderer kedua. |
| Reclaim ruang status bar/cutout | Pertahankan respons terhadap permintaan editor, prioritas sampel inset native, fallback bertanda generasi, dan batas cutout. Tidak menambah delay. |
| Keyboard dan accessory keys | Pertahankan pembacaan inset saat pengukuran, bukan tween kedua di atas animasi IME native. |
| Orientasi, panel landscape, navigasi compact | Pertahankan retarget berdasarkan fokus, viewport dan inset; tidak mengubah ukuran akhir. |
| Resize dengan gesture | Pertahankan gerakan yang mengikuti pointer langsung dan callback terbaru tanpa restart gesture saat recomposition. |
| Status agent bawaan/CLI | Perbaiki fade berulang; semantik dan label terbaru tetap langsung tersedia. |
| Disclosure aktivitas/tool/code preview | Pertahankan animasi saat aksi pengguna; pembaruan stream tidak mengulang animasi ukuran. Glyph kecil boleh crossfade tanpa menggandakan teks atau aksi. |
| Daftar chat dan history | Pertahankan anchor stabil, posisi tersimpan dan follow output hanya saat pengguna memang mengikuti bagian akhir. |
| Explorer dan pencarian | Pertahankan reveal item/hasil dan debounce pencarian. Debounce bukan durasi animasi. |
| Terminal | Pertahankan follow output, posisi baca manual dan scroll Page Up/Down yang sudah ada. |
| Browser/WebView | Pertahankan satu native host, viewport terakhir dan clipping; jangan menggandakan WebView untuk crossfade halaman. |
| Modal, dropdown, dialog | Gunakan lifecycle dan animasi Material. Perbaiki Close biasa yang melewati animasi keluar. |
| Approval dan pemulihan transaksi | Keputusan/cancel/revocation tetap langsung. Jangan menunda keputusan atau mempertahankan tombol approval lama hanya demi animasi keluar. |
| Resource/assets | Tidak ditemukan bundle animasi UI custom lain yang perlu diubah; animasi internal library editor/terminal/Chromium membutuhkan pemeriksaan perangkat. |

Scroll langsung untuk restore posisi, mengikuti stream, reveal target atau
gesture bukan otomatis glitch. Mengganti semuanya dengan tween justru dapat
membuat viewport tertinggal dari input atau menyeret pengguna dari posisi baca.
Navigasi yang menyelesaikan aksi domain tetap langsung. Khusus panel recovery,
Close membatalkan pekerjaan/akses inspeksi sehingga lifecycle itu dipertahankan.

## Referensi VS Code

Source yang dibaca, commit `e646868227c3e3a9e3f24277c8c7220be42c0be7`:

- [scrollable.ts](https://github.com/microsoft/vscode/blob/e646868227c3e3a9e3f24277c8c7220be42c0be7/src/vs/base/common/scrollable.ts#L306): tidak menginterupsi atau memperpanjang animasi dengan tujuan sama; menggunakan waktu/lintasan yang sedang berjalan.
- [accessibilityService.ts](https://github.com/microsoft/vscode/blob/e646868227c3e3a9e3f24277c8c7220be42c0be7/src/vs/platform/accessibility/browser/accessibilityService.ts#L61): menghormati reduced motion sistem dan perubahan preferensinya.

Prinsip tersebut diterapkan melalui Compose/Material dan animation scale
Android. Tidak menyalin implementasi DOM/CSS atau layout desktop VS Code.

## Verifikasi dan batas bukti

139 tes berbeda lulus dalam 15 kelas terpilih, tanpa mengecualikan metode tes
di dalam kelas tersebut. Termasuk 10 tes baru untuk lifecycle panel, geometry
Material dan kontinuitas status. Tes status bar native-host memakai SDK 35
dengan inset yang diinjeksi, bukan klaim telah menjalankan seluruh matriks
SDK 29/30/34/35 yang tercantum pada source tes.

Kotlin/Compose untuk helper gerakan dan dependency terarah dikompilasi dari
source saat ini. Harness menggunakan library, resource Android dan class
dependency cache yang sudah tersedia. Model UI immutable CLI dikompilasi dari
deklarasi source aslinya yang diekstrak persis; controller CLI tidak diganti
dengan implementasi buatan. Rincian input SHA-256 dan log ada pada paket bukti.

Kompilasi integrasi `AgentPane`/`IdeScreen`/`ExplorerExportSheet` tidak tervalidasi: percobaan
harness terarah menemukan dependency cache lama yang tidak cocok dengan API
aplikasi saat ini. Pemanggilan yang diubah pada ketiga file ditinjau melalui
diff; hasil ini **bukan** klaim full build berhasil. Log percobaan tersebut
disimpan, bersama kegagalan pembanding yang memang diharapkan dan koreksi
sinkronisasi frame pada harness tes status.

Tidak dilakukan full build/APK/native build, live provider call, benchmark FPS
atau pengujian pada perangkat fisik. Tes host menguji kontinuitas state,
geometry, callback dan lifecycle; tidak membuktikan performa Sora, terminal
native, Chromium, driver GPU atau IME vendor tertentu.

## Pemeriksaan lanjutan pada perangkat

Pada build Android dari source ini, periksa perpindahan editor/chat/terminal
secara cepat, keyboard buka/tutup dan rotasi, resize sambil input, streaming
aktivitas agent, serta Close/Back/drag pada panel pendek dan panjang. Ulangi
dengan animation scale 0x/1x/2x. Pastikan bentuk/ukuran/urutan kontrol tidak
berubah, input tetap responsif, dan posisi baca chat tidak direbut saat stream.
Gunakan frame timing/Perfetto pada perangkat sasaran untuk menilai jank;
screenshot statis tidak cukup untuk membuktikan kelancaran animasi.
