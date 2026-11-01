package de.robv.android.xposed;

import java.lang.reflect.Member;

/** Compile-only stub; real class provided by LSPosed at runtime. */
public abstract class XC_MethodHook {

    public XC_MethodHook() {}

    public XC_MethodHook(int priority) {}

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;

        public Object getResult() { return null; }

        public void setResult(Object result) {}

        public Throwable getThrowable() { return null; }

        public void setThrowable(Throwable throwable) {}
    }

    public static class Unhook {
        private Unhook() {}
    }
}
