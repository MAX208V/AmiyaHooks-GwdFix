package fer.amiya.amiyahooks.gwdfix;

import android.util.Log;

/** 日志出口，tag 固定便于在 LSPosed / logcat 里过滤。 */
public final class L {
    public static final String TAG = "AmiyaGwdFix";

    private L() {
    }

    public static void i(String m) {
        Log.i(TAG, m);
    }

    public static void w(String m) {
        Log.w(TAG, m);
    }

    public static void e(String m) {
        Log.e(TAG, m);
    }
}
