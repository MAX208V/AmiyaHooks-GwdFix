# AmiyaHooks · 古文岛修复（3.2.0+）

> 补丁模块：修复 [AmiyaHooks](https://github.com/Xposed-Modules-Repo/fer.amiya.amiyahooks)
> 官方 1.5.2 在新版**古文岛 3.2.0** 上失效的问题（会员 / 纯净模式 / 紫金会员）。
>
> 适配目标：`com.guwendao.gwd` 3.2.0 (versionCode 320)，**arm64-v8a**。

---

## 一、官方模块为什么在新版失效（逆向结论）

对 3.2.0 的 APK 完整反编译后，结论很明确：**旧 hook 改的那一层已经不是判定来源了。**

官方 1.5.2 的实际逻辑（从模块 DEX 里解出的字符串即可确认）：

```
com.guwendao.gwd
local.z.androidshared.data.entity_db.UserEntity
    setViptime / setSviptime / setDownloadNum
    getViptime / getSviptime / getDownloadNum
```

也就是说，它 hook 的是 **Android 原生壳**里的 Room 实体。

而在 3.2.0 里：

1. `UserEntity` 这个类**原封不动还在**（`viptime`/`sviptime`/`downloadNum` 字段和 getter/setter 全都在），
   所以模块不会报错——它只是**改了一个已经没人读的对象**。
2. 会员判定已经整体搬进 **Flutter AOT 产物 libapp.so**（Dart 3.10.4 快照）。
3. Flutter 侧有自己的 sqlite 表（同名字段 `vipTimeSpan` / `svipTimeSpan` / `downloadNum`），
   并且每次静默登录都会被服务端 `router/user/getUserInfo.aspx` 的 JSON
   通过 `UserEntity.fillFromJson()` **覆盖**。

Dart 侧真正的判定链（反汇编还原）：

```dart
User.isVIP()
  = _isVIP()                 // viptime  > now
 || isChunjingOnPlatform()   // platformChunjingExpiredMs > now
 || isSVIP()                 // 见下
 || isSubscribedChunjing()   // subscriptionNotifier 的值里含 "chunjing"
 || isPermanentVIP();        // viptime  > now + 1576800000000 (50 年)

User.isSVIP()
  = _isSVIP()                // sviptime > now
 || isSVIPOnPlatform()       // platformSvipExpiredMs > now
 || isSubscribed(...)
 || isPermanentSVIP();
```

> 顺带说明：这也解释了作者在 1.5.1 更新里写的
> “新版本涉及服务器验证的下载功能无法使用（下载次数只能看，没法用）”——
> 因为下载次数是**服务端扣减**的，客户端改不了。

**关键坑**：`vipTimeSpan`、`svipTimeSpan`、`chunjing` 这些字符串
**只存在于 libapp.so，DEX 里一个都没有**。
所以任何基于 dexkit / 按类名搜 DEX 的方案在 3.2.0 上必然零命中——
这也是本补丁没有沿用 dexkit 的原因。

---

## 二、本补丁怎么修

Dart 的判定函数不是 JVM 方法，Xposed 的 Java hook 覆盖不到，所以采用
**native 层就地打补丁**：

1. 等 `libapp.so` 加载完成（`dl_iterate_phdr` 找基址，100ms 轮询，最长 20s）。
2. 按偏移定位 `User.isVIP / isSVIP / ...` 的函数入口。
3. **先校验函数序言**（`stp fp,lr,[sp,#-0x10]!` + `mov fp,sp`），不匹配就跳过——
   绝不盲改，版本不符时只会"不生效"，不会把 App 改崩。
4. 把入口改写成 Dart 自己的 "return true" 指令序列：

```asm
mov  x0, x22        ; x22 = Dart 的 null 基址寄存器
add  x0, x0, #0x20  ; +0x20 = Dart true   （+0x30 = false）
ret
nop
```

这 4 条指令与 Dart 编译器生成 `return true` 的结果**完全一致**
（对照 3.2.0 里 `isPermanentVIP` 的 `0xa54bd8: add x0, x22, #0x20`），
返回的是合法的 Dart bool 对象，不会破坏 GC。

### 打补丁的函数（3.2.0 arm64 实测偏移）

| 函数 | libapp.so 偏移 | 作用 |
|---|---|---|
| `User.isVIP` | `0xa54b30` | 会员总开关 |
| `User.isSVIP` | `0xa552f8` | 紫金会员总开关 |
| `User.isSubscribedChunjing` | `0xa54c00` | **纯净模式**（订阅） |
| `User.isChunjingOnPlatform` | `0xa54cf0` | **纯净模式**（平台下发） |
| `User._isVIP` | `0xa54d50` | 普通会员到期判定 |
| `User._isSVIP` | `0xa556ac` | 紫金会员到期判定 |
| `User.isSVIPOnPlatform` | `0xa555c4` | 平台紫金到期判定 |
| `User.isPermanentVIP` | `0xa54b84` | 永久会员判定 |
| `User.isPermanentSVIP` | `0xa55348` | 永久紫金判定 |

偏移来自 blutter 反编译 + ELF program header 交叉校验
（该 libapp.so 的 vaddr 与文件偏移一一相等，可执行段自 `0x9c0000` 起）。
正反应用清单：**9/9 命中且序言全部校验通过**。

---

## 三、能做什么 / 不能做什么

| 项目 | 状态 | 说明 |
|---|---|---|
| 紫金会员 UI 解锁 | ✅ | `isSVIP()` 恒真 |
| 纯净模式（去广告） | ✅ | `isSubscribedChunjing()` / `isChunjingOnPlatform()` 恒真 |
| 会员到期提示消失 | ✅ | `isVIP()` 恒真，界面按会员态渲染 |
| 下载次数「只能看不能用」 | ⚠️ **仍存在** | 次数由**服务端** `deductionDownloadNum.aspx` 扣减，客户端改不了；这是服务端校验，非本补丁能解决 |
| 需要服务端鉴权的资源 | ⚠️ 视接口 | 若接口返回 403，客户端恒真也拿不到内容 |

> 结论：**界面层的会员/纯净模式限制可以完全解除**；
> 凡是服务端真正校验的功能（下载配额、付费内容拉取），客户端补丁无能为力。

---

## 四、编译

需要 Android Studio / SDK 34 + NDK（`27.0.12077973`）+ JDK 17。

```bash
# 只编 arm64，产物：
#   app/build/outputs/apk/release/app-release-unsigned.apk
./gradlew :app:assembleRelease
```

签名后安装，在 LSPosed 里启用本模块（或与官方 AmiyaHooks 同时启用），
作用域勾选「古文岛」。

### 本仓库的验证程度（请务必看）

- ✅ **Java 侧**：全部源码已用 `javac`（JDK 17 + Android SDK + Xposed API 82）实际编译通过。
- ✅ **Native 侧**：`amiya_gwd.c` 已实际编译成 `.so`，JNI 符号
  `Java_fer_amiya_amiyahooks_gwdfix_NativePatcher_apply` / `..._isLibappLoaded` 正确导出。
- ✅ **偏移**：9 个目标的偏移与函数序言已用真实 `libapp.so` 逐个校验通过；
  `return true` 指令编码已按 ARM64 位域逐位解码确认（`mov x0, x22` / `add x0, x0, #0x20`）。
- ⚠️ **未做**：未在真机 LSPosed 环境跑过 `assembleRelease`（本机无 Android SDK/NDK），
  也未在真机上验证运行效果。首次安装请先看 logcat 过滤 `AmiyaGwdFix`，
  正常应输出 `补丁完成：9/9`。

---

## 五、换版本了怎么办

偏移是按 **3.2.0 (arm64)** 写死的。App 一更新，偏移大概率变化，届时只需：

1. 从新 APK 取出 `lib/arm64-v8a/libapp.so`；
2. 用 [blutter](https://github.com/worawit/blutter) 反编译，打开生成的
   `asm/guwen_core/framework/user/user.dart`；
3. 把各函数的 `// ** addr: 0x...` 抄进 `DartOffsets.java`；
4. 同时更新 `VERSION_NAME` / `VERSION_CODE`。

如果偏移没对上，模块**不会改坏任何东西**：序言校验会失败并打日志
`跳过 xxx：序言不匹配`。

---

## 六、免责

仅供学习与技术研究。打补丁会绕过应用的内购/会员校验，
请自行确认使用场景合法；因使用本模块产生的任何后果由使用者自负。
请勿用于商业倒卖。
