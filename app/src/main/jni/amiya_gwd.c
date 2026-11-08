// libamigwd.so —— 在 libapp.so 的函数入口就地打补丁，让 Dart AOT 的
// bool 判定函数直接返回 true。
//
// 为什么用 native：
//   Flutter AOT 的代码在 libapp.so 里，不是 JVM 方法，Xposed 的 Java hook 碰不到；
//   而且 libapp.so 通常是"私有映射"的 ELF，Xposed 也没有现成的入口。
//   这里直接在进程内按 基址+偏移 改写指令，最简单可靠。
//
// 原理（ARM64）：
//   函数开头改写成：
//       mov  x0, x22        ; x22 是 Dart 隔离区的 NULL 寄存器（也就是 false 的基址）
//       add  x0, x0, #0x20  ; 0x20 偏移 = Dart 的 true
//       ret
//   这条序列与 Dart 自己 "return true" 编译出的指令完全一致（见 isPermanentVIP 的 0xa54bd8），
//   因此返回值就是合法的 Dart bool 对象，不会破坏 GC。
//
// 注意：只改 4 条指令长度（16 字节），函数体其余部分不再执行，
//       这几个函数都是纯判定、没有副作用，因此安全。

#ifndef _GNU_SOURCE
#define _GNU_SOURCE   // dl_iterate_phdr / struct dl_phdr_info
#endif

#define LOG_TAG "AmiyaGwdFix"
#include <android/log.h>
#include <sys/mman.h>
#include <unistd.h>
#include <string.h>
#include <stdint.h>
#include <link.h>
#include <stdio.h>
#include <jni.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// stp fp, lr, [sp, #-0x10]!  ; mov fp, sp  —— 本模块用到的所有函数都是这个序言
static const uint32_t PROLOGUE_0 = 0xa9bf79fd;
static const uint32_t PROLOGUE_1 = 0xaa0f03fd;

// return true 四指令
static const uint32_t TRUE_RET[4] = {
        0xaa1603e0,  // mov x0, x22
        0x910082c0,  // add x0, x0, #0x20   (Dart true)
        0xd65f03c0,  // ret
        0xd503201f,  // nop（占位，保证 16 字节对齐）
};

typedef struct {
    const char *name;
    uint64_t    offset;
} target_t;

static void *g_libapp_base = NULL;

// ---------------------------------------------------------------------------
// 定位 libapp.so 的加载基址
// ---------------------------------------------------------------------------
static int find_libapp_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    if (info->dlpi_name && strstr(info->dlpi_name, "libapp.so")) {
        *(uintptr_t *) data = (uintptr_t) info->dlpi_addr;
        LOGI("找到 libapp.so: %s base=0x%lx", info->dlpi_name, (unsigned long) info->dlpi_addr);
        return 1;
    }
    return 0;
}

static void *find_libapp_base() {
    if (g_libapp_base) return g_libapp_base;
    uintptr_t base = 0;
    dl_iterate_phdr(find_libapp_cb, &base);
    if (base) g_libapp_base = (void *) base;
    return g_libapp_base;
}

// ---------------------------------------------------------------------------
// 让目标地址可写
// ---------------------------------------------------------------------------
static int make_writable(void *addr, size_t len) {
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) page = 4096;
    uintptr_t start = ((uintptr_t) addr) & ~((uintptr_t) page - 1);
    uintptr_t end = (((uintptr_t) addr + len) + page - 1) & ~((uintptr_t) page - 1);
    if (mprotect((void *) start, end - start, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGE("mprotect 失败 addr=%p", addr);
        return -1;
    }
    return 0;
}

static void flush_cache(void *addr, size_t len) {
    __builtin___clear_cache((char *) addr, (char *) addr + len);
}

// ---------------------------------------------------------------------------
// 给一个函数打"恒真"补丁
// ---------------------------------------------------------------------------
static int patch_true(uintptr_t base, const char *name, uint64_t offset) {
    uint32_t *fn = (uint32_t *) (base + offset);

    // 校验序言：偏移错了就跳过，绝不盲改
    if (fn[0] != PROLOGUE_0 || fn[1] != PROLOGUE_1) {
        LOGW("跳过 %s：序言不匹配（0x%08x 0x%08x），偏移可能已失效",
             name, fn[0], fn[1]);
        return -1;
    }

    if (make_writable(fn, sizeof(TRUE_RET)) != 0) return -1;
    memcpy(fn, TRUE_RET, sizeof(TRUE_RET));
    flush_cache(fn, sizeof(TRUE_RET));
    LOGI("已打补丁 %s @0x%lx (+0x%lx)", name, (unsigned long) fn, (unsigned long) offset);
    return 0;
}

// ---------------------------------------------------------------------------
// JNI 入口
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_fer_amiya_amiyahooks_gwdfix_NativePatcher_apply(
        JNIEnv *env, jclass clazz, jstring versionName) {
    (void) env;
    (void) clazz;
    (void) versionName;

    void *base = find_libapp_base();
    if (!base) {
        LOGE("未找到 libapp.so（进程可能还没加载 Flutter）");
        return -1;
    }

    static const target_t targets[] = {
            {"User::isVIP",                0xa54b30L},
            {"User::isPermanentVIP",       0xa54b84L},
            {"User::isSubscribedChunjing", 0xa54c00L},
            {"User::isChunjingOnPlatform", 0xa54cf0L},
            {"User::_isVIP",               0xa54d50L},
            {"User::isSVIP",               0xa552f8L},
            {"User::isPermanentSVIP",      0xa55348L},
            {"User::isSVIPOnPlatform",     0xa555c4L},
            {"User::_isSVIP",              0xa556acL},
    };

    int ok = 0;
    int total = (int) (sizeof(targets) / sizeof(targets[0]));
    for (int i = 0; i < total; i++) {
        if (patch_true((uintptr_t) base, targets[i].name, targets[i].offset) == 0) ok++;
    }
    LOGI("补丁完成：%d/%d", ok, total);
    return ok;
}

JNIEXPORT jboolean JNICALL
Java_fer_amiya_amiyahooks_gwdfix_NativePatcher_isLibappLoaded(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return find_libapp_base() ? JNI_TRUE : JNI_FALSE;
}
