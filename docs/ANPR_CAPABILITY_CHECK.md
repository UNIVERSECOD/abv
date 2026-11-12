# ANPR imkanlarının yalnız oxuma ilə yoxlanması

ANPR kamera Cihazlar səhifəsində əlavə və ya redaktə edilib saxlanıldıqdan sonra yoxlama avtomatik açılır. Mövcud kameranın `İmkanları yoxla` düyməsi eyni yoxlamanı başladır. Nəticə kamera modeli, firmware, Bakı vaxtı ilə yoxlama tarixi və hər imkanın səbəbini göstərir. Kamera qeydiyyatı yoxlamadan əvvəl tamamlanır; yoxlama uğursuzluğu qeydiyyatı silmir. Hesabat saxlanılan firmware zəmanəti deyil, həmin yoxlamanın nəticəsidir; yenidən baxmaq üçün təkrar yoxlama aparılır.

## Nəticələrin mənası

- **Dəstəklənir:** nömrə üçün kamera ayrıca əlavə/redaktə imkanını bildirir və mövcud siyahının axtarışını qəbul edir. Arxiv üçün nümunə axtarışın strukturu və nəticəsi yoxlanır.
- **Dəstəklənmir:** tələb olunan əməliyyat və ya sahə bildirilməyib, yaxud cihaz sorğunu dəstəkləmədiyini qaytarıb. Səbəb müvafiq imkanın yanında göstərilir.
- **Təsdiqlənmədi:** əlaqə, icazə, cavab formatı və ya xidmət problemi nəticəsində qərar vermək mümkün olmayıb. Bu nəticə firmware-in uyğun olmadığını bildirmir.

Ad-soyadın yazılması ayrıca göstərilir. Təqdim olunmuş DeepinView ISAPI sənədinin 18.17.1.12 bölməsində `name` yazma sorğusuna daxildir, amma `ro` işarəsi daşıyır. Kamera bu sahəni imkanlar cavabında bildirsə də, yalnız oxuma ilə yazılacağı təsdiqlənmir. Mövcud ayrıca təsdiqli nömrə göndərmə axını yazmadan sonra oxuyub müqayisə edir; imkan yoxlaması onu çağırmır.

## Sorğular və sərhədlər

Frontend `POST /api/devices/{backendId}/anpr-capabilities` çağırır. Backend tenant və ünvan icazəsini yoxlayır, `DeviceConfig.deviceId` üzrə bridge ID-ni həll edir. Daxili `POST /api/anpr/cameras/{bridgeId}/capabilities` sorğusu API açarı, server tərəfindən qurulan `X-Allowed-Device-Ids`, gözlənilən IP/port və kamera adı ilə məhdudlaşır. İstifadəçi sorğusu sərbəst kamera URL-i qəbul etmir.

Fiziki kameraya yalnız bu oxuma sorğuları gedir:

1. `GET /ISAPI/System/deviceInfo` — model və firmware.
2. `GET /ISAPI/Traffic/channels/1/licensePlateAuditData/record/capabilities?format=json` — ayrıca nömrə əlavə/redaktəsi, sahələr və hədlər.
3. `POST /ISAPI/Traffic/channels/1/searchLPListAudit` — maksimum bir mövcud siyahı qeydi, davam səhifələri oxunmur.
4. `POST /ISAPI/ContentMgmt/search` — son beş dəqiqə, maksimum bir ANPR nəticəsi. Mövcud arxiv inteqrasiyasındakı alternativ `/ISAPI/Intelligent/vehicleRecognition/Search/channels/1` yalnız əsas sorğu açıq şəkildə dəstəklənmədikdə yoxlanır.

POST sorğuları axtarışdır. Diaqnostika PUT/DELETE, sınaq nömrəsi, toplu siyahı importu, yaddaş formatlama və ayar dəyişməsi etmir. Oxunan nömrələr hesabat cavabına çıxarılmır və bu axınla bazaya yazılmır. Standart Digest autentifikasiyası, ölçüsü məhdud cavablar və mövcud XML təhlükəsizlik qaydaları saxlanılır. Probe sorğuları üçün 5 saniyəlik HTTP timeout, bridge üçün 75 saniyəlik timeout istifadə edilir; adi arxiv və üztanıma timeout-ları dəyişmir. Eyni kamerada üst-üstə düşən yoxlama rədd edilir.

Boş arxiv axtarışı sorğunun işlədiyini göstərir, oktyabr qeydlərinin mövcudluğunu göstərmir. Tarix aralığından kənar nəticə və ya nömrə sahəsi olmayan nümunə təsdiqlənmiş ANPR arxivi sayılmır. Əvvəlki avtomatik arxiv sinxronizasiyası ayrıca işləyir; onun səhifələri bu bir qeydlik diaqnostikadan müstəqildir.

## Arxiv və davamiyyət

Arxivdə seçilən tarixlər iş günləridir. Son iş gününün gecə növbəsi üçün kamera sorğusu ertəsi təqvim gününü də oxuyur və ABV yalnız seçilən iş günlərinə aid nəticələri tətbiq edir. Kamera rolu və ya ünvanı dəyişəndən sonra həmin dəyişiklikdən əvvəlki keçidlər cari rolla avtomatik hesablanmır. Xam kamera qeydləri saxlanılır.

Kamera bildirdiyi səhifə və ümumi say faktiki nəticələrlə uyğun gəlməlidir. Canlı və arxiv keçidi birmənalı uyğun gələndə bir hadisəyə bağlanır. Davamiyyət üçün hadisənin vaxtında şirkət daxilində həmin nömrənin təyin olunduğu əməkdaş seçilir. Giriş və çıxışı kameranın rolu müəyyən edir. Oxuma balı yalnız məlumat kimi saxlanılır. Eyni nömrə eyni şirkətdə iki əməkdaşa eyni vaxtda təyin edilə bilməz.

## Yoxlama

2026-11-11: 666 backend, 69 ISAPI Maven, ISAPI Gradle və 70 frontend testi keçdi; frontend lint/build, backend/ISAPI package, Docker image build-ləri və Compose config keçdi. Mock HTTP testində Digest-in tam request target-i, yalnız oxuma sorğuları və bir qeydlik limit yoxlandı. Tenant, bridge scope, IP/port və ad uyğunsuzluğu yoxlandı. Brauzerdə avtomatik açılış, dəstəklənməyən imkan, uğursuzluqdan sonra təkrar yoxlama, mobil görünüş və üztanıma cihazının əlavə edilməsi yoxlandı. Ayrı Docker mühitində mövcud mock nömrə qeydləri dəyişmədi, kamera yazmalarının sayı sıfır qaldı. Fiziki kamera/firmware və production serverində yerləşdirmə bu yoxlamaya daxil deyil.
