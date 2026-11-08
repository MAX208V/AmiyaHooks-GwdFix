package fer.amiya.amiyahooks.gwdfix;

/**
 * native 层（libamigwd.so）的 Java 门面。
 *
 * <p>它只做一件事：在 Flutter 的 libapp.so 已经加载后，把几个会员判定函数
 * 就地改成"直接返回 true"。具体的偏移表和原理见 {@link DartOffsets} 与 jni/amiya_gwd.c。
 */
final class NativePatcher {

    private static boolean sLoaded;

    private NativePatcher() {
    }

    static synchronized boolean ensureLoaded() {
        if (sLoaded) return true;
        try {
            System.loadLibrary("amigwd");
            sLoaded = true;
            L.i("libamigwd.so 已加载");
        } catch (Throwable t) {
            L.e("加载 libamigwd.so 失败: " + t);
            sLoaded = false;
        }
        return sLoaded;
    }

    /** @return 成功打补丁的数量，失败返回 -1 */
    static native int apply(String versionName);

    /** libapp.so 是否已加载 */
    static native boolean isLibappLoaded();
}
