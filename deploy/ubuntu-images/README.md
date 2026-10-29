# ABV — hazır Docker paketi

Tətbiq versiyası: __ABV_VERSION__. Platforma: **linux/amd64**.
Standart Tabel: 07:20 → 7, 07:30 → 7, 07:31 → 8; aylıq cəm gündəlik yuvarlaq saatların cəmidir. Excel eyni qaydadadır.

## Yeni Ubuntu serverinə quraşdırma

Bu paket yeni, ayrıca quraşdırma üçündür. Mövcud bazanı və istifadəçiləri daşımır. Docker Engine, Docker Compose plugin, openssl və tar əvvəlcədən quraşdırılmalıdır. Git, Java, Node.js və build tələb olunmur. Image-lər paketdədir; quraşdırma onları internetdən yükləmir.

Paketi serverə köçürün və aşağıdakı əmrləri işlədin. `PAKET.tar.gz` yerinə arxivin tam adını, `SERVER_IP` yerinə serverin ünvanını yazın:

```bash
sudo install -d -m 700 /opt/abv
sudo tar -xzf PAKET.tar.gz -C /opt/abv
cd /opt/abv
sudo bash install.sh http://SERVER_IP:3000
```

Brauzerdə `http://SERVER_IP:3000` açın. Backend açıldıqdan sonra ilkin admin quraşdırma kodunu serverdə bu əmrlə oxuyun və ilkin quraşdırma ekranına daxil edin:

```bash
sudo cat /opt/abv/runtime/initial-setup-token
```

Bu kodu, `.env`-ni və şifrələri Git/çata göndərməyin. `.env` serverdə təsadüfi açarlarla yaranır. `install.sh`/`prepare.sh` mövcud `.env`-nin üstünə yazmır. İlk açılış bir neçə dəqiqə çəkə bilər.

HTTP server IP-si verildikdə port 3000 bütün interfeyslərdə açılır: onu yalnız nəzərdə tutulan yerli şəbəkə/VPN üçün əlçatan edin. Arqumentsiz quraşdırma yalnız `http://localhost:3000` girişini açır. `https://domain` verildikdə port yalnız localhost-a bağlanır; HTTPS reverse proxy ayrıca qurulmalıdır. Kamera üçün HTTPS və ya localhost tələb oluna bilər. PostgreSQL, backend və ISAPI host portları publish edilmir. Real cihazlara yazmadan əvvəl cihaz kimliyini yoxlayıb ayrıca icazə alın.

## Açılışı yoxlama

```bash
sudo docker compose ps
curl -fsS http://127.0.0.1:3000/api/health
sudo docker compose logs --tail 60 backend isapi frontend
sudo systemctl enable --now docker
```

Konteynerlərin restart qaydası var; Docker açıldıqda yenidən başlayırlar. Logları paylaşmazdan əvvəl məxfi məlumatları təmizləyin.

## Fayllar və mənbə kodu

Paket yalnız `abv-images.tar`, Compose, quraşdırma skriptləri, bazanın başlanğıc əmri, image manifesti və SHA256 yoxlama cəmlərini daşıyır. Java/TypeScript mənbələri, `.git`, real `.env`, müştəri bazası/şəkilləri və build alətləri daxil edilmir. Tətbiq image-ləri yalnız runtime mərhələləridir; frontend source map-ləri daxil edilmir. JAR içindəki SQL migration-lar tətbiqin işləməsi üçün saxlanılır.

Server administratoru JAR bytecode-u analiz edə bilər; brauzerdə işləyən JavaScript görünür. Bu paket mənbə fayllarını daşımır, tam analizdən qoruma və ya lisenziya mexanizmi deyil.

## Backup və sonrakı yeniləmə

Məlumatlar `abv_postgres_data`, şəkillər `abv_face_images` volume-larında saxlanılır. `.env` açarlarını bazalar və şəkillərlə birlikdə qorunan yerdə backup edin. Windows backup worker bu paketə daxil deyil; Ubuntu backup/restore ayrıca hazırlanmalıdır.

Bu paket mövcud quraşdırmanın üstünə avtomatik köçürülməməlidir. Yeniləmədən əvvəl hər iki bazanın, şəkillərin və `.env`-nin yoxlanılmış backup-ını alın. Eyni `/opt/abv`, Compose layihə adı `abv`, `.env` və volume-lar saxlanmalıdır. Yeni buraxılışın yoxlama cəmlərini ayrıca qovluqda yoxlayın, image-ləri `docker load` ilə yükləyin; uyğun yeni Compose faylını köçürdükdən sonra `docker compose up -d --no-build --pull never` işlədin. `install.sh`/`prepare.sh`-ni təkrar işlədərək açarları yeniləməyin.

`docker compose down -v` və `docker volume prune` işlətməyin. Image load əməliyyatı məlumat backup-ı deyil. Ubuntu serverinin özündə və fiziki cihazlarda qəbul sınağı ayrıca aparılmalıdır.
