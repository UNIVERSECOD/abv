---
name: attendra-device-import-resolve
description: Preserve and develop the Attendra plan for resolving the same person with different employee numbers across devices during import. Use for this import identity workflow, its implementation planning, or its handoff to another chat.
---

# Attendra cihaz idxalında şəxslərin uyğunlaşdırılması

## Qərarın vəziyyəti

2026-11-01 tarixində istifadəçi bu fikirlərin saxlanmasını istədi. **Funksiya hələ tətbiq edilməyib.** İstifadəçi açıq şəkildə «hələ əlavə etmə, planlaşdıraq» deyib. Bu fayl plan və kontekst ötürülməsidir; özü implementasiya və ya cihaz əməliyyatı üçün icazə deyil. Sonrakı açıq istifadəçi tapşırığı bu vəziyyəti dəyişə bilər.

İstifadəçinin iki cihazı var. Böyük ehtimalla hər ikisində eyni adamlar var, amma terminal nömrələri fərqli ola bilər; istifadəçi nömrələrin eyni olub-olmadığını hələ bilmir. İstək: proqram mümkün uyğunluğu göstərsin və «bu adamı birləşdirək, ya yox?» soruşsun.

## Hansı layihədir

- Müştəri variantı: private `https://github.com/UNIVERSECOD/abv` repository-si. Əsas `hr_erp_attendra` repository-sini bu plan əsasında avtomatik dəyişməyin.
- Bu söhbətdə mənbə nüsxəsi `D:/Projects/hr_erp_attendra/build/abv` qovluğundadır. Başqa kompüterdə checkout yerini ayrıca müəyyənləşdirin.
- Proqramın görünən adı **Attendra** olmalıdır; interfeysə ABV əlavə etməyin. ABV repository/image/Compose üçün daxili addır.
- Standart Tabelin mövcud qaydası qorunur: 07:20 → 7, 07:30 → 7, 07:31 → 8; aylıq cəm gündəlik yuvarlaq saatların cəmidir. Bu plan saat hesablamasını dəyişmir.
- Əvvəl repository-nin `AGENTS.md`, `docs/CODEX_HANDOFF.md` və cari mənbəni oxuyun. Aşağıdakı mövcud davranış 2026-11-01-də yoxlanmış mənbəni təsvir edir; gələcəkdə kodla yenidən təsdiqləyin.

## İndiki import necə işləyir

- Eyni ərazidə skan edilən cihazlarda eyni `employeeNo` və uyğun ad varsa, bir əməkdaş yaradılır və hər iki cihazın əlaqəsi bağlanır.
- Eyni `employeeNo`, amma ziddiyyətli adlar varsa, konflikt qeyd olunur və həmin qrup ötürülür.
- Eyni adam fərqli `employeeNo` ilədirsə, ad uyğunluğu onu avtomatik birləşdirmir. İki ayrı əməkdaş yarana və davamiyyət/tabel ayrı hesablana bilər.
- Mövcud əməkdaş tapıldıqda təkrar yaratmaq əvəzinə çatışmayan cihaz/ərazi əlaqələri əlavə oluna bilər. Ərazilərarası avtomatik uyğunluq da nömrə və ada əsaslanır.
- `writeToOtherBranchDevices` indiki API-də verilməyəndə `true` sayılır; indiki UI-də də aktiv başlayır. Buna görə adi import təkcə oxuma əməliyyatı deyil: şəxsi və mövcud profil şəklini digər ərazi cihazlarına da yaza bilər. Nömrələr fərqlidirsə, yanlış/təkrar cihaz qeydləri riski var.

## Müzakirə olunmuş gələcək istifadəçi axını

1. İstifadəçi ərazi və cihazları seçir. Skan yalnız cihaz məlumatlarını oxuyur; bu mərhələdə əməkdaş yaradılmır və cihazlara yazılmır.
2. Proqram mümkün uyğunluqları göstərir. Nümunə: cihaz 1-də `1001 — Əli Məmmədov`, cihaz 2-də `502 — Əli Məmmədov`. Cihaz adı, terminal nömrəsi və şəxsin adı görünür; mövcud əməkdaş namizədləri ayrıca göstərilə bilər.
3. İstifadəçi hər şübhəli qrup üçün seçir:
   - **Birləşdir**: bu cihaz qeydlərini bir əməkdaşa bağla; mövcud əməkdaş da axtarılıb seçilə bilər.
   - **Ayrı saxla**: fərqli əməkdaşlar kimi saxla.
   - **Sonra qərar ver**: bu qeydləri indilik import etmə.
4. Ad yalnız namizəd təklif etmək üçündür. Ada görə səssiz avtomatik birləşdirmə etməyin. Eyni adlı fərqli adamlar ola bilər. Seçilməmiş şübhəli qeydləri avtomatik «ayrı saxla» saymayın.
5. Tətbiqdən əvvəl yekun göstərilir: yeni əməkdaşların, mövcud əməkdaşa bağlanacaq cihaz qeydlərinin və gözləyən qeydlərin sayı. İstifadəçi təsdiqləyir.
6. Cihazdakı nömrələr dəyişdirilmir. Proqram bir əməkdaş üçün cihaz üzrə fərqli nömrələri saxlayır; bütün uyğunlaşdırılmış cihazların gələcək davamiyyəti həmin əməkdaşın tabelinə düşür.

İlk mərhələ üçün təklif: **yeni import zamanı cihaz qeydlərinin uyğunlaşdırılması**. Proqramda artıq iki ayrı əməkdaş kimi yaradılmış şəxslərin keçmiş tarixçəsini birləşdirmək ayrıca məsələdir; istifadəçi bunu bu söhbətdə tapşırmayıb. Mövcud təkrar əməkdaşı sadəcə silmək düzgün həll deyil.

## Implementasiyadan əvvəl həll ediləcək texniki məqamlar

- `Employee.id` lokal PK, `Employee.employeeId` HR kodu, `Employee.deviceEmployeeNo` isə indiki əsas terminal nömrəsidir. `DeviceConfig.id` lokal cihaz PK-sı, `DeviceConfig.deviceId` ISAPI bridge ID-sidir; bunları qarışdırmayın.
- İndiki əsas əməkdaş sahəsi tək terminal nömrəsi saxlayır. Təkcə təsdiq pəncərəsi əlavə etmək kifayət etmir: tenant + cihaz + terminal nömrəsini bir əməkdaşa bağlayan model lazımdır. `EmployeeDeviceAccess` əlaqəsində və ya ayrıca xəritə cədvəlində necə saxlanacağı hələ seçilməyib.
- Bir tenant/cihaz/terminal nömrəsi birdən çox əməkdaşa bağlana bilməməlidir. Köhnə əsas nömrə və cihaz əlaqələri geriyə uyğun işləməlidir; lazım olsa yalnız yeni additive Flyway migration əlavə edin.
- Uyğunlaşdırmanı oxuyan bütün yolları nəzərdən keçirin: directional və combined davamiyyət, jurnal adları, cihaz əməkdaş sinxronizasiyası, profil/üz şəkli, reconciliation və işdən çıxarılma/silmə növbələri. Yalnız importu dəyişib qalan yolları əsas nömrə ilə buraxmaq səhv şəxsin oxunmasına/yazılmasına səbəb ola bilər.
- Preview seçimlərini serverdə yenidən yoxlayın: tenant sahibliyi, cihaz/ərazi aidiyyəti, mövcud nömrə konflikti, arada dəyişmiş skan və eyni vaxtda iki import. Preview tokeni, snapshot və qərarların dəqiq API forması hələ layihələndirilməyib.
- Lokal əməkdaş/əlaqə saxlanması cihaz yazılarından asılı distributed transaction-a çevrilməməlidir. Cihaz yazısı ayrıca və açıq təsdiqlə olmalıdır; qismən uğursuzluq lokal məlumatı itirməməlidir.
- Raw access event-ləri, mövcud əməkdaş tarixçəsini, fotoşəkilləri, qrafikləri, təyinatları və digər müştəri məlumatlarını qoruyun. Köhnə davamiyyətin avtomatik yenidən hesablanması/birləşdirilməsi bu planın təsdiqlənmiş hissəsi deyil.

## Baxılacaq mənbələr

Repository kökünə görə yollar:

- `frontend/src/pages/DeviceEmployeeImportPage.tsx`
- `frontend/src/api/setupImportApi.ts`
- `backend/src/main/java/com/hic/controller/SetupImportController.java`
- `backend/src/main/java/com/hic/dto/DeviceEmployeeImportDTO.java`
- `backend/src/main/java/com/hic/service/HikDeviceUserImportService.java`
- `backend/src/main/java/com/hic/model/EmployeeDeviceAccess.java`
- `backend/src/main/java/com/hic/repository/EmployeeDeviceAccessRepository.java`
- `backend/src/main/java/com/hic/repository/EmployeeRepository.java`
- `backend/src/main/java/com/hic/service/AttendanceLogSyncService.java`
- `backend/src/main/java/com/hic/service/DoorAttendanceSyncService.java`
- `backend/src/main/java/com/hic/service/CombinedDeviceAttendanceService.java`
- `backend/src/main/java/com/hic/service/DeviceReconciliationService.java`
- `backend/src/main/java/com/hic/service/IsapiEmployeeUserSyncService.java`
- `backend/src/main/java/com/hic/service/EmployeeFaceDeviceSyncService.java`
- `backend/src/main/java/com/hic/service/EmployeeDeviceRemovalService.java`
- Müvafiq testlər: `HikDeviceUserImportServiceTest`, `SetupImportControllerTest`, `AttendanceLogSyncServiceTest`, `DoorAttendanceSyncServiceTest`, `CombinedDeviceAttendanceServiceTest` və cihaz yazısı servis testləri.

Sonrakı testlərdə eyni nömrə/eyni ad, fərqli nömrə/eyni adam, eyni ad/fərqli adam, nömrə konflikti, qərarı ertələmə, tenant sərhədi, təkrar import, köhnə təyinatların qorunması və hər iki cihazdan bir əməkdaşa düzgün davamiyyət yoxlanmalıdır. Testlər mock/sintetik məlumatla işləməli, real cihazlara qoşulmamalıdır. Fiziki cihaz yazısından əvvəl bridge ID, IP və adı read-only sorğu ilə yoxlamaq və istifadəçidən açıq icazə almaq repository tələbidir.

## Başqa çatda davam etmək

İlk cavabda vəziyyəti qısa izah edin: hazırda plan saxlanıb, funksiya tətbiq edilməyib. İstifadəçi yalnız planı müzakirə edirsə, implementasiyaya başlamayın. İstifadəçi sonradan tətbiq etməyi tapşırarsa, əvvəl cari mənbəni yoxlayıb məlumat modelini və təsdiq axınını konkretləşdirin. Məxfi cihaz məlumatlarını, real şifrələri və müştəri qeydlərini bu skill-ə əlavə etməyin.
