package com.zjh1934.mausbot.local;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * Shizuku 能力封装。
 *
 * <p>这就是那些「AI 操控手机」开源项目里用的同一套东西：Shizuku 在手机上
 * 以 shell(UID 2000) 或 root(UID 0) 身份常驻，普通 App 只要被用户点一次
 * 「允许」，就能借它拿到系统级能力。</p>
 *
 * <p>本 App 用它做两件事：
 * <ol>
 *   <li>引导页里检测「是否已安装 / 是否已启动 / 是否已授权」，做成可视化状态灯；</li>
 *   <li>授权后尝试自动授予 RUN_COMMAND 权限，让用户连系统弹框都不用点。</li>
 * </ol>
 *
 * <p>所有调用都做了无损降级：Shizuku 没装、没启动、没授权、或系统版本差异
 * 导致反射失败，一律静默返回 false，上层回落到 Android 标准授权框。绝不阻塞主流程。</p>
 */
public final class ShizukuKit {

    private static final String TAG = "MausBotShizuku";
    public static final String PKG = "moe.shizuku.privileged.api";
    private static final int REQ_CODE = 0x5A0B;

    private static Runnable onChange;

    private ShizukuKit() {}

    /** 在 Application/Activity 早期注册，监听 binder 与授权结果，变化即刷新引导页。 */
    public static void init(Runnable listener) {
        onChange = listener;
        try {
            Shizuku.addBinderReceivedListenerSticky(ShizukuKit::fire);
            Shizuku.addBinderDeadListener(ShizukuKit::fire);
            Shizuku.addRequestPermissionResultListener((req, res) -> {
                if (req == REQ_CODE) fire();
            });
        } catch (Throwable t) {
            // 未安装 Shizuku 时 API 仍可调用，只是拿不到 binder；此处不应影响启动。
            Log.w(TAG, "Shizuku 监听注册失败（可忽略）", t);
        }
    }

    private static void fire() {
        if (onChange != null) {
            try { onChange.run(); } catch (Throwable ignored) {}
        }
    }

    /** 手机上是否装了 Shizuku。 */
    public static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Shizuku 服务是否正在运行（用户是否已点过「启动」）。 */
    public static boolean running() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    /** 本 App 是否已被用户授权使用 Shizuku。 */
    public static boolean granted() {
        try {
            if (!running()) return false;
            if (Shizuku.isPreV11()) return false; // 极老版本：统一走标准权限框，不折腾
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shizuku 是否以 root(UID 0) 身份运行——root 模式可全自动准备环境。 */
    public static boolean isRoot() {
        try { return running() && Shizuku.getUid() == 0; } catch (Throwable t) { return false; }
    }

    /** 引导页「连接」按钮：弹出 Shizuku 的授权确认。pre-v11 走标准权限框。 */
    public static void request(Activity act) {
        try {
            if (!running()) return;
            if (Shizuku.isPreV11()) {
                act.requestPermissions(new String[]{"moe.shizuku.manager.permission.API"}, REQ_CODE);
            } else {
                Shizuku.requestPermission(REQ_CODE);
            }
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission 失败", t);
        }
    }

    /**
     * 借 Shizuku 的系统级身份，把某个运行时权限授予本 App。
     *
     * <p>等价于官网首页的 IPackageManager.grantRuntimePermission 示例，但用运行时
     * 反射实现，不引入 hidden-api stub 编译依赖。shell(2000) 在部分国产 ROM 上可能
     * 被拒（"Neither user 2000 ... has GRANT_RUNTIME_PERMISSIONS"），root(0) 一定可以；
     * 失败时返回 false，上层改用系统授权框。</p>
     */
    public static boolean grantRuntimePermission(String pkg, String perm) {
        if (!granted()) return false;
        try {
            // 豁免隐藏 API 限制（Android P+）。用 "L" 前缀覆盖整个 IPackageManager 家族，
            // 避免各版本 Stub/Proxy 类名差异导致漏豁免。
            HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/IPackageManager");
            IBinder raw = SystemServiceHelper.getSystemService("package");
            if (raw == null) return false;
            Class<?> stubCls = Class.forName("android.content.pm.IPackageManager$Stub");
            Object pm = stubCls.getMethod("asInterface", IBinder.class)
                    .invoke(null, new ShizukuBinderWrapper(raw));
            if (pm == null) return false;

            // grantRuntimePermission 在不同 Android 版本参数个数不同：
            //   AOSP 常见: (String pkg, String perm, int userId)
            //   部分版本:  (String pkg, String perm, int userId, int flags)
            // 按名字遍历，找到第一个「前两个参数是 String、其余是 int」的重载来调用，
            // 不写死签名，兼容性最好。
            for (Method m : pm.getClass().getMethods()) {
                if (!"grantRuntimePermission".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length < 3 || ps[0] != String.class || ps[1] != String.class) continue;
                boolean restInt = true;
                for (int i = 2; i < ps.length; i++) {
                    if (ps[i] != int.class) { restInt = false; break; }
                }
                if (!restInt) continue;
                Object[] args = new Object[ps.length];
                args[0] = pkg;
                args[1] = perm;
                for (int i = 2; i < ps.length; i++) args[i] = 0;  // userId=0, flags=0
                m.invoke(pm, args);
                return true;
            }
            Log.w(TAG, "未找到匹配的 grantRuntimePermission 重载");
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "grantRuntimePermission via Shizuku 失败（无损回落）", t);
            return false;
        }
    }
}
