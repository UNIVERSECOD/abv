# Backup yaratma, endirmə və bərpa

## İstifadə

MCG tətbiqində **Parametrlər → Backup** bölməsinə baş ofis administratoru ilə daxil olun.

- **Backup yarat və endir**: backend və ISAPI qısa müddətə dayandırılır, uyğun məlumat surəti alınır, eyni konteynerlər/xidmətlər yenidən başladılır. Hazır ZIP avtomatik endirilir; **Hazır backupu endir** təkrar endirməyə imkan verir.
- **Backupdan bərpa et**: Attendra ZIP seçilir və yoxlanır. Önizləmədə tarix, fayl sayı, ölçü və konfiqurasiya barədə məlumat göstərilir. Təsdiq qutusu seçilmədən bərpa başlaya bilməz.
- Bərpa əvvəl ayrıca müvəqqəti bazalarda yoxlanır. **Cari vəziyyətin tam backupu yaradılıb backup qovluğuna saxlanılmadan cari bazalar dəyişdirilmir.**
- Bərpa və sağlamlıq yoxlaması uğursuz olarsa əvvəlki bazalar, fotolar və konfiqurasiya geri qaytarılır. Geri qaytarma da alınmazsa əvvəlki bazalar/safety ZIP saxlanılır, worker yeni bərpa qəbul etmədən dayanır və administrator müdaxiləsi tələb olunur.
- Bərpa zamanı `/backup-progress` səhifəsi JWT açarından asılı olmayan, yalnız status oxuyan 24 saatlıq təsadüfi token istifadə edir. Proses bitəndə backupdakı hesab/şifrə ilə yenidən daxil olun. Backend dayansa və ya brauzer bağlansa da host worker davam edir.

Bu, məlumat və konfiqurasiya backupudur; proqramın kodu/Setup paketi ayrıca saxlanmalıdır. Daha yeni Flyway versiyasından olan baza cari tətbiqə bərpa edilmir.

## Tərkib və konfiqurasiya

Format 2 ZIP aşağıdakıları saxlayır:

- `hic_backend.dump` və `hic_isapi.dump` — tam PostgreSQL custom-format bazaları;
- `faces/` — əməkdaş fotoları;
- `.env.backup` — Docker `.env` və ya Windows `native.json` məzmunu;
- mövcud backup parametrləri, Docker port override-ı, Compose və native Caddy konfiqurasiyalarının surətləri;
- yaradılma vaxtı, quraşdırma rejimi, Flyway versiyası və hər faylın SHA-256 hash-i olan `manifest.json`.

Tətbiqin JWT/şifrələmə/ISAPI açarları backupdan bərpa edilir. Bu hostun quraşdırma yolları, portları, PostgreSQL bağlantı şifrəsi, CORS/host ünvanı, Compose project adı və backup təyinat qovluğu saxlanılır. Arxivdəki Compose/Caddy surətləri istinad üçündür; yüklənmiş fayldan konteyner əmrləri və ya quraşdırma yolları icra edilmir. Beləliklə başqa kompüterdəki köhnə yollar/portlar cari quraşdırmanı pozmur. Docker bərpası mövcud image ID-ləri saxlayır, image çəkmir/build etmir.

Docker və native arxivləri eyni rejimə bərpa olunur. Köhnə gündəlik backup qovluğunu da bir kök qovluqla ZIP edib yükləmək olar: iki dump, `.env.backup`, foto tərkibini göstərən uğurlu manifest və əvvəlki struktur tələb olunur. Köhnə arxivdə hash olmadıqda dump/configuration yoxlaması və müvəqqəti bazaya bərpa yoxlaması tətbiq edilir. Yalnız etibar edilən mənbədən backup seçin.

ZIP upload maksimum **4 GiB**, açılmış tərkib maksimum **20 GiB**, bir fayl maksimum **10 GiB**, maksimum **100,000** entry-dir. Yol traversal-ı, linklər, təkrar/naməlum yollar, Windows cihaz adları və uyğunsuz hash-lər rədd edilir. Açılma zamanı yazılmış faktiki baytlar da limitlənir.

Arxiv məxfi bazalar və açarlar daşıyır; ZIP özü şifrələnmiş deyil. Serverdə iş qovluğu və seçilmiş backup qovluğundakı ayrıca `attendra-operation-<UUID>` qovluğu Windows ACL ilə məhdudlaşdırılır. Endirilmiş faylı da qorunan yerdə saxlayın. Backup təyinatının Windows ACL dəstəyi və worker hesabına yazma icazəsi olmalıdır.

Manual ZIP-lər və bərpadan əvvəlki safety ZIP-lər avtomatik 183 günlük təmizləməyə daxil edilmir. Əvvəlki/failed bazalar da avtomatik silinmir; yoxlanmış ehtiyat nüsxədən sonra administrator onları ayrıca idarə edə bilər. Gündəlik backupun mövcud saxlanma qaydaları dəyişmir.

## Host worker

Backend yalnız autentifikasiya edilmiş sorğunu `runtime/backup-operations/<UUID>` növbəsinə yazır. Docker socket-i web prosesinə verilmir. Windows worker sabit əməliyyatları icra edir; arxivdən skript/əmr işə salmır. Tam sistem backupu başqa tenantın məlumatını HR istifadəçisinə verməmək üçün yalnız bir şirkətli lokal quraşdırmada açıqdır.

Docker host-da:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/install-backup-worker.ps1 -ProjectRoot D:\Projects\hr_erp_attendra
```

Worker həmin quraşdırma yoluna görə ayrıca Scheduled Task kimi qeydiyyata alınır və istifadəçi girişində başlayır. Task qeydiyyatı Windows tərəfindən bloklanarsa Docker üçün cari istifadəçinin Startup shortcut-u istifadə edilir. Native installer worker-i SYSTEM hesabı ilə startup task kimi quraşdırır; bu yol administrator hüququ tələb edir. Native Setup build-i yeni worker skriptlərini paketə daxil edir.

Gündəlik backup və manual/restore əməliyyatları ortaq `backup-host.lock` istifadə edir. Worker ayrıca instance lock saxlayır. Dayandırılma/reboot zamanı yarımçıq database swap planı aşkarlanarsa əvvəlki vəziyyət yeni işlərdən əvvəl bərpa edilir. Standart backup PostgreSQL və frontend-i dayandırmır, tətbiq image-lərini dəyişmir.

**Cihaz təhlükəsizliyi:** bərpa `runtime/device-removal-paused.json` saxlayaraq köhnə fiziki cihaz silmə növbəsinin avtomatik icrasını dayandırır. Marker yalnız hədəflər bridge ID/IP/ad ilə read-only yoxlandıqdan və fiziki yazma üçün ayrıca istifadəçi icazəsi alındıqdan sonra administrator tərəfindən idarə edilməlidir. Backup/bərpa mexanizmi terminala yazma əmri göndərmir.

## Yoxlama

- 531 backend testi, 11 frontend testi, lint və Docker frontend/backend build keçdi.
- PowerShell 5.1 və 7-də hər birində 33 fixture yoxlaması; mövcud gündəlik backupun hər versiyada 70 regresiya yoxlaması keçdi.
- Şəbəkəsiz, müvəqqəti PostgreSQL/saxta foto volume-u ilə 13 real dump/restore yoxlaması keçdi: iki baza, fotolar, tətbiq açarları, avtomatik safety ZIP və simulyasiya edilmiş açılış xətasında əvvəlki vəziyyətə geri qayıtma.
- Ayrı sintetik UI backend/statusları ilə yaratma, avtomatik endirmə, önizləmə/təsdiq və JWT-dən asılı olmayan nəticə səhifəsi yoxlandı. UI sınağı real müştəri bazasına restore etmədi.
- Canlı Docker quraşdırmasında yalnız CREATE worker axını keçdi; arxivin bütün fayl hash-ləri, konfiqurasiya tərkibi və tətbiqin yenidən açılması yoxlandı. HR/istifadəçi/qrafik/cihaz/məzuniyyət sayları, foto sayı və mövcud `.env` hash-i qorundu.
- Real müştəri bazasına restore və fiziki cihaz yazması aparılmayıb. Native konfiqurasiya birləşdirməsi fixture-lərlə yoxlanıb; native SCM/service/reboot ilə tam bərpa və yeni Setup EXE bu tapşırıqda canlı yoxlanmayıb. ISAPI kodu dəyişməyib, ayrıca ISAPI test dəsti təkrar işə salınmayıb.
