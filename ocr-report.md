
─── android/app/src/main/cpp/hand_jni.cpp:10-10 ───
[bug · high] The global `net` is read in `nativeLogits` and reassigned (delete + new) in
`nativeLoad` with no synchronization. If `nativeLoad` runs while an extractor is in flight, or two
loads run concurrently, `net` can be deleted while still in use (use-after-free) or the pointer can
be observed half-updated (data race). Protect all accesses to `net` with a `std::mutex` (or make
load atomic via an immutable shared_ptr swap).



─── android/app/src/main/cpp/hand_jni.cpp:51-52 ───
[bug · high] `net` is assigned before `load_param`/`load_model` succeed, and the error paths return
without resetting it. A failed load therefore leaves `net` non-null but unloaded; a subsequent
`nativeLogits` passes the `net == nullptr` guard and calls into an uninitialized net, which can
crash. Load into a local temporary and only publish it to the global (or reset it to nullptr) after
both calls succeed.



─── android/app/src/main/cpp/hand_jni.cpp:45-45 ───
[maintainability · medium] `net` is a raw owning pointer managed by hand (`new` here, `delete`
below) with no unload hook or destructor, so it leaks if the process/class isn't reloaded and isn't
exception-safe. Prefer `std::unique_ptr<ncnn::Net>` (e.g. a function-local static or a file-scope
unique_ptr) so ownership and teardown are automatic.



─── android/app/src/main/cpp/hand_jni.cpp:62-62 ───
[bug · high] Both `GetFloatArrayElements` and `NewFloatArray` can return null on OOM. The returned
pointer/array is used unconditionally (`memcpy` for `data`, `SetFloatArrayRegion` for `result`),
which can dereference a null pointer or pass a null array and crash. Check each JNI return value for
null before use and bail out or guard accordingly.



─── android/app/src/main/cpp/hand_jni.cpp:70-70 ───
[bug · medium] The output layout is assumed to be single-precision float: `count` is computed as
`w*h*c` and `out.data` is blindly cast to `const jfloat*`. This ignores `out.elemsize`,
`out.elempack`, and `out.type`, so an fp16/bf16/packed or non-float output would produce wrong
results or an out-of-bounds read/over-copy. Validate `out.elemsize == 4 && out.elempack == 1` (and
correct `count`, e.g. via `out.total()`) before copying.



─── android/app/src/main/cpp/hand_jni.cpp:44-44 ───
[bug · medium] If `GetStringUTFChars` returned null, `root` is null but `ReleaseStringUTFChars` is
still invoked with it, which is invalid JNI usage. Only release the string when `root != nullptr`.



─── android/app/src/main/cpp/rime_jni.cpp:21-22 ───
[bug · high] `GetStringUTFChars` can return `nullptr` (OOM, or a null/invalid `jstring`), which
leaves a pending JNI exception. The code neither checks for `nullptr` nor clears the exception.
Worse, `ReleaseStringUTFChars(shared_dir, nullptr)` / `ReleaseStringUTFChars(user_dir, nullptr)` is
later invoked unconditionally, which violates the JNI contract and is undefined behavior. Also a
null `shared`/`user` is stored into `traits` and passed to librime. Guard against null before
proceeding and only release non-null pointers.

      const char *shared = env->GetStringUTFChars(shared_dir, nullptr);
      const char *user = env->GetStringUTFChars(user_dir, nullptr);
+     if (shared == nullptr || user == nullptr) {
+         if (shared != nullptr) env->ReleaseStringUTFChars(shared_dir, shared);
+         if (user != nullptr)   env->ReleaseStringUTFChars(user_dir, user);
+         return JNI_FALSE;
+     }


─── android/app/src/main/cpp/rime_jni.cpp:34-38 ───
[bug · high] The return values of `setup`, `initialize` and `start_maintenance` (all `bool` failure
indicators) are ignored, so `ok` is unconditionally set to `JNI_TRUE`. The Kotlin caller will
believe Rime initialized successfully even when deployment/initialization failed. Aggregate the
boolean results.

-         api->setup(&traits);
-         api->initialize(&traits);
-         api->start_maintenance(True);
+         bool setup_ok = api->setup(&traits);
+         bool init_ok = api->initialize(&traits);
+         bool maint_ok = api->start_maintenance(True);
          api->join_maintenance_thread();
-         ok = JNI_TRUE;
+         ok = (setup_ok && init_ok && maint_ok) ? JNI_TRUE : JNI_FALSE;


─── android/app/src/main/cpp/rime_jni.cpp:83-83 ───
[bug · medium] The `std::string` operations between `get_context` and `free_context` can throw
(`std::bad_alloc`). If that happens, `api->free_context(&context)` is skipped and the context owned
by the session leaks (and the exception would unwind across the JNI boundary, which is undefined).
Ensure `free_context` is always executed, e.g. by guarding the buffer with an RAII scope guard or a
try/catch that frees before rethrowing/returning.



─── android/app/src/main/cpp/rime_jni.cpp:31-34 ───
[maintainability · medium] `nativeSetup` has no guard against being invoked more than once: repeated
calls will re-run `setup`/`initialize`/`start_maintenance` on an already-initialized engine without
any `finalize`/reset in between, which can leak resources or corrupt state. Consider tracking an
initialized flag (or exposing/using a proper finalize path) and returning early / bailing out when
already set up.



─── android/app/src/main/cpp/rime_jni.cpp:67-67 ───
[bug · low] `jlong session` is cast to `RimeSessionId` with only a `== 0` check. `RimeSessionId` is
a signed/pointer-sized id; negative or arbitrary values from the Java side are passed straight to
librime. Consider validating that the session is positive (and, if the value space requires it,
range-checking before the cast) so invalid ids are rejected at the boundary.



─── backend/public/admin.css:1-1 ───
[security · medium] This `@import` pulls a third-party stylesheet from fonts.googleapis.com at CSS
parse time. It is a render-blocking external request and also leaks the visitor's IP to Google, and
it will silently fail/be blocked if the admin UI runs offline or under a strict CSP
(`style-src`/`font-src`). Prefer self-hosting the fonts via `@font-face`, or at minimum
document/allowlist this dependency explicitly.



─── backend/public/admin.css:181-181 ───
[bug · medium] `.entry` is defined with three columns (`92px 1fr auto`) at the base level, but this
breakpoint declares only two (`64px 1fr`). The `.stamp` element therefore becomes an auto-placed
third grid item and gets pushed onto a new implicit row (or shifts the layout) instead of staying at
the right edge. Explicitly account for the stamp here, e.g. keep the `auto` track or reorder it via
`grid-template-areas`.



─── backend/public/admin.css:92-92 ───
[bug · medium] Setting `outline: none` on the form controls removes the only visible focus
indicator, and no `:focus-visible` alternative is provided anywhere in the file. This breaks
keyboard accessibility on the login form (WCAG 2.4.7). Replace it with a visible focus style, e.g.
change the bottom border/box-shadow on `:focus-visible`.



─── backend/public/admin.css:177-177 ───
[bug · medium] This grid fixes the first column at `180px` and the last at `48px`, but no breakpoint
ever adjusts it. On narrow viewports the label/bar/value columns will squeeze or overflow
horizontally (the `.usage` container has no `min-width: 0`/`overflow` handling either). Use relative
sizing such as `minmax(0, 180px) 1fr 48px` or add an entry for it in the `@media (max-width: 860px)`
block.



─── backend/public/admin.css:21-21 ───
[maintainability · low] Hardcoded color literals (`#f7f1e6`, `#e9decc` here; `#fbf6ee` in `.ticket`;
the repeated `rgba(27,20,15,...)` overrides elsewhere) are mixed in next to the token-based palette
in `:root`. This defeats the purpose of the custom properties — theme changes or a dark-mode variant
would require hunting literal values across multiple rules. Move these into named variables.



─── backend/public/admin.css:170-170 ───
[style · low] The disabled/draft state tones are very low contrast: `.stamp.off` uses 0.35/0.45
alpha ink and `.meta` uses 0.72 alpha, which falls near/below the WCAG AA 4.5:1 threshold for small
text on the paper background. Consider darkening these state colors or increasing font-size/weight
so the status remains legible.



─── harmony/entry/src/main/ets/common/ImeHub.ets:9-11 ───
[bug · high] Multiple async/sync calls lack error handling:
ImeHub.insertTextSync/deleteBackwardSync, InputMethodService.createPanel chain,
ContentRead.readPack, Skin.ets preference helpers, EntryAbility.loadContent, and Index.ets
aboutToAppear/doLogin/startAbility. Wrap these in try/catch to avoid unhandled rejections/exceptions
and provide a friendly fallback.

      if (ImeHub.client !== undefined) {
+       try {
-       ImeHub.client.insertTextSync(text)
+         ImeHub.client.insertTextSync(text)
+       } catch (e) {
+         // handle editor rejection / stale client gracefully
+       }
      }


─── harmony/entry/src/main/ets/common/ContentRead.ets:57-61 ───
[bug · high] Both readLexicon in ContentRead.ets and the lexicon read in Keyboard.ets.aboutToAppear
perform a blocking synchronous file read on the UI thread and lack error handling. Wrap in try/catch
and consider moving the read off the main thread or caching it.

+   try {
-   let bytes = context.resourceManager.getRawFileContentSync('base-lexicon.json')
+     let bytes = context.resourceManager.getRawFileContentSync('base-lexicon.json')
-   let decoder = util.TextDecoder.create('utf-8')
+     let decoder = util.TextDecoder.create('utf-8')
-   let text = decoder.decodeToString(bytes)
+     let text = decoder.decodeToString(bytes)
-   let parsed = JSON.parse(text) as LexiconFile
+     let parsed = JSON.parse(text) as LexiconFile
-   return parsed.entries
+     return parsed && parsed.entries ? parsed.entries : []
+   } catch (err) {
+     return []
+   }


─── harmony/entry/src/main/ets/common/ContentSync.ets:100-104 ───
[bug · high] These I/O operations lack try/finally, leaking resources on error and leaving
exceptions unhandled. In ContentSync.ets, the file write in downloadAssets and the HTTP calls in
postJson/getJson leak file descriptors/HTTP clients; in Keyboard.ets, pasteImage leaks
file/source/pixel. Wrap in try/finally to release resources and handle errors.

+     if (response.responseCode !== 200) {
+       client.destroy()
+       continue
+     }
      let bytes = response.result as ArrayBuffer
      let file = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY)
+     try {
-     fs.writeSync(file.fd, bytes)
+       fs.writeSync(file.fd, bytes)
+     } finally {
-     fs.closeSync(file)
+       fs.closeSync(file)
-     client.destroy()
+       client.destroy()
+     }


─── harmony/entry/src/main/ets/common/ContentSync.ets:21-25 ───
[bug · high] The sync response is used without validation: payload['user'] is dereferenced without a
check (c-25), items/since/epoch are trusted without validation (c-26), a falsy responseSince
triggers a full resync (c-27), and serverVersion is persisted without validation (c-28). Validate
all fields before mutating local state.

    let token = payload['token'] as string
-   let user = payload['user'] as Record<string, string>
    if (!token) {
      return payload['error'] as string || '登录失败'
    }
+   let user = payload['user'] as Record<string, string> | undefined
+   let displayName = (user && user['displayName']) ? user['displayName'] : username


─── harmony/entry/src/main/ets/common/ContentRead.ets:43-47 ───
[bug · high] Both queueUsage in ContentRead.ets and the usageQueue clearing in ContentSync.ets
perform read-modify-write on persisted preferences without atomicity. c-9 can drop events due to
overlapping calls, and c-31 clears the queue regardless of send success, causing duplicates or loss.
Serialize writes through a promise chain and only remove successfully delivered entries.

    let raw = await pref.get('usageQueue', '[]') as string
    let queue = JSON.parse(raw) as UsageEvent[]
    queue.push({ itemId: itemId, surface: surface })
    await pref.put('usageQueue', JSON.stringify(queue))
    await pref.flush()


─── harmony/entry/src/main/ets/InputMethodExtensionAbility/InputMethodService.ets:16-16 ───
[maintainability · medium] User-facing strings, colors, and dimensions are hardcoded throughout the
project (Skin.ets, Legal.ets, Keyboard.ets, Index.ets, and panel size in InputMethodService.ets).
Move them to $r('app.string.*') and $r('app.color.*') resources to support i18n and theme switching.



─── harmony/entry/src/main/ets/pages/Index.ets:101-101 ───
[maintainability · medium] Multiple ForEach calls lack a key generator (in Index.ets for
legalTitles, phrases, and skins; in Keyboard.ets for rows and panelItems). This can cause incorrect
diffing and stale UI when lists change. Provide a stable unique key for each.

          ForEach(legalTitles, (title: string) => {


─── harmony/entry/src/main/ets/InputMethodExtensionAbility/InputMethodService.ets:13-20 ───
[bug · high] In InputMethodService.ets, the inputStart listener and created panel are not retained,
so onDestroy cannot unregister the listener or tear down the panel. Store them and clean up in
onDestroy to avoid leaks and stale client references.



─── harmony/entry/src/main/ets/common/Syllables.ets:5-10 ───
[performance · medium] Several hot-path functions use linear scans instead of precomputed lookup
structures: hasSyllable and isSyllablePrefix in Syllables.ets, and mixSpellOptions in
MixedPinyin.ets. Build Sets/Maps once at module load and use O(1) lookups.

-   for (let i = 0; i < SYLLABLES.length; i++) {
-     if (SYLLABLES[i] === text) {
-       return true
-     }
-   }
-   return false
+   return text.length > 0 && SYLLABLE_SET.has(text)


─── harmony/entry/src/main/ets/common/Syllables.ets:13-16 ───
[bug · low] Inconsistent normalization: Syllables.ets exported helpers assume pre-normalized input
(c-35), PinyinEngine.ets candidates compares non-lowercased titles/aliases (c-53), and associate
compares a non-lowercased word (c-55). Normalize both sides before comparison to avoid missed
matches.

  export function isSyllablePrefix(text: string): boolean {
-   if (!text) {
+   const key = text.trim().toLowerCase()
+   if (!key) {
      return false
    }


─── harmony/entry/src/main/ets/common/Skin.ets:73-76 ───
[bug · medium] Skin ids are not properly validated in Skin.ets: the `owned` string parsing (c-14)
only checks `isNaN`, accepting empty/negative/float tokens, and `applySkin` (c-15) accepts any
numeric id without checking it exists in `skins`. Validate that each id is a non-negative integer
and exists in the skin table before mutating or persisting.

-     let n = Number(parts[i])
-     if (!Number.isNaN(n)) {
+     let part = parts[i].trim()
+     if (part.length === 0) {
+       continue
+     }
+     let n = Number(part)
+     if (Number.isInteger(n) && n >= 0 && ids.indexOf(n) < 0) {
        ids.push(n)
      }


─── harmony/entry/src/main/ets/common/ImeHub.ets:6-6 ───
[maintainability · medium] `context` is declared but never read or written anywhere in this file —
it is dead code as written. Either remove it, or (if a bind/unbind/reset step was intended) add a
static release/reset method that clears both `client` and `context` so a stale client cannot survive
a session teardown and send edits to a disconnected input client.



─── harmony/entry/src/main/ets/common/ImeHub.ets:8-8 ───
[bug · low] `insert` forwards an arbitrary string with no emptiness/length validation. Validate the
input (e.g. skip empty strings and cap the length) before pushing text into the editing session to
avoid submitting invalid or unexpectedly large text to the engine.

    static insert(text: string): void {
+     if (text === undefined || text.length === 0) {
+       return
+     }


─── harmony/entry/src/main/ets/common/ContentRead.ets:68-70 ───
[bug · medium] The lazy-init guard is fragile: it returns early whenever `nextWords` is already
non-empty, so if `nextWords` was populated by any path that did not also fill `charPrior`,
`charPrior` will stay empty forever. Track initialization with a dedicated boolean flag instead, and
initialize both tables together.

-   if (nextWords.size > 0) {
+   if (sideTablesLoaded) {
      return
    }
+   sideTablesLoaded = true


─── harmony/entry/src/main/ets/common/ContentRead.ets:64-65 ───
[maintainability · medium] `charPrior` and `nextWords` are exported as mutable module-level `let`
bindings, i.e. shared global state. Any importer can reassign them, and `loadSideTables` mutates
them in place, making the cache hard to reason about and prone to inconsistent state across pages.
Consider exporting read-only accessors (e.g. functions or `ReadonlyMap`) and keeping the maps
private.



─── harmony/entry/src/main/ets/common/Skin.ets:39-43 ───
[maintainability · low] `skinById` silently returns `skins[0]` for any unknown id. This masks data
corruption or logic errors (e.g. a persisted skin id that no longer exists) and shows the user an
unintended theme instead of a detectable problem. Consider logging/reporting the unknown id (or
throwing) so the fallback is not silent.



─── harmony/entry/src/main/ets/entryability/EntryAbility.ets:6-6 ───
[maintainability · low] `onCreate` is overridden but empty and its `_want` parameter is never used,
making it effectively dead code. Remove the empty override unless initialization logic is actually
intended here.



─── harmony/entry/src/main/ets/entryability/EntryAbility.ets:3-3 ───
[maintainability · low] Inconsistent module import styles: `UIAbility`/`window` come from the legacy
`@ohos.*` modules while `Want` comes from the `@kit.*` kit. Verify both resolve against the target
SDK; prefer a consistent import style to avoid type/resolution mismatches.



─── harmony/entry/src/main/ets/common/Legal.ets:0-0 ───
[bug · high] Fallthrough returns the wrong document: any title that does not exactly match one of
the four explicit strings (typo, renamed title, empty string, or undefined input) silently falls
through to the third-party information sharing text. Legal disclosures must never be substituted
silently — an unrecognized title should fail loudly instead of rendering the wrong policy. Consider
an explicit branch or throw for unknown titles.



─── harmony/entry/src/main/ets/common/Legal.ets:1-2 ───
[maintainability · medium] Title literals are duplicated: the same strings are hardcoded in
legalTitles and again in every title === '...' comparison inside legalBody. A rename or typo in one
place breaks the mapping with no compile-time error and silently triggers the fallthrough branch
above. Define the titles once (e.g. a const enum or union type) and reference those constants in
both places.



─── harmony/entry/src/main/ets/common/Legal.ets:9-9 ───
[maintainability · medium] A single shared updated constant is used as the effective date for all
five documents, so changing the date for one policy implicitly changes it for all others. Each
policy typically has its own effective date; store the date per document instead of one global
constant.



─── harmony/entry/src/main/ets/common/ContentSync.ets:90-93 ───
[bug · high] `assetId` comes from the server and is concatenated directly into the local path. A
value containing `../` (e.g. `../../files/other`) escapes the assets directory and can overwrite
arbitrary files (path traversal). Sanitize/reject unsafe ids before building the path.

+     if (assetId.includes('/') || assetId.includes('\\') || assetId.includes('..')) {
+       continue
+     }
      let path = dir + '/' + assetId
      if (fs.accessSync(path)) {
        continue
      }


─── harmony/entry/src/main/ets/common/Syllables.ets:1-1 ───
[maintainability · low] Building the syllable table by `split(' ')` on a single template literal is
fragile: any future edit that introduces a leading/trailing space, a newline, or a double space
silently appends empty/whitespace entries (e.g. `''` or `' ba'`) that corrupt lookups. Split on
whitespace and drop empty tokens so the data stays valid regardless of formatting.

- export const SYLLABLES: string[] = `a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo e ei en eng er fa fan fang fei fen feng fo fou fu ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun ka kai kan kang kao ke ken keng kong kou ku kua kuai kuan kuang kui kun kuo la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu lv lve luan lun luo ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nv nve nuan nuo o ou pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo wa wai wan wang wei wen weng wo wu xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo`.split(' ')
+ export const SYLLABLES: string[] = `a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo e ei en eng er fa fan fang fei fen feng fo fou fu ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun ka kai kan kang kao ke ken keng kong kou ku kua kuai kuan kuang kui kun kuo la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu lv lve luan lun luo ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nv nve nuan nuo o ou pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo wa wai wan wang wei wen weng wo wu xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo`.split(/\s+/).filter((s: string) => s.length > 0)


─── harmony/entry/src/main/ets/pages/Keyboard.ets:11-13 ───
[bug · high] `KeyButton` members are plain (non-decorated) variables. Values passed from the parent
are only used at construction time and are not re-synced. Since `skin` is loaded asynchronously in
`aboutToAppear` (after the first render), the key buttons keep the default `#FFFFFF`/`#222222`
colors and never pick up the persisted skin. Declare them as `@Prop` so one-way updates from the
parent propagate.

-   label: string = ''
-   keyColor: string = '#FFFFFF'
-   textColor: string = '#222222'
+   @Prop label: string = ''
+   @Prop keyColor: string = '#FFFFFF'
+   @Prop textColor: string = '#222222'


─── harmony/entry/src/main/ets/pages/Keyboard.ets:169-171 ───
[bug · low] In the image branch, `choose` does not reset `associating`/`lastCommit` (only the
non-image branch does). If a previous commit had set `associating = true`, the subsequent
`refresh()` will still call `associate(lastCommit, ...)` and show unrelated association candidates.
Reset these fields consistently in both branches.



─── harmony/entry/src/main/ets/pages/Index.ets:18-18 ───
[security · medium] The default content server is plain-text HTTP. Login credentials
(`username`/`password`) are sent to this user-editable URL, so they can be transmitted in cleartext
or exfiltrated by pointing the field at an attacker host. Use HTTPS with certificate verification
and validate/normalize the URL before sending credentials.



─── harmony/entry/src/main/ets/pages/Index.ets:292-292 ───
[performance · low] `skinById(this.skinId)` is a function call executed inside `build` on every
re-render. Prefer computing/caching the current skin (e.g. via `@Watch` on `skinId` or a derived
state field) rather than invoking the lookup during rendering.



─── harmony/entry/src/main/ets/common/PinyinEngine.ets:218-218 ───
[bug · low] The dedupe key falls back to `commit` whenever `itemId` is empty. Lexicon entries (and
some mixed candidates) always have `itemId === ''` and use `commit === w`, so two distinct entries
that produce the same display/commit text collapse into one and the lower-scored suggestion is
silently dropped. Consider including `source`/`kind` in the text-based key, or keying on a stable
per-source identity.

-     let key = list[i].itemId ? 'id:' + list[i].itemId : 't:' + list[i].commit
+     let key = list[i].itemId ? 'id:' + list[i].itemId : list[i].source + ':' + list[i].commit


─── harmony/entry/src/main/ets/common/MixedPinyin.ets:584-587 ───
[bug · high] The index cache is validated only by `lexicon.length` and the first entry's word. If
the lexicon is replaced/reloaded with a different set of entries that happens to have the same size
and the same head word (a very plausible case after a partial reload or a different
market/dictionary), `ensureIndex` returns early and every downstream lookup (`cachedRows`,
`cachedExact`, `cachedFreq`) silently serves stale data, producing wrong candidates. Key the cache
on a signature that actually represents the content (e.g., a dictionary version/identity token, or
check both first and last entries), rather than only length + head.



─── harmony/entry/src/main/ets/common/MixedPinyin.ets:367-371 ───
[bug · medium] `out` is truncated to 8 entries here, but the prefix-candidate loop below then pushes
up to 3 more entries into the same array without re-slicing or re-sorting. As a result `mixedFor`
can return up to 11 results with a non-monotonic score order, contradicting the apparent 'max 8'
cap. Either move the `slice(0, 8)` (and the final sort) to after the prefix loop, or re-sort/re-cap
the array before returning.



─── harmony/entry/src/main/ets/common/MixedPinyin.ets:622-622 ───
[bug · low] Using `||` as a fallback conflates a legitimate `0` value with a missing entry. If
`charPrior`/`cachedFreq` contains an entry whose value is exactly 0 (e.g. a valid prior/frequency of
zero loaded from `char-prior.json`), `charPrior.get(row.w) || row.f` silently substitutes `row.f`,
skewing weighting and scoring. The same pattern appears in `rowWeight` (`charPrior.get(row.w) ||
row.f`), `edgeGain` (`charPrior.get(word) || freq`) and `initialBest`/`mixSpellOptions` (`... ||
0`). Prefer explicit presence checks, e.g. `charPrior.has(row.w) ? (charPrior.get(row.w) as number)
: row.f`.



─── harmony/entry/src/main/ets/common/MixedPinyin.ets:40-41 ───
[maintainability · medium] These caches are module-level mutable singletons that `ensureIndex`
rebuilds in place (`cachedFreq = new Map()` / `cachedExact = new Map()` mid-function). Any
interleaved or re-entrant invocation (e.g. from a worker, an async caller, or a second search issued
while one is in flight) can read a half-rebuilt index. Encapsulating the index in a class/instance,
or at least rebuilding into local variables and swapping all fields at the very end, would make the
state transition atomic and safer.



─── windows/cmd/susheng/skin.go:56-60 ───
[bug · low] `loadSkinID` returns any integer parsed from `skin.txt` without validating it against
the known skin IDs (0..len(allSkins())-1), and `skinByID` silently falls back to skin 0 for an
unknown ID. This masks lookup misses: painting uses skin 0 while the settings UI
(`win_windows.go:309`, `if item.id == d.skinID`) matches no skin and shows nothing as 使用中. Validate
the persisted ID in `loadSkinID` so stored state can never represent a non-existent skin, and either
document `skinByID`'s defaulting or return `(skinDef, bool)` so callers can distinguish an invalid
ID from skin 0.

  	id, err := strconv.Atoi(strings.TrimSpace(string(data)))
- 	if err != nil {
+ 	if err != nil || skinByID(id).id != id {
  		return 0
  	}
  	return id


─── windows/cmd/susheng/skin.go:64-64 ───
[bug · low] The `os.WriteFile` error is discarded, so a failed write silently loses the user's skin
selection (the UI still updates in memory, but the choice reverts on next launch). `acceptPrivacy`
has the same pattern; there the fail-closed re-prompt is acceptable, but here the loss is silent —
consider surfacing/logging the persistence failure.



─── windows/cmd/susheng/layout.go:6-8 ───
[bug · medium] This struct hard-codes a 64-bit-only Win32 layout, but this file carries no build
constraint, so it also compiles for 32-bit Windows targets (GOARCH=386/arm). There `uintptr` is 4
bytes and the struct becomes 32 bytes, whereas the real `INPUT` is 28 bytes (its union is sized by a
24-byte MOUSEINPUT), so SendInput would be handed an invalid `cbSize` and fail. Either restrict this
file to 64-bit Windows (e.g. `//go:build windows && (amd64 || arm64)`) or make the field
types/padding architecture-correct.



─── windows/cmd/susheng/layout.go:17-18 ───
[maintainability · low] `_ [8]byte` is not redundant padding: it models the INPUT union being sized
by the larger MOUSEINPUT member, bringing the total to 40 bytes. Its purpose is undocumented, so a
future 'cleanup' that removes it would silently shrink the struct and corrupt every SendInput call.
Consider documenting this intent and adding a compile-time size (and ideally offset) assertion
instead of relying only on the runtime test, since a field reorder is otherwise undetectable by the
compiler/vet.



─── windows/cmd/engineexport/main.go:90-96 ───
[bug · high] Return contract is broken for the empty case. `var rows []pinyin.Candidate` is nil, and
both `pinyin.Candidates` (returns nil for blank input) and `pinyin.Associate` (returns nil for blank
`last`) can return nil. `json.Marshal` of a nil slice emits `null`, not `[]`, so the native host may
receive `null` where it expects a JSON array, while the error paths below return `"[]"`. Normalize
nil to an empty slice before marshalling.

  	var rows []pinyin.Candidate
  	if req.Op == "associate" {
  		rows = pinyin.Associate(req.Last, lex, items, req.Now, 8)
  	} else {
  		rows = pinyin.Candidates(req.Raw, req.Mode, lex, items, req.Now, 9)
+ 	}
+ 	if rows == nil {
+ 		rows = []pinyin.Candidate{}
  	}
  	raw, err := json.Marshal(rows)


─── windows/cmd/engineexport/main.go:25-25 ───
[bug · medium] A failed lexicon load is silently swallowed here. `pinyin.LoadLexicon()` returns an
error, but it is discarded, and because loading is guarded by `sync.Once` (both here and inside the
pinyin package) a transient/permanent failure is never retried — the process permanently serves
empty candidate results with no signal to the caller. Surface or propagate the error (at minimum log
it), and consider not caching a failure.

- 		lexEntries, _ = pinyin.LoadLexicon()
+ 		entries, err := pinyin.LoadLexicon()
+ 		if err != nil {
+ 			lexErr = err
+ 			return
+ 		}
+ 		lexEntries = entries


─── windows/cmd/engineexport/main.go:56-57 ───
[security · medium] Native boundary hardening: `payload` is a raw `*C.char` accepted from the host
with no NULL or length validation. `C.GoString` assumes a NUL-terminated C string, so a NULL or
non-terminated buffer causes an out-of-bounds read, and the payload is copied into memory and passed
to `json.Unmarshal` without any size bound, allowing unbounded allocation. Validate the pointer/size
at the boundary (e.g. accept an explicit length or guard against NULL/oversized input) before
decoding.



─── windows/internal/content/store.go:280-280 ───
[security · high] AssetID comes straight from the server payload and is joined into the on-disk path
(and the request URL) without validation. An ID containing `..` or path separators lets a
malicious/compromised server write files outside the assets directory (e.g. `../../session.json`).
Validate that the ID is a single, safe filename before both the file write and the URL construction.

- 		path := filepath.Join(assetDir, *item.AssetID)
+ 		name := filepath.Base(*item.AssetID)
+ 		if name != *item.AssetID || name == "." || name == ".." || strings.ContainsAny(name, `/\\`) {
+ 			continue
+ 		}
+ 		path := filepath.Join(assetDir, name)


─── windows/internal/content/store.go:245-246 ───
[bug · critical] `flushUsage` does a read-modify-write of usage-queue.json with no lock, while
`QueueUsage` updates the same file under `s.mu.Lock()`. `Sync()` (which calls flushUsage) runs on a
background goroutine (`startSync` in win_windows.go) concurrently with UI-thread `QueueUsage` calls,
so the two sequences can interleave and events appended by QueueUsage can be clobbered by
flushUsage's clearing write (lost usage), or produce a torn file. Guard the queue with the same
mutex (or a dedicated file lock) on both paths.



─── windows/internal/content/store.go:256-256 ───
[bug · medium] The queue is cleared unconditionally even though the POST results are discarded (`_,
_ = request(...)`). Any usage events that fail to send (network error, non-2xx) are permanently
lost. Retain the failed events (or only remove the ones that were successfully delivered) so they
can be retried on the next flush.



─── windows/internal/content/store.go:299-301 ───
[bug · medium] HTTP failures (>=400) are silently skipped with `continue`, yet `Sync` still returns
success. The item will reference an asset that was never written, so `AssetPath` lookups silently
fail later. Surface the failure (or at least record/log it) instead of swallowing it.



─── windows/internal/content/store.go:294-294 ───
[bug · medium] `io.ReadAll(res.Body)` has no size limit, so a large or malicious asset response can
exhaust memory. Stream to a temp file with a bounded reader (e.g. `io.LimitReader`/`io.Copy`) and
validate the size before renaming.



─── windows/internal/content/store.go:151-156 ───
[bug · medium] The new pack (carrying the updated epoch/serverVersion) is persisted before
`downloadAssets`/`flushUsage` run and before the in-memory fields are updated. If a later step
fails, `Sync` returns an error but leaves disk ahead of memory: a subsequent Sync in the same
process sends the stale `since`/`epoch` and re-applies state, while a restart via `Open` loads the
newer pack. Persist the pack only after all steps succeed, or update in-memory state together with
the file.



─── windows/internal/content/store.go:289-290 ───
[performance · low] A fresh `http.Client` (and thus a new transport/connection) is created on every
call. This is especially wasteful in `flushUsage`, which issues one request per queued event,
preventing keep-alive reuse. Use a shared package-level client with the desired timeout.



─── windows/internal/pinyin/engine.go:191-191 ───
[bug · medium] The prefix test `strings.HasPrefix(entry.W, word)` is byte-based, but the suffix is
computed with rune-based arithmetic. When `word` is not valid UTF-8 (e.g. a `last` value truncated
mid-rune), `matchingAssociate` can still return an entry whose `W` matches at the byte level while
`len([]rune(word)) > len([]rune(entry.W))`, making this slice expression panic with "slice bounds
out of range". Since `HasPrefix` already guarantees `word` is a byte prefix of `entry.W`, slice by
bytes to stay consistent and avoid the panic.

- 		suffix := string([]rune(entry.W)[len([]rune(word)):])
+ 		suffix := entry.W[len(word):]


─── windows/internal/pinyin/engine.go:67-67 ───
[maintainability · low] `lexiconRows` is the package-level cached backing slice, and `LoadLexicon`
hands it out by reference. Any caller that sorts, appends to, or otherwise mutates the returned
slice (especially via `append`, which may write into shared spare capacity) would corrupt the cached
lexicon for all subsequent calls. Consider returning a copy, or documenting the slice as read-only.

- 	return lexiconRows, lexiconErr
+ 	return append([]LexEntry(nil), lexiconRows...), lexiconErr


─── windows/cmd/susheng/win_windows.go:253-256 ───
[bug · high] `GetMessageW` conflates the error return (-1) with `WM_QUIT` (0). Treating `int32(ret)
<= 0` as a shutdown cause means a transient failure (e.g. an invalid HWND/queue state) silently
tears down the loop: the IME stops handling input and exits with no diagnostic. Distinguish the two
return values and, at minimum, report/log the error path.

  		ret, _, _ := procGetMessage.Call(uintptr(unsafe.Pointer(&msg)), 0, 0, 0)
- 		if int32(ret) <= 0 {
+ 		if int32(ret) == -1 {
+ 			// GetMessageW failed; log and bail out rather than treating it as WM_QUIT.
  			break
+ 		}
+ 		if ret == 0 {
+ 			break // WM_QUIT
  		}


─── windows/cmd/susheng/win_windows.go:890-890 ───
[bug · high] Blocking calls made from the low-level keyboard hook (`WH_KEYBOARD_LL`) can exceed
`LowLevelHooksTimeout` and cause Windows to silently uninstall the hook, stalling system keyboard
input. `passwordFocused` issues a synchronous `SendMessageW(EM_GETPASSWORDCHAR)` to the focused
window (blocking indefinitely if the target is hung, and misclassifying a password Edit as
non-password when it fails, so the IME intercepts/echoes into the candidate window), and
`choose`/`refresh` -> `QueueUsage` performs synchronous disk I/O under the store lock. Prefer
non-blocking checks (fail safe to do-not-intercept) and queue usage events in memory for a non-hook
goroutine to flush.



─── windows/cmd/susheng/win_windows.go:867-872 ───
[bug · high] Clipboard/global-memory handling in `copyFileDrop` leaks and can wipe the user's
clipboard:
- If `OpenClipboard` fails (common when another app holds the clipboard), the function returns
without `GlobalFree`-ing `handle`, leaking an HGLOBAL on every failed attempt.
- `SetClipboardData`'s return value is ignored and `EmptyClipboard` is called first, so when
`SetClipboardData` fails the user's original clipboard content is destroyed with nothing put back,
while the handle is also leaked.
- The `GlobalLock` failure path also returns without freeing `handle`.
Free the handle on every failure path (only skip `GlobalFree` when `SetClipboardData` succeeds,
since ownership transfers to the clipboard) and avoid emptying the clipboard until the drop data is
ready to be set.

  	if r, _, _ := procOpenClipboard.Call(0); r == 0 {
+ 		procGlobalFree.Call(handle)
  		return false
  	}
  	procEmptyClipboard.Call()
- 	procSetClipboard.Call(cfHdrop, handle)
+ 	if ok, _, _ := procSetClipboard.Call(cfHdrop, handle); ok == 0 {
+ 		procCloseClipboard.Call()
+ 		procGlobalFree.Call(handle)
+ 		return false
+ 	}
  	procCloseClipboard.Call()


─── windows/cmd/susheng/win_windows.go:251-252 ───
[bug · medium] The message loop / hook are not pinned to an OS thread.
`SetWindowsHookExW(WH_KEYBOARD_LL)` delivers callbacks on the installing thread and that thread must
keep pumping messages; a Go goroutine can be rescheduled onto a different M between blocking calls,
in which case the hook stops firing (or is removed for timing out) and all of the IME's key handling
silently dies. Call `runtime.LockOSThread()` at the start of `run` before installing the hook and
running the loop.



─── windows/cmd/susheng/win_windows.go:250-250 ───
[maintainability · low] The return value of `setHook` is never checked. If `SetWindowsHookExW` fails
it returns 0, and the program then runs a fully non-functional IME with no error surfaced anywhere.
The same unchecked-return pattern exists for `RegisterClassExW`/`CreateWindowExW` in
`register`/`createPopup` (a failed registration yields `hwnd == 0` but the loop still runs). Also
note the hook/tray are already torn down in `onTrayCommand` case 207 and are torn down a second time
after the loop exits; guard the shutdown so it is idempotent. At minimum, validate `current.hook !=
0` and report the failure.



─── windows/internal/pinyin/sentence.go:32-36 ───
[bug · medium] Candidate ordering is non-deterministic across runs because several paths rely on Go
map-iteration order or unstable sorts: `Suggest` returns candidates in `best` map-iteration order,
`longerThan` and `syllablesStarting` iterate the package-level `syllables` map, and `sort.Slice` is
unstable with ties left unordered before truncating `bag` to `k`. The final stable sort only breaks
ties by score/length, so equal-scoring candidates, beam states, and `dedupe`/`clip` boundary
survivors can change between identical inputs. Iterate fixed ordered slices or sort matches
deterministically (e.g. by score then text) to make output stable.

  	out := make([]Candidate, 0, len(best))
  	for _, row := range best {
  		out = append(out, row)
+ 	}
+ 	sort.Slice(out, func(i, j int) bool {
+ 		if out[i].Score != out[j].Score {
+ 			return out[i].Score > out[j].Score
- 	}
+ 		}
+ 		return out[i].Text < out[j].Text
+ 	})
  	return out


─── windows/internal/pinyin/sentence.go:141-142 ───
[performance · medium] `beam[n]` is never truncated: the `len(beam[i]) > 6` pruning only runs while
`i < n` (positions still being expanded). Every source position contributes its surviving states'
edges into `beam[n]`, so for a long input (up to 96 chars) this slice can grow to thousands of
states and is then ordered with `sortPaths`, an O(k^2) insertion sort, on the interactive keystroke
path. Bound `pool` (e.g. keep a top-N by score before `sortPaths`, or prune `beam[n]` the same way
as other positions).



─── windows/internal/pinyin/sentence.go:294-294 ───
[maintainability · low] JSON unmarshal errors for embedded assets are silently discarded: `_ =
json.Unmarshal(charPriorJSON, &charPrior)` leaves `charPrior` empty on a malformed/renamed
`char-prior.json`, and the same pattern in `mixed.go` leaves `nextWords` empty when `ime-rules.json`
fails to parse. Because these assets are embedded at build time (embed does not validate JSON), a
corrupted asset compiles fine and silently changes scoring or disables IME next-word bonuses. Fail
loudly (e.g. panic in `init`) or at least log the error instead of dropping it.

- 	_ = json.Unmarshal(charPriorJSON, &charPrior)
+ 	if err := json.Unmarshal(charPriorJSON, &charPrior); err != nil {
+ 		panic("pinyin: invalid char-prior.json: " + err.Error())
+ 	}


─── windows/internal/pinyin/mixed.go:221-223 ───
[maintainability · low] These tuning constants (200 here, 300 for the follow bonus, and 40 for the
loose/abbrev cost above) hardcode values that already exist in the embedded `ime-rules.json`
(`initialCharCost`, `mixedFollowGain`, `looseCost`). `init` only decodes the `next` section, so the
`weights` object is dead data: editing the shared config will silently not affect Windows and will
drift from the Kotlin/JS engines that read `ImeRules.weight(...)`. Consider decoding the `weights`
section and referencing named constants so all platforms stay in sync.



─── windows/internal/pinyin/index.go:248-251 ───
[performance · medium] `segmentAll` is a DFS with no memoization and no bound on `len(input)`.
`matchingLexicon` feeds it the full user-typed letters (`Candidates` does not cap input length), and
`found` only grows when a full segmentation or a *valid* partial tail is produced. When the input
ends in an unsegmentable tail (e.g. repeated "ni"/"nin"/"xian" followed by "jjj"), no entry is ever
appended, so `limit` never prunes and every segmentation of the valid prefix is enumerated —
exponential (branching factor 2–3). A long pasted string can hang the IME. Note the sibling
`segmentAllowPartial` in engine.go memoizes by position; do the same here and bound the input
length.

  func segmentAll(input string, limit int) []seg {
+ 	if len(input) == 0 || len(input) > 96 {
+ 		return nil
+ 	}
  	var found []seg
  	var acc []string
  	var rec func(int)


─── windows/internal/pinyin/index.go:33-39 ───
[bug · medium] The global index cache is keyed by pointer identity of the *first element* of the
slice (`&lexicon[0]`). This is only valid as long as the caller always passes the same,
never-mutated backing array. Any caller that passes a sub-slice sharing the same first element (e.g.
`lex[:n]`) or mutates entries in place (e.g. updating `F`) will silently receive an index built for
different content, producing wrong/missing matches. The empty-lexicon case also maps every empty
slice onto the single nil key. Prefer keying on slice identity plus content/length fingerprint, or
document and enforce the "stable global lexicon only" invariant.



─── windows/internal/pinyin/index.go:31-32 ───
[performance · medium] The exclusive `indexMu` is held for the entire index build (syllable parsing,
two sorts, and construction of five maps over the whole lexicon). Because the resulting `*lexIndex`
is immutable once built, concurrent lookups for the same or a different lexicon are needlessly
serialized behind long CPU work, and cache hits also take an exclusive lock. Consider double-checked
locking (or per-lexicon `sync.Once`) so the expensive build runs without holding the global lock.



─── macos/Sources/Home.h:3-3 ───
[maintainability · medium] These accessor functions return non-null values (the implementation
always returns a color from `Color(...)`), but the header provides no nullability annotations. Under
Swift interop, an unannotated `NSColor *` return is imported as an implicitly-unwrapped optional
(`NSColor!`), forcing needless unwrapping and obscuring the real contract. Wrap the declarations in
`NS_ASSUME_NONNULL_BEGIN`/`NS_ASSUME_NONNULL_END` (or mark each return `_Nonnull`) so consumers get
a precise, non-optional type.

+ NS_ASSUME_NONNULL_BEGIN
+ 
  NSColor *SushengPanelColor(void);


─── macos/Sources/Home.h:5-5 ───
[maintainability · medium] The ownership of the returned `NSColor *` is undefined by the header. For
C functions returning Objective-C objects the naming convention (`Create`/`Copy` => +1 retained;
otherwise autoreleased/not-owned) governs the ARC contract, yet none of these three functions
follows it nor documents it. The implementation returns autoreleased colors, but callers cannot know
this from the header alone and may over-retain or over-release. Document the ownership rule (or add
`NS_RETURNS_RETAINED`/`NS_RETURNS_NOT_RETAINED` as appropriate) so call sites are unambiguous.



─── macos/Sources/Home.h:6-6 ───
[documentation · medium] No thread-affinity contract is declared for these globals, yet all of them
touch main-thread-only AppKit state (the implementation reads `NSUserDefaults`, builds
`NSColor`/`NSWindow`, and calls `[NSApp activateIgnoringOtherApps:]`). Calling any of them off the
main thread is undefined behavior, but nothing in the header warns consumers. Please add
documentation stating these must be invoked on the main thread (and consider asserting/hopping to
the main queue in the implementation).



─── ios/Rime/RimeBridge.h:7-7 ───
[maintainability · medium] Under `NS_ASSUME_NONNULL` the return value is implicitly nonnull, and the
implementation returns `@""` for every failure/fallback path (rime not linked,
`SushengRimeAvailable()` false, `raw.length == 0`, `rime_get_api()`/`create_session()` failure) as
well as for a legitimate empty result. Because there is no `NSError **`/sentinel channel, callers
cannot distinguish "rime unavailable", "invalid input", and "conversion produced nothing" from a
valid empty conversion. Please document the failure contract in the header (and/or expose an
error/sentinel), otherwise Swift/ObjC callers will silently swallow failures.



─── ios/Rime/RimeBridge.h:6-6 ───
[documentation · low] The availability/initialization contract is not documented.
`SushengRimeAvailable()` performs a lazy `dlsym(RTLD_DEFAULT, "rime_get_api")` lookup, and
`SushengRimeContext` internally creates/destroys a shared rime session; concurrent callers (e.g.
from a keyboard extension's background/foreground paths) could race on global rime state. The header
should state whether these functions are required to be called from a single thread/serialized
queue, and whether callers must guard `SushengRimeContext` behind `SushengRimeAvailable()` or may
call it unconditionally.



─── macos/Sources/Store.h:11-11 ───
[security · high] `assetId` is fully caller-supplied and is later combined into a filesystem path
(`[root/assets]/assetId`) without any sanitisation contract. A value such as `../../Library/...`
escapes the assets directory, allowing arbitrary file read/write (the asset download path also
derives from server-supplied `assetId`). Since this is the public API boundary, the contract must
require/validate a plain filename. Add an explicit doc contract and nullability, and reject
separators/`..` before use.

- - (NSString *)assetPath:(NSString *)assetId;
+ /// Returns the on-disk path for assetId.
+ /// assetId MUST be a bare filename; callers/impl must reject any
+ /// path separators or ".." so the result stays inside the assets dir.
+ - (nullable NSString *)assetPath:(NSString *)assetId;


─── macos/Sources/Store.h:12-12 ───
[security · high] The login API accepts a raw `server` URL and a plaintext `password`, but the
header gives no contract that the endpoint must be HTTPS or that credentials must not be
logged/persisted. Combined with the implementation (which sends `password` in a JSON body to
whatever scheme the caller passes, and where `http://` is accepted), this can leak credentials in
cleartext. Document the required transport and nullability here.

- - (void)loginServer:(NSString *)server username:(NSString *)username password:(NSString *)password done:(void (^)(NSString *message))done;
+ /// `server` MUST be an https:// base URL; `password` is sent as a plaintext
+ /// field, so callers must never log or persist it.
+ - (void)loginServer:(nonnull NSString *)server username:(nonnull NSString *)username password:(nonnull NSString *)password done:(nonnull void (^)(NSString *message))done;


─── macos/Sources/Store.h:15-15 ───
[security · medium] `queryOp:raw:last:mode:` forwards `op`, `raw`, `last` and `mode` verbatim into
the query pipeline with no documented validation or whitelist. Even though the current backend is a
JSON bridge, the `raw`/`last` inputs are unbounded user text and `op` selects the executed
operation, so the contract should require callers to whitelist `op` and to bound/sanitise
`raw`/`last`. At minimum document the accepted values; ideally validate inside the method.

- - (NSArray *)queryOp:(NSString *)op raw:(NSString *)raw last:(NSString *)last mode:(NSString *)mode;
+ /// `op` must be one of the supported operation names (e.g. "candidates",
+ /// "associate"); `raw`/`last` are untrusted input and must be validated/
+ /// length-bounded by the implementation before being forwarded.
+ - (nonnull NSArray *)queryOp:(nonnull NSString *)op raw:(nullable NSString *)raw last:(nullable NSString *)last mode:(nullable NSString *)mode;


─── macos/Sources/Store.h:10-10 ───
[maintainability · medium] Missing nullability: `session` can legitimately return nil (the
implementation returns nil when session.json is absent/invalid) and `assetPath:` can return nil, yet
both are declared nullable-unaware. Callers/Swift interop will treat the results as non-optional and
crash on nil. Annotate `nullable`/`nonnull` (or wrap the interface in `NS_ASSUME_NONNULL_BEGIN/END`)
so the nil contract is explicit.

- - (NSDictionary *)session;
+ - (nullable NSDictionary *)session;


─── macos/Sources/Store.h:13-13 ───
[maintainability · medium] The async APIs (`loginServer:`, `sync:`) and the fire-and-forget
`reportUsage:` give no indication of the queue the completion block runs on, while
`chinese`/`mode`/`items`/`serverVersion` are mutable state mutated inside those callbacks (and
callbacks currently hop to the main queue). Because the properties are `nonatomic`, concurrent reads
from the UI thread racing with callback mutations are unsafe. Document the callback queue and the
threading rules (state is only mutated internally / access must occur on a single queue).

+ /// `done` is invoked on the main queue. Mutable state (chinese/mode/items/
+ /// serverVersion) is only mutated internally on that queue; callers must not
+ /// mutate it and should read it only from the main thread.
  - (void)sync:(void (^)(NSString *message))done;


─── backend/public/index.html:10-11 ───
[bug · low] No `<noscript>` fallback is provided. If JavaScript is disabled or blocked (or
`/admin.js` fails to load), the page renders a completely blank screen with no user feedback at all.
Consider adding a `<noscript>` notice inside `#app` so users understand why nothing is displayed.

    <div id="app"></div>
+   <noscript>本页面需要启用 JavaScript 才能正常使用，请在浏览器设置中开启后重试。</noscript>
    <script src="/admin.js"></script>


─── backend/public/index.html:7-7 ───
[maintainability · low] Both static assets are referenced by unversioned absolute paths
(`/admin.css`, `/admin.js`). Without a content hash or cache-busting query string, browsers/CDNs can
keep serving a stale bundle after a deployment, so users may run outdated admin code against a newer
backend. Consider fingerprinting the filenames or appending a build/version query parameter. Also
consider adding Subresource Integrity (integrity + crossorigin) if these assets are ever served from
a different origin.



─── backend/public/admin.js:74-74 ───
[maintainability · medium] Nested ternary expressions are forbidden by the project rules and this
double-nested one is hard to read. The same tab→label mapping is already declared inline in the nav
below; extract it to a single map and reuse it here.

-           <h1>${esc(state.tab === 'items' ? '稿件' : state.tab === 'groups' ? '分组' : state.tab === 'users' ? '账号' : '使用')}</h1>
+           <h1>${esc(tabName[state.tab] || '')}</h1>


─── backend/public/admin.js:142-142 ───
[bug · medium] `kindName[group.kind]` and `roleName[user.role]` are looked up without a fallback, so
an unexpected/unknown enum from the server makes the lookup `undefined` and `.slice(0, 1)` throws a
TypeError that breaks rendering of the panel/list. Compare with `typeName[item.type] || ''` /
`statusName[item.status] || ''` which are guarded. Add the same fallback to both.

-           <div class="stamp">${kindName[group.kind].slice(0, 1)}</div>
+           <div class="stamp">${(kindName[group.kind] || '').slice(0, 1)}</div>


─── backend/public/admin.js:115-115 ───
[security · low] `item.status` is interpolated into a class attribute without escaping. Although it
comes from the server, the rest of this template consistently runs server values through `esc()`; an
unexpected value containing a quote/space would break the attribute (and could inject markup).
Escape it for consistency and safety.

-           <div class="stamp ${item.status}">${statusName[item.status] || ''}</div>
+           <div class="stamp ${esc(item.status)}">${statusName[item.status] || ''}</div>


─── backend/public/admin.js:247-248 ───
[bug · medium] `find()` can return `undefined`, and then `user.id` / `user.disabled` dereferences
throw an unhandled error inside the async click handler (e.g. if a concurrent refresh already
removed the row). Guard before using the result.

      const user = state.users.find((row) => row.id === button.dataset.disable)
+     if (!user) return
      await act(`/api/admin/users/${user.id}`, 'PATCH', { disabled: !user.disabled })


─── backend/public/admin.js:252-253 ───
[maintainability · low] The `try/catch/finally`-style error handling (`state.error = error.message;
render()`) is duplicated verbatim in the login handler, `act`, and `saveItem`, and diverges subtly
between them (login clears the token on failure, the others don't). Extract a shared
`handleError(error)` helper so error behavior stays consistent.



─── backend/package.json:4-4 ───
[bug · high] The manifest declares no `engines` field, yet the code depends on very recent Node
features. `src/db.mjs` imports `DatabaseSync` from `node:sqlite` (only available in Node >= 22.5.0),
and the test script relies on `node --test` with explicit file arguments. On an older runtime the
install/test would silently succeed but the process would crash at import time. Pin the required
runtime explicitly.

    "type": "module",
+   "engines": {
+     "node": ">=22.5.0"
+   },


─── backend/package.json:7-7 ───
[maintainability · medium] The test target `../protocol/pinyin-engine.test.mjs` resolves outside the
package root. This creates an implicit, undeclared coupling to the sibling `protocol` package: the
command only works when executed from this exact repository layout, and it will break if the package
is installed/copied elsewhere or `npm test` is run from another working directory. Consider moving
the shared test into this package (or referencing it via a declared workspace/dependency) so the
package is self-contained.



─── protocol/content-pack.schema.json:12-15 ───
[bug · medium] The `items` array has no uniqueness constraint, so a single pack can legally contain
two records with the same `id`. Note that JSON Schema's `uniqueItems` only enforces whole-object
uniqueness, not per-property `id` uniqueness, so it cannot fully solve this. Since downstream merge
logic (ContentRepository/PinyinEngine/store) keys records by `id`, duplicate ids make sync results
ambiguous. Consider validating `id` uniqueness in the receiver (or documenting the invariant) in
addition to any schema-level guard.



─── protocol/content-pack.schema.json:33-34 ───
[bug · medium] `assetId`/`mime` are only meaningful for `type: "image"` and `groupKind` only for
grouped items, but nothing conditionally requires or forbids them. As written, structurally invalid
records (e.g. an image item without `assetId`, or a non-image carrying `assetId`) still validate;
consumers such as `ContentRepository.assetFile` and the engines assume `assetId` is present for
images. Consider adding an `if`/`then`/`else` (or `dependentRequired`) block to enforce these
cross-field dependencies.



─── protocol/content-pack.schema.json:35-36 ───
[bug · medium] `validFrom` and `validTo` are independent nullable integers with no ordering or
co-presence rule, so `validFrom > validTo` (or `validTo` set without `validFrom`) still validates,
producing content that is never or incorrectly active. JSON Schema cannot compare two values
directly, so enforce at least co-presence via `dependentRequired` here and validate the ordering
invariant in application code.



─── protocol/content-pack.schema.json:40-40 ───
[bug · medium] The schema does not relate `deleted` to `status`, nor `item.version` to the
envelope's `since`/`epoch`, and it places no bounds on `updatedAt`. Contradictory tombstones (e.g.
`deleted: true` together with a full payload) or out-of-order updates (`version <= since`) therefore
validate and can corrupt client sync state. Consider adding a conditional constraint (e.g.
forbidding payload fields when `deleted` is true) and documenting/validating the version/epoch
ordering.



─── protocol/daily-pinyin-cases.json:0-0 ───
[maintainability · low] The `zuotian` (昨天) block is the only day-block that is NOT expanded into the
templated sentence patterns. 今天/明天/后天 each contain 43 entries per day-part (base phrase plus
要/有/会/去/在 variants), whereas 昨天 only lists the 5 base day-part phrases with no 有/去/在 variants (e.g.
`zuotianzaoshangyouzhibo`, `zuotianzaoshangqushangban`, `zuotianzaoshangzaijia` are missing), which
today's blocks all have. This looks like a generation truncation rather than an intentional omission
(past-tense 有/去/在 sentences are perfectly natural). Please confirm whether the 昨天 block is
intentionally reduced; if not, regenerate it so it is symmetric with the other day blocks.



─── android/app/src/androidTest/java/com/susheng/ime/MixedPinyinTest.kt:24-24 ───
[test · medium] `top(...)` may return an empty list, so `.first()` throws an opaque
`NoSuchElementException` ("List is empty") instead of naming the failing input. This affects both
`top("woxiangs", 1).first()` and `top("nilaixiawdbangongs", 1).first()`. Guard with an explicit
assertion (e.g. `assertTrue(candidates.isNotEmpty())`) or use `firstOrNull()`/`assertEquals` on the
whole list so the offending input appears in the failure message.

-         assertEquals("我想说", top("woxiangs", 1).first())
+         val woxiangs = top("woxiangs", 1)
+         assertTrue("woxiangs 无候选", woxiangs.isNotEmpty())
+         assertEquals("我想说", woxiangs.first())


─── android/app/src/androidTest/java/com/susheng/ime/MixedPinyinTest.kt:24-24 ───
[test · low] Asserting exact top-1 commits (`我想说`, `办公室`, `你来下我的办公室`, and the required set from
`woshis`) tightly couples the test to the current lexicon ordering / scoring. Any benign lexicon or
rules regeneration will fail these tests even without a real regression, and the failure gives no
signal about ordering vs. missing results. Prefer asserting membership/relative ranking with a
descriptive message, or isolate the ordering expectations behind a documented, versioned fixture so
the intent of the test is explicit.



─── android/app/src/androidTest/java/com/susheng/ime/MixedPinyinTest.kt:48-48 ───
[test · low] Asset reads and JSON parsing in these instrumentation tests run without assertion or
diagnostic context. Missing/corrupt `lexicon.bin`, `char-prior.json`, `ime-rules.json`, or the
AbbrevPinyin test assets surface as opaque `FileNotFoundException`/`IOException`/`JSONException`;
`lateinit` companion fields can later present as `UninitializedPropertyAccessException`. Wrap the
loading in a helper that names the failing asset and/or assert existence so failures are
diagnosable.



─── android/app/src/androidTest/java/com/susheng/ime/Ime10kTest.kt:53-53 ───
[bug · high] `BitmapFactory.decodeStream` returns a nullable `Bitmap?`. If the asset is missing or
corrupt, `bitmap` is `null` and is passed directly into `HandModel.recognize(bitmap, 5)`, whose
parameter is non-null (`fun recognize(bitmap: Bitmap, ...)`). This produces a confusing
NPE/NPE-at-boundary instead of a clear test failure, and also makes `bitmap.recycle()` unreachable.
Assert non-null (or fail with a descriptive message) before use.

              val bitmap = testAssets.open("hand/$file").use { BitmapFactory.decodeStream(it) }
+                 ?: throw AssertionError("无法解码手写资源 hand/$file")


─── android/app/src/androidTest/java/com/susheng/ime/Ime10kTest.kt:55-55 ───
[maintainability · medium] Re-calling `HandModel.recognize` with the exact same arguments only
changes the result if recognition is non-deterministic. This masks engine instability, roughly
doubles the time for every miss, and the failure message reports the retried result rather than the
first pass. If a warm-up/priming run is actually intended it should be explicit and justified;
otherwise drop the redundant second call.



─── android/app/src/androidTest/java/com/susheng/ime/Ime10kTest.kt:56-56 ───
[bug · medium] The native bitmap is only released on the success path. If `HandModel.recognize`
throws (or the assertion above it fails), `recycle()` is skipped and the native memory leaks across
up to 1000 iterations, potentially causing OOM in the test run. Release it in a `finally` block.

+             try {
+                 var got = HandModel.recognize(bitmap, 5)
+                 ...
+             } finally {
-             bitmap.recycle()
+                 bitmap.recycle()
+             }


─── android/app/src/androidTest/java/com/susheng/ime/Ime10kTest.kt:40-40 ───
[bug · medium] `handTop1` is mutable instance state that is never reset at the start of
`handwriting()`. It happens to work with JUnit's per-method instance creation, but if the method
were ever re-invoked or run under a shared/reused runner this counter accumulates and yields
non-reproducible results. Initialize/reset it locally inside the test instead of relying on instance
lifecycle.

-     private var handTop1 = 0
+     // move this into handwriting() as a local var, reset per run


─── android/app/src/androidTest/java/com/susheng/ime/Ime10kTest.kt:76-77 ───
[test · medium] Weak assertions/diagnostics: for `py`/`wb` only `count > 0` is checked, so a
shrunken/truncated case set silently passes, and `bad.take(12)` hides all but 12 offenders; when
there are no failures the message is an empty string. The hardcoded top-1 cutoff of 970 on
`handTop1` is device/emulator-sensitive and flaky. Assert expected per-mode counts (as done for
`hw`) and report total failure counts/full list or an explicit "... N more" marker.



─── android/app/src/main/java/com/susheng/ime/engine/HandModel.kt:30-33 ───
[bug · high] Race / visibility bug: `ready` is set to `true` BEFORE `charset` is populated, and both
fields are plain (non-`@Volatile`) `var`s read outside the `synchronized` block in `recognize()`. A
second thread calling `recognize()` can observe `ready == true` while `charset` is still empty, then
hit `charset.length < 3755` and silently return an empty result even though the model loaded fine.
Load the charset first, publish `ready` only after `charset` is fully assigned, and mark
`charset`/`ready` `@Volatile`.

-             ready = nativeLoad(dir.absolutePath)
-             if (ready && charset.isEmpty()) {
+             if (charset.isEmpty()) {
                  charset = context.assets.open("hand-chars.txt").bufferedReader().use { it.readText() }
              }
+             ready = nativeLoad(dir.absolutePath)


─── android/app/src/main/java/com/susheng/ime/engine/HandModel.kt:39-39 ───
[maintainability · medium] Guard is read outside any lock and relies on the unsynchronized `charset`
field (see the ordering/visibility issue in `ensure()`); additionally `3755` is a magic number
hard-coded as a proxy for the model's class count. Extract it to a named constant (e.g.
`CLASS_COUNT`) tied to the charset asset so the intent is clear and cannot drift from the native
model.



─── android/app/src/main/java/com/susheng/ime/engine/HandModel.kt:49-50 ───
[bug · medium] Non-atomic writes can leave partially written files that are then trusted. In
`HandModel`, `dest.length() > 0` is used as the "already extracted" sentinel while the asset is
streamed directly to the final path, so an interrupted copy leaves a corrupt-but-non-empty model. In
`ContentRepository`, `packFile.writeText(...)` overwrites `content-pack.json` directly. Write to a
temp file and atomically rename on success (or verify size/checksum).



─── android/app/src/main/java/com/susheng/ime/engine/HandModel.kt:27-27 ───
[bug · medium] `HandModel.ensure()` and `HandStrokes.ensure()` perform failure-prone operations
(asset reads, `System.loadLibrary`, `nativeLoad`, buffer parsing) with no error handling. A
missing/corrupt asset, `UnsatisfiedLinkError`, or `IOException` propagates out instead of returning
`false`, so callers cannot distinguish "model unavailable" from a crash. Wrap the bodies in
try/catch, return `false` on failure, and check `mkdirs`/buffer bounds.



─── android/app/src/main/java/com/susheng/ime/engine/HandModel.kt:43-43 ───
[bug · medium] Silent mismatch risk: if the native layer returns fewer logits than the charset size
(e.g. a wrong/partial model), `minOf(logits.size, charset.length)` masks the problem and the
truncated indices still map to real characters, producing plausible-but-wrong results. Validate that
`logits.size == charset.length` and bail out (return empty) when they disagree.



─── android/app/src/main/java/com/susheng/ime/engine/HandModel.kt:44-44 ───
[performance · low] Hot-path allocation: `sortedByDescending` boxes every index and fully sorts all
~3755 candidates just to keep `limit` (default 8). On a per-stroke interactive path this is
unnecessary work; a bounded top-k selection (e.g. `Array<Int>` + partial selection /
`sortedByDescending` on a small accumulator) avoids sorting the full class set.



─── android/app/src/main/java/com/susheng/ime/engine/HandModel.kt:57-58 ───
[bug · low] `Bitmap.getPixels` throws `IllegalStateException` if the bitmap has been recycled. Since
this runs off the UI/interaction path, a recycled or concurrently-recycled bitmap will crash
recognition. Check `bitmap.isRecycled` (or guard with try/catch) and return an empty result instead.



─── android/app/src/androidTest/java/com/susheng/ime/DailyPinyinTest.kt:33-33 ───
[test · medium] The expectation table (`daily-pinyin-cases.json`) is loaded from the instrumentation
(test APK) assets, while the lexicon/prior/rules under test come from the target app assets
(`targetContext`). These are two independent copies that can drift: if the test asset becomes stale
relative to the packaged one, the test may keep passing while validating a different corpus than the
app actually ships. Consider loading the case table from the same source as the packaged assets, or
add an explicit consistency/version check so staleness fails loudly.



─── android/app/src/androidTest/java/com/susheng/ime/DailyPinyinTest.kt:38-39 ───
[test · low] `getJSONArray`/`getString` throw a bare `JSONException` on malformed or schema-drifted
assets, aborting the whole test with no indication of which case row was bad. Wrapping this in a
try/catch that rethrows with the `index` (and preferably the raw row) would make failures much
easier to diagnose.



─── android/app/src/androidTest/java/com/susheng/ime/DailyPinyinTest.kt:26-30 ───
[maintainability · low] The manual iteration over `root.keys()` to populate `prior` can be replaced
with a single idiomatic expression using `associateWith`, which is more concise and avoids the
`while` loop boilerplate.

-         val keys = root.keys()
-         while (keys.hasNext()) {
-             val key = keys.next()
-             prior[key] = root.getInt(key)
-         }
+         val prior = root.keys().asSequence().associateWith { root.getInt(it) }


─── android/app/src/main/java/com/susheng/ime/engine/HandPinyin.kt:7-7 ───
[bug · critical] Unsafe double-checked locking. `readings` is a plain (non-volatile) mutable
`HashMap` that is mutated inside `synchronized(this)` in `ensure()` but read without any
synchronization in `of()`. Because there is no happens-before edge, readers on other threads
(candidate rendering vs. the IME service thread that calls `ensure()`) can observe a stale/empty map
or, worse, a partially populated map. The outer `readings.isNotEmpty()` check is also racy. Fix by
building a fully-populated local map and publishing it only through a `@Volatile` field (a
`@Volatile` is only allowed on a `var`).



─── android/app/src/main/java/com/susheng/ime/engine/HandPinyin.kt:13-19 ───
[bug · high] Using `isNotEmpty()` as the "already loaded" sentinel prevents recovery from
failed/partial loads: in `HandPinyin.ensure()` a mid-read failure leaves the map non-empty and
permanently serves incomplete pinyin, and in `WubiTable.load()` the table can never become empty
again so a partial/failed load can never be retried or refreshed. Build into a local map/table and
publish only after a complete successful load, or track an explicit loaded flag.



─── android/app/src/main/java/com/susheng/ime/engine/HandPinyin.kt:11-12 ───
[performance · medium] Disk I/O is performed while holding the object monitor. Reading the asset
inside `synchronized(this)` blocks any other thread that touches this lock for the whole file read,
and it does not protect the unsynchronized reader in `of()` anyway. Load the data outside the
critical section and only synchronize on the volatile publication.



─── android/app/src/main/java/com/susheng/ime/engine/HandPinyin.kt:25-25 ───
[performance · medium] Avoidable allocation in a hot path. `it.toString()` allocates a new `String`
for every character, and `mapNotNull` builds an intermediate list before `joinToString`. Since keys
are single characters, either store the table keyed by `Char` (`readings[it]`) or append results
into a `StringBuilder` directly to avoid per-char `String`/list allocations during candidate
rendering.



─── android/app/src/main/java/com/susheng/ime/data/ContentRepository.kt:84-84 ───
[security · high] JSON body is built by raw string interpolation. If an itemId/surface contains a
double quote, backslash, or newline, the request body becomes malformed JSON (or can inject extra
fields). Build the payload with JSONObject so it is escaped correctly.

-                 request(session.getString("server"), "/api/usage", session.getString("token"), """{"itemId":"$itemId","surface":"$surface"}""")
+                 val payload = JSONObject().put("itemId", itemId).put("surface", surface).toString()
+                 request(session.getString("server"), "/api/usage", session.getString("token"), payload)


─── android/app/src/main/java/com/susheng/ime/data/ContentRepository.kt:130-130 ───
[bug · medium] `word` only strips tabs/newlines while `pinyin` is written verbatim. A tab, newline,
or leading '-' inside pinyin (server-controlled data) corrupts the YAML dictionary structure and can
inject arbitrary entries. Sanitize both fields (e.g. strip whitespace/control chars) before writing.

-             lines.add("$word\t${item.pinyin}\t9000")
+             val pinyin = item.pinyin.replace("\t", " ").replace("\n", "").trim()
+             if (pinyin.isBlank()) continue
+             lines.add("$word\t$pinyin\t9000")


─── android/app/src/main/java/com/susheng/ime/data/ContentRepository.kt:226-227 ───
[bug · high] `errorStream` can be null for error responses without a body, causing an NPE on
`stream.bufferedReader()` in `request()` and `login()`, and the HTTP status is never validated
(non-JSON error bodies later fail JSON parsing). The underlying `HttpURLConnection` returned by
`open()` is also never disconnected, leaking sockets/resources until GC. Return an empty string when
the stream is null, surface non-2xx responses as errors, and call `connection.disconnect()` in a
finally block in consumers.

-         val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
-         return stream.bufferedReader().readText()
+         val stream = (if (connection.responseCode >= 400) connection.errorStream else connection.inputStream)
+             ?: return ""
+         return try {
+             stream.bufferedReader().readText()
+         } finally {
+             connection.disconnect()
+         }


─── android/app/src/main/java/com/susheng/ime/data/ContentRepository.kt:89-89 ───
[security · medium] `assetId` comes from server data and is used directly as a file name. A value
containing `../` (or an absolute path) allows path traversal outside `assetDir`, and a value
containing `/` targets a non-existent subdirectory. Validate/sanitize the id (e.g. reject
separators, or use a hashed name).

-     fun assetFile(assetId: String): File = File(assetDir, assetId)
+     fun assetFile(assetId: String): File = File(assetDir, assetId.substringAfterLast('/').substringAfterLast('\\'))


─── android/app/src/main/java/com/susheng/ime/data/ContentRepository.kt:110-113 ───
[bug · medium] All asset download failures are silently swallowed by `runCatching`, and `sync()`
still returns success. This leaves the app believing content is fully synced while assets are
missing, with no way to retry just the failed files. At minimum log/collect failures and reflect
them in the sync result.



─── android/app/src/main/java/com/susheng/ime/data/ContentRepository.kt:73-75 ───
[bug · medium] Persisting the pack happens before assets and the user dictionary are written. If
`downloadAssets`/`writeUserDict` fail midway, the on-disk state (content-pack.json vs assets vs rime
dict) is left inconsistent while the in-memory version is already advanced. Consider writing the
pack only after dependent artifacts succeed, or making the whole sync transactional.



─── android/app/src/main/java/com/susheng/ime/data/ContentRepository.kt:49-49 ───
[bug · medium] JSON parsing in `ContentRepository` is unguarded: `session()` parses the session file
with `JSONObject(sessionFile.readText())` and required `getString("server")/getString("token")`,
while `parseItems` uses required `getString("id")/getString("type")`. A truncated file or a single
malformed item throws and aborts the whole load/sync. Wrap parsing in try/catch (fall back to null /
skip bad rows) and use `optString` or per-item guards.

-         return JSONObject(sessionFile.readText())
+         return runCatching { JSONObject(sessionFile.readText()) }.getOrNull()


─── android/app/src/main/java/com/susheng/ime/data/ContentRepository.kt:25-25 ───
[bug · medium] `ContentRepository` publishes/mutates shared state without synchronization: `picks`
is a lazily-initialized `LinkedHashMap` mutated by `rememberPick`/`picks()` while `reportUsage`
spawns a raw thread, and `items`/`serverVersion`/`epoch` are plain mutable `var`s read across
threads. This can cause lost updates/`ConcurrentModificationException` or torn state. Guard
mutations with synchronization and mark the published vars `@Volatile` (or use a structured
dispatcher).



─── android/app/src/main/java/com/susheng/ime/engine/ImeRules.kt:68-70 ───
[bug · high] The three `@Volatile` tables are published with three separate writes, so a reader
running concurrently with `load()` (e.g. `LexiconBin` reading `weights`/`follows` while
`ContentRepository.loadLexicon()` reloads on a background thread) can observe a torn state: new
`weights` combined with the old `next`/`follows` (or empty tables), producing inconsistent ranking.
Publish the whole set atomically through a single `@Volatile` reference to an immutable holder,
e.g.:
```kotlin
private class Rules(val weights: Map<String, Int>, val next: Map<String, List<String>>, val follows:
Map<String, Set<String>>)
@Volatile private var rules = Rules(DEFAULT_WEIGHTS, emptyMap(), emptyMap())
```
then assign `rules = Rules(weightMap, nextMap, followMap)` once.



─── android/app/src/main/java/com/susheng/ime/engine/ImeRules.kt:46-46 ───
[bug · high] `load()` performs no exception handling: `JSONObject(json)` and the subsequent
`table.getInt(key)`, `table.getJSONArray(key)` and `list.getString(i)` all throw `JSONException` on
malformed/partial JSON. The only production caller wraps this in `runCatching {}` without logging,
so a bad `ime-rules.json` silently aborts the load and leaves stale/default rules in effect with no
diagnostics. Consider tolerating bad entries (`optInt`/`optJSONArray`/`optString`) and/or wrapping
parsing so a failure is surfaced (logged) while keeping the previous valid tables intact.



─── android/app/src/main/java/com/susheng/ime/engine/ImeRules.kt:10-10 ───
[maintainability · medium] `ImeRules` maintains duplicate sources of truth: `DEFAULT_WEIGHTS` is
manually kept in sync with `assets/ime-rules.json`, and `followMap` duplicates the data already in
`nextMap` (`follows` is just `next[key].toHashSet()`). Any drift or missed dual update silently
changes ranking or doubles memory. Prefer a single source (generate/derive one from the other, or
test equality) and store only `next`, deriving a cached `Set` view.



─── android/app/src/main/java/com/susheng/ime/engine/ImeRules.kt:73-73 ───
[maintainability · low] The `?: DEFAULT_WEIGHTS[name]` fallback is effectively dead: `weights` is
always initialized from a full copy of `DEFAULT_WEIGHTS` in `load()` and the initializer, so every
default key is guaranteed present. The extra branch is misleading (it suggests `weights` may be
missing defaults). Either drop it (`weights[name] ?: 0`) or, if intentional defensive programming,
document why.



─── android/app/src/main/java/com/susheng/ime/engine/WubiTable.kt:23-23 ───
[bug · medium] Case-normalization mismatch: `load()` stores the code substring verbatim while
`candidates()`/`first()` look up `code.lowercase()`. Any mixed- or upper-case code in the source
table would be silently unreachable. Normalize at load time so both sides use the same casing.

-                 val code = line.substring(0, split)
+                 val code = line.substring(0, split).lowercase()


─── android/app/src/main/java/com/susheng/ime/engine/WubiTable.kt:38-38 ───
[bug · medium] `candidates()` returns the internal `ArrayList` stored in the map directly. Although
declared as read-only `List<String>`, a caller (or Java interop) casting to `MutableList` can mutate
it and corrupt the shared table snapshot. Return a defensive/immutable copy, or store immutable
lists when building the table.

-         if (!words.isNullOrEmpty()) return words
+         if (!words.isNullOrEmpty()) return words.toList()


─── android/app/src/main/java/com/susheng/ime/engine/WubiTable.kt:26-27 ───
[performance · medium] Duplicate detection `word !in list` performs a linear scan of the `ArrayList`
for every word, giving O(n²) behavior for codes with many candidates. Use a `LinkedHashSet` per code
for O(1) membership while preserving order.

-                 val list = grouped.getOrPut(code) { ArrayList(1) }
-                 if (word !in list) list.add(word)
+                 val set = grouped.getOrPut(code) { LinkedHashSet() }
+                 set.add(word)


─── android/app/src/main/java/com/susheng/ime/engine/WubiTable.kt:39-39 ───
[bug · low] When no match exists and the key length is >= 4, the raw lower-cased code is returned as
the sole candidate. Confirm this pass-through is intentional and that it cannot surface unexpected
letters in the candidate bar (e.g. for unknown/mistyped 4-letter codes).



─── android/app/src/main/java/com/susheng/ime/MainActivity.kt:34-35 ───
[performance · medium] `repo.loadLocal()` performs blocking disk I/O and JSON parsing on the main
thread both in `MainActivity` and in `SushengInputMethodService.onStartInputView` (the latter
contradicts the nearby comment about not re-parsing the lexicon on the main thread). This risks
dropped frames/ANR/OOM. Move the load to a background dispatcher (e.g.
`lifecycleScope.launch(Dispatchers.IO)`) and refresh UI via a posted callback.



─── android/app/src/main/java/com/susheng/ime/MainActivity.kt:134-135 ───
[bug · medium] When the intent jump fails (`Jump.NONE`) the app never actually left for the system
settings, yet `markAwaitPrivacy` has been set and `leftForSystem` remains `true`. Consequently any
later, unrelated focus loss (home / recents) sets `obscured = true` and forces the user onto the
PRIVACY screen; `openingScreen()` on a subsequent cold start will also wrongly route to PRIVACY.
Reset the pending state when nothing was opened.

          val jump = if (enable) ImeSystem.openEnable(this) else ImeSystem.openSwitch(this)
-         if (jump != ImeSystem.Jump.INPUT) showImeGuide()
+         if (jump == ImeSystem.Jump.NONE) {
+             leftForSystem = false
+             KeyboardPrefs.clearAwaitPrivacy(this)
+         } else if (jump != ImeSystem.Jump.INPUT) {
+             showImeGuide()
+         }


─── android/app/src/main/java/com/susheng/ime/MainActivity.kt:70-72 ───
[bug · low] `obscured` and `leftForSystem` are not persisted here, so a configuration change
(rotation) while the user is away in system settings recreates the activity with `leftForSystem =
false` / `obscured = false`. On returning, `revealPrivacyIfReturned()` no longer detects the
departure and the privacy screen is skipped, defeating the consent gate. Save/restore these flags as
well.



─── android/app/src/main/java/com/susheng/ime/MainActivity.kt:37-37 ───
[maintainability · low] `Bundle.getString(key, default)` never returns null when a non-null default
is supplied, so the `!!` is dead null-safety and contradicts the null-safety rule; it also adds
nothing since the whole call is already wrapped in `runCatching`. The same applies to
`savedInstanceState.getString("legal", legalTitle) ?: legalTitle` below, where the Elvis operator is
unreachable. Drop both redundant constructs.

-             screen = runCatching { Screen.valueOf(savedInstanceState.getString("screen", Screen.GUIDE.name)!!) }.getOrDefault(Screen.GUIDE)
+             screen = runCatching { Screen.valueOf(savedInstanceState.getString("screen", Screen.GUIDE.name)) }.getOrDefault(Screen.GUIDE)


─── android/app/src/main/java/com/susheng/ime/MainActivity.kt:121-121 ───
[performance · low] `show()` re-inflates and replaces the whole view tree via `setContentView` on
every navigation, discarding all view state (scroll position, text input) and re-running every page
builder / side-effecting setup. Prefer keeping stable screens and swapping only the content (e.g.
fragments or a container) so state is retained.



─── android/app/src/main/java/com/susheng/ime/MainActivity.kt:60-61 ───
[bug · low] The callback disables itself (`isEnabled = false`) before dispatching the default back
action and never re-enables it. This matches the documented pattern only while the activity actually
finishes; if back is not consumed by finishing the activity, the custom handling is permanently
disabled and later back presses bypass the screen state machine. Consider re-enabling the callback
when appropriate.



─── android/app/src/androidTest/java/com/susheng/ime/AbbrevPinyinTest.kt:48-48 ───
[maintainability · medium] The diagnostic list `misses` only records cases where the **top‑1**
candidate is wrong, and it is capped at 20 entries. Because `rate5 >= rate1` always holds, a
regression that only breaks the top‑5 threshold (e.g. expected word ranks 2‑5) is never
distinguished, and when the first 20 top‑1 misses happen to all be top‑5 hits, the failure detail
printed for the top‑5 assertion contains no relevant example at all. Record top‑5 misses
separately/labelled so the message reflects the assertion that actually failed.

-             if (texts.firstOrNull() == word) top1++ else if (misses.size < 20) misses.add("$raw → ${texts.take(3).joinToString(" ")}，期望 $word")
+             val hit1 = texts.firstOrNull() == word
+             if (hit1) top1++
+             val hit5 = word in texts
+             if (hit5) top5++
+             if ((!hit1 || !hit5) && misses.size < 20) {
+                 misses.add("$raw → ${texts.take(3).joinToString(" ")}，期望 $word（首选命中=$hit1，前五命中=$hit5）")
+             }


─── android/app/src/main/java/com/susheng/ime/ime/LibrimeBridge.kt:30-32 ───
[bug · high] Thread-safety hazard: `loaded` and `attempted` are plain mutable fields mutated without
synchronization, and `available` may be called concurrently (e.g. from the IME service and
background threads). A racing thread can see `attempted == true` while `loaded` is still `false`,
and `ensureLoaded()` will early-return, permanently reporting the library as unavailable even after
loading succeeded. Mark the fields `@Volatile` and guard `ensureLoaded()` with `synchronized` to
make the one-time load atomic.

+     @Synchronized
      private fun ensureLoaded() {
          if (attempted) return
          attempted = true


─── android/app/src/main/java/com/susheng/ime/ime/LibrimeBridge.kt:45-45 ───
[bug · high] Native/reflective calls can return null where a non-null `String` is expected:
`nativeContext` is declared to return `String`, so a native null return throws an NPE; the
reflective `invoke(...) as String` can also throw if the result is null. Declare `nativeContext` as
`String?` with a `?: ""` fallback, and use a safe cast for the reflective result.

-     private external fun nativeContext(session: Long): String
+     private external fun nativeContext(session: Long): String?


─── android/app/src/main/java/com/susheng/ime/ime/LibrimeBridge.kt:23-23 ───
[bug · medium] Iterating `raw` char-by-char uses UTF-16 code units; characters outside the BMP
(surrogate pairs) are split into two bogus keycodes and produce incorrect context. Iterate by code
point (e.g. `raw.codePoints()` / handling surrogate pairs). Also, the `nativeProcessKey` return
value is ignored, so a rejected key is silently dropped instead of being surfaced.

-             for (ch in raw) nativeProcessKey(session, ch.code)
+             var i = 0
+             while (i < raw.length) {
+                 val cp = raw.codePointAt(i)
+                 if (!nativeProcessKey(session, cp)) return ""
+                 i += Character.charCount(cp)
+             }


─── android/app/src/main/java/com/susheng/ime/ime/LibrimeBridge.kt:36-38 ───
[bug · medium] Only `UnsatisfiedLinkError` is caught during library loading. Other
linkage/initialization failures (e.g. `SecurityException`, `ExceptionInInitializerError`, or errors
thrown by the native library's JNI_OnLoad) will escape `available`/`setup` and crash the caller.
Consider catching broader `Throwable`/`LinkageError` (or at least `SecurityException`) and falling
back to `loaded = false`.

-         } catch (_: UnsatisfiedLinkError) {
+         } catch (_: Throwable) {
              loaded = false
          }


─── android/app/src/main/java/com/susheng/ime/engine/HandStrokes.kt:39-41 ───
[bug · high] The buffer size is only validated *after* `buffer.get(magic)` and the two
`buffer.short` reads. If the asset is truncated to fewer than 8 bytes, those reads throw
`BufferUnderflowException` before any length check runs. Add a `bytes.size >= 8` guard before
reading the header (and keep the total-length check for the records).

+             if (bytes.size < 8) return false
              val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
              val magic = ByteArray(4)
              buffer.get(magic)


─── android/app/src/main/java/com/susheng/ime/engine/HandStrokes.kt:27-31 ───
[bug · medium] These fields are written under `synchronized(this)` in `ensure()` but read without
synchronization in `recognize()`. The double-checked locking here is unsafe on the JMM: a second
thread can observe `ready == true` while `labels`/`packed`/`norm2`/`strokeCounts` are still stale or
only partially published, causing wrong results or a crash. Annotate `ready` (and ideally the
backing arrays) with `@Volatile`, or synchronize the read path.



─── android/app/src/main/java/com/susheng/ime/engine/HandStrokes.kt:81-81 ───
[bug · medium] The stroke-count bonus compares inconsistent definitions. `strokeCounts[index]` is
built by `build-hand-strokes.py` as the number of reference strokes with `len(stroke) >= 2` (point
count), while `written` here counts user strokes whose pixel length is `>= 12f`. A correct-length
short stroke in the user input is not counted, so the +0.02 bonus can spuriously fire or miss. Align
the two criteria (e.g. also count user strokes with >= 2 points, or document why the threshold
differs).



─── android/app/src/main/java/com/susheng/ime/engine/HandStrokes.kt:140-140 ───
[bug · low] Rounding differs from the Python reference. `build-hand-strokes.py` uses `np.rint`,
which rounds half-to-even, whereas `roundToInt()` rounds half away from zero (Math.round semantics).
At values landing exactly on `.5` the quantized template/user vectors diverge, breaking the intended
bit-exact parity with the generator. Consider matching the reference rounding or confirming the
difference is negligible.



─── android/app/src/main/java/com/susheng/ime/engine/HandStrokes.kt:70-70 ───
[maintainability · low] The fallback candidate list `listOf("丶", "。", "〇", "一")` is duplicated in
two branches of `recognize()`. Extract it to a private constant to keep a single source of truth and
avoid them drifting apart.



─── android/app/src/main/java/com/susheng/ime/engine/PinyinEngine.kt:48-48 ───
[bug · medium] `startingCache` is a plain `HashMap` that is mutated lazily from `syllablesStarting`
via `getOrPut` on every keyboard interaction. `PinyinEngine` is an object (singleton) reachable from
the IME service and from background/test callers, so if it is ever hit from more than one thread,
concurrent `getOrPut` can corrupt the map or throw (lost updates / infinite loop in HashMap.resize).
Use a thread-safe cache, e.g. `ConcurrentHashMap` (or guard access with a lock / use
`Collections.synchronizedMap`).

-     private val startingCache = HashMap<String, List<String>>()
+     private val startingCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()


─── android/app/src/main/java/com/susheng/ime/engine/PinyinEngine.kt:169-169 ───
[performance · medium] `Regex("[^a-z]")` is compiled repeatedly in hot paths: in `compact()` (called
per lexicon entry per keystroke) and for the loop-invariant `letters` inside
`scorePinyin`/`extendsByOneSyllable`. Hoist the pattern to a `private val` (or replace with `filter
{ it in 'a'..'z' }`) and compute `letters` once outside the entry loop.

-     private fun compact(pinyin: String) = pinyin.lowercase().replace(Regex("[^a-z]"), "")
+     private val nonAsciiLetters = Regex("[^a-z]")
+     private fun compact(pinyin: String) = pinyin.lowercase().replace(nonAsciiLetters, "")


─── android/app/src/main/java/com/susheng/ime/engine/PinyinEngine.kt:72-72 ───
[maintainability · low] Simplify to `shortcut.startsWith(input)`; the `shortcut == input` clause is
redundant because `startsWith` already covers equality. (This also drops the stray braces noted in
the follow-up.)

-                 if (shortcut.isNotEmpty() && (shortcut == input || shortcut.startsWith(input))) {
+                 if (shortcut.isNotEmpty() && shortcut.startsWith(input)) {}


─── android/app/src/main/java/com/susheng/ime/engine/StrokeTable.kt:110-110 ───
[bug · high] Turn detection only triggers when the dot product of consecutive segments is strictly
negative, i.e. the direction reverses by more than 90°. A stroke with an (approximately) right-angle
corner (横折 etc.) produces a dot product of ~0, so `turn` stays false. Concretely, a 横折 from
(0,0)->(10,0)->(10,10) never sets `turn`, and the later direction checks then classify it as '4'
(点捺) since dx>0 && dy>0 — a wrong stroke type. Use a direction-change threshold based on the
normalized angle instead of a bare `< 0f` sign test.



─── android/app/src/main/java/com/susheng/ime/engine/StrokeTable.kt:67-68 ───
[bug · medium] Table parsing is unguarded: `split(" ")` (a single literal space, not whitespace)
returns empty entries for multiple consecutive spaces, and a blank line yields a single-element
list. Indexing `parts[1]` then throws `IndexOutOfBoundsException` at class-initialization time, and
a stray double space silently produces an empty word string. Validate the parsed row and fail with a
clear message instead of blindly indexing.

-             val parts = line.trim().split(" ")
+             val parts = line.trim().split(Regex("\\s+"))
+             require(parts.size >= 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) { "bad stroke row: $line" }
              parts[0] to parts[1]


─── android/app/src/main/java/com/susheng/ime/engine/StrokeTable.kt:114-114 ───
[maintainability · low] Two redundant constructs in `StrokeTable`: the `abs(dx) + abs(dy) > 0f`
guard on the turn branch never changes behavior (the method already falls through to `'5'` when
`start == end`), and the initialization-time `sortedBy { it.first.length }` is redundant because
`candidates` re-sorts with a comparator that already fully orders by length. Drop both to avoid
noise/extra passes.

-         if (turn && abs(dx) + abs(dy) > 0f) return '5'
+         if (turn) return '5'


─── android/app/src/main/java/com/susheng/ime/engine/StrokeTable.kt:98-98 ───
[maintainability · low] `classify` is never called anywhere in the project (only this declaration
matches a repository-wide search). If it is not intended as a soon-to-be-used public API, it is dead
code and should be removed; otherwise a caller/tests should be added so the heuristic is actually
exercised.



─── android/app/src/main/java/com/susheng/ime/engine/StrokeTable.kt:79-79 ───
[maintainability · low] The `else -> ch.toString()` branch passes any unrecognized character through
unchanged. Since the intent is a stroke-symbol mapping, unexpected input silently leaks downstream
(e.g. digits/latin typed in the stroke field). Consider returning an empty string or a defined
placeholder for unsupported characters so callers can filter them out.



─── android/app/src/main/java/com/susheng/ime/skin/KeyboardSkins.kt:74-78 ───
[bug · high] Invalid skin ids are handled silently: `applySkin` writes any id into `owned_skins` and
the `skin` pref (bypassing ownership gating), while `byId` falls back to `skins[0]` for unknown ids.
This hides catalog/prefs mismatches and can render an unrelated skin. Validate the id against the
catalog and the owned set before persisting, and surface/clamp invalid stored ids instead of
silently defaulting.

      fun applySkin(context: Context, id: Int) {
+         if (KeyboardSkins.skins.none { it.id == id }) return
          val next = owned(context).map { it.toString() }.toMutableSet()
          next.add(id.toString())
          prefs(context).edit().putInt("skin", id).putStringSet("owned_skins", next).apply()
      }


─── android/app/src/main/java/com/susheng/ime/skin/KeyboardSkins.kt:85-86 ───
[bug · medium] `onMeasure` ignores the height MeasureSpec mode entirely and always overrides the
measured height with `width * 0.62`. Under a parent that imposes an EXACTLY or AT_MOST height (e.g.
wrap_content or a fixed-height container), this returns a size that violates the parent's
constraints, producing layout/clipping errors. Also, when the width spec is UNSPECIFIED, `getSize`
returns 0, giving a 1px-tall view. Resolve the desired height first and then reconcile it with the
measured spec.

          val width = MeasureSpec.getSize(widthMeasureSpec)
-         setMeasuredDimension(width, (width * 0.62f).toInt().coerceAtLeast(1))
+         val desired = (width * 0.62f).toInt().coerceAtLeast(1)
+         val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
+             MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
+             MeasureSpec.AT_MOST -> desired.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
+             else -> desired
+         }
+         setMeasuredDimension(width, height.coerceAtLeast(1))


─── android/app/src/main/java/com/susheng/ime/engine/LexiconBin.kt:15-15 ───
[bug · high] `LexiconBin.exactInitials` and `charFreqCache` are plain (non-`@Volatile`) mutable
fields read outside the lock / on hot lookup paths while potentially populated concurrently. Without
`@Volatile` a reader can observe a partially published `Map`/`IntArray`. Mark both fields
`@Volatile` (and keep construction idempotent).

+     @Volatile
      private var exactInitials: Map<String, IntArray>? = null


─── android/app/src/main/java/com/susheng/ime/engine/LexiconBin.kt:18-18 ───
[bug · medium] Only the magic and a minimum length are validated here; all subsequent cursor
arithmetic (`data[cursor]`, `u16`/`u32`, `String(data, cursor, len, …)`, `IntArray(count)`) trusts
the blob implicitly. A truncated or corrupt lexicon throws
`ArrayIndexOutOfBoundsException`/`NegativeArraySizeException`/`OutOfMemoryError` at parse time
instead of failing gracefully. Consider bounds-checking offsets (and the derived entry layout)
before reading, and surface a clear error.



─── android/app/src/main/java/com/susheng/ime/engine/LexiconBin.kt:878-878 ───
[maintainability · low] `WordEdge.singleTail` is populated in `mixedSentencesFor` and threaded
through `WordEdge`/`PathState`, but it is never read anywhere — leftover/unimplemented scoring
state. Either wire it into the scoring/ranking or remove the field and its assignments to avoid dead
state.



─── android/app/src/main/java/com/susheng/ime/engine/LexiconBin.kt:151-152 ───
[maintainability · low] The add-then-immediately-remove pattern on `seen` is only a membership test,
but it mutates shared state transiently: any change in control flow or a thrown exception between
the two calls would leave a stale mark and silently drop later results. Use a plain membership check
instead.

-                 if (!seen.add(index)) continue
-                 seen.remove(index)
+                 if (index in seen) continue


─── android/app/src/main/java/com/susheng/ime/engine/LexiconBin.kt:540-540 ───
[performance · medium] `matchMixed` recomputes `mixOptions(it)` (which sorts
`PinyinEngine.syllablesStarting(spell)` via `sortedWith`) for every sub-span on every call, and
`mixedSentencesFor` invokes `matchMixed` O(n) times per keystroke. The syllable ranking is
input-independent, so cache the result per spell (e.g. a memo map) to avoid repeated
sorting/allocation on the hot path.



─── android/app/src/main/java/com/susheng/ime/engine/LexiconBin.kt:362-363 ───
[performance · low] `initialBest` linearly scans the entire syllable table for each distinct first
letter. Since the per-syllable weight is static, precompute the best weight per initial (a 26-entry
table) once instead of rescanning `syllables` on every call.



─── android/app/src/main/java/com/susheng/ime/ui/LegalCopy.kt:6-6 ───
[maintainability · medium] `LegalCopy` duplicates the title strings between `titles` and the `when
(title)` branch keys, and the fifth title `"第三方信息共享清单"` has no explicit branch, falling into an
`else` that also catches every unknown/misspelled title. A typo or added title silently renders the
wrong legal document. Use a single source of truth (e.g. `Map<String, String>` or an enum with
`displayName` + body) and make unknown titles fail loudly instead of falling through to the
third-party document.



─── android/app/src/main/java/com/susheng/ime/ime/ImeChrome.kt:1202-1203 ───
[bug · critical] Pending runnables are not cancelled when the view is detached or replaced:
`bindRepeat` self-reposts forever and is only cancelled by `ACTION_UP`/`ACTION_CANCEL` on the same
view, so a detached view can keep invoking `action()` and leak the Handler; the previous `HandPad`'s
`recognize`/`idleClear` runnables can also fire after `handPad` is overwritten or the face leaves
`HAND`. Stop these in `onDetachedFromWindow` / before overwriting (`handPad?.clear()`) and cancel
from the IME controller when the keyboard is rebuilt/hidden.



─── android/app/src/main/java/com/susheng/ime/ime/ImeChrome.kt:956-956 ───
[performance · high] Decoding with `BitmapFactory.decodeFile` runs on the UI thread with no
downsampling and no null check. Full-size sticker/photo files can be very large (multi-MB), so this
risks jank and `OutOfMemoryError` when the sticker grid is rebuilt on every relayout (each rebuild
re-decodes every visible cell). Decode with an `inSampleSize` computed from the target `px(72)` size
and guard the result, e.g.:
```kotlin
ime.assetFile(item)?.let { file ->
    val bmp = decodeSampled(file.absolutePath, px(72))
    if (bmp != null) image.setImageBitmap(bmp)
}
```
and prefer decoding off the main thread.



─── android/app/src/main/java/com/susheng/ime/ime/ImeChrome.kt:939-939 ───
[maintainability · medium] Two pieces of dead state in `ImeChrome`: `if (items.isEmpty()) Unit` is a
no-op and the `items` parameter is never used (the method re-queries `ime.itemsOf(...)`), and
`expandCandidates` is only ever assigned `false`, so `extra.visibility` is always `GONE` and the
`list.drop(6)` branch is unreachable. Remove the dead statement/parameter and either wire up the
expand trigger or drop the `extraRow` plumbing.



─── android/app/src/main/java/com/susheng/ime/ime/ImeChrome.kt:644-644 ───
[maintainability · low] `ime.let { colors.accent }` uses `let` with an unused, shadowing receiver —
it is just `colors.accent` and reads confusingly. Simplify to `key.setTextColor(colors.accent)`.



─── android/app/src/main/java/com/susheng/ime/ui/AppUi.kt:84-85 ───
[bug · medium] `full.indexOf(label)` only binds the first occurrence of each label and can match a
label that appears as a substring of earlier text (e.g. label "协议" inside "用户协议"). It also does not
guard against overlapping label ranges, so two `ClickableSpan`s may overlap and one of them silently
becomes unclickable. Consider extracting labels in order while advancing a cursor, and skipping
labels whose range intersects an already-applied span. Additionally, this helper only returns the
spans; it does not attach a `LinkMovementMethod`, so any caller other than `linkedParagraph` will
produce non-clickable links with no compile-time signal.



─── android/app/src/main/java/com/susheng/ime/ui/AppUi.kt:103-104 ───
[maintainability · low] `tv(...)` already assigns `this.text = full`, which is then immediately
overwritten with the `SpannableString` from `linkText(...)`. This performs a redundant `setText` +
layout invalidation pass and obscures intent. Consider passing an empty string to `tv` (or building
the `TextView` without pre-setting text) so the text is only set once.



─── android/app/src/main/java/com/susheng/ime/ui/AppUi.kt:63-63 ───
[maintainability · low] `contentDescription` is a hardcoded string literal. This bypasses
localization and accessibility string handling; prefer a string resource (e.g. `R.string.app_name` /
a dedicated `contentDescription` resource) so it can be translated and reused.



─── android/app/src/main/java/com/susheng/ime/ui/AppUi.kt:97-100 ───
[maintainability · low] `MatchColumn`'s name and its overridden `generateDefaultLayoutParams` imply
that every child added to the column gets `MATCH_PARENT` width, but this only applies to children
added without explicit `LayoutParams`. Callers that pass their own params (e.g. `fill()` with
margins) bypass this behavior, making the guarantee misleading. Consider documenting the actual
scope of the override, or enforce matching width via `onMeasure`/`onLayout` if the invariant is
really required.



─── android/app/src/main/java/com/susheng/ime/ime/SushengInputMethodService.kt:62-68 ───
[bug · medium] This detached thread calls `repo.warmLexicon()` (and HandPinyin/HandModel.ensure)
while the main thread simultaneously calls `repo.loadLocal()` in `onStartInputView`. `repo` is
shared mutable state, so concurrent mutation from this untracked thread can produce lost
updates/corruption. The thread is also never cancelled or joined, so it can outlive the service.
Consider a lifecycle-scoped coroutine (or the repository guarding its own state) plus
synchronization.



─── android/app/src/main/java/com/susheng/ime/ime/SushengInputMethodService.kt:231-235 ───
[bug · medium] The recognition worker has no exception handling and no `finally` for
`bitmap.recycle()`. If `inkBitmap(...)` or `HandModel.recognize(...)` throws, the thread dies
silently, the `handToken` is never cleared and the UI is left stuck (no candidates, no error). Wrap
the body in try/catch/finally so the bitmap is always recycled and a terminal state is posted to the
main thread on failure.



─── android/app/src/main/java/com/susheng/ime/ime/SushengInputMethodService.kt:587-587 ───
[bug · medium] The MIME type is hard-coded to `image/png` regardless of the actual file type. If the
asset is a JPG/WebP, the `InputContentInfoCompat` advertises a wrong MIME, which can make
`commitContent` fail or be rejected by the receiving editor. Derive it from the file (e.g.
`contentResolver.getType(uri)` or the file extension) after building `uri`.



─── android/app/src/main/java/com/susheng/ime/ime/SushengInputMethodService.kt:636-640 ───
[bug · medium] `writeText` performs synchronous file I/O with no error handling, and `saveClips()`
is invoked from `captureClip()`, which runs on the main thread via the
`OnPrimaryClipChangedListener` callback. This both blocks the UI thread and can crash the IME on an
IO error. Guard the write (and ideally move it off the main thread).

      private fun saveClips() {
          val array = JSONArray()
          clips.forEach { array.put(it) }
-         File(filesDir, "clipboard-history.json").writeText(array.toString())
+         runCatching { File(filesDir, "clipboard-history.json").writeText(array.toString()) }
      }


─── android/app/src/main/java/com/susheng/ime/ime/SushengInputMethodService.kt:608-608 ───
[security · high] `clipboard.primaryClip` can throw `SecurityException` on Android 10+ when the app
is not the focused window (background clipboard access restriction), and the listener fires for
every clipboard change. Wrap the access in `runCatching`. Separately, this auto-captures and
persists any clipboard text (including passwords/OTP) in plaintext to `clipboard-history.json`
without consent — consider filtering/skip-sensitive handling.

-         val clip = clipboard.primaryClip
+         val clip = runCatching { clipboard.primaryClip }.getOrNull()


─── android/app/src/main/java/com/susheng/ime/ime/SushengInputMethodService.kt:454-454 ───
[maintainability · low] Mutating `view.isSoundEffectsEnabled = false` permanently changes the
(reused) view's flag, affecting all its future touch feedback rather than just this tap. Prefer
omitting this line, or restore the previous value after `performHapticFeedback`.



─── android/app/src/main/java/com/susheng/ime/ui/ImeSystem.kt:45-45 ───
[performance · medium] `ImeSystem` recomputes expensive device detection on every call:
`harmonyMajor()` recompiles two regexes and performs up to 8 reflective `systemProperty` reads, and
`isHuaweiFamily()` is evaluated multiple times per flow (each call lowercases 4 `Build` fields and
attempts a reflective `BuildEx` lookup). Hoist/lazy-cache both results so the regex/reflection work
runs once.



─── android/app/src/main/java/com/susheng/ime/ui/ImeSystem.kt:110-110 ───
[bug · medium] With `requireResolve = false`, success is reported purely because `startActivity()`
did not throw. If the hardcoded Harmony component exists but ignores the `set_input` action/URI
(common across firmware variants), `openSetInput` returns true and `openHarmonyInput` reports
`Jump.INPUT` even though the user never reached the input-method page, so the caller believes the
jump succeeded. Verify the target is actually reachable, or document/limit this behavior and let the
language-and-input fallback run.



─── android/app/src/main/java/com/susheng/ime/ui/ImeSystem.kt:148-148 ───
[bug · low] `Context.getSystemService(Class)` is nullable; calls like
`activity.getSystemService(InputMethodManager::class.java).showInputMethodPicker()` and
`imm.enabledInputMethodList` can NPE when the service is unavailable. Use safe calls and explicit
fallbacks in `ImeSystem` and `GatePages`.

-             activity.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
+             activity.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()


─── android/app/src/main/java/com/susheng/ime/ui/GatePages.kt:56-56 ───
[bug · low] Every enabled IME is rendered with a '✓' regardless of whether it is the currently
selected default input method. The list therefore cannot distinguish the active keyboard from merely
enabled ones, which is data-inaccurate. Consider showing '✓' only for the current IME (compare
against `currentImeName`/`DEFAULT_INPUT_METHOD`) or use a radio-style indicator.



─── android/app/src/main/java/com/susheng/ime/ui/GatePages.kt:25-25 ───
[style · low] This actionable step button uses a light gray background (0xFFE4E6EA) and gray text
(0xFF9AA0A6), which visually reads as a disabled button. Since it is the primary first-step action
the user must tap, it should use an enabled-looking style (e.g., a filled or outlined accent color)
to avoid confusing users about what to tap first.



─── android/app/src/main/java/com/susheng/ime/ui/GatePages.kt:54-54 ───
[performance · low] Building the guide card triggers `enabledInputMethodList` and `loadLabel`
(package-manager lookups) twice on the UI thread — once in `currentImeName` and again in
`enabledImes`. Query the IME list once (or cache the result) and reuse it for both the default name
and the enabled list to avoid redundant work during layout construction.



─── android/app/src/main/java/com/susheng/ime/ui/HomePages.kt:334-337 ───
[bug · high] Raw `Thread` with no lifecycle/coroutine scope. If the activity is destroyed (e.g.
rotation or user leaves) while login is in flight, the thread keeps running, holds a strong
reference to `activity`/`status`, and mutates the shared `repo` state off the main thread. Prefer a
structured scope, e.g. `activity.lifecycleScope.launch { val msg = withContext(Dispatchers.IO) {
runCatching { repo.login(host, user, pass) }... }; status.text = msg }`.



─── android/app/src/main/java/com/susheng/ime/ui/HomePages.kt:342-346 ───
[bug · high] `repo.loadLocal()` runs outside the `runCatching` and on a raw background thread. If
the content-pack file is missing/corrupt, `loadLocal()` throws (e.g. JSON parse error); an uncaught
exception on a non-main thread kills the whole process. It is also redundant here since `sync()`
already refreshes `items`/`serverVersion`/`epoch`. Move it inside the guarded block (or drop it) and
use a lifecycle-aware coroutine scope.



─── android/app/src/main/java/com/susheng/ime/ui/HomePages.kt:320-320 ───
[security · medium] Credentials are typed here and later sent via `repo.login(host, user, pass)`.
The default host `http://10.0.2.2:8787` uses cleartext HTTP, so the account/password leave the
device unencrypted if the user keeps the default. Consider defaulting to/requiring `https://` and
rejecting non-TLS hosts before submitting credentials.



─── android/app/src/main/java/com/susheng/ime/ui/HomePages.kt:67-68 ───
[performance · low] `refill()` tears down and rebuilds every card, and it is invoked on every single
keystroke via the `TextWatcher`. This creates/measures many views per character on the main thread.
Consider debouncing input and/or skipping the refill when the trimmed query is unchanged.



─── android/app/src/main/java/com/susheng/ime/ui/HomePages.kt:197-197 ───
[performance · low] `setOf("script", "tweet", "promo")` is allocated once per item inside the
`filter` lambda. Hoist it to a `private val` constant and reference it here so no set is allocated
during iteration.



─── android/app/src/main/java/com/susheng/ime/ui/HomePages.kt:129-130 ───
[maintainability · low] `fillSets` is a near-verbatim copy of `fillSkins` (only the source list and
empty-state text differ), just as `setCard` mirrors `skinCard`. Duplicated grid/card construction
means every layout or behavior fix must be applied twice and risks divergence. Extract a shared
generic helper (e.g. `fillCards(items, cardFactory, emptyText)`).



─── android/build.gradle.kts:2-3 ───
[maintainability · low] Plugin versions are hardcoded inline here rather than centralized in a
Gradle version catalog. Since the app module applies these plugins without versions, a future
version bump must be kept consistent across the project; sourcing them from a
`gradle/libs.versions.toml` (e.g. `alias(libs.plugins.android.application) apply false`) reduces
drift and simplifies updates. Note that no version catalog is currently declared in
`settings.gradle.kts`, so one would need to be added first.



─── android/app/build.gradle.kts:20-22 ───
[security · medium] The `release` build type is not hardened: `isMinifyEnabled = false` disables R8
shrinking/obfuscation and `isShrinkResources` is unset, there are no `proguardFiles`, and no
`signingConfig` is declared. As a result the release variant produces an unsigned, unshrunk APK. If
this app is distributed (even internally), consider enabling minification with a rules file and
wiring a release signing config (or explicitly document that signing happens externally in CI) so
artifacts aren't accidentally shipped unsigned or readable.

          release {
-             isMinifyEnabled = false
+             isMinifyEnabled = true
+             isShrinkResources = true
+             proguardFiles(
+                 getDefaultProguardFile("proguard-android-optimize.txt"),
+                 "proguard-rules.pro"
+             )
+             // signingConfig = signingConfigs.getByName("release")
          }


─── android/app/build.gradle.kts:39-39 ───
[bug · medium] Only `armeabi-v7a` and `arm64-v8a` are built. Any x86_64 target (most modern
emulators, some CI/desktop targets) will fail to install/run because there is no native
`librime`/IME library for it. If emulator/CI testing is required, add `x86_64` to the ABI list;
otherwise explicitly document that ARM-only is intentional.



─── android/app/build.gradle.kts:35-35 ───
[maintainability · medium] `assets.srcDir("../../rime")` points outside the Android module with no
include/exclude filtering. Packaging now silently depends on repository layout and will bundle
*everything* under `rime/` (including git metadata, build artifacts, or unintended dictionaries),
and the build breaks if the module is moved or checked out standalone. Prefer a dedicated, filtered
asset source (e.g. an explicit include/exclude filter or a copy task) so the packaged asset set is
deterministic.



─── android/app/build.gradle.kts:15-15 ───
[maintainability · low] Version information is maintained in three places by hand: `versionCode =
1015`, `versionName = "1.0.1.5"`, and the artifact name
`su-ime-${android.defaultConfig.versionCode}`. These drift easily (note `versionCode`/`versionName`
are already mechanically inconsistent), leading to misleading artifact names or release-upload
errors. Consider a single source of truth (e.g. a version catalog or a properties file) read by all
three.



─── macos/Sources/Store.m:64-66 ───
[security · critical] Path traversal / unsafe file write. `assetId` comes straight from the server
(content pack `assetId` fields) and is appended as a path component, then written to disk in
`downloadAssets:`. An id such as `../../../Library/...` escapes the `assets` directory and lets a
malicious/compromised server write arbitrary files under the user's home, and the same value is
interpolated into the request URL. Validate/sanitize the id (reject path separators, `..`, and empty
values) and resolve the final path to confirm it is still inside the assets directory before
writing.



─── macos/Sources/Store.m:185-185 ───
[bug · medium] The URL is built by string interpolation and is never checked for nil. If `assetId`
(or the server URL) contains characters that make the string an invalid URL, `URLWithString:`
returns nil and `requestWithURL:nil` raises an NSInvalidArgumentException. Guard against a nil URL
before creating the request, and percent-encode the path component.



─── macos/Sources/Store.m:157-161 ───
[bug · high] Queued usage events are dropped on failure. The event is removed from the queue before
the POST is issued, and the completion advances via `step()` regardless of the returned `error`, so
any upload that fails is discarded and never retried. Only advance the local queue (and persist it)
when the request succeeds; on error, re-queue the event or stop. Also note the final `writeJSON:@[]`
clobbers events appended by `reportUsage:` while the flush is in flight.



─── macos/Sources/Store.m:74-74 ───
[bug · medium] Unvalidated decoded JSON is consumed without type checks, so a malformed/tampered
response or content pack can crash with an unrecognized-selector exception. `json[@"user"]` may be
`NSNull`, an array, or a string when the key is absent or unexpected — type-check that it is an
NSDictionary and that `displayName` is an NSString before use. Likewise, `pack[@"items"]` is passed
to `replaceItems:` without verifying it is an NSArray; guard with `isKindOfClass:[NSArray class]` as
is done elsewhere in this file.



─── macos/Sources/Store.m:133-135 ───
[bug · low] `SushengQuery`'s result is used without a NULL check: if the C bridge ever returns NULL
the `strlen(result)` call dereferences it and crashes. The current Go implementation happens to
always return a non-NULL buffer, but nothing in the declared C contract (`char *SushengQuery(const
char *)`) guarantees it, and this file is the only guard. Return early when `result == NULL` before
calling `strlen`/`SushengFree`.



─── macos/Sources/Store.m:37-37 ───
[maintainability · low] The readonly `items` property returns the internal mutable array directly,
so callers can cast it to NSMutableArray and mutate state the class assumes it owns. Return an
immutable copy (or `[NSArray arrayWithArray:_items]`) to preserve the encapsulation contract
declared in Store.h.



─── macos/Sources/Home.m:53-53 ───
[bug · high] `releasedWhenClosed` defaults to `YES` for programmatically created `NSWindow`s. Under
ARC the close button therefore triggers an extra AppKit release on top of the one held by the strong
`_window` ivar, so closing the panel can deallocate the window while `_window` still points at it
(dangling strong reference / use-after-free on the next `show`). Explicitly opt out of the
auto-release.

      _window.title = @"苏晟输入法";
+     _window.releasedWhenClosed = NO;


─── macos/Sources/Home.m:52-52 ───
[bug · high] `show` allocates a brand-new `NSWindow` on every invocation and overwrites `_window`
without closing or reusing the previous one. The old window is still on screen (AppKit keeps it
alive), so repeated `SushengPresentHome` calls leave multiple windows floating around the user with
only the newest referenced — and the previously referenced window leaks. Guard on the existing
window and just re-front it.



─── macos/Sources/Home.m:89-89 ───
[bug · medium] `pick:` only retitles the clicked button, so the previously selected button keeps its
`· 使用中` suffix. After switching skins the UI shows two buttons marked as active while only the
persisted value reflects the real choice. Update every skin button (or track/reset the previous one)
so exactly the currently selected skin carries the marker.

-         [sender setTitle:[NSString stringWithFormat:@"%@ · 使用中", names[idValue]]];
+         for (NSButton *b in sender.superview.subviews) {
+             if (![b isKindOfClass:[NSButton class]] || b.tag < 0 || b.tag > 5) continue;
+             NSString *n = names[b.tag];
+             b.title = (b.tag == idValue) ? [n stringByAppendingString:@" · 使用中"] : n;
+         }


─── macos/Sources/Home.m:87-87 ───
[bug · low] `sender.tag` is used directly to index the 6-element `names` array with no bounds check.
Any other sender or tag value reaching this action reads out of bounds and produces garbage (and
would then be persisted as the skin index). Validate the tag against the 0–5 range the buttons
actually use before indexing.

      NSInteger idValue = [sender tag];
+     if (idValue < 0 || idValue > 5) return;


─── macos/Sources/main.m:9-9 ───
[bug · medium] The only strong reference to `IMKServer` is this local inside the `@autoreleasepool`.
The object must stay alive for the entire process lifetime because deallocating it closes the
input-method connection, but an ARC local variable that is never used again may be released as early
as its last use (this is why `objc_precise_lifetime` exists). Relying on the implicit end-of-scope
release here is fragile and can silently drop the IME connection before `[app run]`. Hold the server
in a `static` (or an app-delegate ivar) to guarantee the connection survives the whole run loop.

-         IMKServer *server = [[IMKServer alloc] initWithName:@"SushengIM_Connection" bundleIdentifier:identifier];
+         static IMKServer *server = nil;
+         server = [[IMKServer alloc] initWithName:@"SushengIM_Connection" bundleIdentifier:identifier];


─── macos/Sources/main.m:10-10 ───
[bug · low] `-initWithName:bundleIdentifier:` returns `nil` when the server cannot be created (e.g.
the connection name is already taken or the bundle is not properly installed). That failure is
discarded here, so a failed input-method registration is treated as success and the app keeps
running with a non-functional IME. At minimum log/surface the failure instead of discarding it.

-         (void)server;
+         if (!server) NSLog(@"IMKServer 创建失败，输入法未注册：%@", identifier);


─── ios/Rime/RimeBridge.m:22-22 ───
[bug · medium] `*cursor` is a signed `char`, so any UTF-8 byte >= 0x80 sign-extends to a negative
keycode when cast to `int`, and iterating raw bytes also splits multi-byte characters into separate
`process_key` calls. Input containing non-ASCII text would be mangled (the current Swift caller
restricts `raw` to `[a-zA-Z0-9']`, so this is latent but the exported API accepts arbitrary
strings). Cast through `unsigned char` at minimum, and consider mapping code points/keycodes
correctly if non-ASCII input is ever expected.

-         api->process_key(session, (int)(*cursor), 0);
+         api->process_key(session, (int)(unsigned char)(*cursor), 0);


─── ios/Rime/RimeBridge.m:4-7 ───
[maintainability · medium] This file enables the linked path purely from header presence, unlike the
Android bridge which gates on `#if defined(SUSHENG_HAS_RIME) && __has_include("rime_api.h")`. If
`rime_api.h` ends up in `HEADER_SEARCH_PATHS` but librime is not linked, `SUSHENG_RIME_LINKED` is
defined and the direct `rime_get_api()` call fails to link, defeating the graceful "use built-in
pinyin when not linked" fallback documented in RimeBridge.h. Add the same explicit build-flag guard
for consistency.

- #if __has_include("rime_api.h")
+ #if defined(SUSHENG_HAS_RIME) && __has_include("rime_api.h")
  #include "rime_api.h"
  #define SUSHENG_RIME_LINKED 1
  #endif


─── ios/Rime/RimeBridge.m:9-11 ───
[bug · low] Availability detection (runtime `dlsym`) and the compile-time gate in
`SushengRimeContext` can disagree: this function returns `YES` whenever `rime_get_api` is
resolvable, but `SushengRimeContext` compiles to `return @"";` in the `#else` branch when the header
is absent, even though the symbol is present. Conversely, when the header is present the context
body calls `rime_get_api()` directly, making the `dlsym` check redundant. Align the two paths so
`SushengRimeAvailable()` and `SushengRimeContext()` report the same capability.



─── ios/Rime/RimeBridge.m:18-18 ───
[performance · low] A brand-new Rime session is created and destroyed on every invocation, and the
entire `raw` input is replayed key-by-key each time. Since the Swift caller
(`KeyboardViewController.currentCandidates`) calls this on every keystroke, the work grows
quadratically with input length and discards the session state librime normally keeps per
composition. Consider persisting/reusing a session and feeding only incremental keys instead of
recreating it per call.



─── macos/Sources/InputController.m:367-367 ───
[bug · high] Selecting a palette image shows a confirmation hint via `updateHeader:`, but the very
next statements clear the palette and call `refresh:`. At that point `_raw`, `_associating`, `_rows`
and `_paletteItems` are all empty, so `refresh:` reaches `if (!header.length && !lines.count) {
[_panel hide]; return; }` and immediately hides the panel. The "已复制图片，请在当前应用粘贴" hint is therefore
never visible. Skip the post-insert refresh for image items (the `choose:` row path deliberately
returns without refreshing for the same reason).

-         [self insertItem:_paletteItems[index] client:client];
+         NSDictionary *picked = _paletteItems[index];
+         BOOL isImage = [picked[@"type"] isEqualToString:@"image"];
+         [self insertItem:picked client:client];
+         _palette = nil;
+         if (!isImage) [self refresh:client];


─── macos/Sources/InputController.m:395-395 ───
[bug · medium] When `copyImage:` fails (asset not yet downloaded, missing/empty `assetId`, or read
error) `insertItem:` returns without any user feedback. The caller then dismisses the palette, so
choosing an image silently does nothing — inconsistent with `choose:`, which reports "图片还没同步下来" on
the same failure. Report the failure here as well (and guard `assetId` length).



─── macos/Sources/InputController.m:429-429 ───
[bug · medium] The caret rectangle is queried at a fixed character index of 0, i.e. the start of the
client's text, not the current insertion point. `cursor:` anchors the candidate/palette panel, so
the panel can be positioned at the wrong location (e.g. the beginning of the document) whenever the
caret is not at index 0. Query the rect for the actual insertion point (e.g. the client's
`selectedRange.location`).



─── macos/Sources/InputController.m:65-66 ───
[bug · medium] The flip/off-screen clamp uses only `[NSScreen mainScreen]` (the screen with the menu
bar / key window). The caret rect from the text client is in global screen coordinates and may lie
on a different display, so on multi-monitor setups this comparison uses the wrong screen height and
the panel can be placed off-screen or on the wrong display. Locate the screen that actually contains
`rect` (iterate `[NSScreen screens]`) and clamp against its visibleFrame.



─── macos/Sources/InputController.m:420-421 ───
[performance · low] `copyImage:` performs a synchronous file read plus a general pasteboard write
directly inside key-event handling, so a large asset can stall input processing on the main thread.
It also uses the raw UTI literal `@"public.png"` instead of the `NSPasteboardTypePNG` constant, and
ignores the return value of `setData:forType:`. Consider loading the data off the critical path and
using the named pasteboard type constant.



─── protocol/ime-10k.test.mjs:9-10 ───
[maintainability · medium] Module-level `readFileSync` + `JSON.parse` run at import time with no
error handling in the fixture-loading test files (ime-10k, pinyin-daily, pinyin-abbrev). If a
fixture is missing, empty, or malformed the suite fails with an opaque uncaught exception
(ENOENT/SyntaxError) before any test runs, and `.entries`/row shape may be silently `undefined`.
Wrap loading in a helper with a descriptive error, validate the parsed shape, and/or lazy-load
inside the tests.



─── backend/test/api.test.mjs:21-23 ───
[bug · medium] Several backend API tests parse or rely on responses without asserting the HTTP
status first (`await response.json()` before `assert.equal(response.status, 200)`,
`fetch(...).then((r) => r.json())`, and an unasserted PATCH). A 4xx/5xx or non-JSON body therefore
surfaces as an opaque `JSON.parse` error or a misleading downstream assertion instead of a clear
contract failure. Assert `response.status` (and validate fields such as `body.token`) before parsing
or depending on the effect.

-   const body = await response.json()
    assert.equal(response.status, 200)
+   const body = await response.json()
+   assert.equal(typeof body.token, 'string')
    return body.token


─── protocol/ime-10k.test.mjs:20-20 ───
[maintainability · low] The assertion message only shows the first 12 mismatches (`bad.slice(0,
12).join('\n')`). When a systematic regression fails many cases, the output hides the total blast
radius and can omit the common pattern. Include the total failure count in the assertion message (or
make the truncation limit explicit).



─── protocol/lexicon-daily.mjs:18-18 ───
[maintainability · low] Duplicate keys/entries in the lexicon files (`把` in the same object literal,
`出门`, `拿`/`五分钟`, the `喜欢` phrase, and `'吗'` in the `在家` list). Object literals/arrays silently keep
the last value or deduplicate, hiding accidental divergence and causing duplicate/nondeterministic
results. Remove the redundant duplicates.

-   把: 'ba', 给: 'gei', 让: 'rang', 和: 'he', 跟: 'gen', 从: 'cong', 把: 'ba',
+   把: 'ba', 给: 'gei', 让: 'rang', 和: 'he', 跟: 'gen', 从: 'cong',


─── protocol/build-lexicon.mjs:207-207 ───
[style · low] Loose equality (`==`/`!=`) is used in several places (`prev == null`, `got != null`,
`state.looseMin == null`), which the project rules forbid. Use strict comparisons; where a nullish
check is intended, write `!== null && !== undefined` explicitly.

-     if (prev == null || freq > prev) best.set(key, freq)
+     if (prev === undefined || freq > prev) best.set(key, freq)


─── protocol/build-assets.mjs:70-70 ───
[bug · medium] File-system writes in the build scripts call `mkdirSync`/`writeFileSync` without
error handling, and the rime dict write omits the directory-creation guard used elsewhere. A failed
or truncated write can leave partial/corrupt assets with a raw stack trace and no indication of the
offending path. Wrap the writes (and ensure every target directory exists) so failures are reported
clearly and success is verified.



─── protocol/build-assets.mjs:9-9 ───
[documentation · low] Platform target lists are duplicated across the `rules`, `char-prior`,
`base-lexicon`, and `lexicon.bin` arrays in `build-assets.mjs`, and they are inconsistent with each
other and with the '三端共用' comment (five targets are actually written). Centralize a single platform
list (e.g. `{ name, dir }`) and derive each target set from it, and update the comment to name the
real platforms.



─── protocol/lexicon-source.mjs:416-416 ───
[documentation · low] Ranking weights are inlined as bare magic numbers in the lexicon files
(`phrases` range 1000–2600; template weights 484/482/489/etc. in `lexicon-chat.mjs`). The intended
precedence is not documented or auditable. Extract them into named constants (e.g. `FREQ = { TOP:
489, CORE: 484, ... }`) that document the ordering and keep it consistent.



─── backend/test/api.test.mjs:59-60 ───
[test · medium] These assertions depend on server payload ordering (`report.events[0]` and
`deepEqual` on mapped item IDs). A benign reordering would break the test even when the data is
correct. Select by a stable identifier (e.g. `events.find((e) => e.itemId === 'i-promo')`) or
compare sorted sets instead of positional/order-sensitive equality.

      const report = await fetch(`${base}/api/admin/usage`, { headers: auth(admin) }).then((r) => r.json())
-     assert.equal(report.events[0].itemId, 'i-promo')
+     const event = report.events.find((e) => e.itemId === 'i-promo')
+     assert.ok(event)


─── protocol/ime-10k.test.mjs:24-25 ───
[bug · medium] `count[mode] += 1` silently yields `NaN` for any mode that is not `py`/`wb`/`hw`
(e.g. a typo or a new mode added to the fixture). The malformed row is then neither counted nor
reported, and the coverage test can still pass, masking a fixture/format regression. Validate the
mode explicitly and fail loudly on an unexpected value.

    const count = { py: 0, wb: 0, hw: 0 }
-   for (const [mode] of cases) count[mode] += 1
+   for (const [mode] of cases) {
+     if (!(mode in count)) assert.fail(`未知的输入模式: ${mode}`)
+     count[mode] += 1
+   }


─── protocol/ime-10k.test.mjs:15-19 ───
[performance · medium] This loop runs `candidates()` for every one of the 80k+ rows and accumulates
**all** failures into `bad` with no bound. When the engine regresses systematically, hundreds of
thousands of strings are materialized in memory and the test becomes very slow; a single bad fixture
row also aborts the run with a stack trace that doesn't identify the offending `raw`. Consider
capturing only the first N failures (or breaking early) and asserting on those.



─── protocol/ime-10k.test.mjs:26-29 ───
[maintainability · low] The coverage thresholds (80000 / 99900 / 10000 / 1000) are hardcoded magic
numbers duplicated across the two tests; the total (99900) is also inconsistent with the sum of the
per-mode minimums (91000), so the exact intended fixture composition is unclear. Extract them into
named constants (e.g. `EXPECTED_TOTAL`, `MIN_PY`) so they can be updated in one place and their
relationship is documented.



─── protocol/build-assets.mjs:81-81 ───
[bug · medium] `rmSync` with `force: true` silently deletes the android `base-lexicon.json` asset.
Combined with the unguarded writes above, a partial build failure could leave this committed asset
removed without any warning, and `force: true` swallows errors (e.g. accidental path mistakes).
Guard the deletion with an explicit existence check and log the removal, or drop the deletion from
the build script if the file is meant to be tracked.



─── protocol/build-assets.mjs:11-11 ───
[maintainability · low] The `rules` object is written to five different platform paths, so keep the
rules/lexicon schema versions a single source of truth rather than inline literals. `version: 1`
here and `version: 2` below (plus the rime dict `version: "1.0"`) must stay in sync with every
consumer's reader; any drift is silent. Consider extracting these version constants (or asserting
compatibility) so generator and readers cannot disagree.



─── backend/test/api.test.mjs:9-12 ───
[maintainability · medium] The temp directory created by `mkdtempSync` is never removed. `close()`
only shuts down the HTTP server, so every run leaves a `<tmp>/susheng-*` directory containing a
SQLite file behind. In CI with repeated runs this accumulates files/inodes and can eventually
exhaust disk. Consider cleaning up the directory as part of `close()` (e.g. `rmSync(dir, {
recursive: true, force: true })` after `app.close()`), or register cleanup via `t.after(...)` in
each test.

    const dir = mkdtempSync(join(tmpdir(), 'susheng-'))
    const app = await listen({ dbPath: join(dir, 'susheng.sqlite'), port: 0, host: '127.0.0.1' })
    const base = `http://127.0.0.1:${app.port}`
-   return { base, close: app.close }
+   return {
+     base,
+     close: async () => {
+       await app.close()
+       rmSync(dir, { recursive: true, force: true })
+     }
+   }


─── backend/test/api.test.mjs:26-28 ───
[style · low] `auth` always injects `content-type: application/json`, including on GET requests that
carry no body. It is harmless but misleading; the header is only meaningful for the POST/PATCH
calls. Consider applying the content-type explicitly on the requests that actually send JSON.



─── backend/src/db.mjs:125-125 ───
[security · critical] Seed users are created with hardcoded cleartext passwords ('susheng-admin',
'susheng-staff', 'susheng-creator') embedded in the source. This exposes default credentials to
anyone with source access and creates predictable accounts in any fresh deployment. These should be
provisioned from environment/config, or generated as random one-time secrets that must be changed on
first login.

-   insertUser.run('u-admin', 'admin', hashPassword('susheng-admin'), '管理员', 'admin', JSON.stringify(['g-all', 'g-variety', 'g-spark']), now)
+   const adminPassword = process.env.SEED_ADMIN_PASSWORD || randomBytes(16).toString('hex')
+   insertUser.run('u-admin', 'admin', hashPassword(adminPassword), '管理员', 'admin', JSON.stringify(['g-all', 'g-variety', 'g-spark']), now)


─── backend/src/db.mjs:99-100 ───
[bug · medium] `bump` (and the identical `bumpEpoch`) performs a non-atomic read-modify-write: it
reads the current value via `metaNumber` and then runs a separate INSERT/UPDATE. Under concurrent
access (multiple connections/worker threads, WAL mode), two callers can read the same value and one
increment is lost, producing duplicate version numbers. Use a single atomic statement (e.g. `UPDATE
meta SET value = CAST(value AS INTEGER) + 1 WHERE key = 'version'`) or wrap the read and write in a
transaction. `metaNumber` also silently yields NaN when the stored value is non-numeric, which then
gets persisted as the string 'NaN'.

  function bump(db) {
-   const version = metaNumber(db, 'version') + 1
+   db.prepare(`INSERT INTO meta(key, value) VALUES('version', '1')
+     ON CONFLICT(key) DO UPDATE SET value = CAST(value AS INTEGER) + 1`).run()
+   const version = metaNumber(db, 'version')


─── backend/src/db.mjs:111-112 ───
[bug · medium] `seed` writes groups, users, an asset file, items and meta flags as independent
statements without a transaction. If the process crashes mid-seed, the DB is left partially
populated while the 'seeded' flag is absent, so `openDatabase` will call `seed` again on restart and
fail with PRIMARY KEY conflicts on the already-inserted rows. Wrap the whole seed in a transaction
(BEGIN/COMMIT with ROLLBACK on error) so it is all-or-nothing.

  function seed(db, dbPath) {
+   db.exec('BEGIN')
+   try {
-   const now = Date.now()
+     const now = Date.now()


─── backend/src/db.mjs:163-164 ───
[security · medium] Session tokens never expire and are never revoked or garbage-collected. A
leaked/old token stays valid forever, and the `sessions` table grows unbounded. Store an
`expires_at` (or `last_seen`) column, validate it in `userFromToken`, and prune/rotate expired
sessions.



─── backend/src/db.mjs:158-158 ───
[bug · medium] `JSON.parse(row.group_ids)` is unguarded. If the stored value is NULL, empty, or
malformed, this throws and can break authentication/response serialization for that user. Guard the
parse and fall back to an empty array (or handle the error).

-     groupIds: JSON.parse(row.group_ids),
+     groupIds: parseGroupIds(row.group_ids),


─── backend/src/db.mjs:183-183 ───
[bug · low] All seed groups share the same `created_at` (`now`), so `ORDER BY created_at` has no
deterministic tie-breaker and group ordering may be unstable across queries/DB versions. Add a
secondary sort key (e.g. `ORDER BY created_at, rowid`) or a dedicated sort column.

-   return db.prepare('SELECT * FROM groups ORDER BY created_at').all().map((row) => ({
+   return db.prepare('SELECT * FROM groups ORDER BY created_at, rowid').all().map((row) => ({


─── backend/src/server.mjs:497-497 ───
[security · high] Path traversal prefix check is insufficient. `file.startsWith(publicDir)` also
matches sibling paths such as `/public-secret/...` (publicDir = `/srv/public`), so files outside
`public` can be served. Additionally, `normalize` does not resolve symlinks, so a symlink inside
`public` pointing outside would be followed. Compare the path against `publicDir` + a path separator
(and consider `realpath`).

-   if (!file.startsWith(publicDir) || !existsSync(file)) {
+   if ((file !== publicDir && !file.startsWith(publicDir + sep)) || !existsSync(file)) {


─── backend/src/server.mjs:221-221 ───
[security · medium] For incremental (non-full) syncs the query returns every row with `version >
since`, including items the caller cannot see. Those rows are then emitted as deleted tombstones
(`toSyncItem(row, groups, !keep)`), leaking `id`, `groupId`, `groupName`, `groupKind`, `version` and
`updatedAt` of items in groups the user has no access to. Only send tombstones for ids the user
could previously see, or filter non-visible rows out entirely.



─── backend/src/server.mjs:353-353 ───
[bug · medium] `body.groupIds.map(String)` throws a TypeError when `groupIds` is provided but not an
array (e.g. a string or object), which surfaces as an opaque 500 `服务器错误` instead of a 400. Unlike
`createUser`, this path lacks an `Array.isArray` guard.

-     const groupIds = body.groupIds !== undefined ? body.groupIds.map(String) : JSON.parse(current.group_ids)
+     const groupIds = body.groupIds !== undefined ? body.groupIds : JSON.parse(current.group_ids)
+     if (!Array.isArray(groupIds)) throw Object.assign(new Error('分组列表无效'), { status: 400 })


─── backend/src/server.mjs:433-434 ───
[bug · medium] `patchItem` does not validate that an image item's `assetId` refers to an existing
asset, unlike `createItem`. This allows persisting broken/orphan asset references that will later
400/404 when served. Add the same existence check used in `createItem`.

      ensureGroups([item.groupId])
+     if (item.type === 'image' && item.assetId && !readAsset(db, item.assetId)) {
+       throw Object.assign(new Error('图片不存在'), { status: 400 })
+     }
      const version = nextVersion(db)


─── backend/src/server.mjs:198-198 ───
[security · medium] The login handler verifies credentials with no rate limiting, throttling, or
account lockout, so an attacker can perform unlimited credential-stuffing/brute-force attempts.
Combined with the server defaulting to bind on `0.0.0.0`, this is exposed to the whole network. Add
per-IP/username attempt throttling or a lockout.



─── protocol/build-lexicon.mjs:83-83 ───
[maintainability · medium] `JSON.parse` here is unguarded: a truncated/malformed `char-prior.json`
(or a partially written cache file) aborts the entire build with an opaque `SyntaxError`, unlike the
poetry loader which wraps parsing in try/catch. More importantly, the fallback `{}` is silently
swallowed by `settleHomophones` (see below). Wrap the parse and surface a clear error instead of
failing deep in unrelated code.

+   try {
-   return JSON.parse(readFileSync(file, 'utf8'))
+     return JSON.parse(readFileSync(file, 'utf8'))
+   } catch (err) {
+     throw new Error(`failed to parse char-prior.json: ${err.message}`)
+   }


─── protocol/build-lexicon.mjs:87-87 ───
[maintainability · medium] Silent no-op risk: when `char-prior.json` is missing (or hand-edited to a
malformed/empty object) `loadCharPrior()` returns `{}`, so `minPrior()` is always `0` and every
threshold below (`>= 300`, `> 310`, `>= 340`, `+ 50`) is never satisfied — the whole
homophone-disambiguation heuristic silently degrades to a no-op with no warning, which is very hard
to detect from the output. Please fail fast (or at least log) when the prior map is empty, and pull
the magic thresholds (220/180/320/300/310/340) into named constants so the intent is reviewable.



─── protocol/build-lexicon.mjs:186-186 ───
[bug · medium] `pinyinOf` silently skips every non-Han character, so for words mixed with
marks/punctuation that `add()` does not strip (e.g. `《静夜思》` from a poem title, or `一·二`), the
returned syllable list is shorter than the character count. The entry is then written with
`ids.length !== Array.from(word).length`, breaking the word↔pinyin invariant the binary format
relies on. Enforce the equality (or strip/validate non-Han chars) before producing a pinyin.

-     if (!/[\u3400-\u9fff]/.test(ch)) continue
+     if (!/[\u3400-\u9fff]/.test(ch)) return null


─── protocol/build-lexicon.mjs:274-275 ───
[maintainability · low] This source-parsing block is duplicated almost verbatim for `dict.txt.big`
(above) and the THUOCL files (here): read → split lines → skip blank/`#` → split word/freq →
`toSimplified` → pinyin lookup → `add`. The same pattern is re-implemented for `large_pinyin.txt`
and the poetry JSON as well. Extract a shared `parseWordFreqFile(file, { weightFn })` helper so a
format fix in one place cannot be missed in the others.



─── protocol/build-abbrev-cases.mjs:21-21 ───
[maintainability · medium] The `470` frequency cutoff is a magic business number that silently
controls the fixture size. Note the Kotlin consumer asserts `cases.length() >= 20000`
(AbbrevPinyinTest.kt), so changing this literal can break that test with no local hint of why.
Extract it into a named constant with a short comment explaining the intent.

- const phrases = [...seen.values()].filter((row) => row.freq >= 470).sort((a, b) => b.freq - a.freq || (a.word < b.word ? -1 : 1))
+ // 词频低于此值的短语不进用例集；改动会直接改变生成用例数量（android 侧断言 >= 20000 条）。
+ const MIN_FREQ = 470
+ const phrases = [...seen.values()].filter((row) => row.freq >= MIN_FREQ).sort((a, b) => b.freq - a.freq || (a.word < b.word ? -1 : 1))


─── protocol/build-abbrev-cases.mjs:45-45 ───
[bug · low] When `cases` is empty this produces `[\n\n]\n`, which is not valid JSON and would make
`JSON.parse` throw in the test. Guard the empty case so the emitted fixture is always parseable.

- const json = '[\n' + cases.map((row) => JSON.stringify(row)).join(',\n') + '\n]\n'
+ const json = cases.length ? '[\n' + cases.map((row) => JSON.stringify(row)).join(',\n') + '\n]\n' : '[]\n'


─── protocol/lexicon-daily.mjs:114-114 ───
[maintainability · medium] `add()` silently returns for words whose length is outside 2–16, so any
overly long phrase in `fixed` is dropped without any diagnostic. This makes accidental data loss
hard to notice. Consider recording the skipped word in `missing` (or a dedicated list) and surfacing
it at the end of the module, or raise the limit if these phrases are intended to be kept.

-   if (chars.length < 2 || chars.length > 16) return
+   if (chars.length < 2 || chars.length > 16) { missing.add(word); return }


─── protocol/lexicon-daily.mjs:424-425 ───
[maintainability · low] The `Object.assign(W, {...})` patch that supplies the extra words
(便宜/多少/晚/下个/记得/…/的话) is placed after the `fixed` array. The `for (const [freq, parts] of fixed)
line(...)` loop that consumes those words executes later, so the ordering currently works — but it
is fragile: moving the patch above `fixed` or the consuming loop above the patch would make the
whole module throw at import. Since both the patch and `line()` mutate shared module state, consider
applying the patch immediately after the initial `W` literal (before any words are consumed) and
adding a comment explaining the required ordering.

- // 便宜、多少、晚 这些单字要先补进词典再组句。
+ // 补充词典必须在使用 W 的 line() 组句之前执行，见下方 fixed 循环。
  Object.assign(W, {


─── protocol/lexicon-source.mjs:496-496 ───
[maintainability · medium] These entries appear to be homophone/fixture additions (黑胡椒 2600, 二维码
2600, 二维火 2500, 还回家 2400, 二位好 2400, 哈哈镜 2300, 二维化 2300, 还喝酒 2200), yet their weights are set above
core greetings such as `你好` (2200) and `谢谢` (2100). As a result a rare/homophone term would be
ranked above a high-frequency greeting, which is almost certainly unintended. If these are
disambiguation test fixtures they should not silently dominate the ranking; otherwise the weights
need to be re-tuned below the common greetings.



─── protocol/pinyin-abbrev.test.mjs:39-39 ───
[maintainability · medium] The set of tested kinds is derived from `ABBREV_THRESHOLDS` rather than
from the fixture file. If a new kind is added to `pinyin-abbrev-cases.json` but no matching
threshold entry is added (or vice-versa), the suite silently ignores it and the build stays green.
Derive the kinds from the fixtures and assert a threshold exists for each so coverage gaps fail
loudly.

- for (const kind of Object.keys(ABBREV_THRESHOLDS)) {
+ const KINDS = [...new Set(cases.map((row) => row[0]))]
+ assert.deepEqual(KINDS.slice().sort(), Object.keys(ABBREV_THRESHOLDS).sort(), '用例种类与卡线种类不一致')
+ for (const kind of KINDS) {


─── protocol/pinyin-abbrev.test.mjs:46-46 ───
[maintainability · low] The misses list only records top-1 misses, is truncated to the first 20, and
is written with `console.log` rather than attached to the assertion. As a result a top-5-only
regression prints no diagnostics at all, and when output is swallowed/interleaved in CI the failure
message carries only the rate. Consider attaching the (untruncated or first-N) misses to the
assertion messages so failures are self-describing.



─── protocol/pinyin-abbrev.test.mjs:17-17 ───
[maintainability · low] `ABBREV_THRESHOLDS` is exported but nothing else in the repository imports
it (only used within this file). Exporting test-internal tuning constants leaks config and invites
external coupling; drop the `export` unless cross-file reuse is actually intended.

- export const ABBREV_THRESHOLDS = {
+ const ABBREV_THRESHOLDS = {


─── protocol/lexicon-chat.mjs:118-118 ───
[maintainability · medium] `adverbs` is declared but never referenced anywhere in the file — the
adverb layer is actually driven by the `adverbVerbs` object keys below, so this list is dead code.
If it was meant to enumerate the adverbs to combine with subjects/verbs, wire it into the loops (or
delete it) so the two tables cannot drift apart.



─── protocol/lexicon-chat.mjs:87-87 ───
[bug · medium] `follow` silently hard-caps every list at 16, and `handNext` below truncates again
with `slice(0, 16)`. Because template entries beyond the 16th are discarded *before* `handNext`
merges, the curated hand-written suggestions for that previous word may be lost even though they
were intended to be promoted to the front. Consider raising/removing the cap inside `follow` (or
letting `handNext` do the slicing) and define the 16 limit as a shared named constant.



─── protocol/lexicon-chat.mjs:337-337 ───
[maintainability · low] The sort comparator never returns 0, which is an inconsistent comparator. It
currently works only because Map keys are unique; make the ordering explicit and non-nested (the
project disallows nested ternaries) by using `localeCompare`.

- export const chatNext = Object.fromEntries([...next.entries()].sort((a, b) => (a[0] < b[0] ? -1 : 1)))
+ export const chatNext = Object.fromEntries([...next.entries()].sort((a, b) => a[0].localeCompare(b[0])))


─── protocol/pinyin-engine.test.mjs:8-8 ───
[maintainability · medium] `lexicon` and `items` are module-level mutable objects shared by
reference across all tests. `candidates`/`associate` currently only read them, but the API also
sorts derived arrays and could plausibly normalize/reorder lexicon entries in future. Any such
mutation would leak between tests and produce order-dependent, flaky failures that are hard to
diagnose. Consider freezing the fixtures (`Object.freeze` + deep-freeze) or building a fresh copy
per test. Also note `.entries` silently becomes `undefined` if `base-lexicon.json` changes shape,
which then falls back to the engine's `lexicon = []` default and makes every lexicon assertion fail
with a misleading message.



─── protocol/pinyin-engine.test.mjs:35-35 ───
[maintainability · low] The `candidates({ raw: ..., lexicon, items })` boilerplate is repeated
dozens of times across the suite. Extract a small helper (e.g. `run(raw, extra)` that injects
lexicon/items and forwards overrides) so tests focus on the expectation and future fixture changes
only touch one place.

-   const rows = candidates({ raw: 'nihao', lexicon, items })
+   const rows = run('nihao')


─── protocol/pinyin-engine.test.mjs:99-100 ───
[maintainability · medium] These assertions encode implicit ranking semantics through magic numbers
(`indexOf(...) > 0`, `Array.from(text).length > 6`) without stating the intended contract. A benign
reordering or a single-char-addition in the lexicon will break them and the failure won't tell a
maintainer which behavior actually regressed. Prefer asserting named properties (first element,
membership) or extract the threshold into a documented constant.



─── protocol/pinyin-engine.test.mjs:183-183 ───
[test · medium] Coverage is happy-path only. The engine returns `[]` for empty/blank input and has
branches for unknown pinyin, malformed items (missing `items`/`title`/`body`, non-published or
deleted items) and the `limit` option, none of which are exercised. Add negative/edge-case tests
(e.g. `candidates({ raw: '' })`, unknown pinyin, `deleted: true` / `status: 'draft'` items) to lock
down these contracts.



─── protocol/pinyin-engine.test.mjs:187-188 ───
[test · low] The expiry test derives both `validTo` and `now` from `Date.now()` at slightly
different instants, relying on the engine's `>` comparison and a 1s margin rather than explicit
values. Injecting fixed timestamps makes the intent unambiguous and the test fully deterministic
regardless of clock behavior.

-     items: [{ ...items[0], validTo: Date.now() - 1000 }],
-     now: Date.now()
+     items: [{ ...items[0], validTo: 999_999 }],
+     now: 1_000_000


─── protocol/pinyin-engine.mjs:240-241 ───
[bug · high] Case-sensitivity bug: `input` is lowercased up front (`raw.trim().toLowerCase()`), but
`item.title`/`item.aliases` are compared as-is. Any official title or alias containing uppercase
letters (e.g. "WeChat", "OK") will never match `name === input` or `name.startsWith(input)`,
silently dropping those official candidates. Normalize the names before comparison. The same pattern
(without lowercasing) recurs in `associate`.

      const names = [item.title, ...(item.aliases || [])]
-     if (names.some((name) => name && (name === input || name.startsWith(input)))) {
+     if (names.some((name) => name && (name.toLowerCase() === input || name.toLowerCase().startsWith(input)))) {


─── protocol/pinyin-engine.mjs:188-189 ───
[bug · medium] Falsy `validFrom`/`validTo` disables the validity window. If a publisher stores `0`
(or an empty string) as an explicit boundary, the item never expires / is always considered valid.
Use explicit null/undefined checks instead of truthiness so `0` is honored as a real timestamp.

-     if (item.validFrom && now < item.validFrom) return false
-     if (item.validTo && now > item.validTo) return false
+     if (item.validFrom != null && now < item.validFrom) return false
+     if (item.validTo != null && now > item.validTo) return false


─── protocol/pinyin-engine.mjs:197-198 ───
[bug · medium] Nested ternary is disallowed by the project rules; it is also hard to read.
Additionally, for non-image / non-lexicon items the displayed text is `title` while the committed
text is `body`, which is a likely text/commit mismatch (the user sees a title but a different string
gets committed). Extract the branch into named variables and confirm the intended commit target.

-     text: image ? item.title : (item.type === 'lexicon' ? item.body : item.title),
+     text: image || item.type !== 'lexicon' ? item.title : item.body,
      commit: image ? '' : item.body,


─── protocol/pinyin-engine.mjs:187-187 ───
[maintainability · medium] Business values are hardcoded as string literals throughout the engine
(e.g. 'published', 'image', 'lexicon', 'official', 'qwerty', 't9', the 'v' prefix). Since this file
is shared contract across three platforms, a typo or divergence in any literal silently breaks
cross-platform consistency. Extract them into exported constants/enums and reference those.

-     if (!item || item.deleted || item.status !== 'published') return false
+     if (!item || item.deleted || item.status !== STATUS_PUBLISHED) return false


─── protocol/pinyin-engine.mjs:792-792 ───
[maintainability · low] `edge.singleTail` is computed but never read anywhere in this file, making
it dead code. Either remove the field or use it in the beam scoring (if it is only needed for
structural parity with the other platform engines, drop it to avoid confusion).



─── android/scripts/build-wubi-table.py:7-9 ───
[bug · high] Several direct input accesses/parses assume well-formed data and raise unhelpful bare
exceptions when the assumption fails. `sys.argv[1..3]` is indexed without checking the argument
count (IndexError instead of a usage message); `int(parts[2])` is applied to each data row without
guarding malformed weights (ValueError with no offending line); `entry["w"]` / `int(entry["f"])`
assume valid JSON fields (KeyError/ValueError with no entry id); and `medians["们"]` / `medians["的"]`
assume the Make Me a Hanzi source contains those keys (KeyError with no file/character context).
Validate these inputs and report explicit errors (check `len(sys.argv)`, use try/except or
validation for each row, and `.get()`/explicit messages for dictionary lookups).

+ if len(sys.argv) < 4:
+     sys.exit(f"usage: {sys.argv[0]} <source> <lexicon> <out>")
  SOURCE = sys.argv[1]
  LEXICON = sys.argv[2]
  OUT = sys.argv[3]


─── android/scripts/build-wubi-table.py:33-33 ───
[maintainability · low] Both guards are dead/unreachable. `not code.isalpha()` can never be true
because `parts[1].isalpha()` was already validated and `code = parts[1].lower()` preserves
alpha-ness. Similarly, `if not code: continue` can never fire because `code` is built by
`phrase_code()` from codes stored in `full` that already passed `code.isalpha()` (non-empty). These
redundant checks obscure the real validation intent and should be removed.



─── android/scripts/build-hand-strokes.py:126-126 ───
[maintainability · medium] `matrix` is accumulated in both branches of the loop
(`matrix.append(vec)` / `matrix.append(np.zeros(DIM))`) and then stacked here, but the resulting
array is never read anywhere afterwards. This is dead work (a ~6763×512 float copy plus the
per-iteration allocations). Either remove the `matrix = []`, both `matrix.append(...)` calls and
this `np.stack`, or actually use `matrix` for the intended purpose.



─── android/scripts/build-hand-strokes.py:99-100 ───
[bug · low] `np.rint` uses round-half-to-even (banker's rounding), whereas the Kotlin side quantizes
with `(hist[i] * 127.0).roundToInt()` (ties rounded away from/above half). Since the module
docstring states the Kotlin recognizer must reproduce this histogram exactly, the two sides can
disagree by 1 on values landing exactly on a .5 tie, silently perturbing the template/user vectors.
Use a tie rule matching Kotlin (all values here are non-negative, so `floor(x + 0.5)` matches
ties-toward-positive-infinity).

  def quantize(vec):
-     return np.clip(np.rint(vec * 127.0), -127, 127).astype(np.int16)
+     return np.clip(np.floor(vec * 127.0 + 0.5), -127, 127).astype(np.int16)


─── ios/scripts/build-librime.sh:3-3 ───
[bug · high] This script declares executable build semantics (`#!/bin/sh` + `set -eu`) but its body
only `echo`s documentation; it never builds/locates `librime.xcframework`, configures header
search/linking, or verifies that the required integration steps exist (e.g. checking for
`rime_api.h`, the framework/header paths). Because it ends normally, it always exits 0, so any
CI/developer invocation expecting a built framework will silently 'succeed' without producing
anything. Implement the actual build/link and artifact/header assertions, or fail loudly with a
non-zero status so missing work cannot go unnoticed.

  set -eu
+ 
+ # NOTE: this script currently performs no build/link work.
+ # If invoked as a build step it must not report success silently.
+ exit 1


─── ios/scripts/build-librime.sh:5-5 ───
[security · medium] The BSD-3 / no-vendor-UI bundling constraint is only stated in an echo; nothing
verifies that the produced framework contains only librime code and not the vendor's UI code.
Relying purely on manual discipline means a licensing violation could ship unnoticed. Add an
automated check of the bundled framework contents, or at minimum a clear failure if it cannot be
verified.



─── android/scripts/build-librime.sh:8-8 ───
[security · medium] The clone uses `--depth 1` against the default branch with no pinned tag or
commit, so the native librime being compiled changes silently over time. This breaks build
reproducibility and is a supply-chain risk (an upstream compromise or breaking change would be
pulled in unnoticed). Pin to an explicit ref and allow it to be overridden.

-   git clone --depth 1 https://github.com/rime/librime.git "$WORKDIR/librime"
+   LIBRIME_REF="${LIBRIME_REF:-<pinned-tag-or-commit>}"
+   git clone --depth 1 --branch "$LIBRIME_REF" https://github.com/rime/librime.git "$WORKDIR/librime"


─── android/scripts/build-librime.sh:7-7 ───
[bug · medium] The guard only checks that the directory exists. An interrupted or failed clone
leaves an empty/partial `$WORKDIR/librime` that will be silently reused on the next run instead of
being re-fetched, and the failure only surfaces much later in CMake. Verify the clone is actually
usable (e.g. a valid `.git` / known file) and clean up before re-cloning.

- if [ ! -d "$WORKDIR/librime" ]; then
+ if [ ! -d "$WORKDIR/librime/.git" ]; then
+   rm -rf "$WORKDIR/librime"


─── android/scripts/build-librime.sh:13-13 ───
[bug · low] The emitted build command interpolates `$ROOT`/`$WORKDIR` unquoted, so it produces an
incorrect, non-runnable command if the paths contain spaces (e.g. a checkout under a directory with
a space). Quote the paths in the printed instructions.

- echo "  cd $ROOT/android && ./gradlew :app:assembleDebug -Psusheng.native=true -Plibrime.root=$WORKDIR/librime"
+ echo "  cd \"$ROOT/android\" && ./gradlew :app:assembleDebug -Psusheng.native=true -Plibrime.root=\"$WORKDIR/librime\""


─── android/scripts/build-librime.sh:11-11 ───
[maintainability · low] The script never validates that `git` is available or that the required
native dependencies (boost, glog, leveldb, yaml-cpp, marisa, opencc) are present; it only prints
instructions. A missing `git` aborts late with a generic `set -e` error, and missing deps fail deep
inside the Gradle/CMake build. Add a lightweight `command -v git` check (and ideally dep checks) so
failures are reported here.



─── windows/build.sh:3-3 ───
[bug · medium] `cd "$(dirname "$0")"` is not robust: if `CDPATH` is set in the environment, `cd` may
resolve to a different directory and the build will run from an unexpected location (or fail to find
`./cmd/susheng`). The sibling `macos/build.sh` already guards against this with `CDPATH= cd -- ...`,
so this script is inconsistent with the project's existing convention. Use the same defensive idiom.

- cd "$(dirname "$0")"
+ cd "$(CDPATH= cd -- "$(dirname "$0")" && pwd)"


─── windows/build.sh:4-4 ───
[maintainability · low] `GOARCH=amd64` is hardcoded, so building for Windows on ARM64 (or 32-bit) is
impossible without editing the script; allow an environment override. In addition, no `-trimpath` or
`-ldflags` are used, so the produced binary embeds local build paths and carries no version/commit
metadata, making it non-reproducible and hard to trace. Consider parameterizing the architecture and
injecting build info.

- CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -o susheng.exe ./cmd/susheng
+ CGO_ENABLED=0 GOOS=windows GOARCH="${GOARCH:-amd64}" go build -trimpath -ldflags "-X main.version=${VERSION:-dev}" -o susheng.exe ./cmd/susheng


─── android/scripts/package-apk.sh:15-15 ───
[bug · medium] `tr -d '\\'` strips *all* backslashes from the SDK path. On Windows,
`local.properties` escapes the value (`sdk.dir=C\:\\Users\\...\\Sdk`), so this silently turns it
into `C:Users...Sdk`, which is unusable. It also keeps CR if the file has CRLF endings and doesn't
trim surrounding whitespace. Prefer stripping only CR/whitespace and unescaping the `\:`/`\\`
sequences. Additionally, this branch is only taken when `ANDROID_HOME` is empty; a
stale/exported-but-invalid `ANDROID_HOME` or a set `ANDROID_SDK_ROOT` will cause a confusing build
failure. Consider warning when the resolved/existing SDK dir does not exist.

-   SDK_DIR=$(sed -n 's/^sdk\.dir=//p' "$ANDROID_DIR/local.properties" | head -1 | tr -d '\\')
+   SDK_DIR=$(sed -n 's/^sdk\.dir=//p' "$ANDROID_DIR/local.properties" | head -1 | tr -d '\r' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//; s/\\\\/\\/g')


─── android/scripts/package-apk.sh:22-22 ───
[maintainability · medium] The Gradle distribution path hardcodes version `8.10.2`; if the project
ever moves to another Gradle release this lookup silently fails and the script falls back to
`command -v gradle` (or aborts when it is also absent). Glob an arbitrary version
(`gradle-*-bin/*/gradle-*/bin/gradle`) instead of pinning it, and note that parsing `ls` output is
fragile if several distributions/lock files exist. Since no Gradle wrapper is present in the repo,
consider preferring a wrapper if one is added later.

- GRADLE=$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-8.10.2-bin/*/gradle-8.10.2/bin/gradle 2>/dev/null | head -1 || true)
+ GRADLE=$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-*-bin/*/gradle-*/bin/gradle 2>/dev/null | head -1 || true)


─── android/scripts/package-apk.sh:31-31 ───
[maintainability · low] VERSION_CODE is parsed with a regex that only matches a literal single-line
`versionCode = <digits>`, and the APK path is hardcoded from that versionCode. Both duplicate the
naming rule already in `app/build.gradle.kts`
(`archivesName.set("su-ime-${android.defaultConfig.versionCode}")`), so they can drift: an
expression/multiline versionCode aborts the script, and a changed `archivesName`/variant/flavor
makes a successful build report as failure. Prefer reading the value/manifest from the built
artifact, or use a glob fallback in the debug output APK directory, or at least document the
duplication.



─── android/scripts/package-apk.sh:8-9 ───
[maintainability · low] The Android Studio JBR path is macOS-specific and, when present,
unconditionally overwrites any `JAVA_HOME` the caller may have set. This makes behaviour surprising
on Linux/Windows and ignores a deliberately configured JDK on macOS. Consider only using it as a
fallback when `JAVA_HOME` is unset, and probe a couple of platform-specific locations.



─── macos/build.sh:4-4 ───
[bug · medium] SDK detection has no explicit failure handling. If `xcrun` exits non-zero the script
aborts with only its stderr (no hint about the real cause), and if it succeeds but returns an empty
string the empty value is passed straight to `clang -isysroot ""`, producing a confusing clang error
instead of a clear diagnostic. Validate the result and emit an actionable message.

- SDK="$(xcrun --show-sdk-path)"
+ SDK="$(xcrun --show-sdk-path 2>/dev/null || true)"
+ if [ -z "$SDK" ]; then
+   echo "error: unable to locate the macOS SDK; run 'xcode-select --install'" >&2
+   exit 1
+ fi


─── macos/build.sh:6-6 ───
[maintainability · low] The Go engine package path (`../windows`, `./cmd/engineexport`) is
hardcoded. If the module layout is renamed/moved, the only feedback is a generic `cd`/`go build`
error. Consider making the engine directory/package overridable via environment variables and
validating the directory exists so failures are self-explanatory.



─── macos/build.sh:8-8 ───
[bug · medium] `rm -rf` is executed on a relative path. It currently works because the script `cd`s
to `$ROOT` first, but any future change to that `cd` (or reuse of this snippet elsewhere) turns this
into a recursive delete of an unintended directory in the current working directory. Use the
resolved absolute path to make the destructive operation explicit and safe.

- rm -rf "苏晟输入法.app"
+ APP="$ROOT/苏晟输入法.app"
+ rm -rf "$APP"


─── macos/build.sh:13-15 ───
[bug · medium] No `-arch` (and no `-mmacosx-version-min`) is passed to clang, so the produced
executable matches only the build host architecture and the SDK's default deployment target. A
bundle built on Apple Silicon will not launch on Intel Macs (and vice versa) once distributed. If
universal distribution is intended, build both slices (e.g. `-arch arm64 -arch x86_64` for clang and
a matching GOARCH loop for the Go archive) and set an explicit deployment target.



─── macos/build.sh:16-16 ───
[maintainability · medium] The finished bundle is never code-signed (or notarized). A
developer-ID/ad-hoc signature is required for an InputMethodKit input method to run on other
machines without Gatekeeper blocking it. Add a `codesign` step (and notarization for distribution),
and consider cleaning up the intermediate `libsusheng.a`/generated header afterward.



─── ios/Shared/ContentRepository.swift:163-163 ───
[bug · high] URLs built from `session.server` (server-loaded/user-entered) plus a path or `assetId`
are force-unwrapped with `URL(string:)!`; a malformed value makes `URL(string:)` return nil and
crashes the process. Additionally, for the asset request `assetId` is interpolated directly into the
path, so a value containing `../` can escape `assetDirectory` (path traversal). Construct and
validate the URL safely (no force-unwrap) and reject/normalize unsafe path components before use.

-         var request = URLRequest(url: URL(string: base + path)!)
+         guard let url = URL(string: base + path) else { throw RepositoryError.message("无效的服务器地址") }
+         var request = URLRequest(url: url)


─── ios/Shared/ContentRepository.swift:70-70 ───
[security · high] The bearer token is written in cleartext to `session.json` inside the shared App
Group container. Any process/extension granted App Group access can read this credential. Persist
the token in the Keychain (with the non-secret display name/server in the file) instead of plaintext
shared storage.



─── ios/Shared/ContentRepository.swift:94-96 ───
[bug · high] `sync()` persists the new pack (advanced `serverVersion`/`epoch` and the updated item
set) BEFORE `downloadAssets`, `writeUserDict` and `flushUsage` run. If `downloadAssets` throws, the
persisted pack claims assets that were never downloaded and the epoch is already advanced, leaving
inconsistent on-disk state for the next sync. Persist the pack only after the auxiliary steps
succeed, or roll back on failure.



─── ios/Shared/ContentRepository.swift:124-124 ───
[bug · high] `flushUsage` ignores each request's outcome (`try?`) and then unconditionally
overwrites the queue with an empty array. Any transient network failure (or non-2xx response)
silently discards up to 200 unsent usage events. Only remove events that were successfully
acknowledged.



─── ios/Shared/ContentRepository.swift:139-140 ───
[bug · medium] `downloadAssets` ignores the HTTP status code; a 4xx/5xx response body (e.g. a JSON
error) is written as the asset file, and the existence check on the next sync then treats the
corrupt file as valid. Check the response status before writing.

-             let (data, _) = try await URLSession.shared.data(for: request)
-             try data.write(to: file)
+             let (data, response) = try await URLSession.shared.data(for: request)
+             if let http = response as? HTTPURLResponse, http.statusCode >= 400 { continue }
+             try data.write(to: file, options: .atomic)


─── ios/Shared/ContentRepository.swift:32-36 ───
[other · medium] `ContentRepository` is a plain (non-`@MainActor`, non-actor) class shared across
the app and keyboard extension. `items`, `lexicon`, `serverVersion`, `epoch` are mutated from
`loadLocal()`, `sync()` (across `await` boundaries) and read/used by `reportUsage` from different
execution contexts, including SwiftUI `body` reading `holder.repo.items` on the main actor in
SushengApp.swift. Concurrent callers (e.g. background sync + UI usage reporting) can race and
corrupt state; the `loadUsage()`→append→write sequence in `reportUsage` is also a lost-update race.
Isolate this type (actor, `@MainActor`, or a dedicated serial queue/lock).



─── ios/Shared/ContentRepository.swift:86-86 ───
[bug · medium] `Dictionary(uniqueKeysWithValues:)` traps at runtime if the persisted `items` contain
duplicate `id` values (server-supplied ids), crashing during sync. Build the dictionary defensively
instead.

-             var map = Dictionary(uniqueKeysWithValues: items.map { ($0.id, $0) })
+             var map = Dictionary(items.map { ($0.id, $0) }, uniquingKeysWith: { _, new in new })


─── ios/Shared/Skin.swift:35-35 ───
[security · high] Silently falling back to `UserDefaults.standard` when the app-group container is
unavailable (missing/misconfigured entitlement, provisioning mismatch, or extension sandbox) means
the host app and the keyboard extension can end up reading and writing *different* stores.
Consequences: `accepted` (privacy consent) set in the app may not be seen by the keyboard, and
`skinId`/`owned` diverge between the two processes. This is a privacy/correctness hazard, not just a
fallback convenience. Prefer treating an unavailable shared container as an error condition (log it
and avoid silently redirecting state to standard defaults) rather than `?? .standard`.



─── ios/Shared/Skin.swift:46-48 ───
[bug · medium] This getter collapses three distinct states into `[0]`: (a) never persisted, (b)
corrupt/unparsable data, and (c) an intentionally empty set. Note that the setter round-trip of an
empty set stores `""`, which reads back as `[0]`, so an empty ownership set is unrepresentable.
Corrupt or unexpected persisted values are also silently hidden instead of being surfaced/reset
explicitly. Recommend distinguishing `nil` (unset -> default `[0]`) from an empty string (valid
empty set) and validating parsed ids.



─── ios/Shared/Skin.swift:52-57 ───
[bug · medium] Non-atomic read-modify-write across two keys: `owned` and `skinId` are separate
persistence operations. Concurrent `apply` calls (e.g. host app and keyboard extension sharing the
app group) or a crash between the writes can lose an ownership insertion or leave `skinId` pointing
at a skin that is not in `owned`. Also, `id` is inserted without validating it exists in
`KeyboardSkins.all`, so `apply` can persist arbitrary/invalid ids and `owned` becomes a
client-controlled set with no verification. Consider validating the id and performing the update
atomically (or serializing access) so selected and owned state stay consistent.



─── ios/App/SushengApp.swift:25-25 ───
[security · medium] The default endpoint is cleartext `http://` and the field is fully
user-editable. Login posts `username`/`password` (see `ContentRepository.login` → `request`, which
uses `URLSession` with no TLS enforcement / scheme validation) to whatever address is entered, so
credentials can be sent over plaintext HTTP to a non-local host. Recommend defaulting to `https://`,
validating the scheme before sending credentials, and/or enforcing ATS.



─── ios/App/SushengApp.swift:82-82 ───
[bug · medium] Calling `exit(0)` to terminate the app from a normal UI path is discouraged by Apple
(an explicit app termination can be rejected in App Store review and presents to users as a crash).
Prefer keeping the user on the gate screen or providing an in-app exit flow instead of invoking
`exit(0)` directly.



─── ios/App/SushengApp.swift:13-15 ───
[maintainability · low] `RepositoryHolder` conforms to `ObservableObject` but exposes no
`@Published` state, so SwiftUI never observes `ContentRepository` mutations. UI refresh therefore
relies incidentally on unrelated `@State` changes (e.g. `status` after login/sync); any repo update
made without such a change will not refresh views that read `holder.repo.items` (e.g. the phrases
list). Consider publishing the repo's observable state (or the items) so redraws are driven by
actual data changes.



─── ios/App/SushengApp.swift:44-44 ───
[performance · low] `loadLocal()` performs synchronous file reads and JSON decoding of the shared
content pack on the main thread — called during `onAppear` in SushengApp.swift and in
`viewDidLoad`/`viewWillAppear` of the keyboard — so with a large synced pack it can block the first
frame/render. Also, `loadLocal()` internally swallows any failure (returning empty `items`), so a
load/decode error is silently indistinguishable from an empty pack with no user-visible signal. Move
the disk work off the main actor and expose load status so callers can react to failures.



─── ios/Keyboard/KeyboardViewController.swift:165-165 ───
[performance · high] `currentCandidates()` is called inside `onKey` for the space/newline branches
and then again by `refresh()` (and `choose()` also calls `refresh()`), so a single space/newline
press can run the full offline IME pipeline 3–4 times: every call builds a brand-new
`SushengRimeContext(raw)` (create session / process each key / free / destroy) and runs
`PinyinEngine.candidates` scanning all items and lexicon entries. This is all synchronous on the
main thread and directly adds latency to every keystroke. Compute the candidate list once per input
state (raw/mode/chinese/associating) and reuse it instead of recomputing on each call.



─── ios/Keyboard/KeyboardViewController.swift:267-267 ───
[performance · high] `Data(contentsOf:)` performs a synchronous, full-file read on the main thread.
For a large image asset this blocks the keyboard UI and can trip the watchdog, especially since it
is invoked from a tap handler. Load the data asynchronously (e.g. `Task`/background queue) and only
then write to the pasteboard, updating `hint.text` on the main actor.



─── ios/Keyboard/KeyboardViewController.swift:216-216 ───
[performance · medium] `repo.reportUsage` does a synchronous disk read + JSON encode + write
(`loadUsage()` + `data.write(to:options:.atomic)`) on the calling thread. Since this runs on the
main thread for every committed candidate or panel insert, frequent commits stall typing. Consider
buffering usage events and flushing them off the main thread.



─── ios/Shared/PinyinEngine.swift:75-76 ───
[performance · medium] Per-keystroke full-lexicon scan with no caching. Every call to `candidates`
re-runs `scorePinyin` (→ `syllablesOf` → `segmentAllowPartial`) for every lexicon entry, and the
same re-segmentation happens again for the item pinyin; `associate` likewise linearly scans the
entire lexicon (`entry.w.hasPrefix(word)`) on every invocation instead of using a prefix index. The
JS reference builds a cached `buildIndex` (parsed syllables/initials/t9) and uses
`shortlist(...)`/head/prefix buckets to score only a narrow candidate set. Since this runs on the
keyboard main thread per keystroke, precompute/cache each entry's syllables (invalidated when
`lexicon` changes) and maintain a cached prefix-indexed view of the lexicon rather than re-scanning
the whole array.



─── ios/Shared/MixedPinyin.swift:35-37 ───
[bug · medium] These lazily-populated static caches (`cachedIndex`/`cachedCount`/`cachedHead`,
`nextWords`, `charPrior`) are read and written with no synchronization or actor isolation.
`indexFor`, `loadNext` and `loadPrior` mutate them on first use, so if `mixedSentences` is ever
reached off the main thread (or concurrently) you get a data race / torn read (e.g. `cachedIndex`
rebuilt twice, or a partially updated dictionary observed). All current callers appear to run on the
main thread, so this is latent rather than currently reachable, but the state is global to the
process and easy to guard: isolate the type to `@MainActor` or guard the cache reads/writes with a
lock or serial queue.



─── ios/Shared/MixedPinyin.swift:347-347 ───
[bug · medium] The index cache is validated only by `lexicon.count` and `lexicon.first?.w`. Two
distinct lexicons that happen to share the same element count and leading word will silently reuse
the previously built `MixIndex` (its `rows`/`exactIni`/`sylFreq`), producing candidates drawn from
the wrong dictionary. Today `ContentRepository.lexicon` is set once and effectively immutable, so
this is not reachable yet, but it is a fragile key. Consider keying on a stable lexicon
identity/hash (or clearing the cache when the lexicon source changes) instead of count+head word.



─── ios/Shared/MixedPinyin.swift:392-393 ───
[maintainability · low] On any load failure (missing resource, decode error) `table` stays empty and
is then cached permanently in `nextWords`/`charPrior`, with no retry path. Because
`loadNext`/`loadPrior` return the cached value when present, a single transient failure silently
disables next-word bonuses / char priors for the lifetime of the process and provides no error
signal. Consider only caching when the resource actually loaded, so a later call can recover.



─── ios/Shared/MixedPinyin.swift:282-282 ───
[performance · low] `mixSpellOptions` filters and sorts the entire `syllables` set on every
invocation. It is called for each spell inside `matchMixed`, which itself runs for every candidate
span of every segmentation on each keystroke, so the same O(N log N) work over the full syllable
table is repeated many times per input on the main thread. Since the result depends only on `spell`
and the per-index `sylFreq`, memoizing it (e.g. a per-index `[String: [String]]` cache) removes most
of this redundant work.



─── android/app/src/main/res/layout/activity_main.xml:28-28 ───
[security · high] The server field ships with a hardcoded cleartext endpoint
(`http://10.0.2.2:8787`). Because the same screen collects username/password, this default
encourages sending credentials over unencrypted HTTP and, if not removed, would leak the emulator
loopback address into release builds. Prefer no default text (let the user enter it), or a `@string`
resource containing an `https://` URL.

-             android:text="http://10.0.2.2:8787" />
+             android:text="@string/default_server" />


─── android/app/src/main/res/layout/activity_main.xml:13-13 ───
[maintainability · medium] All user-facing text (labels, hints, button captions, and status) is
inlined as literal strings. This blocks localization and makes strings hard to keep
consistent/reuse. Extract them into `strings.xml` and reference via `@string/...` (e.g. the title,
hint texts, and "在这里试打：nihao、vhy").

-             android:text="苏晟输入法"
+             android:text="@string/app_title"


─── android/app/src/main/res/layout/activity_main.xml:4-5 ───
[maintainability · medium] The ScrollView root does not set `android:fillViewport="true"`; with
`wrap_content` content this can leave the scrollable area unable to fill the screen and, combined
with several EditTexts, results in fields being obscured when the soft keyboard is shown. Consider
`android:fillViewport="true"` and letting the Activity handle `windowSoftInputMode`/scroll-to-focus.

      android:layout_height="match_parent"
+     android:fillViewport="true"
      android:padding="24dp">


─── android/app/src/main/res/layout/activity_main.xml:40-40 ───
[security · medium] The password field relies solely on `inputType="textPassword"`. Confirm the
Activity does not persist or log the password, and consider explicitly disabling
autofill/suggestions for this credential form (e.g. `android:importantForAutofill="no"` or proper
`autofillHints`) so credentials are not captured by third-party autofill or IME learning.

+             android:importantForAutofill="no"
              android:inputType="textPassword" />


─── android/app/src/main/res/layout/activity_main.xml:33-34 ───
[maintainability · low] The input fields expose only `android:hint` and have no associated
label/`android:labelFor` on the preceding TextViews, which is weak for accessibility (hints vanish
once typed and screen readers may not announce a stable label). Add proper labels or
`android:contentDescription`/`labelFor` for each field.

-             android:hint="账号"
-             android:inputType="text" />
+             android:hint="@string/hint_username"
+             android:inputType="text"
+             android:autofillHints="username" />


─── android/app/src/main/res/values/colors.xml:5-5 ───
[bug · medium] Accessibility/contrast concern: `cinnabar` (#C4372C) on `paper` (#F3EADB) yields a
contrast ratio of only ~4.48:1, just below the WCAG AA minimum of 4.5:1 for normal-sized text.
`cinnabar` is used as `colorAccent`, which AppCompat applies to text/label states (e.g. focused/hint
text, links, checkbox labels) rendered on the paper background, so this pairing can be hard to read.
Consider darkening the accent (e.g. a slightly deeper red) or reserving cinnabar for
large/accent-only elements and using `ink` for readable text.



─── android/app/src/main/res/values/colors.xml:2-2 ───
[maintainability · low] Generic, unprefixed resource names such as `white` (and similarly common
names) risk colliding with framework/library resources (e.g. AppCompat/Material define their own
`color/white`). Because app resources silently override library resources, a library that internally
references `@color/white` would pick up this value, and future library changes could alter app
appearance unexpectedly. Recommend namespacing the palette, e.g. `susheng_white`, or at least
prefixing all palette entries consistently.



─── android/app/src/main/res/values/colors.xml:1-1 ───
[maintainability · low] No `values-night` (or other qualifier) variant exists for either the color
palette (`values/colors.xml`) or the theme (`values/themes.xml`). The app hard-codes a Light theme
(`Theme.AppCompat.Light.NoActionBar`) and a light palette (`paper` background with dark `ink` text),
so on Android 10+ (API 29+, with `minSdk = 28` / `targetSdk = 34`) the system-wide dark-mode setting
is ignored and users get an unexpectedly bright UI. If light-only is intentional, declare it
explicitly so the intent is documented; otherwise add night-qualified resources.



─── android/app/src/main/AndroidManifest.xml:21-21 ───
[security · high] `android:usesCleartextTraffic="true"` globally permits unencrypted HTTP/plaintext
traffic for the whole app. Combined with the `INTERNET` permission this weakens transport security
for every request (e.g. dictionary/cloud sync) and exposes user input data to MITM/tampering. Prefer
removing this flag and, if some legacy endpoint truly needs HTTP, restrict cleartext to specific
domains via a `networkSecurityConfig` instead of enabling it application-wide.

-         android:usesCleartextTraffic="true">
+         android:networkSecurityConfig="@xml/network_security_config">


─── android/app/src/main/AndroidManifest.xml:19-19 ───
[performance · medium] `android:largeHeap="true"` raises the per-process heap limit, which usually
masks memory leaks / excessive allocation rather than fixing them, and increases the risk of the
system killing the process / OOM crashes. For an IME this is rarely justified; confirm it is
actually required, and if so document why and keep allocations bounded (bitmaps, dictionaries,
caches).



─── android/app/src/main/res/drawable/ic_launcher.xml:2-2 ───
[bug · critical] This adaptive-icon XML lives in `res/drawable/` without any `-v26` version
qualifier, while `AndroidManifest.xml` references it directly as
`android:icon="@drawable/ic_launcher"`. The `<adaptive-icon>` element only exists from API 26; on
API < 26 the framework's DrawableInflater does not recognize the tag and throws when the launcher
loads the icon, so the icon is broken/uppercrashes on older devices. The conventional and safe
layout is to put the adaptive definition in `res/mipmap-anydpi-v26/ic_launcher.xml` and provide
legacy density-specific `mipmap-*` fallbacks (referenced from the manifest as
`@mipmap/ic_launcher`); there is currently no mipmap directory at all, so no legacy fallback exists.



─── android/app/src/main/res/drawable/ic_launcher.xml:4-4 ───
[bug · medium] No `<monochrome>` layer is declared. On Android 13+ (API 33) themed icons require a
monochrome layer; without it the launcher falls back to an unstyled icon and the icon does not adapt
to the user's theme. Add a `monochrome` element pointing at a suitable single-color (alpha-only)
drawable, e.g. `<monochrome android:drawable="@drawable/ic_launcher_monochrome" />`.



─── android/app/src/main/res/drawable/ic_launcher.xml:3-3 ───
[maintainability · low] Using a pure-white background (`@color/white`, #FFFFFF) makes the launcher
icon effectively invisible / very low-contrast on light launcher backgrounds and on the white
app-drawer surface. Consider using a dedicated brand background color resource (e.g.
`@color/ic_launcher_background`) with sufficient contrast against the foreground, rather than
hard-coding white into the adaptive icon.



─── android/app/src/main/res/xml/method.xml:7-7 ───
[bug · medium] The IME declares only a single `zh_CN` keyboard subtype and none of the subtypes is
marked ASCII-capable. On Android, when an IME has no subtype with `android:isAsciiCapable="true"`,
the framework treats it as unable to produce ASCII input; combined with a subtype locale that does
not match the current system locale, the IME can be omitted from the input-method picker or fall
back to being unusable for non-Chinese locales. Add an ASCII-capable subtype (or mark this one
ASCII-capable) so the keyboard remains selectable/usable regardless of the system language.

          android:imeSubtypeMode="keyboard"
+         android:isAsciiCapable="true"


─── android/app/src/main/res/xml/method.xml:8-8 ───
[maintainability · low] The subtype reuses `@string/app_name` as its label, which couples the
subtype naming to the app title and can show a misleading name in the IME picker (e.g. the app name
instead of the language/input description). Use a dedicated subtype label string so the picker shows
an accurate, localizable name independent of the app name.

-         android:label="@string/app_name" />
+         android:label="@string/ime_subtype_zh_cn" />


─── android/app/src/main/res/xml/file_paths.xml:3-3 ───
[security · low] This entry exposes the entire `filesDir/assets` download cache through the
FileProvider, not just the image files that are actually shared. `ContentRepository` uses the same
directory (`assetDir = File(context.filesDir, "assets")`) for all downloaded assets, so any file
placed there in the future becomes shareable via a content URI by default. To follow least
privilege, store shareable files in a dedicated subdirectory (e.g. `filesDir/shared/`) and expose
only that here.

Also note the name `assets` is easily confused with the APK's `context.assets` (used extensively in
`ContentRepository`, `HandModel`, etc.) — `<files-path>` resolves relative to
`Context.getFilesDir()`, not the APK assets folder. A more explicit name and/or an inline comment
would prevent that confusion for maintainers.



─── rime/susheng_t9.schema.yaml:40-41 ───
[bug · high] `xlit` is a positional 1:1 character transliteration, but here the source `23456789`
has 8 characters while the target `abc def ghi jkl mno pqrs tuv wxyz` has 33 (including spaces). The
extra target characters are ignored, so only `2→a, 3→b, 4→c, 5→' ', 6→d, 7→e, 8→f, 9→' '` are
produced — the preedit is mis-rendered (digits collapse to a few letters/spaces) and the intended T9
grouping can never be shown. Note also that `xlit` cannot expand one digit into several letters
(one-to-many), so the keypad-layout string is not a valid target at all. Align source and target to
equal length (a true 1:1 mapping) or drop `preedit_format` and let the input digits display as-is.

    preedit_format:
-     - xlit/23456789/abc def ghi jkl mno pqrs tuv wxyz/
+     - xlit/23456789/abcdefgh/


─── rime/susheng_phrase.schema.yaml:43-43 ───
[bug · high] The `shortcut` affix declares `suffix: "'"`, but the recognizer pattern `^v[a-z0-9]*$`
cannot match a trailing apostrophe. As soon as the user types the closing `'`, the input no longer
matches the pattern, the `shortcut` tag is dropped, and `affix_segmentor@shortcut` /
`script_translator@shortcut` never fire. Extend the pattern to allow the optional suffix, mirroring
Rime's reverse-lookup convention so prefix+suffix styled codes remain tagged.

-     shortcut: "^v[a-z0-9]*$"
+     shortcut: "^v[a-z0-9]*'?$"


─── rime/susheng_phrase.schema.yaml:37-38 ───
[maintainability · medium] No entry in `engine.translators` references this top-level `translator:`
section: `script_translator@shortcut` binds to the `shortcut:` section and `punct_translator` binds
to `punctuator:`. Therefore `dictionary: susheng` and `enable_user_dict: false` are dead
configuration and silently ignored. Either add a plain `script_translator` entry (if normal input is
intended) or remove this unused section to avoid confusion.



─── rime/susheng_phrase.schema.yaml:32-32 ───
[bug · medium] `affix_segmentor@shortcut` strips the declared `prefix`/`suffix` before the
translator receives the code, yet the code keys already include the leading `v` (e.g.
`rime/susheng_phrase.dict.yaml` has `开场问候\tvhy`, and the client feeds the raw `vhy`). If the `v` is
stripped, the lookup becomes `hy` and will never match a dictionary entry. Please verify the actual
lookup code and either align the dictionary keys (drop the leading `v`) or remove the
prefix/affix_segmentor and rely on the recognizer tag.



─── rime/susheng_phrase.schema.yaml:16-16 ───
[maintainability · low] The processors list omits `punctuator`, although `punct_segmentor` and
`punct_translator` are declared below. The sibling schemas (`susheng_pinyin`, `susheng_t9`) both
list `punctuator` in processors, so without it punctuation input will not be produced by these punct
components. Add the `punctuator` processor, or drop the unused punct components for consistency.

      - speller
+     - punctuator


─── rime/susheng_pinyin.schema.yaml:38-39 ───
[bug · medium] The second `abbrev` rule does not produce the shorthand it appears to intend.
`abbrev` derives an alias from the spelled syllable, but the trailing `.+` forces at least one
character to remain after capture group 2, so `([a-z])` is anchored to the second-to-last letter
rather than the last one (`zhong` → `zn`, `abcd` → `ac`), and syllables shorter than 4 letters can
never match. If a "first + last letter" abbreviation is wanted, drop the trailing `.+` (e.g.
`abbrev/^([a-z]).*([a-z])$/$1$2/`); otherwise the rule should be removed or documented, since as
written it only adds nonsensical aliases to the prism that no user would type.

      - abbrev/^([a-z]).+$/$1/
-     - abbrev/^([a-z]).+([a-z]).+$/$1$2/
+     - abbrev/^([a-z]).*([a-z])$/$1$2/


─── harmony/hvigorw:3-3 ───
[bug · medium] Hardcoded macOS-only JDK path is used as the JAVA_HOME fallback whenever JAVA_HOME is
unset. On Linux/CI (or Windows with sh) this path does not exist, so the build silently proceeds
with a non-existent JDK and fails later with an opaque error. Consider only applying the default
when the path actually exists, and otherwise leave JAVA_HOME unset (or emit a clear diagnostic) so
the user's real toolchain is used.

- export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
+ if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
+   export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
+ fi


─── harmony/hvigorw:4-4 ───
[maintainability · low] The launcher execs node and the bundled hvigor.js without any pre-flight
validation. If node is not on PATH or node_modules/@ohos/hvigor is not installed, the user gets a
raw shell/node error with no build context. Add a check that the entry point exists (and that node
is resolvable) and print an actionable message before exec'ing.

- exec node "$ROOT/node_modules/@ohos/hvigor/bin/hvigor.js" "$@"
+ HVIGOR_JS="$ROOT/node_modules/@ohos/hvigor/bin/hvigor.js"
+ if ! command -v node >/dev/null 2>&1; then
+   echo "hvigorw: 'node' not found in PATH. Please install Node.js." >&2
+   exit 1
+ fi
+ if [ ! -f "$HVIGOR_JS" ]; then
+   echo "hvigorw: cannot find $HVIGOR_JS; run 'npm install' first." >&2
+   exit 1
+ fi
+ exec node "$HVIGOR_JS" "$@"


─── .gitignore:9-9 ───
[security · high] No patterns exclude secrets/credentials. `local.properties` is covered, but
signing material and service configs can still be committed. Add rules such as `.env`, `*.keystore`,
`*.jks`, `google-services.json`, and `GoogleService-Info.plist` to prevent leaking sensitive data.

  android/local.properties
+ .env
+ *.keystore
+ *.jks
+ google-services.json
+ GoogleService-Info.plist


─── .gitignore:11-11 ───
[bug · medium] Several .gitignore patterns are non-recursive, so nested files remain tracked:
`ios/*.xcuserstate` only matches directly under `ios/` (use `ios/**/*.xcuserstate`), and
`macos/*.app` / `windows/*.exe` miss nested build artifacts (e.g. `macos/**/*.app`, `**/*.exe`).
Broaden these patterns.

- ios/*.xcuserstate
+ ios/**/*.xcuserstate


─── .gitignore:17-18 ───
[bug · medium] `libsusheng.h` is ignored by exact filename. If this header is maintained source (not
a generated artifact), it will be silently excluded from version control. Verify it is truly
generated output before ignoring; otherwise ignore only the binary/library and keep the header
tracked.



─── .gitignore:21-21 ───
[maintainability · low] `*.log` matches every log file anywhere with no negation rules, which can
silently drop intentional log fixtures or required reference logs. Consider scoping it (e.g.
`/logs/*.log`) or adding exceptions with `!` for paths that must stay tracked.



─── .gitignore:14-14 ───
[maintainability · low] Common build/cache and editor artifacts are missing: `ios/Pods/`, `.idea/`,
`*.iml`, Python `venv/`/`__pycache__/`, `dist/`, `coverage/`, and editor state directories. Add them
to avoid accidentally committing large or machine-specific files.

  harmony/**/build/
+ ios/Pods/
+ .idea/
+ *.iml
+ venv/
+ __pycache__/
+ dist/
+ coverage/


─── harmony/hvigorw:2-2 ───
[bug · low] ROOT is derived from `dirname "$0"`, so if the script is invoked as a bare command
resolved via PATH (`$0` == `hvigorw`, dirname == `.`) or through a symlink, ROOT resolves to the
wrong directory and the node_modules entry point is not found. Consider resolving the real script
location (e.g. following symlinks) or documenting that the script must be run from its own
directory.




──────── Project Summary ────────

### Top Issues

1. **Untrusted `assetId` used as a filesystem path (path traversal) — repeated on every platform.** The content-pack `assetId` is joined into an on-disk path and sometimes a URL with no sanitisation, so a malicious/compromised server (or tampered pack) can write/read outside the assets directory.
   - `windows/internal/content/store.go`, `macos/Sources/Store.m` / `Store.h`, `harmony/entry/src/main/ets/common/ContentSync.ets`, `android/.../data/ContentRepository.kt`, `ios/Shared/ContentRepository.swift`.
   - Related server-side weakness: `backend/src/server.mjs` uses `file.startsWith(publicDir)`, which matches sibling directories like `/public-secret`.

2. **Credentials are sent over cleartext HTTP by default on every client.** The login/user field defaults to an `http://` endpoint (or is freely user-editable), and passwords are posted without TLS enforcement.
   - `android/.../ui/HomePages.kt`, `android/app/src/main/res/layout/activity_main.xml`, `harmony/.../pages/Index.ets`, `ios/App/SushengApp.swift`, `macos/Sources/Store.h`, `android/app/src/main/AndroidManifest.xml` (`usesCleartextTraffic="true"`).
   - Supporting backend weakness: `backend/src/db.mjs` seeds hardcoded plaintext passwords (`susheng-admin`, `susheng-staff`, `susheng-creator`).

3. **Sync is not atomic and silently loses data; the local version advances before assets/dict/usage are persisted.** Every implementation persists the new pack/epoch first, then downloads assets and flushes usage, and clears/discards events regardless of delivery outcome.
   - `windows/internal/content/store.go` (queue cleared even though POST result discarded, persists pack before assets), `ios/Shared/ContentRepository.swift` (`try?` + unconditional queue clear, ignores HTTP status), `android/.../data/ContentRepository.kt` (`runCatching` swallows download failures, persists pack first), `macos/Sources/Store.m`, `harmony/.../common/ContentSync.ets`.

4. **Unsafe sharing / visibility of mutable IME state across threads (JMM/happens-before violations).** "Loaded/ready" sentinels and caches are plain fields read outside locks while written under `synchronized`; `ready = true` is often published before the data it guards.
   - `android/.../engine/HandModel.kt`, `HandStrokes.kt`, `HandPinyin.kt`, `LexiconBin.kt`, `PinyinEngine.kt`, `ime/LibrimeBridge.kt`, `engine/ImeRules.kt`, `data/ContentRepository.kt`.
   - `ios/Shared/MixedPinyin.swift`, `ios/Shared/ContentRepository.swift`; `harmony/.../common/MixedPinyin.ets`, `ContentRead.ets`.
   - Native: `android/app/src/main/cpp/hand_jni.cpp` reassigns/deletes the global `net` with no synchronisation (use-after-free).

5. **Errors are broadly swallowed, turning failures into silent corruption.** Missing assets, non-2xx responses, failed lexicon/JSON loads, failed writes, and failed JNI/library loads are discarded, so callers believe operations succeeded.
   - `windows/internal/content/store.go` (HTTP ≥400 skipped yet `Sync` returns success), `windows/cmd/engineexport/main.go` (lexicon load error discarded, `sync.Once` prevents retry), `android/.../data/ContentRepository.kt`, `harmony/.../common/ContentRead.ets`/`ContentSync.ets`, `protocol/build-lexicon.mjs` (missing `char-prior.json` silently disables disambiguation), plus `rime_jni.cpp` ignoring `setup`/`initialize`/`start_maintenance` return values.

6. **Cursor-based binary/native data parsing without bounds or type validation.**
   - `android/.../engine/LexiconBin.kt` (all cursor arithmetic trusts the blob; truncated blob throws AIOOBE), `HandStrokes.kt` (front-to-header reads occur before any length check).
   - `android/app/src/main/cpp/hand_jni.cpp` assumes single-precision float output, ignoring `out.elemsize`/`elempack`/`type`.

7. **Hot-path recomputation of input-independent work.** Full-lexicon rescans, repeated `Regex` compilation, `Set`/`Map` allocation per keystroke, and unstable sorts dominate interactive latency.
   - `ios/Shared/PinyinEngine.swift` and `ios/Shared/MixedPinyin.swift` (per-keystroke full scans; `mixSpellOptions` sorts the whole syllable set per span), `android/.../engine/PinyinEngine.kt`/`LexiconBin.kt` (recompiled `Regex`, repeated `mixOptions`), `harmony/.../Syllables.ets`/`MixedPinyin.ets`, `windows/internal/pinyin/index.go` (`indexMu` held across the whole build; DFS with no memoisation in `segmentAll`), `windows/internal/pinyin/sentence.go` (`beam[n]` never truncated).

8. **Backend auth/session hardening gaps.** Tokens never expire/are never revoked, no login rate limiting, and non-atomic `bump`/`bumpEpoch` read-modify-writes can corrupt the epoch.
   - `backend/src/db.mjs` (sessions table, `bump`), `backend/src/server.mjs` (login handler, opaque 500 from `body.groupIds.map(String)`).

9. **Protocol/schema cannot express its own invariants.** `protocol/content-pack.schema.json` doesn't enforce per-`id` uniqueness, conditional `assetId`/`mime`/`groupKind`, or `validFrom <= validTo`/co-presence, and doesn't relate `deleted`/`status` or `version`/`since`; `protocol/pinyin-engine.mjs` compares `item.title`/`aliases` case-sensitively against a lowercased input and treats falsy `validFrom`/`validTo` as disabled.

10. **Build/release tooling is non-reproducible and unverified.**
    - `android/scripts/build-librime.sh` clones `--depth 1` off the default branch with no pinned ref and no usable-clone check; `android/scripts/package-apk.sh` breaks Windows SDK paths and hardcodes Gradle/version rules; `macos/build.sh` never signs/notarises and hardcodes the Go engine path; `ios/scripts/build-librime.sh` only `echo`s documentation and has no licensing/vendoring check; `harmony/hvigorw` hardcodes a macOS JDK path; `windows/build.sh` hardcodes `GOARCH=amd64` and lacks `CDPATH` guarding.

### Module Hotspots

- **`harmony/entry/src/main/ets/**`** — highest density per file. `common/ImeHub.ets`, `common/ContentRead.ets`, `common/ContentSync.ets`, `common/Syllables.ets`, `common/MixedPinyin.ets`, `common/Skin.ets`, `common/Legal.ets`, `common/PinyinEngine.ets`, `pages/Keyboard.ets`, `pages/Index.ets`, `entryability/EntryAbility.ets` all carry multiple concurrency, validation, i18n, and error-handling comments.
- **`android/app/src/main/java/com/susheng/ime/**`** — heavy concentration in `engine/*` (`HandModel`, `HandStrokes`, `HandPinyin`, `LexiconBin`, `PinyinEngine`, `WubiTable`, `StrokeTable`, `ImeRules`), `ime/*` (`LibrimeBridge`, `ImeChrome`, `SushengInputMethodService`), `data/ContentRepository.kt`, and `ui/*` (`HomePages`, `GatePages`, `AppUi`, `ImeSystem`).
- **`macos/Sources/*`** — `Store.m`/`Store.h`, `Home.m`/`Home.h`, `InputController.m`, `main.m` each carry multiple ownership/interop/contract comments; the header files flag missing nullability and undocumented thread/ownership contracts.
- **`windows/internal/content/store.go`** and **`windows/internal/pinyin/*`** (`index.go`, `sentence.go`, `mixed.go`, `engine.go`) plus **`windows/cmd/susheng/win_windows.go`** — security, concurrency, determinism, and hot-path issues cluster here.
- **`protocol/*.mjs`** — build scripts and lexicon sources (`build-lexicon.mjs`, `build-assets.mjs`, `lexicon-source.mjs`, `lexicon-chat.mjs`, `lexicon-daily.mjs`, `build-abbrev-cases.mjs`) plus the test/schema files are dense with duplicate-source-of-truth and magic-number findings.
- **`ios/Shared/*` and `ios/Keyboard/KeyboardViewController.swift`** — `ContentRepository.swift`, `MixedPinyin.swift`, `Skin.swift`, `PinyinEngine.swift`.
- **Build scripts across all platforms** (`*/scripts/*.sh`, `windows/build.sh`, `macos/build.sh`, `harmony/hvigorw`).

### Cross-Cutting Concerns

- **Error handling is inconsistent or absent at boundaries.** Async/IO/network paths are wrapped in `runCatching`/`try?`/`_, _ =` and then ignored (see #5 above), while JNI returns, `env->GetFloatArrayElements`/`NewFloatArray` results, native `SushengQuery`/`C.GoString`, and `setHook`/`RegisterClassExW` results are unchecked. Representative: `rime_jni.cpp`, `hand_jni.cpp`, `windows/cmd/engineexport/main.go`, `macos/Sources/Store.m`.
- **Shared mutable caches are published without synchronisation.** Same pattern ("already loaded" sentinel on a plain non-volatile field, mutated under a lock, read outside it) repeats in `HandModel.kt`, `HandPinyin.kt`, `LexiconBin.kt`, `LibrimeBridge.kt`, `ios/Shared/MixedPinyin.swift`, and `harmony/.../MixedPinyin.ets`/`ContentRead.ets`. In several places a `ready` flag is set before its guarded data, which is a torn-publish bug rather than a theoretical race.
- **Duplicate sources of truth / drifted constants.** `ImeRules.kt` `DEFAULT_WEIGHTS` vs `assets/ime-rules.json`; `windows/internal/pinyin/mixed.go` weights vs embedded `ime-rules.json`; `protocol/lexicon-source.mjs` bare frequency numbers; `protocol/build-assets.mjs` per-platform target lists and schema versions (`1` vs `2` vs `"1.0"`); `android/app/build.gradle.kts` version triple (`versionCode`/`versionName`/archive name duplicated in `package-apk.sh`); title lists duplicated in `Legal.ets`/`LegalCopy.kt` with silent fall-through to the wrong document.
- **Dead / unreachable code.** `harmony/.../ImeHub.ets` (`context`), `android/.../engine/StrokeTable.kt` (`classify`, redundant guards), `LexiconBin.kt` (`WordEdge.singleTail`), `ImeChrome.kt` (`expandCandidates` always `false`, unused `items`), `protocol/lexicon-chat.mjs` (`adverbs`), `protocol/pinyin-engine.mjs` (`edge.singleTail`), `android/scripts/build-wubi-table.py` (dead guards), `ios/scripts/build-librime.sh` (echo-only), `EntryAbility.ets` empty `onCreate`.
- **i18n / accessibility regressions.** Hardcoded user-facing strings in `harmony` (`InputMethodService.ets`, `Skin.ets`, `Legal.ets`, `Keyboard.ets`), `android/app/src/main/res/layout/activity_main.xml`, `AppUi.kt` (`contentDescription`), and `res/xml/method.xml` (subtype label reuses `app_name`); contrast/focus failures in `backend/public/admin.css` (`outline: none` with no `:focus-visible`) and `android/.../colors.xml` (`cinnabar` ~4.48:1); no `values-night` variant; launcher icon lacks `<monochrome>` and `-v26` qualifier.
- **Tests give weak or opaque diagnostics.** Miss lists capped at 12–20 with top-1-only recording (`AbbrevPinyinTest.kt`, `pinyin-abbrev.test.mjs`, `ime-10k.test.mjs`), status codes asserted after body parsing (`backend/test/api.test.mjs`), module-level `readFileSync`+`JSON.parse` with no error handling, missing negative/edge-case coverage (`protocol/pinyin-engine.test.mjs`), and asset-load failures surfacing as bare `JSONException`/`ENOENT`. `android/.../androidTest/.../Ime10kTest.kt` also skips `bitmap.recycle()` on the failure path.
- **Untrusted input goes straight into native/persistence layers.** `LibrimeBridge.kt` iterates UTF-16 code units (splits surrogate pairs); `rime_jni.cpp`/`hand_jni.cpp` trust `jlong`/buffer sizes; `macos/Sources/Store.m` builds URLs by string interpolation and force-uses `strlen` on a possibly-NULL C result; `windows/cmd/engineexport/main.go` passes a raw `*C.char` to `C.GoString` with no NULL/length validation.

### Quick Wins

- **Hoist and cache input-independent work.** Compile `Regex("[^a-z]")` once in `PinyinEngine.kt`; build `Set`/`Map` lookups once in `Syllables.ets`/`MixedPinyin.ets`; precompute the per-initial best-weight table in `LexiconBin.kt`; cache `mixSpellOptions` results across `matchMixed` calls in `LexiconBin.kt` and `ios/Shared/MixedPinyin.swift`.
- **Add missing `try/finally`/`try/catch` at obvious boundaries** — `bitmap.recycle()` in `Ime10kTest.kt` and the recognition worker in `SushengInputMethodService.kt`; file/HTTP cleanup in `harmony/.../ContentSync.ets`; wrap `loadLocal()` in `HomePages.kt` so a corrupt pack can't kill the process.
- **Guard the obvious crash sites:** check `GetStringUTFChars`/`GetFloatArrayElements`/`NewFloatArray` for NULL in `rime_jni.cpp`/`hand_jni.cpp`; check `bitmap == null` in `Ime10kTest.kt`; assert `top(...)` is non-empty before `.first()` in `MixedPinyinTest.kt`; guard `find()` results before `user.id` in `backend/public/admin.js`.
- **Stop unconditional queue clearing on sync failure** in `windows/internal/content/store.go`, `ios/Shared/ContentRepository.swift`, `android/.../ContentRepository.kt`, and `harmony/.../ContentSync.ets` — retain failed usage events and only drop what was acknowledged.
- **Small parsing-robustness fixes:** add `bytes.size >= 8` before header reads in `HandStrokes.kt`; split the syllable template on `\s+` in `Syllables.ets`; use `\s+` instead of literal `" "` in `StrokeTable.kt`; validate `mode` in `protocol/ime-10k.test.mjs` instead of silently producing `NaN`.
- **Emit valid JSON on empty input** in `protocol/build-abbrev-cases.mjs` (`[\n\n]` → `[]`), and add a `try/catch` around `JSON.parse` in `build-lexicon.mjs` so a missing `char-prior.json` fails loudly instead of silently disabling disambiguation.
- **Reduce duplicated literals with named constants:** the fallback candidate list in `HandStrokes.kt`, the enum name maps and title lists in `backend/public/admin.js` / `LegalCopy.kt` / `Legal.ets`, the `setOf("script","tweet","promo")` allocation in `HomePages.kt`, and the coverage thresholds in `protocol/ime-10k.test.mjs`.
- **Hygiene wins:** add `.env`, `*.keystore`, `*.jks`, `google-services.json`, `GoogleService-Info.plist` to `.gitignore` and make existing patterns recursive (`ios/**/*.xcuserstate`, `macos/**/*.app`); add a `<noscript>` block and cache-busted asset names in `backend/public/index.html`; restore a visible `:focus-visible` style in `backend/public/admin.css`; quote `$ROOT`/`$WORKDIR` in the printed command in `android/scripts/build-librime.sh`; guard `cd` with `CDPATH= cd --` in `windows/build.sh`.
