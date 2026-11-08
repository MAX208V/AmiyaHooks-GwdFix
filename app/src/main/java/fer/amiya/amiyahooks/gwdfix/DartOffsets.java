package fer.amiya.amiyahooks.gwdfix;

/**
 * 古文岛 3.2.0 (arm64) 的 Dart AOT 函数偏移表。
 *
 * <h3>怎么来的</h3>
 * 用 blutter 反编译 libapp.so（Dart 3.10.4 快照）得到
 * {@code package:guwen_core/framework/user/user.dart} 的反汇编，
 * 每个方法开头的 {@code addr} 就是 libapp.so 里的 <b>虚拟地址</b>；
 * 经 ELF program header 校验，该文件的 vaddr 与文件偏移一一相等（可执行段 0x9c0000 起），
 * 所以这些数值可直接当作 {@code libapp.so 基址 + 偏移} 使用。
 *
 * <h3>对应源码</h3>
 * <pre>
 * static bool isVIP()                 = _isVIP() || isChunjingOnPlatform() || isSVIP() || isSubscribedChunjing() || isPermanentVIP()
 * static bool isSVIP()                = _isSVIP() || isSVIPOnPlatform() || isSubscribed(...) || isPermanentSVIP()
 * static bool isSubscribedChunjing()  = subscriptionNotifier 里含 "chunjing"
 * static bool isChunjingOnPlatform()  = platformChunjingExpiredMs > now
 * static bool _isVIP()                = viptime > now
 * static bool _isSVIP()               = sviptime > now
 * static bool isSVIPOnPlatform()      = platformSvipExpiredMs > now
 * </pre>
 *
 * <p>升级 App 后若偏移失效，只需重新跑 blutter 更新本表；类名本身被混淆，不能按名字找。
 */
final class DartOffsets {

    private DartOffsets() {
    }

    /** 适配的版本号（versionName / versionCode），不匹配时只记日志不强行 Hook */
    static final String VERSION_NAME = "3.2.0";
    static final int VERSION_CODE = 320;

    /** User::isVIP —— 总开关，改它等于"会员恒真" */
    static final long IS_VIP = 0xa54b30L;
    /** User::isPermanentVIP */
    static final long IS_PERMANENT_VIP = 0xa54b84L;
    /** User::isSubscribedChunjing —— 纯净模式（订阅） */
    static final long IS_SUBSCRIBED_CHUNJING = 0xa54c00L;
    /** User::isChunjingOnPlatform —— 纯净模式（平台下发到期时间） */
    static final long IS_CHUNJING_ON_PLATFORM = 0xa54cf0L;
    /** User::_isVIP —— 本地普通会员到期判定 */
    static final long PRIVATE_IS_VIP = 0xa54d50L;
    /** User::isSVIP —— 紫金会员总开关 */
    static final long IS_SVIP = 0xa552f8L;
    /** User::isPermanentSVIP */
    static final long IS_PERMANENT_SVIP = 0xa55348L;
    /** User::isSVIPOnPlatform */
    static final long IS_SVIP_ON_PLATFORM = 0xa555c4L;
    /** User::_isSVIP —— 本地紫金会员到期判定 */
    static final long PRIVATE_IS_SVIP = 0xa556acL;

    /** 每个函数入口的前两条指令，用于校验偏移是否仍然有效（stp fp,lr + mov fp,sp） */
    static final int PROLOGUE_0 = 0xa9bf79fd;
    static final int PROLOGUE_1 = 0xaa0f03fd;
}
