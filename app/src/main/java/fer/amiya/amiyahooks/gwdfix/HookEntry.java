package fer.amiya.amiyahooks.gwdfix;

import android.content.Context;
import android.content.pm.PackageInfo;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * AmiyaHooks · 古文岛修复（适配 3.2.0+ / arm64）
 *
 * <h3>为什么旧模块失效</h3>
 * 旧版（1.5.2）hook 的是原生类
 * {@code local.z.androidshared.data.entity_db.UserEntity} 的
 * {@code setViptime / setSviptime / getDownloadNum}。
 * 这些成员在 3.2.0 里<b>依然存在</b>，但已不再是会员判定的来源：
 * 会员逻辑整体搬进了 Flutter（libapp.so），
 * 数据由服务端 {@code router/user/getUserInfo.aspx} 下发并写进 Flutter 自己的 sqflite 表。
 * 所以"改原生实体"在 3.2.0 上是无效的，必须改 Dart 侧。
 *
 * <h3>本模块做法</h3>
 * Flutter 的判定函数不是 JVM 方法，Xposed 的 Java hook 覆盖不到，
 * 因此在 libapp.so 加载后由 native 层按偏移把
 * {@code User.isVIP / isSVIP / isSubscribedChunjing / ...}
 * 改写成"直接返回 true"。偏移表与校验逻辑见 {@link DartOffsets}。
 */
public class HookEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    /** 古文岛 */
    private static final String PKG_GWD = "com.guwendao.gwd";
    /** 古诗文网同源包（同一套 Flutter 代码，兼容用） */
    private static final String PKG_GSW = "com.gushiwen.gsw";

    /** 轮询 libapp.so 的最大次数与间隔 */
    private static final int MAX_TRIES = 200;
    private static final long RETRY_MS = 100L;

    @Override
    public void initZygote(StartupParam startupParam) {
        L.i("AmiyaHooks-GwdFix 已载入");
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!PKG_GWD.equals(lpparam.packageName) && !PKG_GSW.equals(lpparam.packageName)) {
            return;
        }
        L.i("进入进程: " + lpparam.processName);

        // Application.attach 之后 Flutter 引擎才有机会加载 libapp.so，
        // 且必须等它加载完才能按偏移打补丁，所以这里起一个独立线程轮询。
        XposedHelpers.findAndHookMethod(
                android.app.Application.class,
                "attach",
                android.content.Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        final int versionCode = versionCodeOf(lpparam);
                        Thread t = new Thread(() -> applyWhenReady(versionCode), "AmiyaGwdFix-Patcher");
                        t.setDaemon(true);
                        t.start();
                    }
                });
    }

    /** 版本号取 PackageManager，拿不到就返回 -1（不影响打补丁，只影响版本告警） */
    private static int versionCodeOf(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Context ctx = (Context) XposedHelpers.callMethod(
                    XposedHelpers.getStaticObjectField(
                            Class.forName("android.app.ActivityThread"), "currentActivityThread"),
                    "getSystemContext");
            if (ctx == null) return -1;
            PackageInfo info = ctx.getPackageManager().getPackageInfo(lpparam.packageName, 0);
            return info.versionCode;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** 等 libapp.so 加载完成后打补丁 */
    private static void applyWhenReady(int versionCode) {
        if (!NativePatcher.ensureLoaded()) return;

        int applied = -1;
        for (int i = 0; i < MAX_TRIES; i++) {
            try {
                if (NativePatcher.isLibappLoaded()) {
                    applied = NativePatcher.apply(DartOffsets.VERSION_NAME);
                    break;
                }
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                L.e("打补丁异常: " + t);
                return;
            }
        }

        if (applied > 0) {
            L.i("补丁成功 " + applied + " 处（会员 / 紫金会员 / 纯净模式）");
        } else if (applied == 0) {
            L.e("补丁 0 处：偏移全部不匹配，可能不是适配的版本，请更新 DartOffsets");
        } else {
            L.e("补丁未执行：等不到 libapp.so 加载（" + (MAX_TRIES * RETRY_MS) + "ms 超时）");
        }

        if (versionCode > 0 && versionCode != DartOffsets.VERSION_CODE) {
            L.w("版本号不匹配：当前 versionCode=" + versionCode
                    + "，本偏移表适配 " + DartOffsets.VERSION_NAME
                    + "(" + DartOffsets.VERSION_CODE + ")，若功能无效请重新生成偏移表");
        }
    }
}
