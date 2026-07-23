# pano-plugin-social-login — İnceleme Raporu

> Tarih: 2026-05-20 · Kapsam: backend (Kotlin/Vert.x) + frontend (Svelte SSR) uçtan uca,
> OAuth güvenlik analizi ve `pano-plugin-premium-login` ile karşılaştırma.

## TL;DR (Karar)

Plugin **sağlam ve mimari olarak `premium-login` ile tutarlı**. OAuth güvenlik temelleri
**doğru** kurulmuş: sunucu-tarafı tek-kullanımlık CSRF `state`, X için PKCE, yalnızca
**doğrulanmış e-postaya** güvenme, hesaba bağlamada **sahiplik kanıtı** (parola/oturum), ve
panel/public uçlarda **client secret sızıntısı yok**. "Kritik bir güvenlik açığı" yok.

Bulunan şeyler ağırlıkla **sağlamlaştırma (hardening) ve UX** kalemleri. Tek "gerçekten ele
alınmalı" olan: hassas uçların **rate-limit tier**'ı. Geri kalanlar küçük/iyileştirme.

| # | Önem | Konu |
|---|------|------|
| 1 | **Orta-Yüksek** | `link-existing` (parola doğrular) gevşek `PUBLIC_API` rate-limit tier'ında, `AUTH` değil → brute-force |
| 2 | Orta | `state`/pending-token tüketimi ve "zaten bağlı" kontrolü atomik değil (pooled, transaction yok); DB unique key kurtarıyor ama yarış → 500 |
| 3 | Orta | Kullanıcı silinince sosyal bağlar temizlenmiyor (`deleteByUserId` kullanılmıyor, delete-user listener yok) → yetim satırlar unique slotu kilitler |
| 4 | Düşük | `PENDING_REGISTRATION` satırları proaktif temizlenmiyor (yalnızca yeni OAuth init süpürüyor) |
| 5 | Düşük | `LicenseGuard.assert` hiçbir route'ta çağrılmıyor (premium çağırıyor) — yalnızca premium dağıtılırsa DRM eksik |
| 6 | Düşük | Boş string secret ile el-yapımı PUT kayıtlı secret'ı silebilir |
| 7 | Düşük | GET init uçları (`/auth`, `/link`) demo modda gate'lenmiyor; provider enable mid-flow yeniden kontrol edilmiyor |
| 8 | Kozmetik | Ölü kod (`SocialLoginFailed`, `deleteByUserId`); panelde karışık Svelte 4/5 idiom; iki panel write'ta `getUsernameFromUserId(...)!!` |

---

## 1. Mimari ve Akışlar (Flows)

**DB tabloları** (`db/impl/`):
- `<prefix>social_login_link` — `id, userId, provider, providerUserId, email, displayName, avatarUrl, linkedAt`;
  UNIQUE `(provider, providerUserId)` ve `(userId, provider)` (`SocialLoginLinkDaoImpl.kt:22-34`).
- `<prefix>social_login_state` — `id, token, kind, payload, expiresAt`; UNIQUE `token`
  (`SocialLoginStateDaoImpl.kt:22-30`). `kind` ∈ {`OAUTH_STATE`, `PENDING_REGISTRATION`}.

Tarayıcı callback'i (`GET /api/social-login/:provider/callback`, `SocialCallbackAPI.kt:23`)
yalnızca SSR sayfasına 302 atar; ağır iş (`code`→token→profil) `POST /api/social-login/process`
içinde yapılır.

**(a) Yeni kullanıcı — sosyal giriş**
1. `GET /api/social-login/:provider/auth` (`SocialAuthInitAPI.kt:27`): provider enabled mı bakar,
   gerekiyorsa PKCE verifier üretir, `OAUTH_STATE` satırı oluşturur (intent=`auth`), authorize URL döner.
2. Tarayıcı → provider → callback → `/social-login/callback` (SSR) → `POST .../process` (`SocialProcessAPI.kt:42`).
3. `handleAuth`: bağ yok + e-posta eşleşmiyor → `PENDING_REGISTRATION` satırı, `action="register"`.
4. `/social-login/register` → `POST .../register` (`SocialRegisterCompleteAPI.kt:36`): pending token tüketir,
   `RegisterUtil` ile kullanıcı oluşturur, (sosyal e-posta doğrulanmış ve eşitse) e-postayı verified işaretler,
   `social_login_link` ekler, oturum açar.

**(b) Mevcut bağlı kullanıcı — giriş**
`handleAuth`: `(provider, providerUserId)` ile bağ bulunur → `loginUser` (`SocialLoginService.kt:139`):
**ban kontrolü**, auth token + CSRF cookie, last-login güncelle. `action="login"`.

**(c) "Bu e-postayla hesap var" → girişle bağla**
`handleAuth`: bağ yok ama `emailVerified && isEmailExists` → `action="account-exists"` (e-posta maskeli).
Kullanıcı `/social-login/link` → `POST .../link-existing` (`SocialLinkExistingAPI.kt:35`): pending tüketir,
Pano **parolasıyla authenticate**, **e-posta eşitliği şartı** (`EmailMismatch`), zaten-bağlı kontrolü, bağ ekler, giriş.

**(d) Ayarlardan bağlama (oturum açıkken)**
`GET /api/social-login/:provider/link` (`SocialLinkInitAPI.kt:28`, `LoggedInApi`) → `OAUTH_STATE` (intent=`link`,
payload'da userId). Callback → `handleLink` (`SocialProcessAPI.kt:113`): **userId state'ten değil oturumdan**
alınır; başka kullanıcıya bağlıysa `SocialAccountAlreadyLinked`; aynı provider'ın eski bağı silinir, yenisi eklenir.

**(e) Bağ kaldırma**
- Kullanıcı: `POST /api/social-login/unlink` (`SocialUnlinkAPI.kt:29`, `LoggedInApi`).
- Admin: `POST /api/panel/social-login/player/:username/unlink` (`PanelUnlinkPlayerSocialAPI.kt:31`, `PanelApi`),
  `ManageSocialLinksPermission` + `UnlinkedSocialAccountLog`.

**(f) Admin config güncelle**
`PUT /api/panel/social-login/config` (`PanelUpdateSocialLoginConfigAPI.kt:30`, `PanelApi`),
`ManageSocialLoginSettingsPermission` + alan-alan merge + `UpdatedSocialLoginSettingsLog`.

---

## 2. Güvenlik Analizi (doğru kurgulanmış kısımlar)

- **CSRF `state`**: `CSRFTokenGenerator.nextToken()*2`, sunucuda `social_login_state`'te saklanır,
  10 dk TTL, consume'da silinir + doğrulanır (`SocialLoginService.kt:65-99`). `provider` state'e bağlı,
  `link` akışında oturum kullanıcısıyla eşleştirilir.
- **PKCE**: yalnız X (`TwitterOAuthService.kt:18`), S256 doğru (`PkceUtil.kt`); verifier state payload'ında.
  Diğer 6 sağlayıcı confidential client + secret → PKCE gereksiz, kabul edilebilir.
- **Redirect URI**: yalnız sunucu config'inden (`redirectBaseUrl` ya da `websiteUrl`) kurulur,
  request Host'una **güvenilmiyor** → host-spoof/open-redirect yok (`SocialLoginService.kt:57-61`).
  Callback hedefi sabit göreli path. Giriş sonrası `returnUrl` parametresi **yok** (açık-yönlendirme yüzeyi yok;
  "geldiğin yere dön" özelliği de yok — işlevsel eksik, güvenlik değil).
- **Profil güvenilirliği**: eşleşme her zaman `(provider, providerUserId)` ile, asla e-posta ile değil.
  "account-exists" yalnız `emailVerified == true` ise tetiklenir (`SocialProcessAPI.kt:167`). Her sağlayıcı
  `emailVerified`'ı savunmacı doldurur (Google/Discord verified flag; GitHub verified primary; X/Instagram e-posta döndürmez).
  Kayıt sırasında yeni hesabın e-postası ancak sosyal e-posta doğrulanmış **ve** kullanıcının girdiğiyle eşitse verified yapılır.
- **Hesap ele geçirme koruması**: mevcut hesaba bağlama parola (`link-existing`) ya da oturum (`link`) ister;
  sosyal hesap sessizce başkasının hesabına bağlanamaz (`SocialAccountAlreadyLinked`).
- **Secret'lar**: public uç yalnız enabled provider key'leri döner (`GetSocialLoginConfigPublicAPI.kt:30-34`);
  panel GET yalnız `hasSecret` + `clientId` döner, secret döndürmez (`PanelGetSocialLoginConfigAPI.kt:59`);
  UI yalnız yeni secret yazıldığında gönderir.

---

## 3. Bulgular (detay + öneri)

### 1 — Hassas uçlar yanlış rate-limit tier'ında (Orta-Yüksek)
Platform rate-limiter'ı **path-prefix tabanlı**: yalnız `/api/auth/` → `AUTH` (10 istek/6s);
gerisi `else -> PUBLIC_API` (500 istek/100ms) (`Pano/.../util/RateLimitManager.kt:70-83, 31-40`).
Plugin uçları `/api/social-login/...` altında olduğundan **`PUBLIC_API`** tier'ına düşüyor.
Özellikle `link-existing`, `authProvider.authenticate(usernameOrEmail, password)` çağırıyor
(`SocialLinkExistingAPI.kt:87`) → platformun kendi login'i gibi **brute-force throttle edilmiyor**.
`register` ve `process` de aynı durumda.
**Öneri:** platform `RateLimitManager.getTierForPath`'e `/api/social-login/` → `AUTH` eşlemesi eklensin
(en temizi), ya da plugin kendi başına bu uçlara IP-bazlı throttle koysun.

### 2 — `state`/pending tüketimi ve "zaten bağlı" kontrolü atomik değil (Orta)
`consumeOAuthState`/`consumePendingRegistration` = `getByToken` + `deleteByToken` ayrı iki
autocommit statement (paylaşılan pool, transaction yok — `SocialLoginService.kt:93-99, 125-131`).
İki eşzamanlı istek aynı token'ı tüketebilir. Benzer şekilde `SOCIAL_ACCOUNT_ALREADY_LINKED`
check-then-act. **Doğruluğu DB unique key kurtarıyor** (`unique_provider_account`), ama bu SQL hatası
`handleLink`/`link-existing`/`register`'da yakalanmadığından kaybedilen yarış kullanıcıya **ham 500**
olarak yansır. Güvenlik sorunu değil, UX/tutarlılık.
**Öneri:** consume'u tek tx/atomik delete-returning ile yap; insert'lerde unique-violation'ı yakalayıp
`SocialAccountAlreadyLinked` döndür.

### 3 — Kullanıcı silinin​ce sosyal bağlar kalıyor (Orta)
`SocialLoginLinkDao.deleteByUserId` tanımlı ama **hiç çağrılmıyor**; kullanıcı-silme event listener'ı yok.
Silinen kullanıcının `(provider, providerUserId)` satırı unique slotu kilitler → o sosyal hesap
gelecekte başka kullanıcıya **bağlanamaz**; ayrıca veri-saklama (PII e-posta) artığı.
**Öneri:** bir delete-user event listener ekleyip `deleteByUserId` çağır.

### 4 — `PENDING_REGISTRATION` proaktif temizlenmiyor (Düşük)
`deleteExpired` yalnız `createOAuthState` içinde çağrılıyor (`SocialLoginService.kt:79`);
`createPendingRegistration`'da yok. (deleteExpired tüm kind'leri sildiği için sonunda temizleniyor ama
yalnız yeni bir OAuth init olursa.) **Öneri:** pending oluştururken de `deleteExpired` çağır veya periyodik temizlik.

### 5 — `LicenseGuard.assert` route'larda kullanılmıyor (Düşük; yalnız premium ise)
Lisans yalnız `onStart`/`verifyLicense`'ta kontrol ediliyor (`SocialLoginPlugin.kt:31,39`);
`LicenseGuard.assert(plugin)` hiçbir handler'da çağrılmıyor (premium `PanelGetPlayerPremiumStatusAPI.kt:37`'de çağırıyor).
`LicenseGuard`'ın amacı ("tek bir kontrolü silmek yetmesin") boşa çıkıyor. **Free template olduğu sürece zararsız**
(gömülü key yokken no-op). Premium yapılırsa kritik handler'lara serpiştir.

### 6 — Boş secret ile silme (Düşük)
Merge `body.getString(key, current)` kullanıyor; gövdede secret için açık `""` gönderilirse kayıtlı secret **silinir**
(`PanelUpdateSocialLoginConfigAPI.kt`). Resmi UI bunu yapmaz; el-yapımı PUT yapabilir. **Öneri:** boş string'i "değiştirme" say.

### 7 — Demo/enable kenar durumları (Düşük)
GET init uçları (`/auth`, `/link`) demo modda `Api.isAllowedInDemo` ile bloklanmaz (GET serbest) → demo'da gerçek
provider redirect URL'i döner. `register`/`link-existing`/`unlink` provider enable durumunu yeniden kontrol etmez
(yalnız mid-flow disable senaryosunda önemli).

### 8 — Kozmetik
Ölü kod: `SocialLoginFailed` (`SocialLoginErrors.kt:9`) hiç fırlatılmıyor; `deleteByUserId` kullanılmıyor.
Panel bileşenleri Svelte 4 idiom (`export let`, `$:`, `on:click`), tema sayfaları Svelte 5 runes — karışık.
`getUsernameFromUserId(...)!!` iki panel write yolunda force-unwrap.

> Not: OAuth exchange hatalarının `Successful({error})` (HTTP 200) ile dönmesi premium-login ile **aynı**
> konvansiyon (SSR callback sayfası bunu bekliyor), bu yüzden bulgu değil.

---

## 4. premium-login Karşılaştırması

`premium-login` Minecraft **premium hesap doğrulama + otomatik giriş** plugini (Microsoft/Mojang OAuth +
MC-sunucu/FastLogin entegrasyonu). Farklı problem; ama her ikisi de "alternatif giriş yöntemi" plugini.

**Tutarlı / eşleşen konvansiyonlar (must-match):** lisans client/guard dosyaları (neredeyse birebir aynı),
lifecycle wiring, gradle key-embedding, `PluginConfig(version)`, `Api/LoggedInApi/PanelApi`, validation/error
idiomları, `@Dao @Lazy @Scope` DAO + `PluginDatabaseManager` init/uninstall, `SetupEventListener`,
`@PermissionDefinition`/`PanelPermission`, `PluginActivityLog`, `plugins.<id>.*` i18n, tüm `pano.ui.*` kayıt
yüzeyi ve **SSR `<script module> export async function load(event)` deseni**. → **social-login hepsine uyuyor.**

**social-login'in premium'a göre EKSİĞİ:**
- **Route içi `LicenseGuard.assert` çağrıları yok** (yukarıda #5). Tek anlamlı konvansiyon boşluğu; sadece premium dağıtımında önemli.
- `db/migration/` yok — **gerekmiyor** (premium'unki eski yanlış log-type'ı yeniden adlandıran tek seferlik migration'dı; social'ın sınıf adları zaten doğru). Bug değil.
- Parola-korumalı "reveal secret" ucu/UI yok — **bilinçli**; social secret'ı hiç döndürmüyor, daha güvenli.
- Pano-store release/publish wiring yok — **bilinçli** (free template).
- MC-sunucu/FastLogin/mail modülleri yok — **domain farkı**.

**social-login'in DAHA İYİ yaptıkları:** gerçek OAuth güvenliği (sunucu-tarafı tek-kullanımlık CSRF state,
PKCE, doğrulanmış-e-posta gating) — premium stateless Base64 state kullanıyor; girişte **ban kontrolü**
(premium'un Microsoft auto-login yolunda yok); temiz config helper'ları; 7 sağlayıcı için iyi soyutlanmış
`OAuthProvider`/registry; secret'ı panele asla döndürmemesi.

**Sonuç:** social-login, premium-login ile **mimari olarak tutarlı ve birçok yerde daha sağlam**. "premium gibi
olması gerekir mi?" → must-match konvansiyonların hepsini zaten karşılıyor; tek gerçek boşluk premium ise
`LicenseGuard.assert` kullanımı. Diğer farklar domain/feature-scope farkı, eksiklik değil.

---

## 5. Öneri (eğer aksiyon alınacaksa, öncelik sırası)
1. Rate-limit: `/api/social-login/` (en azından `link-existing`/`register`/`process`) `AUTH` tier'ına.
2. Kullanıcı-silme listener'ı + `deleteByUserId`.
3. consume + insert'lerde atomiklik/unique-violation yakalama → dostça hata.
4. Pending kayıtların periyodik temizliği.
5. (Premium yapılacaksa) kritik handler'lara `LicenseGuard.assert`.
6. Kozmetik temizlik (ölü kod, boş-secret koruması).
