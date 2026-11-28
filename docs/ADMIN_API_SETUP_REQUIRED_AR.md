# تهيئة الإدارة وربط حسابات السائقين — Hamed Smart Delivery

هذه الخطوات هي المتطلبات الخارجية المتبقية لتشغيل واجهة الإدارة بعد نشر Worker. لا ترسل أي كلمة مرور أو Client Secret أو API Token إلى المحادثة.

## 1) أسرار GitHub المطلوبة للنشر

من مستودع GitHub افتح Settings → Secrets and variables → Actions → New repository secret، وأضف:

| الاسم | القيمة |
|---|---|
| `ADMIN_INITIAL_PASSWORD` | كلمة المرور الأولية التي اعتمدها المالك (تُدخل مباشرة في GitHub Secret فقط) (كلمة المرور الأولية التي اعتمدها مالك المشروع) |
| `AUTH0_MGMT_CLIENT_ID` | Client ID لتطبيق Auth0 من نوع Machine-to-Machine |
| `AUTH0_MGMT_CLIENT_SECRET` | Client Secret للتطبيق نفسه |
| `AUTH0_DB_CONNECTION` | اسم Auth0 Database Connection الذي يسمح بتسجيل الدخول باسم المستخدم وكلمة المرور |

لا تضف أسراراً في ملفات Git أو Gradle أو APK. Workflow النشر يمرر هذه القيم مباشرة إلى Cloudflare Worker Secrets ولا يطبع قيمها في السجلات. اسم مستخدم الإدارة مضبوط على `Hamed` في إعداد Worker.

بعد أول دخول ناجح، يخزن Worker مشتق كلمة المرور بخوارزمية PBKDF2-HMAC-SHA256 مع salt عشوائي في D1. يمكن بعد ذلك إزالة سر `ADMIN_INITIAL_PASSWORD` من GitHub ثم حذف Worker Secret يدوياً؛ تبقى بيانات الدخول المعتمدة في D1.

## 2) إنشاء Auth0 Management API application

1. افتح Auth0 Dashboard → Applications → Applications → Create Application.
2. اختر Machine to Machine Applications.
3. اختر Auth0 Management API.
4. امنح أقل الصلاحيات اللازمة:
   - `create:users`
   - `read:users`
   - `update:users`
   - `delete:users` (يستخدم فقط للتراجع عن إنشاء مستخدم إذا فشل ربطه بقاعدة التطبيق).
5. احفظ Client ID وClient Secret في GitHub Secrets بالأسماء الموضحة أعلاه. لا ترسلهما في المحادثة.

## 3) إعداد Database Connection

1. من Auth0 Dashboard افتح Authentication → Database.
2. اختر اتصال قاعدة بيانات يدعم Username-Password.
3. فعّل تسجيل الدخول باسم المستخدم إذا كان الاتصال يتطلب تفعيل خيار username.
4. استخدم اسم الاتصال الفعلي في السر `AUTH0_DB_CONNECTION`.
5. تأكد أن Native Application الخاص بتطبيق السائق يسمح باستخدام هذا الاتصال.

## 4) نشر Worker وتطبيق migrations

بعد حفظ GitHub Secrets، افتح Actions وشغّل workflow:
`Deploy Hamed Smart Delivery API to Cloudflare`
على الفرع:
`feature/implementation-4-5-6-integration-20261128`

الـworkflow يكتشف قاعدة D1 المخصصة `hamed-smart-delivery-accounts`، ويطبق migrations بالتسلسل، ويضبط الأسرار الاختيارية ثم ينشر Worker. لا تنشئ قاعدة أخرى ولا تستبدل قاعدة الرياض.

## 5) فحص التشغيل

افتح:
`https://hamed-smart-delivery-api.hamedalhomdi11.workers.dev/health`

المطلوب:
- HTTP 200
- `ok: true`
- `database: "connected"`

ثم افتح:
`https://hamed-smart-delivery-api.hamedalhomdi11.workers.dev/v1/admin/setup`

المطلوب بعد إعداد الأسرار:
- `adminBootstrapConfigured: true` قبل أول دخول (بعد إزالة السر يصبح false، لكن تسجيل الدخول يستمر إذا كان سجل الإدارة موجوداً).
- `auth0ManagementConfigured: true`
- `pairingCodeRequired: false`

## 6) أول دخول للإدارة

افتح تطبيق الإدارة:
- اسم المستخدم: `Hamed`
- كلمة المرور الأولية: كلمة المرور الأولية التي اعتمدها المالك (تُدخل مباشرة في GitHub Secret فقط)

يُنشأ سجل الإدارة في D1 عند أول دخول ناجح، ويُحفظ رمز الجلسة على الهاتف باستخدام Android Keystore وEncryptedSharedPreferences. الجلسة تمتد حتى 90 يوماً من آخر استخدام، مع إمكانية إلغائها من زر الخروج.

## 7) حدود التشغيل التي يجب عدم تجاوزها

- إنشاء حساب السائق يتم في Auth0 Database Connection ثم يربط بمعرّف Auth0 في D1.
- مدة الصلاحية تُحسب من ساعة الخادم، والأشهر تقويمية.
- تعطيل/تفعيل السائق يحدّث Auth0 وD1 معاً.
- تغيير مدة الصلاحية يتم على الخادم.
- لا توجد رموز اقتران؛ دخول الإدارة باسم المستخدم وكلمة المرور فقط.
- العمل دون إنترنت لمدة 30 دقيقة لم يُفعّل بعد؛ لا تعتبره جاهزاً حتى تنفيذ تصريح offline موقّع واختباره.
- لا تعتبر النسخ Debug APK إصداراً نهائياً موقعاً.
