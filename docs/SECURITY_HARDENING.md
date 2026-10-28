# Təhlükəsizlik düzəlişləri — 28 oktyabr 2026

Bu dəyişikliklər mənbə kodundadır. Köhnə Docker konteyneri/image-i avtomatik dəyişmir. Müştəriyə deployment-dən əvvəl hər iki baza, şəkillər və orijinal `.env`/native konfiqurasiya yoxlanılmış backup ilə saxlanmalıdır. V046 yalnız `users.session_version` sütununu əlavə edir; mövcud istifadəçi, şifrə, tenant və digər məlumatları yenidən yazmır.

## Dəyişən davranış

- Cihazın update/delete/enabled/start/stop/status/sync əməliyyatları bridge çağırışından ƏVVƏL tenant sahiblik yoxlaması edir. Yad cihaz üçün 404 qaytarılır. Cihazın yeni ərazi əlaqəsi də əvvəlcədən yoxlanır. Bridge-dən metadata sync başqa tenantın mövcud cihazını yeniləmir.
- Hadisə və cihaz-user proxy-si backend-də icazəli bridge ID-lərini yoxlayır. İstifadəçi öz `X-Allowed-Device-Ids` header-i ilə sərhədi genişləndirə bilmir; backend onu əvəz edir. ISAPI ümumi punches/raw-events/failed-attempts sorğularını SQL-də icazəli cihazlarla məhdudlaşdırır, sonra sort/pagination tətbiq edir. Brauzerin Authorization/Cookie-si bridge-ə ötürülmür; server API key-i istifadə olunur.
- Bridge-in legacy `device_users` keşi qlobaldır. Ona çıxan proxy yolları yalnız bir tenantlı quraşdırmada açıqdır; birdən çox tenant olduqda bu köhnə yollar fail-closed işləyir. Təkşirkətli istifadə saxlanır. Bu dəyişiklik bridge-i tam çoxşirkətli məhsula çevirmir.
- Şəkil URL-si yalnız cari tenantın qeydiyyatlı terminal origin-inə (scheme/host/port) aid ola bilər. Əvvəl tenant yoxlanır, sonra tenant+cihaz+URL keşi oxunur. Eyni tenantdakı başqa terminaldan alınmış pictureURL üçün onun düzgün bridge ID-si seçilir. ISAPI həmin seçilmiş cihazdan başqa origin-ə credential göndərmir və URL ilə səssiz cihaz dəyişmir. Şəkil tokenli URL-lər yeni prefetch loglarında yazılmır.
- Autentifikasiya olunmuş HR-in yaratdığı hesab çağıranın tenantında qalır.
- Biometrik şəkil cavabları `Cache-Control: no-store` daşıyır; brauzer/shared HTTP keşi başqa sessiyaya köhnə şəkil saxlamamalıdır. Serverin qısaömürlü keşi yenə tenant və cihazla məhdudlaşdırılır.
- Şifrə dəyişikliyi DB kilidi altında session_version artırır. Köhnə access/refresh tokenləri növbəti sorğuda rədd edilir; istifadəçi yenidən daxil olur. Upgrade zamanı əvvəlki tokenlər version=0 kimi tanınır və ilk şifrə dəyişikliyində ləğv edilir. Yeni tokenlər həmin anın versiyasını daşıyır.
- Yeni ilkin admin quraşdırması üçün lokal quraşdırma kodu tələb edilir. Artıq qurulmuş admin hesabı bundan təsirlənmir.
- Frontend dependency-ləri yenilənib: Vite 6.4.4 və React Router 7.18.5; frontend Docker build Node 22 istifadə edir. JavaScript-in production build formatı saxlanır.
- Standart Compose-də backend 8080 yalnız localhost-a bind edilir; LAN brauzerləri frontend-in mövcud `/api` proxy-si ilə işləyir. Real hostun mövcud override faylları ayrıca nəzərə alınmalıdır. Frontend-in LAN girişi saxlanır; HTTPS sertifikatı avtomatik yaradılmır.

## İlkin quraşdırma kodu

Backend ilk quraşdırmada `APP_RUNTIME_DIR/initial-setup-token` faylını yaradır. Fayl POSIX sistemlərdə 0600 icazəsi ilə yaradılır; kod heç bir HTTP cavabına və tətbiq loguna yazılmır. Windows-da runtime qovluğunun NTFS icazələrini servis hesabı və administratorlarla məhdudlaşdırmaq lazımdır.

Quraşdırmanı edən administrator Docker hostunda aşağıdakı əmri ÖZ terminalında işlədib kodu ilkin quraşdırma sahəsinə daxil edə bilər:

```bash
docker compose exec backend cat /app/runtime/initial-setup-token
```

Kodu çata, Git-ə, screenshot-a və ya support loguna göndərməyin. Native quraşdırmada eyni fayl konfiqurasiya olunmuş runtime qovluğundadır. İdarə olunan quraşdırıcı alternativ olaraq ən azı 32 simvolluq `APP_INITIAL_SETUP_TOKEN` mühit dəyişəni verə bilər. Fayldakı kodu URL-yə yazmaq olmaz. Quraşdırma tamamlananda DB-nin mövcud setup flag-i həmin axını bağlayır; kodu bilmək bitmiş admin şifrəsini sıfırlamır.

## Yoxlamalar

- 599 backend testi və Maven package keçdi. Əvvəl 79 fokuslanmış təhlükəsizlik testi, son HTTP/no-store əlavəsindən sonra onun 2 fokuslanmış controller testi və bütün 599 test uğurludur. Regresiyalar yad cihaz update/delete/start/stop/sync-dən əvvəl sıfır bridge çağırışı, şəklin sahiblik/URL yoxlaması, köhnə sessiyanın rəddi, quraşdırma kodu, token versiyası və qlobal device-user keşinin çoxşirkətli rejimdə bağlanması üçündür.
- 35 ISAPI Maven testi/package və Gradle offline test keçdi.
- Frontend lint, build və 57 test keçdi. Canlı registry-yə qarşı son `npm audit`: 0 həssas paket qeydi. Bu, Java/Linux image CVE zəmanəti deyil; Docker Scout skanı xaricə metadata göndərilməsi üçün icazə alınmadığına görə işlədilməyib.
- Ayrı `internal=true` Docker fixture-də fresh V046 startup; V045→V046 upgrade-də istifadəçi adları/password hash-ləri/rollar/tenant əlaqələrinin checksum uyğunluğu və əvvəlki istifadəçilərin session_version=0 başlanğıcı təsdiqləndi.
- Yeni image-lərlə 28 API şərti: kod olmadan bootstrap rəddi, düzgün kodla bootstrap, öz tenantında signup, yad cihazların 404/sıfır upstream çağırışı, şəkil/SSRF bloklanması, normal şəkil və yeni login-in işləməsi. End-to-end real backend+ISAPI+PostgreSQL yoxlamaları raw-events/failed-attempts/punches və tarix aralığındakı pagination-dan əvvəl tenant filtrini təsdiqlədi. Test zamanı saxta scope header-i sərhədi keçmədi.
- Brauzer smoke: yenilənmiş Router ilə sintetik admin login, dashboard və settings/security formuna keçid uğurlu. Brauzerdə yeni credential yazılması/şifrə dəyişmə submit-i aparılmadı; həmin əməliyyat API fixture-də yoxlanıb. Mock bridge status xəbərdarlığı tam cihaz sağlamlığı testi deyil.
- Real bazaya dəyişiklik, fiziki cihaz yazısı və canlı deploy edilməyib. Lokal sübutlar `build/security-audit/` və `build/security-*.log` altındadır; bunlar çatdırılma/secret mənbəyi deyil və Git-ə əlavə edilmir.

## Ayrı qalan host işləri

HTTPS, host firewall/port qaydaları, Windows servis hesabı və NTFS ACL-ləri hər quraşdırmaya görə tətbiq edilməlidir; bu kod düzəlişi həmin maşınların ayarlarını dəyişmir. Backup/restore, avtomatik cihaz hadisələrinin toplanması və mövcud müştəri məlumatları məqsədli şəkildə saxlanıb. Əvvəl hazırlanmış Ubuntu image arxivi bu düzəlişlərdən əvvəldir: yeni yoxlanmış image-lərdən yenidən export edilmədən müştəriyə çatdırılmamalıdır.
