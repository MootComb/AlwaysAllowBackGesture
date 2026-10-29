package MootComb.NoShadeOnBack;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class Main extends XposedModule {

    private static final String TAG = "NoShadeOnBack";
    private static final boolean DEBUG = false;

    private static final String[] SYSTEMUI_ANCHORS = {
            "com.android.systemui.SystemUIApplication",
            "com.android.systemui.SystemUIService"
    };

    private static final String EDGE_HANDLER_CLASS =
            "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler";

    private static final float EDGE_WIDTH_DP = 40f;
    private static final long BOOTSTRAP_RETRY_DELAY_MS = 2000L;
    private static final int BOOTSTRAP_MAX_RETRIES = 15;

    private static volatile float edgeDownX = Float.NaN;
    private static volatile float edgeDownY = 0f;
    private static volatile long edgeDownTime = 0L;
    private static volatile boolean edgeGestureActive = false;

    private static volatile boolean savedDisabledForQuickstep;
    private static volatile boolean savedIsBackGestureAllowed;
    private static volatile boolean savedBlockingActivity;
    private static volatile long savedSysUiFlags;
    private static volatile boolean savedUsingThreeButtonNav;
    private static volatile boolean hasSavedState = false;

    private volatile boolean bootstrapInstalled = false;
    private volatile boolean hooksInstalled = false;
    private Context systemContext;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log("Module loaded: " + param.getProcessName());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!"com.android.systemui".equals(param.getPackageName())) return;
        if (bootstrapInstalled) return;
        log("SystemUI ready, scheduling bootstrap hook...");
        ClassLoader seedCl = param.getClassLoader();
        new Handler(Looper.getMainLooper())
                .postDelayed(() -> tryInstallBootstrap(seedCl, 0), 500L);
    }

    private void tryInstallBootstrap(ClassLoader seedCl, int attempt) {
        if (bootstrapInstalled) return;

        ClassLoader[] candidates = {
                seedCl,
                currentApplicationLoader(),
                Thread.currentThread().getContextClassLoader()
        };

        for (ClassLoader cl : candidates) {
            if (cl == null) continue;
            for (String anchor : SYSTEMUI_ANCHORS) {
                Method onCreate = findOnCreate(cl, anchor);
                if (onCreate == null) continue;
                try {
                    hook(onCreate).intercept(chain -> {
                        try {
                            Object self = chain.getThisObject();
                            ClassLoader realCl = self.getClass().getClassLoader();
                            log("Bootstrap reached; real SystemUI class loader: " + realCl);
                            installHooks(realCl);
                        } catch (Throwable t) {
                            loge("Bootstrap intercept failed", t);
                        }
                        return chain.proceed();
                    });
                    bootstrapInstalled = true;
                    log("Bootstrap hook installed on " + anchor + ".onCreate");
                    return;
                } catch (Throwable t) {
                    loge("Bootstrap hook failed on " + anchor, t);
                }
            }
        }

        if (attempt >= BOOTSTRAP_MAX_RETRIES) {
            log("Bootstrap hook: giving up after " + attempt + " attempts");
            return;
        }
        log("Bootstrap hook not ready yet, retry #" + (attempt + 1));
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> tryInstallBootstrap(seedCl, attempt + 1),
                BOOTSTRAP_RETRY_DELAY_MS);
    }

    private Method findOnCreate(ClassLoader cl, String className) {
        try {
            Class<?> cls = Class.forName(className, false, cl);
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals("onCreate") && m.getParameterCount() == 0) return m;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private ClassLoader currentApplicationLoader() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method currentApp = at.getDeclaredMethod("currentApplication");
            currentApp.setAccessible(true);
            Object app = currentApp.invoke(null);
            if (app instanceof Application) {
                systemContext = ((Application) app).getApplicationContext();
                ClassLoader cl = ((Application) app).getClassLoader();
                if (cl != null) return cl;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private synchronized void installHooks(ClassLoader realCl) {
        if (hooksInstalled || realCl == null) return;
        try {
            installEdgeBackHook(realCl);
            hooksInstalled = true;
            log("All hooks installed.");
        } catch (Throwable t) {
            loge("Hook installation failed", t);
        }
    }

    private void installEdgeBackHook(ClassLoader cl) {
        try {
            Class<?> ebgh = cl.loadClass(EDGE_HANDLER_CLASS);
            Class<?> inputEventCls = cl.loadClass("android.view.InputEvent");
            Method onInputEvent = ebgh.getDeclaredMethod("onInputEvent", inputEventCls);

            hook(onInputEvent).intercept(chain -> {
                Object arg = chain.getArg(0);
                if (!(arg instanceof MotionEvent)) return chain.proceed();
                MotionEvent event = (MotionEvent) arg;
                Object self = chain.getThisObject();
                int action = event.getActionMasked();

                if (action == MotionEvent.ACTION_DOWN) {
                    float x = event.getX();
                    float y = event.getY();
                    int screenWidth = getDisplayWidth(self);
                    float edgeWidthPx = getEdgeWidthPx();
                    boolean fromEdge = x < edgeWidthPx
                            || (screenWidth > 0 && x > screenWidth - edgeWidthPx);

                    edgeDownX = fromEdge ? x : Float.NaN;
                    edgeDownY = y;
                    edgeDownTime = SystemClock.uptimeMillis();
                    edgeGestureActive = fromEdge;

                    log("EBGH DOWN x=" + x + " y=" + y
                            + " fromEdge=" + fromEdge + " w=" + screenWidth);

                    if (fromEdge) {
                        saveEbgState(self);
                        setBooleanField(self, "mDisabledForQuickstep", false);
                        setBooleanField(self, "mIsBackGestureAllowed", true);
                        setAtomicBoolean(self, "mGestureBlockingActivityRunning", false);
                        setBooleanField(self, "mUsingThreeButtonNav", false);
                        setLongField(self, "mSysUiFlags", 0L);
                        log("Forced EBGH conditions (sysUiFlags=0)");
                    }
                    return chain.proceed();
                }

                if (action == MotionEvent.ACTION_MOVE) {
                    if (edgeGestureActive && !Float.isNaN(edgeDownX)) {
                        float dx = Math.abs(event.getX() - edgeDownX);
                        float dy = Math.abs(event.getY() - edgeDownY);
                        float touchSlop = getTouchSlop();
                        if (dy > dx * 2f && dy > touchSlop * 3f) {
                            log("Vertical swipe -> not back (dx=" + dx + " dy=" + dy + ")");
                            edgeGestureActive = false;
                            edgeDownX = Float.NaN;
                            restoreEbgState(self);
                        }
                    }
                    return chain.proceed();
                }

                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    boolean wasActive = edgeGestureActive;
                    float dx = Float.isNaN(edgeDownX) ? 0 : Math.abs(event.getX() - edgeDownX);
                    float dy = Float.isNaN(edgeDownX) ? 0 : Math.abs(event.getY() - edgeDownY);
                    boolean wasHorizontal = wasActive && dx > dy && dx > getTouchSlop() * 2f;

                    log("EBGH UP dx=" + dx + " dy=" + dy
                            + " wasActive=" + wasActive
                            + " wasHorizontal=" + wasHorizontal);

                    edgeDownX = Float.NaN;
                    edgeGestureActive = false;
                    restoreEbgState(self);

                    return chain.proceed();
                }

                return chain.proceed();
            });

            log("Hook installed on " + EDGE_HANDLER_CLASS + ".onInputEvent");
        } catch (Throwable t) {
            loge("EdgeBack hook failed", t);
        }
    }

    private void saveEbgState(Object ebgh) {
        if (hasSavedState) return;
        try {
            savedDisabledForQuickstep = getBooleanField(ebgh, "mDisabledForQuickstep");
            savedIsBackGestureAllowed = getBooleanField(ebgh, "mIsBackGestureAllowed");
            savedBlockingActivity = getAtomicBoolean(ebgh, "mGestureBlockingActivityRunning");
            savedSysUiFlags = getLongField(ebgh, "mSysUiFlags");
            savedUsingThreeButtonNav = getBooleanField(ebgh, "mUsingThreeButtonNav");
            hasSavedState = true;
            log("Saved EBGH state: mDisabledForQuickstep=" + savedDisabledForQuickstep
                    + " mIsBackGestureAllowed=" + savedIsBackGestureAllowed
                    + " mSysUiFlags=" + savedSysUiFlags);
        } catch (Throwable t) {
            loge("saveEbgState failed", t);
        }
    }

    private void restoreEbgState(Object ebgh) {
        if (!hasSavedState) return;
        try {
            setBooleanField(ebgh, "mDisabledForQuickstep", savedDisabledForQuickstep);
            setBooleanField(ebgh, "mIsBackGestureAllowed", savedIsBackGestureAllowed);
            setAtomicBoolean(ebgh, "mGestureBlockingActivityRunning", savedBlockingActivity);
            setLongField(ebgh, "mSysUiFlags", savedSysUiFlags);
            setBooleanField(ebgh, "mUsingThreeButtonNav", savedUsingThreeButtonNav);
            log("Restored EBGH state");
        } catch (Throwable t) {
            loge("restoreEbgState failed", t);
        } finally {
            hasSavedState = false;
        }
    }

    private boolean getBooleanField(Object obj, String n) {
        try { Field f = obj.getClass().getDeclaredField(n); f.setAccessible(true); return f.getBoolean(obj); }
        catch (Throwable t) { return false; }
    }
    private void setBooleanField(Object obj, String n, boolean v) {
        try { Field f = obj.getClass().getDeclaredField(n); f.setAccessible(true); f.setBoolean(obj, v); }
        catch (Throwable t) { loge("setBooleanField " + n + " failed", t); }
    }
    private long getLongField(Object obj, String n) {
        try { Field f = obj.getClass().getDeclaredField(n); f.setAccessible(true); return f.getLong(obj); }
        catch (Throwable t) { return 0L; }
    }
    private void setLongField(Object obj, String n, long v) {
        try { Field f = obj.getClass().getDeclaredField(n); f.setAccessible(true); f.setLong(obj, v); }
        catch (Throwable t) { loge("setLongField " + n + " failed", t); }
    }
    private boolean getAtomicBoolean(Object obj, String n) {
        try {
            Field f = obj.getClass().getDeclaredField(n); f.setAccessible(true);
            AtomicBoolean ab = (AtomicBoolean) f.get(obj);
            return ab != null && ab.get();
        } catch (Throwable t) { return false; }
    }
    private void setAtomicBoolean(Object obj, String n, boolean v) {
        try {
            Field f = obj.getClass().getDeclaredField(n); f.setAccessible(true);
            AtomicBoolean ab = (AtomicBoolean) f.get(obj);
            if (ab != null) ab.set(v);
        } catch (Throwable t) { loge("setAtomicBoolean " + n + " failed", t); }
    }

    private int getDisplayWidth(Object ebgh) {
        try {
            Field f = ebgh.getClass().getDeclaredField("mDisplaySize");
            f.setAccessible(true);
            Object p = f.get(ebgh);
            if (p instanceof android.graphics.Point) {
                int w = ((android.graphics.Point) p).x;
                if (w > 0) return w;
            }
        } catch (Throwable ignored) {}
        if (systemContext != null) {
            try { return systemContext.getResources().getDisplayMetrics().widthPixels; }
            catch (Throwable ignored) {}
        }
        return 0;
    }

    private float getTouchSlop() {
        try {
            if (systemContext != null) {
                return ViewConfiguration.get(systemContext).getScaledTouchSlop();
            }
        } catch (Throwable ignored) {}
        return 20f;
    }

    private float getEdgeWidthPx() {
        try {
            if (systemContext != null) {
                return EDGE_WIDTH_DP
                        * systemContext.getResources().getDisplayMetrics().density;
            }
        } catch (Throwable ignored) {}
        return EDGE_WIDTH_DP * 3f;
    }

    private static void log(String msg) {
        if (DEBUG) Log.i(TAG, msg);
    }
    private static void loge(String msg, Throwable t) {
        if (DEBUG) Log.e(TAG, msg, t);
    }
}
