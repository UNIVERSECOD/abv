# Attendra ABV

Mənbə: Attendra main, 2905751 (2026-10-29). Tabel standart hesablamadan istifadə edir. Hər günün işlənmiş dəqiqəsi ayrıca yuvarlaqlaşdırılır: 00–30 aşağı, 31–59 yuxarı. 07:20 → 7, 07:30 → 7, 07:31 → 8. Aylıq cəm həmin gündəlik saatların cəmidir. UI və Excel eynidir. Faktiki davamiyyət, digər hesabatlar, məzuniyyət/istirahət kodları və bazadakı dəqiq vaxtlar saxlanır. Müsbət vaxtı olan gün, yuvarlaq nəticəsi 0 olsa da, işlənmiş gün sayılır.

## Yeni Ubuntu serverində quraşdırma

Docker Engine, Docker Compose plugin, Git və openssl əvvəlcədən quraşdırılmalıdır.

```bash
git clone https://github.com/UNIVERSECOD/abv.git
cd abv
bash scripts/prepare-ubuntu.sh http://SERVER_IP:3000
docker compose config --quiet
docker compose up -d --build
docker compose ps
docker compose logs --tail 80 backend isapi frontend
```

SERVER_IP yerinə serverin IP ünvanını yazın. Brauzerdə həmin ünvanı açın. İlkin quraşdırma kodunu serverdə `runtime/initial-setup-token` faylından götürüb ilkin admin şifrəsini yaradın; kodu Git və ya çata göndərməyin. Docker əmrləri üçün server hesabının müvafiq icazəsi olmalıdır (lazımdırsa sudo). Port 3000 girişini yalnız nəzərdə tutulan şəbəkəyə açın; internet istifadəsində HTTPS reverse proxy və uyğun CORS ünvanı qurun. HTTP IP üzərində brauzer kamerası məhdud ola bilər.

Compose layihəsi `abv` adlanır və ayrıca volume-lar yaradır. Mövcud Attendra ilə eyni serverdə portlar toqquşa bilər; bu təlimat yeni server üçündür. Bu nüsxə müştəri məlumatlarını və real .env-ni daşımır. Windows backup worker Ubuntu-da işləmir; server üçün ayrıca backup qurulmalıdır.

## Yeniləmə

Əvvəl hər iki PostgreSQL bazasını, şəkilləri və .env-ni qorunan yerdə backup edin. Eyni qovluğu, .env-ni və abv Compose layihə adını saxlayın.

```bash
git pull --ff-only origin main
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

.env-ni yenidən yaratmayın. `docker compose down -v` və `docker volume prune` işlətməyin. Real cihaz sinxronizasiyasından əvvəl cihazın kimliyi yoxlanmalı və ayrıca icazə alınmalıdır.

## Yoxlama (2026-10-29)

9 fokuslu Tabel testi, bütün 621 backend testi və paketlənmə, 57 frontend testi, lint/build, 35 ISAPI Maven testi keçdi. Compose konfiqurasiyası və hər üç tətbiq image build-i keçdi. Təcrid edilmiş Linux Docker mühitində təzə PostgreSQL bazasına 46 migration tətbiq olundu; ABV səhifəsi, backend health, ilkin admin quraşdırması/giriş, autentifikasiyalı STANDARD Tabel və XLSX export yoxlandı. Ubuntu skripti bash sintaksis yoxlamasından keçdi. Native Java/Maven əvəzinə Maven Docker image-ləri istifadə olundu; ISAPI Gradle ayrıca işlədilmədi. Ubuntu serverinin özündə və fiziki cihazlarda sınaq aparılmadı. npm production audit: 0; ümumi install audit-də bir moderate development dependency xəbərdarlığı var.
