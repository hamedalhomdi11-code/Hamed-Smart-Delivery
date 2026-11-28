# سجل التنفيذ والتسليم — Hamed Smart Delivery
تاريخ آخر تحديث: 2026-11-28

> هذا الملف هو سجل الاستئناف العملي للمهمة. لا يحتوي على كلمات مرور أو مفاتيح أو قيم أسرار. لا تعتبر أي مرحلة مكتملة إلا إذا كانت نتيجة الاختبار أو النشر مثبتة.

## 1. النطاق المعتمد
- المعماريات 1 و2 و3: منتهية في النسخة السابقة حسب تأكيد المستخدم؛ لا نعيد تنفيذها.
- نطاق التنفيذ الحالي: معمارية 4 (الملاحة)، معمارية 5 (الواجهة)، معمارية 6 (الحسابات)، ومعمارية الربط غير المرقمة (ChatGPT/GitHub/Render/Auth0/Cloudflare).
- التطبيقان المطلوبان:
  1. Hamed Smart Delivery — تطبيق السائق.
  2. Hamed Smart Delivery Admin — تطبيق الإدارة الخاص بالمالك.
- لا تخلط هذا المشروع مع Hamed Maps.
- لا تغيّر قاعدة الرياض أو بياناتها أو الخدمات الحالية على Render.
- لا تدمج أي فرع إلى main دون موافقة منفصلة.

## 2. خط الأساس والفروع
- خط أساس التطبيق الذي بُنيت عليه معمارية 4: `feature/0.4.3-final-integrated-gps-20261128`.
- فرع معمارية 4: `feature/architecture-4-navigation-only-20261128`.
- فرع تنفيذ العمل الحالي: `feature/implementation-4-5-6-integration-20261128`.
- فرع مواصفات معمارية 5: `docs/architecture-5-ui-spec-20261128`.
- فرع مسودة معمارية 6: `docs/architecture-6-accounts-draft-20261128`.
- فرع توثيق التكامل: `docs/integration-architecture-chatgpt-github-render-auth0-cloudflare-20261128`.
- فرع إعداد Auth0 الأولي: `feature/auth0-native-sdk-setup-20261128`.
- لا تستخدم `main` كقاعدة دمج عمياء: المقارنة أظهرت أن فرع معمارية 4 متقدم بـ244 commit ومتأخر عن main بـ50 commit، أي أن الفرعين متباعدان. اعتمدنا خط الأساس 0.4.3 بدلاً من main للحفاظ على التطبيق الأحدث.

## 3. ما نُفّذ في فرع التنفيذ الحالي
### تطبيق السائق
- أُضيف Auth0 Android SDK 4.3.1 مع إعداد Native App العام (Domain وClient ID فقط).
- أضيفت Manifest placeholders لآلية HTTPS callback التي يسجلها SDK.
- أضيفت شاشة حارس دخول تمنع عرض تجربة السائق قبل نجاح Auth0 والتحقق من الحساب من Worker.
- تخزين credentials يستخدم SecureCredentialsManager، ولا يوجد Client Secret داخل APK.
- إعداد API URL ما زال فارغاً في BuildConfig؛ يجب تعبئته بعد نشر Worker ومعرفة عنوانه الفعلي.
- لم يتم بعد تنفيذ سياسة offline 30 دقيقة أو تصريح offline موقّع.

### Cloudflare Worker وD1
- أُنشئ مجلد `worker/` وفيه package.json وtsconfig.json وwrangler.toml.
- أضيفت migration أولية للجداول: driver_accounts وadmin_devices وadmin_nonces وaudit_log.
- أضيفت نقطة `GET /health`.
- أضيفت `GET /v1/driver/session` للتحقق من Auth0 JWT باستخدام JWKS مع issuer وaudience وRS256، ثم التحقق من حالة الحساب وتواريخ الصلاحية باستخدام وقت الخادم.
- نقاط إدارة الحسابات ما زالت مقفلة وتعيد 503 حتى اكتمال تسجيل جهاز الإدارة وربط Auth0 Management API.
- database_id في wrangler.toml قيمة placeholder؛ workflow يستبدلها في بيئة التشغيل بعد العثور على D1 أو إنشائه.
- Worker وD1 لم يُنشآ أو يُنشرا في Cloudflare بعد.

### تطبيق الإدارة
- أُضيف Android module مستقل باسم `:admin`، ومعرّف التطبيق `com.hamed.smartdelivery.admin`.
- التطبيق يفتح مباشرة إلى لوحة الإدارة ولا يحتوي على شاشة تسجيل دخول تقليدية.
- أُضيفت واجهة أولية لملخص السائقين ونموذج إعداد حساب جديد ومدد الأيام/الأسابيع/الأشهر.
- عمليات الحفظ والإدارة معطلة حالياً عمداً؛ لم تكتمل بعد مصادقة الجهاز وواجهات Admin API. لا تعتبر لوحة الإدارة مكتملة وظيفياً بعد.

### GitHub Actions
- أضيف workflow لبناء واختبار تطبيق السائق وبناء APK منفصل لتطبيق الإدارة على فرع التنفيذ الحالي.
- أضيف workflow يدوي لتجهيز/إنشاء D1 وتطبيق migrations ثم نشر Worker.
- Workflow نشر Cloudflare يعمل فقط عند تشغيله يدوياً (workflow_dispatch)، ولا ينشر تلقائياً عند كل push.
- workflow يستخدم secret `CLOUDFLARE_API_TOKEN` الذي أكد المستخدم أنه أضافه، ويستخدم Account ID العام الذي زوّد به المستخدم داخل workflow. لم نتحقق من secret value ولا نطلبه.
- لا توجد أداة Cloudflare مباشرة في جلسة العمل؛ تشغيل Workflow اليدوي ومراجعة ناتجه هما الطريق المتاح لنشر الموارد.

## 4. ما لم يُنفذ أو لم يُختبر بعد
1. لم نشغّل حتى الآن Gradle tests أو build من فرع التنفيذ؛ يجب متابعة نتيجة GitHub Actions.
2. لم يتم اختبار Auth0 login على جهاز Android فعلي.
3. لم يُحدد API_BASE_URL النهائي بعد نشر Worker.
4. لم يتم إنشاء Cloudflare Worker أو D1 أو تطبيق migration على السحابة.
5. لم تُنشأ Auth0 Machine-to-Machine application ولا إعداد Management API اللازم لإنشاء/تعديل مستخدمي Auth0 من Admin API.
6. لم يتم تأكيد اسم Database Connection في Auth0 أو أن username login مفعّل عليه.
7. لم تُنفذ Admin device enrollment؛ يجب اعتماد طريقة تفعيل أولية آمنة للجهاز دون شاشة دخول تقليدية.
8. لم تُنفذ endpoints لإضافة السائقين وتعديل كلمات المرور والصلاحيات وتعطيل الحساب وإعادة تفعيله وسجل التدقيق.
9. لم تُنفذ سياسة offline 30 دقيقة وتصريح offline موقّع ومقاوم لتغيير ساعة الهاتف.
10. لم تُستكمل واجهة معمارية 5 على جميع الشاشات؛ لوحة الإدارة الحالية shell أولي، وواجهة السائق تحتاج مراجعة بصرية واختبارات.
11. لم يُختبر الصوت/الاهتزاز وGPS وإعادة التوجيه والتجمعات ميدانياً بعد تعديلات معمارية 4.
12. لا توجد روابط APK نهائية حتى يتم نجاح البناء ورفع artifacts.

## 5. متطلبات خارجية لازمة لإكمال الربط
- تأكيد أن GitHub Actions secret `CLOUDFLARE_API_TOKEN` موجود على المستودع وأن صلاحياته تشمل Workers Scripts Edit وD1 Edit.
- بعد نشر Worker: استخراج عنوان `workers.dev` الفعلي وضبط `HAMED_API_BASE_URL` في إعداد البناء ثم إعادة بناء التطبيقين.
- إنشاء Auth0 Machine-to-Machine application مخصص لـManagement API، ومنحه أقل scopes لازمة لإنشاء وقراءة وتعديل المستخدمين.
- ضبط Database Connection في Auth0 بحيث يدعم اسم المستخدم وكلمة المرور، وتحديد اسم connection الفعلي.
- توفير قيم Management API السرية في Cloudflare Worker secrets مباشرة عبر آلية آمنة؛ لا تُرسل إلى ChatGPT ولا تُحفظ في Git.
- اعتماد وسيلة ربط أولي لجهاز الإدارة. الخيار المقترح: رمز اقتران لمرة واحدة عند أول تشغيل فقط، ثم مفتاح RSA خاص محفوظ في Android Keystore وتوقيع الطلبات. هذه ليست شاشة تسجيل دخول تقليدية، لكنها تتطلب موافقة المستخدم على خطوة الاقتران الأولية.

## 6. ترتيب الاستكمال
1. مراجعة نتيجة Android CI وإصلاح أي compile/test failures.
2. تشغيل workflow اليدوي لنشر Worker وإنشاء D1 وتطبيق migration، بعد التأكد من وجود secret.
3. فحص `/health` والتحقق من Worker وD1.
4. إضافة Auth0 Management API credentials وDatabase Connection إلى Cloudflare Worker secrets.
5. تنفيذ Admin device enrollment وآلية التوقيع والتحقق من replay.
6. تنفيذ Admin API لإنشاء حساب Auth0 وربطه بجدول driver_accounts، مع حساب المدة على الخادم (الأشهر تقويمية).
7. تنفيذ API تعديل الصلاحيات والتعطيل/التفعيل وتغيير كلمة المرور وسجل التدقيق.
8. ربط تطبيق الإدارة بالـAPI، وإزالة حالة تعطيل الحفظ بعد الاختبار الأمني.
9. ربط تطبيق السائق بعنوان Worker الفعلي، والتحقق من الحساب قبل فتح وظائف التطبيق.
10. تنفيذ تصريح offline آمن لمدة أقصاها 30 دقيقة، مع استمرار الملاحة النشطة بصورة آمنة عند انقضاء المهلة ومنع بدء عمل جديد.
11. استكمال UI معمارية 5 واختبارات GPS/التجمعات/الملاحة.
12. بناء APK منفصل لكل تطبيق، اختبار التثبيت والتشغيل على الهاتف، ثم تسليم روابط artifacts.

## 7. سجل commits لفرع التنفيذ
- `893f7a27c993d7e87ec4e3c88912a94494d1ce50` — إضافة Auth0 SDK وإعداد placeholders.
- `467187cd629480720d29796645f45b1414bdb5db` — إضافة إعدادات Auth0 العامة.
- `ba01918339465a30cfb218c17d8bdd2f603fc87a` — إعداد BuildConfig لعنوان API والجمهور.
- `42811882df951b41622091babd5b1dd272d4fc17` — ربط MainActivity بحارس الدخول.
- `93fed1a2b5a874bead1fb1b0a139e2d7379e55cd` — إضافة Auth0 driver gate والتحقق من الخادم.
- `75289ee56f786b9222a6c88c4e27f77ff3983c91` — إضافة Worker package.
- `a661f5284c72c51aff218369790cba9938266e51` — إعداد TypeScript.
- `ddcad16a27999cda510aac118bb5a62cff864857` — إعداد Wrangler وD1 binding template.
- `00148ce03c9a6c564965ce9d42f3a4a555a0c863` — مخطط D1 الأولي.
- `ab8881d788dd79e6f07d93613694b8e2ac1a2733` — Worker health وdriver session API.
- `5d8d4e3475abeeff0ccb8afa13941369f06b9f0d` — تسجيل Admin module في Gradle.
- `4a7ece1bac3799ad6eb1108ba8cac753046bce8c` — إعداد بناء تطبيق الإدارة.
- `441a6a91002bf4ae89a39d25f4291600f0ca6378` — Manifest لتطبيق الإدارة.
- `530b3aa041ac60c8ca1f5ffa31ba48f01aebf542` — لوحة الإدارة الأولية.
- `39c71c40bddda1b8dead04219383ba15e6817cac` — CI لبناء التطبيقين.
- `656e3c5dd530b146032669236850cb73710f8061` — Workflow يدوي لنشر Worker وD1.
- `a484ccc5bb60943428442ff4cf937a8eb4989401` — تمرير متغير `HAMED_API_BASE_URL` إلى عملية بناء التطبيقين.
- `4acf49979996ffe5d1236be4330d5393a665f159` — جعل عنوان API لتطبيق السائق قابلاً للضبط من متغير بيئة.
- `f6d1872891c052369b1322b7e65a3d735f7c25f0` — جعل عنوان API لتطبيق الإدارة قابلاً للضبط من متغير بيئة.

## 8. أوامر الاستئناف
من جهاز مطور يملك صلاحية المستودع:
```bash
git fetch origin
git checkout feature/implementation-4-5-6-integration-20261128
git pull --ff-only
```

تشغيل فحوص Android محلياً مع JDK 17 وAndroid SDK 35:
```bash
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug :admin:assembleDebug
```

تشغيل Worker محلياً:
```bash
cd worker
npm install
npx wrangler dev
```

نشر Worker لا يتم عبر الأمر المحلي هنا؛ استخدم GitHub Actions workflow المسمى `Deploy Hamed Smart Delivery API to Cloudflare` بعد مراجعة الإعدادات. لا تنفّذ migrations على أي قاعدة أخرى غير `hamed-smart-delivery-accounts`.

## 9. الحالة عند آخر تحديث
**العمل مستمر وغير مكتمل.** آخر commit موثق عند تحديث السجل هو `f6d1872891c052369b1322b7e65a3d735f7c25f0` (تحديث Gradle لتطبيق الإدارة؛ راجع commits أدناه). أُضيف إعداد عنوان API من متغير GitHub Actions `HAMED_API_BASE_URL` إلى كلا التطبيقين. لم يتم تأكيد نتيجة البناء، ولم يتم نشر Worker أو إنشاء D1، ولم يتم دمج أي شيء إلى `main`.

## 10. متابعة التنفيذ والتحقق — 2026-11-28
- عولج خطأ Android CI المثبت في السجل: مكتبة `androidx.browser:1.10.0` تتطلب compileSdk 36؛ تم رفع `compileSdk` للتطبيقين إلى 36 مع إبقاء targetSdk=35، وتحديث تثبيت Android SDK إلى platform/build-tools 36.
- محاولات التحقق على GitHub Actions:
  - فشل البناء السابق عند `checkDebugAarMetadata` بسبب compileSdk 35، وتم تصحيح ذلك في commits اللاحقة.
  - تشغيل Android CI على commit `1de5bff79f1dee934daab87684c741437683332b` كان جارياً عند آخر مراجعة، ولم تظهر نتيجة نهائية بعد.
  - تشغيل Android CI على commit `38f43049619ef7945147f79c7fccad26fc9e5639` كان جارياً عند آخر مراجعة، ولم تظهر نتيجة نهائية بعد.
  - بناء APKين عبر `build-both-apps.yml` كان في قائمة الانتظار؛ لم تُرفع artifacts بعد.
- Worker:
  - تم تصحيح إصدار `@cloudflare/workers-types` إلى إصدار منشور `5.20261127.1`.
  - تم إصلاح provisioning في workflow ليستخدم Cloudflare D1 REST API بدلاً من خيار Wrangler غير المدعوم في الإصدار المستخدم.
  - نجح `npm install` و`npm run typecheck`.
  - تم إنشاء قاعدة D1 المخصصة `hamed-smart-delivery-accounts` وتطبيق migration `0001_initial_accounts.sql) بنجاح. UUID القاعدة: `d7cd499b-764c-460f-8ec9-b81b8b5d188a`.
  - فشل نشر Worker فقط لأن الحساب لم يسجل workers.dev subdomain بعد. يلزم تسجيل النطاق من Cloudflare Workers onboarding ثم إعادة تشغيل Workflow.
- إعداد دخول تطبيق الإدارة:
  - اعتماد المستخدم: username `Hamed` وكلمة المرور الأولية التي قدمها المستخدم، مع تذكر الجلسة بعد أول دخول.
  - لم تُضمّن كلمة المرور في المستودع أو APK؛ لم يتم بعد إنشاء endpoint تسجيل الدخول أو تخزين الجلسة المشفر.
  - Admin CRUD لا يزال مقفلاً حتى اكتمال مصادقة الإدارة وإعداد Auth0 Management API وDatabase Connection.
- لم يتم تعديل main أو قاعدة الرياض أو خدمات Render. لم يتم تسليم APK لأن البناء والتحقق ورفع artifacts لم تكتمل بعد.


## تحديث متابعة التنفيذ — 2026-11-28 (بعد إصلاحات البناء والنشر)

### نتائج مؤكدة من GitHub Actions
- تم رفع compileSdk إلى 36 في وحدتي التطبيق، مع إبقاء targetSdk كما كان.
- تم تحديث حزمة `@cloudflare/workers-types` إلى إصدار منشور متاح.
- تم استبدال محاولة استخدام `wrangler d1 list --json` (غير مدعومة) باستدعاء Cloudflare D1 REST API.
- نجح تثبيت تبعيات Worker وفحص TypeScript.
- تم إنشاء قاعدة D1 مخصصة باسم `hamed-smart-delivery-accounts`، ونجحت الهجرة `0001_initial_accounts.sql` على القاعدة البعيدة.
- فشل نشر Worker فقط لأن حساب Cloudflare لم يسجل بعد workers.dev subdomain؛ أظهر Wrangler رابط onboarding للحساب. لا يمكن نشر Worker إلى workers.dev قبل إتمام تسجيل subdomain في لوحة Cloudflare أو توفير route على نطاق مملوك.
- في تشغيل Android CI رقم `46107418527` نجح `:app:testDebugUnitTest` وبناء APK debug والتحقق من توقيعه، وتم رفع artifact `hamed-smart-delivery-0.4.3-build`. بناء Release الدائم تخطّاه workflow لعدم توفر أسرار توقيع 0.4.3، واختبار المحاكي كان لا يزال جارياً عند كتابة هذا التحديث.
- تشغيل `Build Hamed Smart Delivery Apps` رقم `46107418526` كان لا يزال ينفذ خطوة بناء/اختبار التطبيقين؛ لم نثبت بعد نجاح APK الإدارة في هذا التشغيل.

### بيانات دخول تطبيق الإدارة
- طلب المستخدم اعتماد اسم المستخدم الأولي `Hamed` وكلمة المرور `hamed1234` مع الاحتفاظ بالجلسة بعد أول دخول.
- لا يجوز تضمين كلمة المرور كنص صريح في APK أو Git. يجب تنفيذ تحقق خادمي، وتخزين مشتق آمن لكلمة المرور، وحفظ رمز الجلسة محلياً عبر تخزين Android آمن.
- واجهة الإدارة الحالية ما زالت نموذجاً أولياً؛ إنشاء حسابات السائقين وأوامر الإدارة غير مفعلة. لم يتم بعد بناء نظام تسجيل دخول الإدارة أو واجهات إدارة Auth0/السائقين.

### ما يلزم قبل إعلان الاكتمال
1. تسجيل workers.dev subdomain للحساب في Cloudflare أو توفير route صالح على نطاق يملكه المستخدم، ثم إعادة تشغيل نشر Worker والتحقق من `/health`.
2. استكمال مصادقة الإدارة بصورة آمنة، وإعداد تكامل Auth0 Management API/قاعدة مستخدمين عند الحاجة، ثم فتح عمليات إدارة السائقين بعد الاختبارات.
3. استكمال تشغيل بناء APK الإدارة والتحقق منه، واختبار APK على محاكي/جهاز.
4. إعداد مفاتيح توقيع إصدار 0.4.3 الدائمة قبل إصدار APK تحديث متوافق مع التثبيت السابق.
5. استكمال دعم offline 30 دقيقة والتحقق من انتهاء الجلسة، وعدم الادعاء بأنها منفذة حالياً.
6. عدم دمج الفرع في main أو تعديل قاعدة الرياض أو خدمات Render دون موافقة منفصلة.


## متابعة لاحقة — 2026-11-28 (بناء يشمل قاعدة الرياض)
- تم تعديل workflow الخاص ببناء التطبيقين ليحمّل أحدث artifact ناجح لقاعدة الرياض من workflow الأصلي، ويتحقق من SQLite integrity ومن وجود جداول food_density_clusters_v3 والصيدليات النشطة، ثم يضمّن قاعدة البيانات في APK السائق.
- نجح فحص وتنزيل قاعدة الرياض في تشغيل Build Hamed Smart Delivery Apps رقم 46107744668 (commit 000245db02ce8e0973268b6713cd1ac706d94839).
- عند آخر تحقق، كانت خطوة `:app:testDebugUnitTest :app:assembleDebug :admin:assembleDebug` لا تزال قيد التنفيذ؛ لم تُرفع artifacts النهائية من هذا التشغيل بعد.
- تشغيل Android CI رقم 46107744651 كان أيضاً جارياً؛ يجب انتظار اكتمال اختبار المحاكي قبل وصفه بالناجح.
- التشغيل السابق 46107418526 نجح في بناء APK السائق والإدارة ورفع artifact منفصل لكل تطبيق، لكنه سبق تضمين قاعدة الرياض في workflow؛ لذلك لا يعتمد كنسخة نهائية للسائق.
- روابط التحقق:
  - Build both apps: https://github.com/hamedalhomdi11-code/Hamed-Smart-Delivery/actions/runs/46107744668
  - Android CI: https://github.com/hamedalhomdi11-code/Hamed-Smart-Delivery/actions/runs/46107744651


## تحديث تنفيذ فعلي — 2026-11-28 (تفعيل واجهات الإدارة وربطها)

### ما أضيف إلى فرع التنفيذ
- استُبدل Worker الأولي بواجهة API تشمل:
  - دخول الإدارة `POST /v1/admin/login` باسم المستخدم وكلمة المرور، دون رمز اقتران.
  - اشتقاق كلمة مرور الإدارة PBKDF2-HMAC-SHA256 مع salt عشوائي وتخزين المشتق فقط في D1 عند أول دخول.
  - جلسات عشوائية opaque مخزنة كـSHA-256 hashes في D1، مع إلغاء الجلسة وتوسيع صلاحيتها حتى 90 يوماً من آخر نشاط.
  - تحديد معدل محاولات دخول الإدارة حسب IP (5 محاولات ثم حظر 15 دقيقة).
  - عرض السائقين، إنشاء سائق في Auth0 Database Connection وربطه بجدول D1، تعديل المدة، التعطيل/التفعيل، وإعادة تعيين كلمة مرور السائق عبر Management API.
  - تسجيل أحداث الإدارة في audit_log.
  - فحص `/health` يتضمن استعلاماً فعلياً إلى D1.
- أضيفت migrations:
  - `0002_admin_accounts.sql`: بيانات اعتماد الإدارة وجلسات الإدارة.
  - `0003_admin_login_rate_limit.sql`: سجل محاولات الدخول وتحديد المعدل.
- أُعيد بناء واجهة تطبيق الإدارة لتتصل بالـAPI، وتعرض قائمة السائقين وإنشاء الحسابات وتعديل المدة والتفعيل/التعطيل.
- تُحفظ جلسة الإدارة في Android EncryptedSharedPreferences المدعوم بـAndroid Keystore. لا توجد كلمة مرور ثابتة في APK.
- ضبط اسم الإدارة `Hamed` ومدة الجلسة المتحركة حتى 90 يوماً.
- تم ضبط عنوان Worker المتوقع افتراضياً في Gradle لتطبيقي السائق والإدارة، مع إبقاء إمكانية تجاوزه عبر متغير البيئة `HAMED_API_BASE_URL`.
- أضيفت خطوة في Workflow النشر لتمرير أسرار الإدارة وAuth0 الاختيارية من GitHub Secrets إلى Cloudflare Worker Secrets دون طباعة قيمها.
- أضيف ملف تعليمات الإعداد الخارجي `docs/ADMIN_API_SETUP_REQUIRED_AR.md`.

### Commits التنفيذ
- `b0f01a99e2eb7c7b9f30cd4484f586450e654533` — Admin API وAuth0 Management integration.
- `785607479aa622c5e7a5318e35687fc1a9355951` — migration 0002.
- `fa6449105212e5b5aa65ecf9987518979d3d313d` — ربط تطبيق الإدارة بالـAPI.
- `6e838b8d82bff02b3fe7b4141f9510abdf96e538` — rate limiting.
- `a85d514b09c90297e1a9920ae95d8dc41249edef` — migration 0003.
- `ce3ac4ccdf65dcf54a9f51dfe1768fa9921a3461` — تمرير أسرار الإدارة/Auth0 الاختيارية وقت النشر.
- `9adb95120bcd513f4af7f19d9c7bc4dfd3c83532` — إصلاح تسجيل الدخول بعد bootstrap وتحريك انتهاء الجلسة.
- `b55afb91fdc039179d7ed7fa99c0bb8a9b855deb` — إعداد 90 يوماً للجلسة.

### متطلبات لم تُنجز بعد / لا يجوز الادعاء بها
- يجب على مالك الحساب إضافة GitHub Repository Secrets التالية بنفسه: `ADMIN_INITIAL_PASSWORD` (قيمتها الأولية المعتمدة `hamed1234`)، `AUTH0_MGMT_CLIENT_ID`، `AUTH0_MGMT_CLIENT_SECRET`، `AUTH0_DB_CONNECTION`. لا نملك إمكانية إنشاء/قراءة هذه الأسرار من GitHub connector ولا نطلب كشف قيمها.
- يجب إنشاء Auth0 Machine-to-Machine Application ومنحه scopes `create:users read:users update:users delete:users`، وإعداد Database Connection يدعم username/password.
- بعد إضافة الأسرار، شغّل Workflow نشر Cloudflare API ثم تحقق من `/health` و`/v1/admin/setup`. لا يوجد تأكيد حتى الآن أن أحدث deploy نجح.
- لم تُنفذ بعد سياسة offline 30 دقيقة الموقعة والمقاومة لتغيير وقت الجهاز.
- لم يتم بعد إثبات اختبار Auth0 login على جهاز حقيقي أو اختبار إنشاء حساب من APK الإدارة end-to-end.
- لا تزال وظائف معمارية 4 والملاحة والتجمعات تحتاج اختبارات ميدانية؛ نجاح بناء APK لا يثبت سلوكها.
- لا دمج إلى main، ولا تغيير قاعدة الرياض أو Render أو Hamed Maps.

