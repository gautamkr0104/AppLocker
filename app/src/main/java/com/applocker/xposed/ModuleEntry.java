package com.applocker.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed entry point. Only reacts to the "android" package, i.e. system_server.
 * No application processes are hooked: launch interception happens entirely
 * inside the framework, which is why no accessibility/notification/usage
 * permissions or monitoring services are needed.
 */
public class ModuleEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) return;
        Hooks.install(lpparam);
    }
}
