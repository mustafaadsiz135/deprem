# DEP-app — Telefon Sensörleriyle Deprem Algılayıcı

Akıllı telefondaki **ivmeölçer** ve **jiroskop** sensörlerini kullanarak deprem sarsıntısını algılayan Android uygulaması.

## Özellikler

- Saniyede ~50 ölçüm, ekran kapalıyken de arka planda çalışır (ön plan servisi)
- **STA/LTA** algoritması (sismologların deprem dalgası yakalamak için kullandığı klasik yöntem)
- **Jiroskop ile yanlış alarm eleme**: telefon elde/cepte/taşınırken algılama duraklar
- **Süre + süreklilik kontrolü**: kapı çarpması, ayak sesi gibi kısa darbeler elenir
- Canlı sismograf grafiği (X, Y, Z eksenleri)
- Alarm: siren sesi + titreşim + sesli uyarı ("Dikkat! Deprem algılandı. Çök, kapan, tutun.") + bildirim
- Tahmini **Mercalli şiddeti** (Wald ve diğ., 1999 bağıntısı)
- Her olayın verisi **CSV** olarak kaydedilir (Excel'de grafiği çizilebilir)
- 5 kademeli hassasiyet ayarı
- **Deprem Simülasyonu** butonu: jüri/sınıf sunumunda gerçek deprem beklemeden gösterim
- Türkçe / İngilizce (telefonun diline göre)

## APK nasıl alınır (GitHub Actions)

1. GitHub'da yeni bir depo oluşturun (ör. `deprem-algilayici`).
2. Bu klasördeki **tüm dosyaları** (gizli `.github` klasörü dahil) depoya yükleyin.
   - Web'den yüklerken `.github` klasörü görünmeyebilir. Bu durumda depoda
     **Add file → Create new file** ile `.github/workflows/build-apk.yml` adında dosya açıp
     içeriğini yapıştırın.
3. **Actions** sekmesinde "APK Derle" iş akışı otomatik çalışır (~3-5 dk).
4. Bitince iş akışına tıklayın → en altta **Artifacts → DEP-app-apk** indirin, zip'ten APK çıkar.
5. APK'yı telefona atıp kurun ("bilinmeyen kaynaklara izin ver" gerekebilir).

Android Studio ile açıp doğrudan da derlenebilir.

## Kullanım ve test

1. Telefonu **düz ve sabit bir yüzeye** (tercihen yere) koyun → **İzlemeyi Başlat**.
2. ~8 saniye kalibrasyon → durum yeşil "İzleniyor".
3. Testler:
   - **Deprem Simülasyonu**: yapay P + S dalgası eklenir, ~3-4 sn içinde alarm vermeli.
   - **Masa sallama deneyi**: telefon masadayken masayı 3-4 saniye sürekli sallayın → alarm.
   - **Yanlış alarm denemeleri**: masaya tek vuruş, kapı çarpması, yanında yürüme, telefonu eline alma → alarm **vermemeli**.
4. Pil optimizasyonu bazı telefonlarda servisi kapatabilir: Ayarlar → Uygulamalar → DEP-app → Pil → **Kısıtlama yok**.

## Algoritma (kısaca)

| Adım | Ne yapılıyor | Neden |
|---|---|---|
| 1 | Alçak geçiren filtre ile yerçekimi çıkarılır | Sadece titreşim kalsın |
| 2 | STA (0,5 s) / LTA (10 s) oranı hesaplanır | Ani artış = sarsıntı başladı |
| 3 | Jiroskop dönme hızı > 0,25 rad/s ise duraklat | İnsan hareketi deprem değildir |
| 4 | Sarsıntı ≥ 1-2 s sürmeli, tepe/RMS oranı < 5, örneklerin ≥ %60'ı aktif | Darbeleri ele |
| 5 | En yüksek ivmeden Mercalli şiddeti tahmini | Şiddeti yorumlamak |

Hassasiyet seviyeleri (`QuakeDetector.java`):

| Seviye | Min. ortalama ivme | STA/LTA eşiği | Min. süre |
|---|---|---|---|
| 1 | 0,30 m/s² | 6,0 | 2,0 s |
| 2 | 0,18 m/s² | 5,0 | 1,5 s |
| 3 (varsayılan) | 0,10 m/s² | 4,0 | 1,2 s |
| 4 | 0,06 m/s² | 3,5 | 1,0 s |
| 5 | 0,04 m/s² | 3,0 | 0,8 s |

## CSV kayıtları

Konum: `Android/data/io.github.mustafaadsiz135.depapp/files/kayitlar/`
Sütunlar: `t_s, lin_x, lin_y, lin_z, lin_mag, gyro_rad_s, sta_lta` (olaydan önceki ~80 saniye dahil).
Simülasyon kayıtlarının adı `_sim` ile biter.

## Sınırlamalar

- Resmi bir erken uyarı sistemi **değildir**; yalnızca telefonun bulunduğu yerdeki sarsıntıyı algılar.
- Telefon sensörleri hassas sismometre değildir; zayıf (hissedilmeyen) depremleri algılayamaz.
- Telefonun bulunduğu mobilya sarsıntıyı büyütebilir veya azaltabilir; şiddet tahmini kabadır.
- Alarm sesi ve titreşim telefonu da salladığından, olay süresi ölçümü birkaç saniye uzun çıkabilir.

## Geliştirme fikirleri

- Birden çok telefonun aynı anda algılamasını bir sunucuda birleştirmek (ağ tabanlı erken uyarı)
- Konum + zaman ile AFAD/Kandilli kayıtlarıyla karşılaştırma
- Frekans analizi (FFT) ile makine titreşimlerini ayırma
- Teachable Machine / TensorFlow Lite ile CSV verisinden sarsıntı sınıflandırma modeli
