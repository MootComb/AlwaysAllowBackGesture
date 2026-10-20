package MootComb.unlocker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

// Xposed module for SystemUI: hooks KeyguardViewMediator and allows
// unlocking the screen via the broadcast intent com.mootcomb.unlocker.UNLOCK
public class Main extends XposedModule {

    // Debug logs disabled (set true to enable for debugging)
    private static final boolean DEBUG = false;

    private static final String TAG = "DeviceUnlocker";
    // Action used to receive the unlock command
    private static final String ACTION_UNLOCK = "com.mootcomb.unlocker.UNLOCK";
    // Target SystemUI class responsible for the keyguard (lock screen)
    private static final String TARGET_CLASS = "com.android.systemui.keyguard.KeyguardViewMediator";
    // Delay before hooking after package is ready (wait for SystemUI initialization)
    private static final long HOOK_DELAY_MS = 5000L;
    // Retry delay if KeyguardViewMediator is not ready yet
    private static final long RETRY_DELAY_MS = 2000L;

    // KeyguardViewMediator methods hooked to capture the instance (this)
    private static final String[] METHODS_TO_HOOK = {
        "start",
        "onSystemReady",
        "hideLocked",
        "dismiss",
        "setShowingLocked",
        "doKeyguardLocked",
        "doKeyguardTimeout",
        "onStartedWakingUp",
        "onStartedGoingToSleep",
        "onFinishedWakingUp"
    };

    // Methods that can programmatically dismiss the keyguard (tried in order)
    private static final String[] DISMISS_METHODS = {
        "hideLocked",
        "dismiss"
    };

    private Context systemContext;                 // SystemUI context obtained via hooking Application.attach
    private Object keyguardViewMediatorInstance = null; // KeyguardViewMediator instance (captured from a hook)
    private Method dismissMethod = null;           // Reference to the unlock method (hideLocked or dismiss)
    private volatile boolean methodsHooked = false; // Flag to avoid re-hooking (volatile — accessed from different threads)

    // Logging wrappers: only write when DEBUG=true to avoid spamming logcat
    private static void logI(String msg) {
        if (DEBUG) Log.i(TAG, msg);
    }

    private static void logW(String msg) {
        if (DEBUG) Log.w(TAG, msg);
    }

    private static void logE(String msg) {
        if (DEBUG) Log.e(TAG, msg);
    }

    private static void logE(String msg, Throwable t) {
        if (DEBUG) Log.e(TAG, msg, t);
    }

    // Called when the module is loaded into a process — just log the process name
    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        logI("onModuleLoaded: process=" + param.getProcessName());
    }

    // Called when the package is ready. Only handle com.android.systemui
    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        logI("onPackageReady: pkg=" + param.getPackageName());
        if (!"com.android.systemui".equals(param.getPackageName())) {
            return; // Not our package — exit
        }
        logI("SystemUI ready, hooking...");
        try {
            // 1) Hook Application.attach to obtain the SystemUI Context
            hookApplicationAttach();
            // 2) Hook KeyguardViewMediator methods with a delay (wait for SystemUI init)
            new Handler(Looper.getMainLooper())
                .postDelayed(this::hookKeyguardViewMediatorMethods, HOOK_DELAY_MS);
        } catch (Throwable t) {
            logE("onPackageReady error: " + t);
        }
    }

    // Hook Application.attach(Context): intercept the moment the context is attached,
    // save systemContext and register a BroadcastReceiver for the unlock command
    private void hookApplicationAttach() {
        try {
            Method attachMethod = android.app.Application.class
                .getDeclaredMethod("attach", Context.class);
            hook(attachMethod).intercept(chain -> {
                Object result = chain.proceed(); // Execute the original method first
                Context context = (Context) chain.getArg(0); // Argument — Context
                if (context != null && systemContext == null) { // Save only the first context
                    systemContext = context;
                    logI("Context obtained");
                    registerUnlockReceiver(systemContext); // Register the command receiver
                }
                return result;
            });
        } catch (Throwable t) {
            logE("hookApplicationAttach error: " + t);
        }
    }

    // Hooks all methods from METHODS_TO_HOOK on KeyguardViewMediator
    // to capture the class instance (chain.getThisObject()) and store it
    private void hookKeyguardViewMediatorMethods() {
        if (methodsHooked) return; // Already hooked — exit
        try {
            ClassLoader cl = getSystemUIClassLoader(); // Need the correct SystemUI ClassLoader
            if (cl == null) {
                logE("ClassLoader not obtained");
                return;
            }

            Class<?> kvmClass = cl.loadClass(TARGET_CLASS); // Load the target class
            logI("KeyguardViewMediator found: " + kvmClass.getName());

            resolveDismissMethod(kvmClass); // Find the unlock method (hideLocked/dismiss)

            int hooked = 0;
            for (Method m : kvmClass.getDeclaredMethods()) {
                if (!isHookableMethod(m.getName())) continue; // Hook only methods from the list
                logI("Hooking method: " + m.getName() + " params=" + m.getParameterCount());
                hook(m).intercept(chain -> {
                    Object result = chain.proceed(); // Execute the original
                    if (keyguardViewMediatorInstance == null) {
                        // Save the KeyguardViewMediator instance (this) on the first method call
                        keyguardViewMediatorInstance = chain.getThisObject();
                    }
                    return result;
                });
                hooked++;
            }
            logI("Hooked KeyguardViewMediator methods: " + hooked);
            methodsHooked = true; // Mark hooks as installed
        } catch (Throwable t) {
            logE("hookKeyguardViewMediatorMethods error: " + t);
        }
    }

    // Finds the unlock method: tries hideLocked(), then dismiss()
    private void resolveDismissMethod(Class<?> kvmClass) {
        for (String name : DISMISS_METHODS) {
            try {
                Method m = kvmClass.getDeclaredMethod(name); // Method with no parameters
                m.setAccessible(true); // Remove access restrictions
                dismissMethod = m;
                logI(name + "() found");
                return; // Found — exit
            } catch (NoSuchMethodException ignored) {
                // Method not found — try the next one
            }
        }
        logE("Neither hideLocked() nor dismiss() found");
    }

    // Checks whether the method name is in the list of methods to hook
    private static boolean isHookableMethod(String name) {
        for (String candidate : METHODS_TO_HOOK) {
            if (candidate.equals(name)) return true;
        }
        return false;
    }

    // Obtains the SystemUI ClassLoader: first via ActivityThread.mInitialApplication,
    // then (fallback) via the saved systemContext
    private ClassLoader getSystemUIClassLoader() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method currentAT = at.getDeclaredMethod("currentActivityThread");
            Object thread = currentAT.invoke(null); // Current ActivityThread
            if (thread != null) {
                java.lang.reflect.Field mInitialApplication =
                    at.getDeclaredField("mInitialApplication");
                mInitialApplication.setAccessible(true);
                Object app = mInitialApplication.get(thread); // Initial Application
                if (app instanceof Context) {
                    ClassLoader cl = ((Context) app).getClassLoader();
                    logI("ClassLoader from mInitialApplication: " + cl);
                    if (cl != null) return cl;
                }
            }
        } catch (Throwable t) {
            logW("getSystemUIClassLoader (ActivityThread): " + t);
        }

        // Fallback: ClassLoader from the saved SystemUI context
        if (systemContext != null) {
            ClassLoader cl = systemContext.getClassLoader();
            logI("ClassLoader from systemContext: " + cl);
            if (cl != null) return cl;
        }

        return null;
    }

    // Registers a BroadcastReceiver for the ACTION_UNLOCK command.
    // RECEIVER_EXPORTED — receiver is accessible externally (command sent from another app)
    private void registerUnlockReceiver(Context ctx) {
        try {
            if (ctx == null) {
                logE("registerUnlockReceiver: ctx is null");
                return;
            }
            IntentFilter filter = new IntentFilter(ACTION_UNLOCK);
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ACTION_UNLOCK.equals(intent.getAction())) {
                        logI("UNLOCK command received");
                        performUnlock(); // On command — attempt to unlock
                    }
                }
            }, filter, Context.RECEIVER_EXPORTED);
            logI("Receiver registered");
        } catch (Throwable t) {
            logE("registerUnlockReceiver error: " + t);
        }
    }

    // Performs the unlock: invokes the saved method on the KeyguardViewMediator instance.
    // If the instance/method is not ready yet — retries after RETRY_DELAY_MS
    private void performUnlock() {
        logI("performUnlock: instance=" + keyguardViewMediatorInstance
            + " method=" + dismissMethod);

        if (keyguardViewMediatorInstance == null || dismissMethod == null) {
            logW("KeyguardViewMediator not ready, retry in " + RETRY_DELAY_MS + "ms");
            new Handler(Looper.getMainLooper())
                .postDelayed(this::performUnlock, RETRY_DELAY_MS);
            return;
        }

        // Invoke the method on the main thread (SystemUI UI thread)
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                dismissMethod.invoke(keyguardViewMediatorInstance); // The actual unlock
                logI("Method invoked: " + dismissMethod.getName());
            } catch (Throwable t) {
                logE("invoke error: " + t, t);
            }
        });
    }
}
