# Ekran Çevirici

Ekrandaki herhangi bir alanı seçip yazıları **yerinde** çeviren Android uygulaması (HarmonyOS 4.x tabletlerde çalışır).

## Nasıl çalışır
1. Uygulamayı aç, **BAŞLAT**'a bas.
2. İlk seferde "Diğer uygulamaların üzerinde göster" iznini aç, sonra "ekranı kaydetme / yayınlama" onayını ver.
3. Uygulama kapanır, ekranda mavi yuvarlak buton kalır (sürükleyip kenara yapıştırabilirsin).
4. Butona dokun → **Hangi dilden / Hangi dile** seç (OTO, EN, DE, ES, PT, TR).
5. **Alan Seç ve Çevir** → çevirmek istediğin yeri parmağınla çiz.
6. Seçtiğin alandaki yazıların üstüne çevirileri yazılır. Kapatmak için çeviriye dokun.
7. Tamamen kapatmak: menüde **Uygulamayı kapat** ya da bildirimdeki **Kapat**.

## Teknik
- Yazı okuma: ML Kit Text Recognition (cihaz içi, Google Play Hizmetleri gerekmez)
- Çeviri: çevrimiçi (Google Translate uç noktası, olmazsa MyMemory); internet gerekir
- Ekran görüntüsü: MediaProjection

## APK'yı derlemek
- **GitHub ile:** Bu klasörü bir GitHub deposuna yükle. Actions otomatik derler; APK **Releases** sayfasına `EkranCevirici.apk` olarak gelir.
- **Android Studio ile:** Klasörü aç → Build → Build APK(s). APK: `app/build/outputs/apk/debug/app-debug.apk`

## Huawei / HarmonyOS notları
- Ayarlar → Uygulamalar → Uygulama başlatma → Ekran Çevirici: **Manuel yönet** yapıp üç seçeneği de aç (yoksa sistem arka planda kapatabilir).
- APK'yı kurarken "bilinmeyen kaynaklardan yükleme" iznini vermen gerekir.
