package com.applocker.xposed;

import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Framework hook installation for Android 13 / Samsung One UI 5.1 (SM-A035F).
 *
 * Design notes (Android 13 / API 33 investigation):
 *
 * Every user-visible path to opening an app -- launcher, Recents, notification
 * PendingIntent, external/implicit Intent, deep link, share sheet, trampolines --
 * ultimately has to RESUME an ActivityRecord of the target task. Fresh starts go
 *   ActivityStarter -> startActivityInner -> Task/TaskFragment.resumeTopActivity*
 * and task switches (Recents / moveTaskToFront) go
 *   RootWindowContainer.resumeFocusedTasksTopActivities -> Task.resumeTopActivity*
 *
 * Hooking the resume gate therefore covers ALL of those paths with a single,
 * cheap interception point that:
 *   - knows the exact target ActivityRecord (package + task id),
 *   - runs before the framework schedules LaunchActivityItem to the app process,
 *     so the protected activity is never created/usable when blocked,
 *   - lets us abort the resume and attach our AuthActivity to the same task,
 *     so finishing auth naturally resumes the protected activity underneath.
 *
 * Selected hook points (each installed only if the class/method exists on this
 * exact Samsung build -- everything is existence-checked, failures degrade
 * gracefully and are reported to the diagnostics screen):
 *
 *   1. com.android.server.wm.Task#resumeTopActivityUncheckedLocked
 *      com.android.server.wm.TaskFragment#resumeTopActivityInnerLocked
 *      -> primary launch gate (block + show auth)
 *   2. com.android.server.wm.ActivityRecord#setState
 *      -> IMMEDIATE policy revocation when another package resumes
 *   3. com.android.server.SystemServer#start / #startBootstrapServices
 *      -> early bridge initialization (receivers, config fetch)
 *
 * No polling, no foreground monitoring, no notification/accessibility/usage APIs.
 */
public final class Hooks {

    private Hooks() {}

    private static final List<String> installed = new ArrayList<>();
    private static final List<String> missing = new ArrayList<>();

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;

        installSystemServerLifecycle(cl);
        installResumeGate(cl);
        installStateRevocation(cl);

        StringBuilder sb = new StringBuilder();
        sb.append("Android13/OneUI hooks; ok=[").append(String.join(",", installed)).append("]");
        if (!missing.isEmpty()) sb.append(" missing=[").append(String.join(",", missing)).append("]");
        SystemBridge.hookReport = sb.toString();
        try {
            de.robv.android.xposed.XposedBridge.log("AppLocker " + SystemBridge.hookReport);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------
    // 1. Primary launch gate
    // ------------------------------------------------------------------

    private static void installResumeGate(ClassLoader cl) {
        XC_MethodHook gate = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    // THREADING CONTRACT: this hook runs under the WM global lock.
                    // Only volatile reads and Handler.post() are allowed here --
                    // all IPC happens on the bridge's background thread.
                    if (!SystemBridge.isReady()) {
                        SystemBridge.ensureInitDeferred();
                        return; // fail open during the brief boot window
                    }
                    SystemBridge.ensureFreshConfig();   // posts only, never blocks
                    if (!SystemBridge.cfg.enabled) return;

                    Object self = param.thisObject;
                    Object next = topRunningActivity(self);
                    if (next == null) return;

                    String pkg = pkgOf(next);
                    if (pkg == null || pkg.equals(SystemBridge.MODULE)) return;   // never block ourselves
                    if (!SystemBridge.isProtectedNow(pkg)) return;                 // fast path for the 99% case
                    if (SystemBridge.isGranted(pkg)) return;                       // already authenticated

                    int taskId = taskIdOf(self, next);
                    SystemBridge.launchAuth(pkg, taskId);
                    skipOriginal(param);
                } catch (Throwable t) {
                    SystemBridge.logErr("resume gate", t);
                }
            }
        };

        hookIfPresent(cl, "com.android.server.wm.Task", "resumeTopActivityUncheckedLocked", gate, true);
        // On A13 the inner method lives on Task; on some builds (S-era forks / Samsung)
        // it lives on TaskFragment. Hook both where present -- blocking at the outer
        // method prevents the inner from running, so they can never double-fire.
        hookIfPresent(cl, "com.android.server.wm.Task", "resumeTopActivityInnerLocked", gate, false);
        hookIfPresent(cl, "com.android.server.wm.TaskFragment", "resumeTopActivityInnerLocked", gate, false);
    }

    // ------------------------------------------------------------------
    // 2. IMMEDIATE-policy revocation when another activity resumes
    // ------------------------------------------------------------------

    private static void installStateRevocation(ClassLoader cl) {
        XC_MethodHook state = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (SystemBridge.grants.isEmpty()) return;              // hot-path guard
                    Object[] args = param.args;
                    if (args == null || args.length == 0 || args[0] == null) return;
                    String stateName = String.valueOf(args[0]);
                    if (!"RESUMED".equals(stateName)) return;
                    String pkg = pkgOf(param.thisObject);
                    if (pkg == null) return;
                    SystemBridge.onActivityResumed(pkg);
                } catch (Throwable t) {
                    SystemBridge.logErr("setState revocation", t);
                }
            }
        };
        hookIfPresent(cl, "com.android.server.wm.ActivityRecord", "setState", state, false);
    }

    // ------------------------------------------------------------------
    // 3. Early bridge init inside system_server boot
    // ------------------------------------------------------------------

    private static void installSystemServerLifecycle(ClassLoader cl) {
        XC_MethodHook boot = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                SystemBridge.ensureInitDeferred();   // posts to bg thread; never inline
            }
        };
        hookIfPresent(cl, "com.android.server.SystemServer", "start", boot, false);
        hookIfPresent(cl, "com.android.server.SystemServer", "startBootstrapServices", boot, false);
    }

    // ------------------------------------------------------------------
    // Reflection helpers (all existence-checked; Samsung may rename anything)
    // ------------------------------------------------------------------

    private static void hookIfPresent(ClassLoader cl, String cls, String method,
                                      XC_MethodHook hook, boolean critical) {
        try {
            Class<?> c = XposedHelpers.findClass(cls, cl);
            // hookAllMethods returns the set of installed unhookers; an empty set means
            // the method does not exist on this build -- report honestly.
            java.util.Set<?> unhookers = XposedBridge.hookAllMethods(c, method, hook);
            String shortName = cls.substring(cls.lastIndexOf('.') + 1) + "#" + method;
            if (unhookers == null || unhookers.isEmpty()) {
                missing.add(shortName);
                if (critical) SystemBridge.logErr("critical hook missing: " + shortName, null);
            } else {
                installed.add(shortName + "(" + unhookers.size() + ")");
            }
        } catch (Throwable t) {
            missing.add(cls.substring(cls.lastIndexOf('.') + 1) + "#" + method);
            if (critical) SystemBridge.logErr("critical hook missing: " + cls + "#" + method, t);
        }
    }

    /** Abort the original method, adapting to its return type. */
    private static void skipOriginal(XC_MethodHook.MethodHookParam param) {
        Class<?> rt = null;
        if (param.method instanceof Method) rt = ((Method) param.method).getReturnType();
        if (rt == null || rt == void.class) param.setResult(null);
        else if (rt == boolean.class || rt == Boolean.class) param.setResult(true);
        else if (rt == int.class || rt == Integer.class) param.setResult(0);
        else param.setResult(null);
    }

    /** Find the ActivityRecord that is about to resume on the given Task/TaskFragment. */
    private static Object topRunningActivity(Object taskOrFragment) {
        String[] methods = {"topRunningActivity", "getTopMostActivity", "getTopRunningActivity"};
        for (String m : methods) {
            try {
                Method meth = findMethod(taskOrFragment.getClass(), m);
                if (meth != null) {
                    meth.setAccessible(true);
                    Object r = meth.getParameterCount() == 0
                            ? meth.invoke(taskOrFragment)
                            : meth.invoke(taskOrFragment, Boolean.FALSE);
                    if (r != null) return r;
                }
            } catch (Throwable ignored) { }
        }
        // Field fallbacks
        String[] fields = {"mTopRunningActivity", "topRunningActivity"};
        for (String f : fields) {
            Object v = readField(taskOrFragment, f);
            if (v != null) return v;
        }
        return null;
    }

    /** Resolve the package name of an ActivityRecord (field `info` first: present on A13). */
    private static String pkgOf(Object activityRecord) {
        if (activityRecord == null) return null;
        Object info = readField(activityRecord, "info");
        if (info instanceof ActivityInfo) {
            ApplicationInfo ai = ((ActivityInfo) info).applicationInfo;
            if (ai != null && ai.packageName != null) return ai.packageName;
        }
        Object p = readField(activityRecord, "packageName");
        if (p instanceof String) return (String) p;
        p = readField(activityRecord, "mPackageName");
        if (p instanceof String) return (String) p;
        try {
            Method m = findMethod(activityRecord.getClass(), "getPackageName");
            if (m != null) {
                m.setAccessible(true);
                Object r = m.invoke(activityRecord);
                if (r instanceof String) return (String) r;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Resolve the task id for attaching the auth activity (-1 = unknown). */
    private static int taskIdOf(Object taskOrFragment, Object activityRecord) {
        Integer id = taskIdOfSingle(taskOrFragment);
        if (id != null) return id;
        try {
            Method getTask = findMethod(activityRecord.getClass(), "getTask");
            if (getTask != null) {
                getTask.setAccessible(true);
                Object task = getTask.invoke(activityRecord);
                if (task != null) {
                    Integer t = taskIdOfSingle(task);
                    if (t != null) return t;
                }
            }
        } catch (Throwable ignored) { }
        Object t = readField(activityRecord, "task");
        if (t != null) {
            Integer id2 = taskIdOfSingle(t);
            if (id2 != null) return id2;
        }
        return -1;
    }

    private static Integer taskIdOfSingle(Object task) {
        try {
            Method m = findMethod(task.getClass(), "getTaskId");
            if (m != null) {
                m.setAccessible(true);
                Object r = m.invoke(task);
                if (r instanceof Integer) return (Integer) r;
            }
        } catch (Throwable ignored) { }
        for (String f : new String[]{"mTaskId", "taskId"}) {
            Object v = readField(task, f);
            if (v instanceof Integer) return (Integer) v;
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null) {
            try {
                Method[] all = cur.getDeclaredMethods();
                for (Method m : all) {
                    if (m.getName().equals(name)
                            && (m.getParameterCount() == 0 || m.getParameterCount() == 1)) {
                        return m;
                    }
                }
            } catch (Throwable ignored) { }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static Object readField(Object o, String name) {
        Class<?> cur = o.getClass();
        while (cur != null) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                cur = cur.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
