# Door Butik

टेलर / बुटीक ऐप — मॉडल पर कपड़ा बदलकर देखिए, नाप लीजिए, ऑर्डर कीजिए।

## फ़ाइलें
- `index.html` — पूरा ऐप
- `sys1.js`, `sys2.js` — सिस्टम 2/3 के स्टाइल (index.html के साथ उसी फ़ोल्डर में)
- `supabase/schema.sql` — डेटाबेस की टेबलें (Supabase में पहले ही लग चुकी हैं)
- `.github/workflows/build-apk.yml` — GitHub पर अपने आप APK बनाता है

## वेब लाइव (GitHub Pages)
Settings → Pages → Branch `main` / root → Save।

## APK
फ़ाइलें अपलोड होते ही Actions टैब में "Build APK" चलता है (5–8 मिनट)।
बाद में **Releases** में `Door Butik APK` खोलकर `app-debug.apk` डाउनलोड कीजिए।

## Supabase
Project URL: https://goyvhgpfxjpxkytpeigr.supabase.co
टेबलें `db_` नाम से शुरू होती हैं (Committee Manager वाले प्रोजेक्ट में साथ रहती हैं)।
