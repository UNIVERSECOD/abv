# Təhlükəsizlik auditi — 28 oktyabr 2026

Sonrakı düzəliş: aşağıdakı tapıntıların ilkin audit nəticələri tarixçə kimi saxlanıb. Kodda görülən tədbirlər və yeni test nəticələri [SECURITY_HARDENING.md](SECURITY_HARDENING.md) sənədindədir. Köhnə işləyən konteynerlər ayrıca deploy edilməyənədək bu düzəlişləri daşımır.

Yoxlanılan Git bazası: `cbeed2c` (main). Mənbə kodu auditi Docker açıldıqdan sonra izolə olunmuş dinamik API sınaqları ilə genişləndirildi. Müştəri bazasına və fiziki cihazlara müraciət edilməyib. Tətbiq kodu və miqrasiyalar dəyişdirilməyib. Bu, tam pentest/fuzzing əhatəsi və ya təhlükəsizlik zəmanəti deyil.

## Docker açıldıqdan sonra faktiki nəticələr

`attendra-security-audit` adlı `internal=true` Docker şəbəkəsi, tmpfs PostgreSQL və süni iki tenant yaradıldı. Production volume-ları qoşulmadı, host portu publish edilmədi, real credential istifadə edilmədi. Backend üçün mock ISAPI, SSRF üçün ayrıca real ISAPI image-i və Digest challenge verən süni HTTP server istifadə edildi. Sınaq konteynerləri sonunda silindi.

**21 API yoxlama şərtinin hamısı gözlənən müşahidəni verdi: 10 qoruma işləyir, 11 şərt isə boşluqları təkrarlayır. “Pass” yazılmış VULNERABILITY nəticəsi təhlükəsiz davranış deyil, boşluğun uğurla təkrarlanmasıdır.**

Təkrarlananlar:

- Anonim fresh-install admin quraşdırması qəbul edildi.
- Tenant B admininin yaratdığı istifadəçi tenant A/DEFAULT-a yazıldı.
- Tenant B-nin tenant A cihazını dayandırmaq sorğusu mock bridge-ə çatdı, HTTP 200 qaytarıldı.
- Tenant B-nin tenant A cihazını dəyişməsi HTTP 400 ilə bitsə də, cihazın adı artıq DB-də dəyişmişdi; bunu tenant A-nın sonrakı oxuması təsdiqlədi.
- Tenant B tenant A cihazını sildi: DELETE 200, sahib tenantın sonrakı GET-i 404.
- Başqa şirkətin bridge ID-si ilə raw-events sorğusu dəyişmədən mock bridge-ə çatdı və mock hadisə cavabı qaytarıldı. Burada real bridge məlumat sızması deyil, backend sərhədinin buraxması dinamik təsdiqlənib.
- Tenant A şəkli keşə yazdıqdan sonra tenant B eyni URL-ni mövcud olmayan deviceId ilə oxudu: HTTP 200 və eyni süni şəkil.
- Şifrə dəyişikliyindən sonra həm köhnə access, həm köhnə refresh token işləyirdi.
- Real ISAPI image-i qeydiyyatdakı süni cihazdan fərqli URL hostunu götürdü və HTTP 200 şəkil cavabını qaytardı.
- Həmin fərqli host süni cihaz credential-ları əsasında Digest autentifikasiya header-i aldı. Header-in özü saxlanmadı, yalnız alınması faktı qeyd edildi.

İşləyən qorumalar: anonim protected API 401; EMPLOYEE raw-events 403; refresh token ilə protected API 401; dəyişdirilmiş JWT imzası 401; bootstrap-dan sonra anonim signup 401; yad cihazın detail GET-i 400; yad CORS origin-i 403; login rate-limit 429; ISAPI-də itkin və yanlış API key 401.

Lokal sübutlar (Git-də izlənməyən `build/security-audit/`): `bootstrap.json`, `api-results.json`, `ssrf-results.json`, `probe.cjs`, `ssrf.cjs`, `mock.cjs`, `seed.sql`, `isapi-gradle.log`. Fixture credential-ları yalnız sintetikdir; nəticə JSON-larında JWT və şifrə yoxdur.

Yoxlanılan mövcud image-lər:

- Backend: `sha256:2d628e6a4467671127572dedd4dbd793dd1831e324cb6309b56adf3d61412a88`
- ISAPI: `sha256:24d00b37156ef15b0884114a0280fe5252a64fcac8b7f74568aa6b70a5e39f84`

Backend/ISAPI/frontend image konfiqurasiyasında ayrıca USER təyin edilməyib. Root-dan istifadə səlahiyyətin azaldılması üçün ayrıca sərtləşdirmə mövzusudur; bu audit konteynerdən hosta çıxış istismarı göstərmir. Faktiki mövcud quraşdırmada backend portu localhost:8082-yə bağlıdır; aşağıdakı HTTP/8080 qeydi standart Compose üçündür.

## Prioritet tapıntılar

### 1. Yüksək — cihaz əməliyyatlarında tenant sahiblik yoxlaması yoxdur

`backend/src/main/java/com/hic/controller/DeviceController.java:70–143`: update/delete/enabled/start/stop/status/sync əməliyyatları cihazı qlobal `findById` ilə götürür və tenant yoxlamadan ISAPI-yə ötürür. Update/enabled üçün sonda çağırılan `getById` artıq icra edilmiş əməliyyatı geri qaytarmır. HR rolu olan başqa tenant istifadəçisi məlum/təxmin edilən DB ID ilə başqa tenant cihazının konfiqurasiyasını dəyişə, silə və bridge işini dayandıra bilər. Anonim giriş tələb olunmur: HR hesabı lazımdır.

Təklif: hər hansı bridge çağırışından ƏVVƏL `findByIdAndTenantId` və ya mərkəzləşdirilmiş məcburi sahiblik yoxlaması; branch əlaqəsini də eyni tenant daxilində yoxlamaq. Tenant A/B üçün bütün endpointlərdə 404/403 və sıfır upstream çağırışını mock testlə təsdiqləmək.

### 2. Yüksək — hadisə proxy-si şirkətlərarası məlumat sızdırır

`backend/.../controller/EventReadController.java:29–69` və `backend/.../service/IsapiProxyService.java:38–50,98–105`: sorğu parametrləri və body tenant cihazları ilə məhdudlaşdırılmadan ümumi bridge-ə göndərilir. `isapi/.../controller/EventReadController.java:47–137` deviceId olmadan ümumi hadisə qeydlərini də qaytarır. HR hesabı başqa tenant cihazının deviceId-sini seçə və ya ümumi siyahını tələb edə bilər.

Təklif: backend-də cari tenantın icazəli bridge ID-lərini çıxarmaq, həm query, həm JSON girişini yoxlamaq, deviceId olmayan sorğuları da həmin dəstlə məhdudlaşdırmaq. Sadəcə frontend filtrinə etibar etmək olmaz.

### 3. Yüksək — şəkil proxy-sində SSRF və başqa cihazın credential istifadəsi

`backend/.../service/DeviceLogSearchService.java:146–202`: istifadəçi URL-si server tərəfindən götürülür; cihaz `findById`, alternativ cihaz isə qlobal IP axtarışı ilə seçilir. `isapi/.../controller/EventReadController.java:248–258` naməlum host üçün seçilən cihaza fallback edir. `isapi/.../device/DigestHttpClient.java:91–93,239–259` sərbəst URL authority-sinə həmin cihazın istifadəçi adı və şifrəsi ilə yeni Digest client yaradır.

Nəticə: HR hesabı ISAPI serverindən daxili/xarici HTTP hədəflərinə sorğu göndərilməsinə səbəb ola bilər. İdarə olunan server Digest challenge verərsə, terminal credential-larından yaradılan autentifikasiya cavabını qəbul edə bilər; bu, açıq mətn şifrənin avtomatik sızması demək deyil. Real serverə/cihaza belə sınaq göndərilməyib.

Təklif: URL-ni standart URI parser ilə yoxlamaq; scheme/host/port-u tenant daxilində icazəli terminal origin-i ilə müqayisə etmək; userinfo və başqa origin-i rədd etmək; redirect və cavab ölçüsünü məhdudlaşdırmaq. Uyğunluq üçün başqa terminaldan şəkil lazım olsa, həmin terminal da tenant üzrə ayrıca səlahiyyətləndirilməlidir.

### 4. Yüksək — şəkil keşindən tenant yoxlamasından əvvəl oxunur

`backend/.../service/DeviceLogSearchService.java:146–153`: keş açarı yalnız URL-dir və cache hit cihazın mövcudluğu/sahibliyi yoxlanmadan qaytarılır. Başqa tenant HR istifadəçisi URL-ni əldə edərsə, keşdəki biometrik şəkli oxuya bilər. URL-nin bilinməsi ilkin şərtdir.

Təklif: əvvəl tenant/cihaz səlahiyyəti, sonra tenant+cihaz+URL açarı ilə keş; tenantlararası eyni URL sınağı əlavə etmək.

### 5. Yüksək, çoxşirkətli quraşdırmada — yeni hesab yanlış tenantda yaradılır

`backend/.../service/AuthService.java:82–93`: autentifikasiya olunmuş HR tərəfindən yaradılan hər hesab çağıranın tenantı əvəzinə `DEFAULT` tenantına yazılır. Başqa tenant HR-i DEFAULT daxilində aşağı rollu hesab yarada və signup cavabındakı tokenlə oranın məlumatlarına giriş əldə edə bilər.

Təklif: yalnız ilkin bootstrap üçün DEFAULT istifadə etmək; sonrakı signup zamanı tenantı etibarlı server kontekstindən götürmək. Şöbə/ərazi səlahiyyətlərini də müəyyən etmək. Tək tenant quraşdırmada bu konkret şirkətlərarası təsir mövcud deyil.

### 6. Orta — şifrə dəyişəndə oğurlanmış sessiya qüvvədə qalır

`backend/.../service/AuthService.java:148–181`, `backend/.../config/JwtAuthenticationFilter.java:62–89`, `backend/.../util/JwtUtil.java`: şifrə dəyişikliyi yalnız hash-i yeniləyir; token versiyası, etibarsızlaşdırma və refresh rotasiyası yoxdur. Konfiqurasiyada access token 24 saat, refresh token 7 gündür. Oğurlanmış refresh token şifrə dəyişəndən sonra da yeni access token yarada bilər. Frontend logout yalnız lokal state-i təmizləyir.

Təklif: istifadəçidə session/token versiyası və ya passwordChangedAt yoxlaması; şifrə dəyişikliyində bütün köhnə access/refresh tokenlərini rədd etmək; refresh rotasiyası və təkrar istifadənin aşkarlanması.

### 7. Yüksək təsirli quraşdırma riski — ilkin admini şəbəkədən ilk gələn şəxs qura bilər

`backend/.../config/SecurityConfig.java:43–47`, `backend/.../service/AuthService.java:127–147`: initial-setup anonimdir və əvvəlcədən verilən birdəfəlik quraşdırma sirri tələb etmir. `passwordSetupRequired` aktiv olan təzə quraşdırma LAN-a açıqdırsa, ilk sorğu göndərən admin şifrəsini seçə bilər. Tamamlanmış quraşdırmada təkrar istifadə yoxlaması və DB kilidi var.

Təklif: quraşdırmanı loopback/host təsdiqi və birdəfəlik token ilə bağlamaq; tokeni URL/loglarda saxlamamaq. Bu risk yalnız quraşdırma açıq qaldığı müddətdə tətbiq edilir.

### 8. Orta — standart yerləşdirmə HTTP istifadə edir

`docker-compose.yml`: frontend 3000 və backend 8080 bütün interfeyslərə publish olunur; `frontend/nginx.conf` yalnız HTTP dinləyir. Bu konfiqurasiya ayrıca TLS reverse proxy olmadan istifadə edilirsə, şifrə, JWT və şəxsi məlumat şəbəkədə açıq mətnlə ötürülür. Faktiki host firewall/TLS topologiyası yoxlanmayıb. Backend-ə birbaşa çıxış nginx upload limitlərini də kənardan keçməyə imkan verir; Spring limitləri ayrıca qüvvədədir.

Təklif: LAN istifadəsini saxlayan HTTPS sonlandırması və backend-i yalnız frontend/proxy üçün əlçatan etmək. LocalStorage-da JWT saxlanması (`frontend/src/store/authStore.ts`) XSS baş verərsə sessiya oğurlanmasının təsirini artırır; bu audit ayrıca XSS istismarını təsdiqləməyib.

## Asılılıq təhlükəsizliyi

Canlı npm registry sorğusu ilə `npm audit --json`: **13 paket qeydi: 9 high, 4 moderate, 0 critical**. Bu rəqəm 13 müstəqil istismar və ya 13 production boşluğu demək deyil; transitive və development zəncirləri də sayılır.

- High: Vite, minimatch, source-map-js, follow-redirects və TypeScript ESLint zənciri.
- Moderate: esbuild, postcss-selector-parser, react-router, react-router-dom.
- Vite/esbuild tapıntılarının əksəriyyəti development server/tooling şərtlərinə bağlıdır; nginx-in statik production çıxışı ilə eyniləşdirilməməlidir.
- `follow-redirects` Node tərəfindəki istifadəyə aiddir; brauzer Axios istifadəsi üçün istismar ayrıca sübut olunmayıb.
- Dəqiq registry cavabı lokal `build/security-npm-audit-2026-10-28.json` faylındadır (Git-də izlənmir).
- Backend/ISAPI üçün resolved dependency CVE skanı və container image CVE skanı tamamlanmayıb. Docker Scout əməliyyatını avtomatik icazə yoxlaması xaricə image/package metadata/SBOM ötürülməsi səbəbilə rədd etdi. İstifadəçi buna hələ icazə verməyib; heç bir dolayı üsulla davam edilmədi. Yaşa əsasən konkret CVE nəticəsi çıxarılmadı.

## Faktiki yoxlamalar

| Yoxlama | Nəticə |
| --- | --- |
| Frontend `npm run lint` | Keçdi |
| Frontend `npm run build` | Keçdi |
| `node --test frontend/tests/*.test.cjs` | 57/57 keçdi; bunlar təhlükəsizlik pentesti deyil |
| Backup operations PowerShell fixture testi | 33 assertion keçdi; Docker/SQL/foto əməliyyatları mock edildi |
| Backup-attendra PowerShell fixture testi | Sonrakı icazəli lokal icrada 70 assertion keçdi; real Docker/cihaz çağırışı yoxdur |
| `docker compose config --quiet` | Keçdi; sirlər ekrana çıxarılmadı |
| `npm audit --json` | Tamamlandı; 13 həssas paket qeydi |
| Backend test/package | Docker Maven: əvvəl fokuslanmış auth/security testləri, sonra 585/585 tam test və package keçdi; test konteyneri network=none |
| ISAPI Maven test/package | Docker Java 21/Maven test və package keçdi; network=none |
| ISAPI Gradle test | Docker Java 21/Gradle 9.4.1 ilə offline test keçdi; wrapper yalnız müvəqqəti nüsxədə LF-ə çevrildi, distributiv ayrıca endirildi |
| Docker start/image scan | İzolə backend/ISAPI start və fresh Flyway migration uğurlu; image CVE skanı üçün metadata ötürülməsi icazəsi alınmayıb |
| Dinamik autentifikasiya/tenant/SSRF sınaqları | 21 gözlənən müşahidə; 10 qoruma, 11 boşluğu təkrarlayan şərt |
| Real cihaz / real bazada dəyişiklik | Edilmədi |

## Təmir ardıcıllığı və tamamlanmamış əhatə

Əvvəl cihaz və hadisə API-lərinin tenant sərhədini, sonra SSRF/şəkil keşini və signup tenantını düzəltmək lazımdır. Ardınca sessiya ləğvi, təhlükəsiz bootstrap, HTTPS və dependency yeniləmələri gəlir. Hər düzəliş A/B tenant mock/integration testləri ilə yoxlanmalıdır. Fiziki cihaz testi bu auditin şərti deyil.

Bütün controller/service/migration/host-worker yollarının sətir-sətir formal auditi, canlı brauzer pentesti, fuzzing, yük/DoS sınaqları, quraşdırılmış host ACL/firewall yoxlaması və bütün Git tarixində secret skanı tamamlanmayıb. Buna görə “hər şey test edildi” və ya “digər boşluq yoxdur” nəticəsi çıxarmaq olmaz. Mövcud məlumatlar, runtime faylları və miqrasiyalar dəyişdirilməyib. Təmir kodu yazılmayıb və tətbiq yenidən deploy edilməyib.
