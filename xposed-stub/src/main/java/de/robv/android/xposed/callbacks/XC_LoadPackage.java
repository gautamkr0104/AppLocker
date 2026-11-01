package de.robv.android.xposed.callbacks;

/** Compile-only stub; real class provided by LSPosed at runtime. */
public abstract class XC_LoadPackage {

    public static final class LoadPackageParam {
        public String packageName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;

        private LoadPackageParam() {}
    }
}
