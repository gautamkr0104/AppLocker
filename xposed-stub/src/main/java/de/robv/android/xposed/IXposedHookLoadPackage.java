package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Compile-only stub of the Xposed API. The real implementation is provided by
 * LSPosed at runtime inside system_server; this class is never packaged into
 * the APK (compileOnly dependency). Signatures/descriptors mirror the official
 * API (api-82) exactly for the members this project uses.
 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
