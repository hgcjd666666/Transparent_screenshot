package com.dszsu.tss;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

@SuppressWarnings({"FieldCanBeLocal"}) // cross-method reflection cache fields
public class MainXposedModule extends XposedModule {

    private static final String TAG = "TransScreenshot";
    private static final String SYSTEM_HIDE_GROUP = "system_hide";


    private static final int FLAG_NOT_FOCUSABLE = 0x00000008;
    private static final int FLAG_NOT_TOUCHABLE = 0x00000010;
    private static final int FLAG_NOT_TOUCH_MODAL = 0x00000020;

    /** Oplus 小窗（缩放窗）的窗口模式值 WINDOWING_MODE_ZOOM。 */
    private static final int WINDOWING_MODE_ZOOM = 100;

    private static volatile boolean sAppHooksInstalled = false;
    private static volatile boolean sSystemHooksInstalled = false;
    private final Object appLock = new Object();
    private static volatile boolean sAppCacheReady = false;
    private static volatile Field sSurfaceControlField;
    private static volatile Class<?> sScClass;
    private static volatile Method sScIsValid;
    private static volatile Constructor<?> sTxnConstructor;
    private static volatile boolean sSystemUIHookInstalled = false;
    private static volatile String sProcessName;
    private final Object systemUILock = new Object();
    private final Set<String> enabledFeatures = new HashSet<>();
    private static volatile Method sTxnSetSkipScreenshot;
    private static volatile Method sTxnSetSkipScreenshotLegacy;
    private static volatile Method sTxnSetSecure;
    private static volatile Method sTxnApply;
    private static volatile Method sTxnClose;
    private final Object systemLock = new Object();
    private final Map<String, SharedPreferences.OnSharedPreferenceChangeListener> appPrefsListeners
            = new ConcurrentHashMap<>();
    private final Set<Object> systemSecureApplied = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final Set<Object> taskSecureApplied = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final Set<Object> secureApplied = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final Set<Object> flexibleTaskVri = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final ThreadLocal<Boolean> sFlexibleMenuShowing = new ThreadLocal<>();
    private volatile boolean systemUIEnhancementEnabled = false;
    private volatile String windowTitle = null;
    private int systemTxnMethodType = 0;

    // ==================== 隐藏小窗（Oplus 缩放窗）配置 ====================
    private volatile boolean hideZoomEnabled = false;
    private volatile Set<String> showZoomPackages = Collections.emptySet();

    private final SharedPreferences.OnSharedPreferenceChangeListener systemPrefsListener =
            (prefs, key) -> {
                if ("hide_zoom_enabled".equals(key) || "show_zoom_packages".equals(key)) {
                    loadSystemHideConfig(prefs);
                    log(Log.INFO, TAG, "System hide config updated: " + key);
                } else if ("system_ui_enhancement_enabled".equals(key)) {
                    systemUIEnhancementEnabled = prefs.contains("system_ui_enhancement_enabled");
                    log(Log.INFO, TAG, "SystemUI enhancement toggled: " + systemUIEnhancementEnabled);
                }
            };

    private Class<?> windowStateClass;
    private Class<?> windowStateAnimatorClass;
    private Class<?> windowSurfaceControllerClass;
    private Class<?> transactionClass;
    private Class<?> surfaceControlClass;
    @Nullable
    private Class<?> taskClass;
    private Method getOwningPackageMethod;
    @Nullable
    private Method getWindowTagMethod;
    @Nullable
    private Method getTaskMethod;
    @Nullable
    private Method taskGetSurfaceControlMethod;
    @Nullable
    private Method taskGetWindowingModeMethod;
    private Field animatorWinField;
    @Nullable
    private Field animatorSurfaceControllerField;
    @Nullable
    private Field surfaceControllerSurfaceField;
    @Nullable
    private Field windowStateScField;
    private Constructor<?> systemTxnConstructor;
    @Nullable
    private Method systemTxnSetSkipScreenshot;
    @Nullable
    private Method systemTxnSetSecure;
    private Method systemTxnApply;
    private Method systemTxnClose;

    // ==================== Oplus 小窗状态（system_server 反射） ====================
    @Nullable
    private Class<?> zoomWindowManagerClass;
    @Nullable
    private Method zoomWindowGetInstanceMethod;
    @Nullable
    private Method zoomWindowGetCurrentStateMethod;
    @Nullable
    private Class<?> zoomWindowInfoClass;
    @Nullable
    private Field zoomWindowInfoWindowShownField;
    @Nullable
    private Field zoomWindowInfoZoomPkgField;
    private volatile Object zoomWindowManagerInstance;

    private static Method findMethodInHierarchy(Class<?> cls, String name, Class<?>... paramTypes)
            throws NoSuchMethodException {
        if (cls == null) throw new NullPointerException("cls must not be null");
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(cls.getName() + "#" + name);
    }

    private static Field findFieldInHierarchy(Class<?> cls, String name) throws NoSuchFieldException {
        if (cls == null) throw new NullPointerException("cls must not be null");
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(cls.getName() + "#" + name);
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static String getProcessNameReflective() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            @SuppressLint("DiscouragedPrivateApi") Object at = atClass.getDeclaredMethod("currentActivityThread").invoke(null);
            return (String) atClass.getDeclaredMethod("getProcessName").invoke(at);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public void onSystemServerStarting(
            @NonNull XposedModuleInterface.SystemServerStartingParam param) {
        super.onSystemServerStarting(param);
        initSystemServerHooks(param.getClassLoader());
    }

    private void initSystemServerHooks(ClassLoader cl) {
        try {
            SharedPreferences sysPrefs = getRemotePreferences(SYSTEM_HIDE_GROUP);
            loadSystemHideConfig(sysPrefs);
            systemUIEnhancementEnabled = sysPrefs.contains("system_ui_enhancement_enabled");
            sysPrefs.registerOnSharedPreferenceChangeListener(systemPrefsListener);
            installSystemHooks(cl);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to init system hooks: " + t);
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if ("com.android.systemui".equals(param.getPackageName())) {
            initSystemUIHooks(param.getClassLoader());
            return;
        }
        initAppHooks(param.getPackageName(), param.getClassLoader());
    }

    private void initSystemUIHooks(ClassLoader cl) {
        try {
            SharedPreferences sysPrefs = getRemotePreferences(SYSTEM_HIDE_GROUP);
            systemUIEnhancementEnabled = sysPrefs.contains("system_ui_enhancement_enabled");
        } catch (Throwable t) {
            systemUIEnhancementEnabled = false;
        }
        if (systemUIEnhancementEnabled && !sSystemUIHookInstalled) {
            synchronized (systemUILock) {
                if (!sSystemUIHookInstalled) {
                    try {
                        initAppReflection(cl);
                        installSystemUIHook(cl);
                        sSystemUIHookInstalled = true;
                        log(Log.INFO, TAG, "SystemUI enhancement hook installed");
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "SystemUI hook failed: " + t);
                    }
                }
            }
        }
    }

    private void initAppHooks(String packageName, ClassLoader cl) {
        String configPackage = resolveConfigPackage(packageName);
        loadConfig(configPackage);

        if (!sAppHooksInstalled) {
            synchronized (appLock) {
                if (!sAppHooksInstalled) {
                    try {
                        initAppReflection(cl);
                        if (needsLayoutParamChanges()) {
                            installWindowManagerHook(cl);
                        }
                        if (enabledFeatures.contains("enable_skip_screenshot")) {
                            installAntiScreenshotHook(cl);
                        }
                        if (enabledFeatures.contains("hide_recent_card")) {
                            installHideRecentsHook();
                        }
                        sAppHooksInstalled = true;
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "App hook init failed: " + t);
                    }
                }
            }
        }
    }

    // ==================== 热重载 ====================
    @Override
    public boolean onHotReloading(@NonNull XposedModuleInterface.HotReloadingParam param) {
        log(Log.INFO, TAG, "onHotReloading: " + getProcessName());
        return true; // 同意热重载
    }

    @Override
    public void onHotReloaded(@NonNull XposedModuleInterface.HotReloadedParam param) {
        log(Log.INFO, TAG, "onHotReloaded: " + param.getProcessName()
                + ", " + param.getOldHookHandles().size() + " old hooks");
        // 从旧代 hook 的 executable 反推宿主 classloader(宿主 loader 热重载后不变)，
        // 必须在 unhook 之前读取(getExecutable 在 unhook 后可能失效)。
        ClassLoader cl = null;
        for (XposedInterface.HookHandle h : param.getOldHookHandles()) {
            try {
                java.lang.reflect.Executable ex = h.getExecutable();
                if (ex != null) {
                    cl = ex.getDeclaringClass().getClassLoader();
                    break;
                }
            } catch (Throwable ignored) {
            }
        }

        // 卸载旧代次安装的所有 hook
        param.getOldHookHandles().forEach(XposedInterface.HookHandle::unhook);

        // 重置静态安装标志与运行时缓存，允许按需重新安装
        resetForHotReload();

        if (cl == null) {
            log(Log.WARN, TAG, "No host classloader derivable from old hooks, hooks cannot be reinstalled");
            return;
        }
        if (param.isSystemServer()) {
            initSystemServerHooks(cl);
        } else if ("com.android.systemui".equals(param.getProcessName())) {
            initSystemUIHooks(cl);
        } else {
            initAppHooks(param.getProcessName(), cl);
        }
        log(Log.INFO, TAG, "Hot reload done: " + param.getProcessName());
    }

    private void resetForHotReload() {
        sSystemHooksInstalled = false;
        sAppHooksInstalled = false;
        sSystemUIHookInstalled = false;
        sAppCacheReady = false;
        sSurfaceControlField = null;
        sScClass = null;
        sScIsValid = null;
        sTxnConstructor = null;
        systemSecureApplied.clear();
        taskSecureApplied.clear();
        secureApplied.clear();
        flexibleTaskVri.clear();
        zoomWindowManagerInstance = null;
        appPrefsListeners.clear();
        synchronized (enabledFeatures) {
            enabledFeatures.clear();
        }
    }

    private String getProcessName() {
        String name = sProcessName;
        if (name != null) return name;
        try {
            name = Application.getProcessName();
        } catch (Throwable ignored) {
        }
        if (name == null || name.isEmpty()) {
            name = getProcessNameReflective();
        }
        sProcessName = name;
        return name;
    }

    private String resolveConfigPackage(String fallbackPkg) {
        String processName = getProcessName();
        String key = processName != null ? processName : fallbackPkg;
        int idx = key.indexOf(':');
        return idx > 0 ? key.substring(0, idx) : key;
    }

    private void loadConfig(String configPackage) {
        try {
            SharedPreferences globalPrefs = getRemotePreferences("global");
            SharedPreferences prefs = getRemotePreferences(configPackage.toLowerCase(Locale.ROOT));

            Set<String> features = new HashSet<>();
            if (prefs.contains("enable_skip_screenshot")) features.add("enable_skip_screenshot");
            if (prefs.contains("FLAG_DIM_BEHIND_0")) features.add("FLAG_DIM_BEHIND_0");
            if (prefs.contains("show_wallpaper")) features.add("show_wallpaper");
            if (prefs.contains("magic_flags")) features.add("magic_flags");
            if (prefs.contains("nofocus_only")) features.add("nofocus_only");
            if (prefs.contains("hide_recent_card")) features.add("hide_recent_card");

            String titleValue = prefs.getString("window_title", null);
            String resolvedTitle = null;
            if ("$global".equals(titleValue)) {
                resolvedTitle = globalPrefs.getString("title", null);
            } else if (titleValue != null && !titleValue.isEmpty()) {
                resolvedTitle = titleValue;
            }

            synchronized (enabledFeatures) {
                enabledFeatures.clear();
                enabledFeatures.addAll(features);
            }
            windowTitle = resolvedTitle;

            String lowerPkg = configPackage.toLowerCase(Locale.ROOT);
            if (!appPrefsListeners.containsKey(lowerPkg)) {
                SharedPreferences.OnSharedPreferenceChangeListener listener =
                        (p, key) -> loadConfig(configPackage);
                prefs.registerOnSharedPreferenceChangeListener(listener);
                appPrefsListeners.put(lowerPkg, listener);
            }

            log(Log.INFO, TAG, "Config[" + configPackage + "] features=" + features
                    + " title=" + resolvedTitle);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Config load failed [" + configPackage + "]: " + t);
        }
    }

    private boolean needsLayoutParamChanges() {
        return windowTitle != null
                || enabledFeatures.contains("FLAG_DIM_BEHIND_0")
                || enabledFeatures.contains("show_wallpaper")
                || enabledFeatures.contains("magic_flags")
                || enabledFeatures.contains("nofocus_only");
    }

    // ==================== 系统 hook：隐藏 Oplus 小窗 ====================
    @SuppressLint("PrivateApi")
    private void installSystemHooks(ClassLoader cl) throws Exception {
        if (sSystemHooksInstalled) return;
        synchronized (systemLock) {
            if (sSystemHooksInstalled) return;

            initSystemReflection(cl);

            // 主判定：每次 updateSurfacePosition 用传入的 transaction 动态设/取消 skipScreenshot，
            // 可随配置变化实时恢复（参考 Oplus16_HideZoomWindow）。
            if (transactionClass != null
                    && methodExists(windowStateClass, "updateSurfacePosition", transactionClass)) {
                installZoomHideHook();
            } else {
                log(Log.WARN, TAG, "updateSurfacePosition not found, fallback to performShowLocked only");
            }
            if (methodExists(windowStateAnimatorClass, "performShowLocked")) {
                installShowFallbackHook();
            }
            installDestroySurfaceHook();

            sSystemHooksInstalled = true;
            log(Log.INFO, TAG, "System hooks installed, txnType=" + systemTxnMethodType);
        }
    }

    @SuppressLint("PrivateApi")
    private void initSystemReflection(ClassLoader cl) throws Exception {
        windowStateClass = Class.forName("com.android.server.wm.WindowState", false, cl);
        windowStateAnimatorClass = Class.forName("com.android.server.wm.WindowStateAnimator", false, cl);

        try {
            windowSurfaceControllerClass = Class.forName("com.android.server.wm.WindowSurfaceController", false, cl);
        } catch (ClassNotFoundException e) {
            log(Log.WARN, TAG, "WindowSurfaceController not found");
            windowSurfaceControllerClass = null;
        }

        surfaceControlClass = Class.forName("android.view.SurfaceControl", false, cl);
        transactionClass = Class.forName("android.view.SurfaceControl$Transaction", false, cl);

        try {
            taskClass = Class.forName("com.android.server.wm.Task", false, cl);
            getTaskMethod = findMethodInHierarchy(windowStateClass, "getTask");
            getTaskMethod.setAccessible(true);
            taskGetSurfaceControlMethod = findMethodInHierarchy(taskClass, "getSurfaceControl");
            taskGetSurfaceControlMethod.setAccessible(true);
            taskGetWindowingModeMethod = findMethodInHierarchy(taskClass, "getWindowingMode");
            taskGetWindowingModeMethod.setAccessible(true);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Task reflection failed: " + t.getMessage());
            taskClass = null;
            getTaskMethod = null;
            taskGetSurfaceControlMethod = null;
            taskGetWindowingModeMethod = null;
        }

        try {
            getOwningPackageMethod = findMethodInHierarchy(windowStateClass, "getOwningPackage");
            getOwningPackageMethod.setAccessible(true);
        } catch (Throwable ignored) {
            getOwningPackageMethod = null;
        }

        try {
            getWindowTagMethod = findMethodInHierarchy(windowStateClass, "getWindowTag");
            getWindowTagMethod.setAccessible(true);
        } catch (Throwable ignored) {
            getWindowTagMethod = null;
        }

        animatorWinField = findFieldInHierarchy(windowStateAnimatorClass, "mWin");
        animatorWinField.setAccessible(true);

        try {
            animatorSurfaceControllerField =
                    findFieldInHierarchy(windowStateAnimatorClass, "mSurfaceController");
            animatorSurfaceControllerField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            log(Log.WARN, TAG, "mSurfaceController not found in WindowStateAnimator");
            animatorSurfaceControllerField = null;
        }

        if (windowSurfaceControllerClass != null) {
            try {
                surfaceControllerSurfaceField =
                        findFieldInHierarchy(windowSurfaceControllerClass, "mSurfaceControl");
                surfaceControllerSurfaceField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                log(Log.WARN, TAG, "mSurfaceControl not found in WindowSurfaceController");
                surfaceControllerSurfaceField = null;
            }
        } else {
            surfaceControllerSurfaceField = null;
        }

        try {
            windowStateScField = findFieldInHierarchy(windowStateClass, "mSurfaceControl");
            windowStateScField.setAccessible(true);
        } catch (Throwable ignored) {
            windowStateScField = null;
        }

        systemTxnConstructor = transactionClass.getDeclaredConstructor();
        systemTxnConstructor.setAccessible(true);
        systemTxnApply = transactionClass.getDeclaredMethod("apply");
        systemTxnApply.setAccessible(true);
        systemTxnClose = transactionClass.getDeclaredMethod("close");
        systemTxnClose.setAccessible(true);

        try {
            systemTxnSetSkipScreenshot = transactionClass.getDeclaredMethod(
                    "setSkipScreenshot", surfaceControlClass, boolean.class);
            systemTxnSetSkipScreenshot.setAccessible(true);
            systemTxnMethodType = 1;
        } catch (Throwable ignored) {
        }

        try {
            systemTxnSetSecure = transactionClass.getDeclaredMethod(
                    "setSecure", surfaceControlClass, boolean.class);
            systemTxnSetSecure.setAccessible(true);
            if (systemTxnMethodType == 0) systemTxnMethodType = 2;
        } catch (Throwable ignored) {
        }

        if (systemTxnMethodType == 0) {
            log(Log.WARN, TAG, "Neither setSkipScreenshot nor setSecure found");
        }

        // Oplus 小窗状态（system_server 公开类）
        try {
            zoomWindowManagerClass = Class.forName("com.oplus.zoomwindow.OplusZoomWindowManager", false, cl);
            zoomWindowGetInstanceMethod = zoomWindowManagerClass.getDeclaredMethod("getInstance");
            zoomWindowGetInstanceMethod.setAccessible(true);
            zoomWindowGetCurrentStateMethod = zoomWindowManagerClass.getDeclaredMethod("getCurrentZoomWindowState");
            zoomWindowGetCurrentStateMethod.setAccessible(true);
            zoomWindowInfoClass = Class.forName("com.oplus.zoomwindow.OplusZoomWindowInfo", false, cl);
            zoomWindowInfoWindowShownField = findFieldInHierarchy(zoomWindowInfoClass, "windowShown");
            zoomWindowInfoWindowShownField.setAccessible(true);
            zoomWindowInfoZoomPkgField = findFieldInHierarchy(zoomWindowInfoClass, "zoomPkg");
            zoomWindowInfoZoomPkgField.setAccessible(true);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "OplusZoomWindow reflection failed: " + t.getMessage());
            zoomWindowManagerClass = null;
            zoomWindowGetInstanceMethod = null;
            zoomWindowGetCurrentStateMethod = null;
            zoomWindowInfoClass = null;
            zoomWindowInfoWindowShownField = null;
            zoomWindowInfoZoomPkgField = null;
            zoomWindowManagerInstance = null;
        }
    }

    /** 主钩子：WindowState.updateSurfacePosition 每次调用后，动态设置/取消小窗 surface 的 skipScreenshot。 */
    private void installZoomHideHook() throws Exception {
        Method m = findMethodInHierarchy(windowStateClass, "updateSurfacePosition", transactionClass);
        hook(m)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object winState = chain.getThisObject();
                        Object transaction = chain.getArgs()[0];
                        boolean isNeedHide = shouldHideZoom(winState);
                        if (transaction == null) return result;
                        Object sc = windowStateScField != null ? windowStateScField.get(winState) : null;
                        if (sc != null) setSkipScreenshotInTxn(transaction, sc, isNeedHide, systemSecureApplied);
                        if (getTaskMethod != null && taskGetSurfaceControlMethod != null) {
                            Object task = getTaskMethod.invoke(winState);
                            if (task != null) {
                                Object taskSc = taskGetSurfaceControlMethod.invoke(task);
                                if (taskSc != null) {
                                    setSkipScreenshotInTxn(transaction, taskSc, isNeedHide, taskSecureApplied);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                });
    }

    /**
     * 在给定 transaction 上按 hide 设置/取消 skipScreenshot，并用 appliedSet 跟踪每个 surface 的当前状态：
     * 已设为 true 的不重复设置；需取消时仅对已设置过的 surface 调 setSkipScreenshot(false)，实现动态恢复。
     */
    private void setSkipScreenshotInTxn(Object txn, Object sc, boolean hide, Set<Object> appliedSet) {
        if (systemTxnSetSkipScreenshot == null) return;
        if (hide) {
            if (!appliedSet.add(sc)) return;
        } else {
            if (!appliedSet.remove(sc)) return;
        }
        try {
            systemTxnSetSkipScreenshot.invoke(txn, sc, hide);
        } catch (Throwable t) {
            if (hide) appliedSet.remove(sc);
            else appliedSet.add(sc);
        }
    }

    /** 后备钩子：主钩子缺失（无 updateSurfacePosition）时，在小窗 performShowLocked 后设一次 skipScreenshot(true)。 */
    private void installShowFallbackHook() throws Exception {
        Method m = findMethodInHierarchy(windowStateAnimatorClass, "performShowLocked");
        hook(m)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    applySkipScreenshotIfZoom(chain.getThisObject());
                    return result;
                });
    }

    private void installDestroySurfaceHook() {
        try {
            Method m = findMethodInHierarchy(windowStateAnimatorClass, "destroySurfaceLocked");
            hook(m)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object sc = null;
                        try {
                            Object animator = chain.getThisObject();
                            if (animatorSurfaceControllerField != null) {
                                Object ctrl = animatorSurfaceControllerField.get(animator);
                                if (ctrl != null && surfaceControllerSurfaceField != null) {
                                    sc = surfaceControllerSurfaceField.get(ctrl);
                                }
                            }
                        } catch (Throwable ignored) {
                        }

                        Object result = chain.proceed();

                        if (sc != null) systemSecureApplied.remove(sc);
                        return result;
                    });
        } catch (Throwable ignored) {
        }
    }

    // ==================== 隐藏小窗配置与判定 ====================

    private void loadSystemHideConfig(SharedPreferences prefs) {
        hideZoomEnabled = prefs.getBoolean("hide_zoom_enabled", false);
        Set<String> raw = prefs.getStringSet("show_zoom_packages", Collections.emptySet());
        Set<String> lower = new HashSet<>(raw.size() * 2);
        for (String p : raw) {
            if (p != null && !p.isEmpty()) lower.add(p.toLowerCase(Locale.ROOT));
        }
        showZoomPackages = Collections.unmodifiableSet(lower);
    }

    /**
     * 是否隐藏该窗口：总开关开启 && 是小窗 && 小窗所属包（# 分隔）任一不在“要显示的小窗”集合中。
     */
    private boolean shouldHideZoom(Object winState) {
        if (!hideZoomEnabled || winState == null) return false;

        boolean isZoom = isZoomWindow(winState);
        String zoomPkg = getZoomPkg();
        boolean hide = isZoom && !isZoomPkgShown(zoomPkg);

        if (isZoom || hide) {
            debugLog(Log.INFO, TAG, "[ZOOM] tag=" + getWindowTag(winState)
                    + " isZoom=" + isZoom
                    + " zoomPkg=" + zoomPkg
                    + " windowShown=" + isZoomWindowShown()
                    + " hide=" + hide);
        }
        return hide;
    }

    /** 判断窗口是否处于 Oplus 小窗（缩放窗）模式：所在 Task 的 windowingMode == WINDOWING_MODE_ZOOM。 */
    private boolean isZoomWindow(Object winState) {
        if (winState == null || getTaskMethod == null || taskGetWindowingModeMethod == null) return false;
        try {
            Object task = getTaskMethod.invoke(winState);
            if (task == null) return false;
            Object mode = taskGetWindowingModeMethod.invoke(task);
            return mode instanceof Integer && (Integer) mode == WINDOWING_MODE_ZOOM;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 读取当前小窗所属包名（OplusZoomWindowInfo.zoomPkg），失败/不可用时返回 null。 */
    private String getZoomPkg() {
        if (zoomWindowGetCurrentStateMethod == null || zoomWindowInfoZoomPkgField == null) return null;
        try {
            Object mgr = getZoomWindowManager();
            if (mgr == null) return null;
            Object info = zoomWindowGetCurrentStateMethod.invoke(mgr);
            if (info == null) return null;
            return (String) zoomWindowInfoZoomPkgField.get(info);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** OplusZoomWindowInfo.windowShown：仅用于诊断日志，不参与判定。 */
    private boolean isZoomWindowShown() {
        if (zoomWindowGetCurrentStateMethod == null || zoomWindowInfoWindowShownField == null) return false;
        try {
            Object mgr = getZoomWindowManager();
            if (mgr == null) return false;
            Object info = zoomWindowGetCurrentStateMethod.invoke(mgr);
            if (info == null) return false;
            return Boolean.TRUE.equals(zoomWindowInfoWindowShownField.get(info));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Object getZoomWindowManager() {
        if (zoomWindowManagerClass == null) return null;
        Object cached = zoomWindowManagerInstance;
        if (cached != null) return cached;
        if (zoomWindowGetInstanceMethod == null) return null;
        try {
            Object mgr = zoomWindowGetInstanceMethod.invoke(null);
            if (mgr != null) zoomWindowManagerInstance = mgr;
            return mgr;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** zoomPkg 可能以 "#" 分隔多个包：全部在“要显示的小窗”集合中才视为豁免（不隐藏）。 */
    private boolean isZoomPkgShown(String zoomPkg) {
        if (zoomPkg == null || zoomPkg.isEmpty()) return false;
        for (String part : zoomPkg.split("#")) {
            if (part.isEmpty()) return false;
            if (!showZoomPackages.contains(part.toLowerCase(Locale.ROOT))) return false;
        }
        return true;
    }

    private String getWindowTag(Object winState) {
        if (winState == null || getWindowTagMethod == null) return null;
        try {
            Object tag = getWindowTagMethod.invoke(winState);
            return tag == null ? null : tag.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 后备：对小窗窗口 surface 与 task surface 各建一次 Transaction 设 skipScreenshot(true)。 */
    private void applySkipScreenshotIfZoom(Object animator) {
        if (animator == null) return;
        Object winState = animatorWinFieldGet(animator);
        if (winState == null || !shouldHideZoom(winState)) return;
        try {
            Object sc = null;
            if (animatorSurfaceControllerField != null) {
                Object ctrl = animatorSurfaceControllerField.get(animator);
                if (ctrl != null && surfaceControllerSurfaceField != null) {
                    sc = surfaceControllerSurfaceField.get(ctrl);
                }
            }
            if (sc == null && windowStateScField != null) {
                sc = windowStateScField.get(winState);
            }
            if (sc != null) applySkipScreenshot(sc);
            applySkipScreenshotToTask(winState);
        } catch (Throwable ignored) {
        }
    }

    private Object animatorWinFieldGet(Object animator) {
        try {
            return animatorWinField.get(animator);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void applySkipScreenshotToTask(Object winState) {
        if (getTaskMethod == null || taskGetSurfaceControlMethod == null) return;
        try {
            Object task = getTaskMethod.invoke(winState);
            if (task == null || !taskSecureApplied.add(task)) return;
            Object taskSc = taskGetSurfaceControlMethod.invoke(task);
            if (taskSc != null) applySkipScreenshot(taskSc);
        } catch (Throwable ignored) {
        }
    }

    private void applySkipScreenshot(Object sc) {
        if (systemTxnMethodType == 0) return;
        if (!systemSecureApplied.add(sc)) return;

        Object txn = null;
        boolean success = false;
        try {
            txn = systemTxnConstructor.newInstance();
            if (systemTxnMethodType == 1) {
                if (systemTxnSetSkipScreenshot != null) {
                    systemTxnSetSkipScreenshot.invoke(txn, sc, true);
                }
            } else {
                if (systemTxnSetSecure != null) {
                    systemTxnSetSecure.invoke(txn, sc, true);
                }
            }
            systemTxnApply.invoke(txn);
            success = true;
        } catch (Throwable t) {
            if (systemTxnMethodType == 1 && systemTxnSetSecure != null) {
                log(Log.WARN, TAG, "setSkipScreenshot failed, falling back to setSecure");
                systemTxnMethodType = 2;
                try {
                    if (txn != null) {
                        systemTxnClose.invoke(txn);
                        txn = null;
                    }
                    txn = systemTxnConstructor.newInstance();
                    systemTxnSetSecure.invoke(txn, sc, true);
                    systemTxnApply.invoke(txn);
                    success = true;
                } catch (Throwable t2) {
                    log(Log.ERROR, TAG, "setSecure also failed: " + t2.getMessage());
                }
            } else {
                log(Log.ERROR, TAG, "applySkipScreenshot failed: " + t.getMessage());
            }
        } finally {
            if (txn != null) try {
                systemTxnClose.invoke(txn);
            } catch (Throwable ignored) {
            }
        }
        if (!success) systemSecureApplied.remove(sc);
    }

    /** 仅 debug 构建输出日志，release 构建静默以减少日志量。 */
    private void debugLog(int level, String tag, String msg) {
        if (BuildConfig.DEBUG) log(level, tag, msg);
    }

    @SuppressLint("PrivateApi")
    private void initAppReflection(ClassLoader cl) throws Exception {
        if (sAppCacheReady) return;
        synchronized (appLock) {
            if (sAppCacheReady) return;

            Class<?> vriClass = Class.forName("android.view.ViewRootImpl", false, cl);
            for (String name : new String[]{
                    "mSurfaceControl", "mSurface", "mLeash", "mSurfaceControlLocked"}) {
                try {
                    Field f = vriClass.getDeclaredField(name);
                    f.setAccessible(true);
                    sSurfaceControlField = f;
                    debugLog(Log.DEBUG, TAG, "VRI SC field: " + name);
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (sSurfaceControlField == null) {
                log(Log.WARN, TAG, "No SurfaceControl field found in ViewRootImpl");
            }

            sScClass = Class.forName("android.view.SurfaceControl", false, cl);
            sScIsValid = sScClass.getDeclaredMethod("isValid");
            sScIsValid.setAccessible(true);

            Class<?> txnClass = Class.forName(
                    "android.view.SurfaceControl$Transaction", false, cl);
            sTxnConstructor = txnClass.getDeclaredConstructor();
            sTxnConstructor.setAccessible(true);
            sTxnApply = txnClass.getDeclaredMethod("apply");
            sTxnApply.setAccessible(true);
            sTxnClose = txnClass.getDeclaredMethod("close");
            sTxnClose.setAccessible(true);

            try {
                sTxnSetSkipScreenshot = txnClass.getDeclaredMethod(
                        "setSkipScreenshot", sScClass, boolean.class);
                sTxnSetSkipScreenshot.setAccessible(true);
            } catch (Throwable ignored) {
            }
            try {
                sTxnSetSkipScreenshotLegacy = txnClass.getDeclaredMethod(
                        "setSkipScreenshot", boolean.class);
                sTxnSetSkipScreenshotLegacy.setAccessible(true);
            } catch (Throwable ignored) {
            }
            try {
                sTxnSetSecure = txnClass.getDeclaredMethod(
                        "setSecure", sScClass, boolean.class);
                sTxnSetSecure.setAccessible(true);
            } catch (Throwable ignored) {
            }

            sAppCacheReady = true;
        }
    }

    @SuppressLint("PrivateApi")
    private void installWindowManagerHook(ClassLoader cl) throws Exception {
        Class<?> wmg = Class.forName(
                "android.view.WindowManagerGlobal", false, cl);
        for (Method method : wmg.getDeclaredMethods()) {
            String name = method.getName();
            if (!"addView".equals(name) && !"updateViewLayout".equals(name)) continue;
            hook(method)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        for (Object arg : chain.getArgs()) {
                            if (arg instanceof WindowManager.LayoutParams) {
                                modifyLayoutParams((WindowManager.LayoutParams) arg);
                                break;
                            }
                        }
                        return chain.proceed();
                    });
        }
    }

    @SuppressLint("WrongConstant")
    private void modifyLayoutParams(WindowManager.LayoutParams lp) {
        if (enabledFeatures.contains("FLAG_DIM_BEHIND_0")) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            lp.dimAmount = 0f;
        }
        if (enabledFeatures.contains("show_wallpaper")) {
            lp.flags |= WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
        }
        if (enabledFeatures.contains("magic_flags")) {
            lp.flags |= FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_NOT_TOUCH_MODAL;
        }
        if (enabledFeatures.contains("nofocus_only")) {
            lp.flags |= FLAG_NOT_FOCUSABLE;
        }
        if (windowTitle != null) {
            try {
                lp.setTitle(windowTitle);
            } catch (Throwable ignored) {
            }
        }
    }

    @SuppressLint("PrivateApi")
    private void installAntiScreenshotHook(ClassLoader cl) throws Exception {
        Class<?> vriClass = Class.forName(
                "android.view.ViewRootImpl", false, cl);
        for (Method m : vriClass.getDeclaredMethods()) {
            final String name = m.getName();
            if (!"setView".equals(name) && !"relayoutWindow".equals(name)) continue;

            hook(m)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!enabledFeatures.contains("enable_skip_screenshot")) {
                            return chain.proceed();
                        }
                        Object vri = chain.getThisObject();
                        if ("setView".equals(name)) {
                            chain.proceed();
                            applySecure(vri);
                            return null;
                        } else { // relayoutWindow
                            secureApplied.remove(vri);
                            Object result = chain.proceed();
                            applySecure(vri);
                            return result;
                        }
                    });
        }
    }

    private void applySecure(Object vri) {
        if (!sAppCacheReady || secureApplied.contains(vri)) return;
        Object sc = getValidSurface(vri);
        if (sc == null) return;

        Object txn = null;
        try {
            txn = sTxnConstructor.newInstance();
            boolean applied = false;
            if (sTxnSetSkipScreenshot != null) {
                try {
                    sTxnSetSkipScreenshot.invoke(txn, sc, true);
                    applied = true;
                } catch (Throwable ignored) {
                    // Permanent fallback: failed method is likely broken on this ROM
                    sTxnSetSkipScreenshot = null;
                }
            }
            if (!applied && sTxnSetSkipScreenshotLegacy != null) {
                try {
                    sTxnSetSkipScreenshotLegacy.invoke(txn, true);
                    applied = true;
                } catch (Throwable ignored) {
                    sTxnSetSkipScreenshotLegacy = null;
                }
            }
            if (!applied && sTxnSetSecure != null) {
                try {
                    sTxnSetSecure.invoke(txn, sc, true);
                    applied = true;
                } catch (Throwable ignored) {
                }
            }
            if (applied) {
                sTxnApply.invoke(txn);
                secureApplied.add(vri);
            }
        } catch (Throwable ignored) {
        } finally {
            if (txn != null) try {
                sTxnClose.invoke(txn);
            } catch (Throwable ignored) {
            }
        }
    }

    private Object getValidSurface(Object vri) {
        if (sSurfaceControlField == null || sScClass == null) return null;
        try {
            Object sc = sSurfaceControlField.get(vri);
            if (sc != null && sScClass.isInstance(sc) && Boolean.TRUE.equals(sScIsValid.invoke(sc))) {
                return sc;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @SuppressLint("PrivateApi")
    private void installSystemUIHook(ClassLoader cl) throws Exception {
        Class<?> menuManagerClass = Class.forName(
                "com.oplus.flexibletask.menu.FlexibleMenuManager",
                false, cl);

        Method getWindowParams = menuManagerClass.getDeclaredMethod("getWindowParams");
        getWindowParams.setAccessible(true);

        hook(getWindowParams)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    sFlexibleMenuShowing.set(Boolean.TRUE);
                    return result;
                });

        Method dismissMethod = menuManagerClass.getDeclaredMethod("realExeDismiss");
        dismissMethod.setAccessible(true);

        hook(dismissMethod)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    try {
                        return chain.proceed();
                    } finally {
                        sFlexibleMenuShowing.remove();
                    }
                });

        Class<?> vriClass = cl.loadClass("android.view.ViewRootImpl");
        for (Method m : vriClass.getDeclaredMethods()) {
            final String name = m.getName();
            if (!"setView".equals(name) && !"relayoutWindow".equals(name)) continue;

            hook(m)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!systemUIEnhancementEnabled) {
                            return chain.proceed();
                        }
                        Object vri = chain.getThisObject();
                        if ("setView".equals(name)) {
                            chain.proceed();
                            if (Boolean.TRUE.equals(sFlexibleMenuShowing.get())) {
                                flexibleTaskVri.add(vri);
                                applySecure(vri);
                                log(Log.DEBUG, TAG, "FlexibleTaskMenu skip-screenshot applied");
                            }
                            return null;
                        } else {
                            if (flexibleTaskVri.contains(vri)) {
                                secureApplied.remove(vri);
                            }
                            Object result = chain.proceed();
                            if (flexibleTaskVri.contains(vri)) {
                                applySecure(vri);
                            }
                            return result;
                        }
                    });
        }
    }

    private void installHideRecentsHook() throws Exception {
        hook(Activity.class.getDeclaredMethod("onCreate", Bundle.class))
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    chain.proceed();
                    if (!enabledFeatures.contains("hide_recent_card")) return null;
                    Activity act = (Activity) chain.getThisObject();
                    try {
                        ActivityManager am = (ActivityManager)
                                act.getSystemService(Activity.ACTIVITY_SERVICE);
                        if (am == null) return null;
                        int myTaskId = act.getTaskId();
                        for (ActivityManager.AppTask task : am.getAppTasks()) {
                            if (task.getTaskInfo().taskId == myTaskId) {
                                task.setExcludeFromRecents(true);
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return null;
                });
    }

    private boolean methodExists(Class<?> cls, String name, Class<?>... params) {
        try {
            findMethodInHierarchy(cls, name, params);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
