# Suruhaja — Project Rules

Ride-hailing: customer app (`Suruhaja/`), driver app (`suruhajadriver/`), web admin (`D:\suruhhaja\web`). Satu Firebase project `suruhaja-7c341`.

## Build
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && ./gradlew assembleDebug

## Rules (immutable)
- Tarif Rp1.200/km, min Rp7.000 (Firestore rules: price>=7000 & distanceKm>0).
- Split fee: driver 80% / owner 20%.
- QRIS merchant GoPay STATIS — JANGAN konversi dinamis (memutus notif PayHook).

## Konvensi
- SELALU re-read file sebelum patch — user sering edit manual antar sesi, jangan asumsikan state.
- Snapshot listener correctness-critical: MetadataChanges.INCLUDE + skip isFromCache (server-only).
- Field numerik data class wajib @JvmField (toObject default 0 tanpa itu).

## Detail
Arsitektur lengkap: C:\Users\andri\AndroidStudioProjects\DOCS.md
