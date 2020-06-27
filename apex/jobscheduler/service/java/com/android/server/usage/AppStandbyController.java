/**
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.android.server.usage;

import static android.app.usage.UsageStatsManager.REASON_MAIN_DEFAULT;
import static android.app.usage.UsageStatsManager.REASON_MAIN_FORCED_BY_SYSTEM;
import static android.app.usage.UsageStatsManager.REASON_MAIN_FORCED_BY_USER;
import static android.app.usage.UsageStatsManager.REASON_MAIN_MASK;
import static android.app.usage.UsageStatsManager.REASON_MAIN_PREDICTED;
import static android.app.usage.UsageStatsManager.REASON_MAIN_TIMEOUT;
import static android.app.usage.UsageStatsManager.REASON_MAIN_USAGE;
import static android.app.usage.UsageStatsManager.REASON_SUB_DEFAULT_APP_UPDATE;
import static android.app.usage.UsageStatsManager.REASON_SUB_FORCED_SYSTEM_FLAG_BUGGY;
import static android.app.usage.UsageStatsManager.REASON_SUB_MASK;
import static android.app.usage.UsageStatsManager.REASON_SUB_PREDICTED_RESTORED;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_ACTIVE_TIMEOUT;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_EXEMPTED_SYNC_SCHEDULED_DOZE;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_EXEMPTED_SYNC_SCHEDULED_NON_DOZE;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_EXEMPTED_SYNC_START;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_FOREGROUND_SERVICE_START;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_MOVE_TO_BACKGROUND;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_MOVE_TO_FOREGROUND;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_NOTIFICATION_SEEN;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_SLICE_PINNED;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_SLICE_PINNED_PRIV;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_SYNC_ADAPTER;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_SYSTEM_INTERACTION;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_SYSTEM_UPDATE;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_UNEXEMPTED_SYNC_SCHEDULED;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_USER_INTERACTION;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_EXEMPTED;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_NEVER;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET;

import static com.android.server.SystemService.PHASE_BOOT_COMPLETED;
import static com.android.server.SystemService.PHASE_SYSTEM_SERVICES_READY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.usage.AppStandbyInfo;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager.StandbyBuckets;
import android.app.usage.UsageStatsManager.SystemForcedReasons;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.CrossProfileAppsInternal;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.net.NetworkScoreManager;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IDeviceIdleController;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.telephony.TelephonyManager;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.KeyValueListParser;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.view.Display;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.ConcurrentUtils;
import com.android.server.LocalServices;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.usage.AppIdleHistory.AppUsageHistory;

import java.io.File;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Manages the standby state of an app, listening to various events.
 *
 * Unit test:
   atest com.android.server.usage.AppStandbyControllerTests
 */
public class AppStandbyController implements AppStandbyInternal {

    private static final String TAG = "AppStandbyController";
    // Do not submit with true.
    static final boolean DEBUG = false;

    static final boolean COMPRESS_TIME = false;
    private static final long ONE_MINUTE = 60 * 1000;
    private static final long ONE_HOUR = ONE_MINUTE * 60;
    private static final long ONE_DAY = ONE_HOUR * 24;

    /**
     * The minimum amount of time the screen must have been on before an app can time out from its
     * current bucket to the next bucket.
     */
    private static final long[] SCREEN_TIME_THRESHOLDS = {
            0,
            0,
            COMPRESS_TIME ? 2 * ONE_MINUTE : 1 * ONE_HOUR,
            COMPRESS_TIME ? 4 * ONE_MINUTE : 2 * ONE_HOUR,
            COMPRESS_TIME ? 8 * ONE_MINUTE : 6 * ONE_HOUR
    };

    /** The minimum allowed values for each index in {@link #SCREEN_TIME_THRESHOLDS}. */
    private static final long[] MINIMUM_SCREEN_TIME_THRESHOLDS = COMPRESS_TIME
            ? new long[SCREEN_TIME_THRESHOLDS.length]
            : new long[]{
                    0,
                    0,
                    0,
                    30 * ONE_MINUTE,
                    ONE_HOUR
            };

    /**
     * The minimum amount of elapsed time that must have passed before an app can time out from its
     * current bucket to the next bucket.
     */
    private static final long[] ELAPSED_TIME_THRESHOLDS = {
            0,
            COMPRESS_TIME ?  1 * ONE_MINUTE : 12 * ONE_HOUR,
            COMPRESS_TIME ?  4 * ONE_MINUTE : 24 * ONE_HOUR,
            COMPRESS_TIME ? 16 * ONE_MINUTE : 48 * ONE_HOUR,
            COMPRESS_TIME ? 32 * ONE_MINUTE : 30 * ONE_DAY
    };

    /** The minimum allowed values for each index in {@link #ELAPSED_TIME_THRESHOLDS}. */
    private static final long[] MINIMUM_ELAPSED_TIME_THRESHOLDS = COMPRESS_TIME
            ? new long[ELAPSED_TIME_THRESHOLDS.length]
            : new long[]{
                    0,
                    ONE_HOUR,
                    ONE_HOUR,
                    2 * ONE_HOUR,
                    4 * ONE_DAY
            };

    private static final int[] THRESHOLD_BUCKETS = {
            STANDBY_BUCKET_ACTIVE,
            STANDBY_BUCKET_WORKING_SET,
            STANDBY_BUCKET_FREQUENT,
            STANDBY_BUCKET_RARE,
            STANDBY_BUCKET_RESTRICTED
    };

    /** Default expiration time for bucket prediction. After this, use thresholds to downgrade. */
    private static final long DEFAULT_PREDICTION_TIMEOUT = 12 * ONE_HOUR;

    /**
     * Indicates the maximum wait time for admin data to be available;
     */
    private static final long WAIT_FOR_ADMIN_DATA_TIMEOUT_MS = 10_000;

    private static final int HEADLESS_APP_CHECK_FLAGS =
            PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                    | PackageManager.GET_ACTIVITIES | PackageManager.MATCH_DISABLED_COMPONENTS;

    // To name the lock for stack traces
    static class Lock {}

    /** Lock to protect the app's standby state. Required for calls into AppIdleHistory */
    private final Object mAppIdleLock = new Lock();

    /** Keeps the history and state for each app. */
    @GuardedBy("mAppIdleLock")
    private AppIdleHistory mAppIdleHistory;

    @GuardedBy("mPackageAccessListeners")
    private final ArrayList<AppIdleStateChangeListener> mPackageAccessListeners = new ArrayList<>();

    /** Whether we've queried the list of carrier privileged apps. */
    @GuardedBy("mAppIdleLock")
    private boolean mHaveCarrierPrivilegedApps;

    /** List of carrier-privileged apps that should be excluded from standby */
    @GuardedBy("mAppIdleLock")
    private List<String> mCarrierPrivilegedApps;

    @GuardedBy("mActiveAdminApps")
    private final SparseArray<Set<String>> mActiveAdminApps = new SparseArray<>();

    /**
     * Set of system apps that are headless (don't have any declared activities, enabled or
     * disabled). Presence in this map indicates that the app is a headless system app.
     */
    @GuardedBy("mHeadlessSystemApps")
    private final ArraySet<String> mHeadlessSystemApps = new ArraySet<>();

    private final CountDownLatch mAdminDataAvailableLatch = new CountDownLatch(1);

    // Cache the active network scorer queried from the network scorer service
    private volatile String mCachedNetworkScorer = null;
    // The last time the network scorer service was queried
    private volatile long mCachedNetworkScorerAtMillis = 0L;
    // How long before querying the network scorer again. During this time, subsequent queries will
    // get the cached value
    private static final long NETWORK_SCORER_CACHE_DURATION_MILLIS = 5000L;

    // Messages for the handler
    static final int MSG_INFORM_LISTENERS = 3;
    static final int MSG_FORCE_IDLE_STATE = 4;
    static final int MSG_CHECK_IDLE_STATES = 5;
    static final int MSG_REPORT_CONTENT_PROVIDER_USAGE = 8;
    static final int MSG_PAROLE_STATE_CHANGED = 9;
    static final int MSG_ONE_TIME_CHECK_IDLE_STATES = 10;
    /** Check the state of one app: arg1 = userId, arg2 = uid, obj = (String) packageName */
    static final int MSG_CHECK_PACKAGE_IDLE_STATE = 11;
    static final int MSG_REPORT_SYNC_SCHEDULED = 12;
    static final int MSG_REPORT_EXEMPTED_SYNC_START = 13;

    long mCheckIdleIntervalMillis;
    /**
     * The minimum amount of time the screen must have been on before an app can time out from its
     * current bucket to the next bucket.
     */
    long[] mAppStandbyScreenThresholds = SCREEN_TIME_THRESHOLDS;
    /**
     * The minimum amount of elapsed time that must have passed before an app can time out from its
     * current bucket to the next bucket.
     */
    long[] mAppStandbyElapsedThresholds = ELAPSED_TIME_THRESHOLDS;
    /** Minimum time a strong usage event should keep the bucket elevated. */
    long mStrongUsageTimeoutMillis;
    /** Minimum time a notification seen event should keep the bucket elevated. */
    long mNotificationSeenTimeoutMillis;
    /** Minimum time a system update event should keep the buckets elevated. */
    long mSystemUpdateUsageTimeoutMillis;
    /** Maximum time to wait for a prediction before using simple timeouts to downgrade buckets. */
    long mPredictionTimeoutMillis;
    /** Maximum time a sync adapter associated with a CP should keep the buckets elevated. */
    long mSyncAdapterTimeoutMillis;
    /**
     * Maximum time an exempted sync should keep the buckets elevated, when sync is scheduled in
     * non-doze
     */
    long mExemptedSyncScheduledNonDozeTimeoutMillis;
    /**
     * Maximum time an exempted sync should keep the buckets elevated, when sync is scheduled in
     * doze
     */
    long mExemptedSyncScheduledDozeTimeoutMillis;
    /**
     * Maximum time an exempted sync should keep the buckets elevated, when sync is started.
     */
    long mExemptedSyncStartTimeoutMillis;
    /**
     * Maximum time an unexempted sync should keep the buckets elevated, when sync is scheduled
     */
    long mUnexemptedSyncScheduledTimeoutMillis;
    /** Maximum time a system interaction should keep the buckets elevated. */
    long mSystemInteractionTimeoutMillis;
    /**
     * Maximum time a foreground service start should keep the buckets elevated if the service
     * start is the first usage of the app
     */
    long mInitialForegroundServiceStartTimeoutMillis;
    /**
     * User usage that would elevate an app's standby bucket will also elevate the standby bucket of
     * cross profile connected apps. Explicit standby bucket setting via
     * {@link #setAppStandbyBucket(String, int, int, int, int)} will not be propagated.
     */
    boolean mLinkCrossProfileApps;
    /**
     * Whether we should allow apps into the
     * {@link android.app.usage.UsageStatsManager#STANDBY_BUCKET_RESTRICTED} bucket or not.
     * If false, any attempts to put an app into the bucket will put the app into the
     * {@link android.app.usage.UsageStatsManager#STANDBY_BUCKET_RARE} bucket instead.
     */
    private boolean mAllowRestrictedBucket;

    private volatile boolean mAppIdleEnabled;
    private boolean mIsCharging;
    private boolean mSystemServicesReady = false;
    // There was a system update, defaults need to be initialized after services are ready
    private boolean mPendingInitializeDefaults;

    private volatile boolean mPendingOneTimeCheckIdleStates;

    private final AppStandbyHandler mHandler;
    private final Context mContext;

    private AppWidgetManager mAppWidgetManager;
    private PackageManager mPackageManager;
    Injector mInjector;

    private static class Pool<T> {
        private final T[] mArray;
        private int mSize = 0;

        Pool(T[] array) {
            mArray = array;
        }

        @Nullable
        synchronized T obtain() {
            return mSize > 0 ? mArray[--mSize] : null;
        }

        synchronized void recycle(T instance) {
            if (mSize < mArray.length) {
                mArray[mSize++] = instance;
            }
        }
    }

    private static class StandbyUpdateRecord {
        private static final Pool<StandbyUpdateRecord> sPool =
                new Pool<>(new StandbyUpdateRecord[10]);

        // Identity of the app whose standby state has changed
        String packageName;
        int userId;

        // What the standby bucket the app is now in
        int bucket;

        // Whether the bucket change is because the user has started interacting with the app
        boolean isUserInteraction;

        // Reason for bucket change
        int reason;

        public static StandbyUpdateRecord obtain(String pkgName, int userId,
                int bucket, int reason, boolean isInteraction) {
            StandbyUpdateRecord r = sPool.obtain();
            if (r == null) {
                r = new StandbyUpdateRecord();
            }
            r.packageName = pkgName;
            r.userId = userId;
            r.bucket = bucket;
            r.reason = reason;
            r.isUserInteraction = isInteraction;
            return r;

        }

        public void recycle() {
            sPool.recycle(this);
        }
    }

    private static class ContentProviderUsageRecord {
        private static final Pool<ContentProviderUsageRecord> sPool =
                new Pool<>(new ContentProviderUsageRecord[10]);

        public String name;
        public String packageName;
        public int userId;

        public static ContentProviderUsageRecord obtain(String name, String packageName,
                int userId) {
            ContentProviderUsageRecord r = sPool.obtain();
            if (r == null) {
                r = new ContentProviderUsageRecord();
            }
            r.name = name;
            r.packageName = packageName;
            r.userId = userId;
            return r;
        }

        public void recycle() {
            sPool.recycle(this);
        }
    }

    public AppStandbyController(Context context, Looper looper) {
        this(new Injector(context, looper));
    }

    AppStandbyController(Injector injector) {
        mInjector = injector;
        mContext = mInjector.getContext();
        mHandler = new AppStandbyHandler(mInjector.getLooper());
        mPackageManager = mContext.getPackageManager();

        DeviceStateReceiver deviceStateReceiver = new DeviceStateReceiver();
        IntentFilter deviceStates = new IntentFilter(BatteryManager.ACTION_CHARGING);
        deviceStates.addAction(BatteryManager.ACTION_DISCHARGING);
        deviceStates.addAction(PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED);
        mContext.registerReceiver(deviceStateReceiver, deviceStates);

        synchronized (mAppIdleLock) {
            mAppIdleHistory = new AppIdleHistory(mInjector.getDataSystemDirectory(),
                    mInjector.elapsedRealtime());
        }

        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addDataScheme("package");

        mContext.registerReceiverAsUser(new PackageReceiver(), UserHandle.ALL, packageFilter,
                null, mHandler);
    }

    @VisibleForTesting
    void setAppIdleEnabled(boolean enabled) {
        synchronized (mAppIdleLock) {
            if (mAppIdleEnabled != enabled) {
                final boolean oldParoleState = isInParole();
                mAppIdleEnabled = enabled;
                if (isInParole() != oldParoleState) {
                    postParoleStateChanged();
                }
            }
        }
    }

    @Override
    public boolean isAppIdleEnabled() {
        return mAppIdleEnabled;
    }

    @Override
    public void onBootPhase(int phase) {
        mInjector.onBootPhase(phase);
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            Slog.d(TAG, "Setting app idle enabled state");
            // Observe changes to the threshold
            SettingsObserver settingsObserver = new SettingsObserver(mHandler);
            settingsObserver.registerObserver();
            settingsObserver.updateSettings();

            mAppWidgetManager = mContext.getSystemService(AppWidgetManager.class);

            mInjector.registerDisplayListener(mDisplayListener, mHandler);
            synchronized (mAppIdleLock) {
                mAppIdleHistory.updateDisplay(isDisplayOn(), mInjector.elapsedRealtime());
            }

            mSystemServicesReady = true;

            // Offload to handler thread to avoid boot time impact.
            mHandler.post(mInjector::updatePowerWhitelistCache);

            boolean userFileExists;
            synchronized (mAppIdleLock) {
                userFileExists = mAppIdleHistory.userFileExists(UserHandle.USER_SYSTEM);
            }

            if (mPendingInitializeDefaults || !userFileExists) {
                initializeDefaultsForSystemApps(UserHandle.USER_SYSTEM);
            }

            if (mPendingOneTimeCheckIdleStates) {
                postOneTimeCheckIdleStates();
            }
        } else if (phase == PHASE_BOOT_COMPLETED) {
            setChargingState(mInjector.isCharging());

            // Offload to handler thread after boot completed to avoid boot time impact. This means
            // that headless system apps may be put in a lower bucket until boot has completed.
            mHandler.post(this::loadHeadlessSystemAppCache);
        }
    }

    private void reportContentProviderUsage(String authority, String providerPkgName, int userId) {
        if (!mAppIdleEnabled) return;

        // Get sync adapters for the authority
        String[] packages = ContentResolver.getSyncAdapterPackagesForAuthorityAsUser(
                authority, userId);
        final long elapsedRealtime = mInjector.elapsedRealtime();
        for (String packageName: packages) {
            // Only force the sync adapters to active if the provider is not in the same package and
            // the sync adapter is a system package.
            try {
                PackageInfo pi = mPackageManager.getPackageInfoAsUser(
                        packageName, PackageManager.MATCH_SYSTEM_ONLY, userId);
                if (pi == null || pi.applicationInfo == null) {
                    continue;
                }
                if (!packageName.equals(providerPkgName)) {
                    final List<UserHandle> linkedProfiles = getCrossProfileTargets(packageName,
                            userId);
                    synchronized (mAppIdleLock) {
                        reportNoninteractiveUsageCrossUserLocked(packageName, userId,
                                STANDBY_BUCKET_ACTIVE, REASON_SUB_USAGE_SYNC_ADAPTER,
                                elapsedRealtime, mSyncAdapterTimeoutMillis, linkedProfiles);
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Shouldn't happen
            }
        }
    }

    private void reportExemptedSyncScheduled(String packageName, int userId) {
        if (!mAppIdleEnabled) return;

        final int bucketToPromote;
        final int usageReason;
        final long durationMillis;

        if (!mInjector.isDeviceIdleMode()) {
            // Not dozing.
            bucketToPromote = STANDBY_BUCKET_ACTIVE;
            usageReason = REASON_SUB_USAGE_EXEMPTED_SYNC_SCHEDULED_NON_DOZE;
            durationMillis = mExemptedSyncScheduledNonDozeTimeoutMillis;
        } else {
            // Dozing.
            bucketToPromote = STANDBY_BUCKET_WORKING_SET;
            usageReason = REASON_SUB_USAGE_EXEMPTED_SYNC_SCHEDULED_DOZE;
            durationMillis = mExemptedSyncScheduledDozeTimeoutMillis;
        }

        final long elapsedRealtime = mInjector.elapsedRealtime();
        final List<UserHandle> linkedProfiles = getCrossProfileTargets(packageName, userId);
        synchronized (mAppIdleLock) {
            reportNoninteractiveUsageCrossUserLocked(packageName, userId, bucketToPromote,
                    usageReason, elapsedRealtime, durationMillis, linkedProfiles);
        }
    }

    private void reportUnexemptedSyncScheduled(String packageName, int userId) {
        if (!mAppIdleEnabled) return;

        final long elapsedRealtime = mInjector.elapsedRealtime();
        synchronized (mAppIdleLock) {
            final int currentBucket =
                    mAppIdleHistory.getAppStandbyBucket(packageName, userId, elapsedRealtime);
            if (currentBucket == STANDBY_BUCKET_NEVER) {
                final List<UserHandle> linkedProfiles = getCrossProfileTargets(packageName, userId);
                // Bring the app out of the never bucket
                reportNoninteractiveUsageCrossUserLocked(packageName, userId,
                        STANDBY_BUCKET_WORKING_SET, REASON_SUB_USAGE_UNEXEMPTED_SYNC_SCHEDULED,
                        elapsedRealtime, mUnexemptedSyncScheduledTimeoutMillis, linkedProfiles);
            }
        }
    }

    private void reportExemptedSyncStart(String packageName, int userId) {
        if (!mAppIdleEnabled) return;

        final long elapsedRealtime = mInjector.elapsedRealtime();
        final List<UserHandle> linkedProfiles = getCrossProfileTargets(packageName, userId);
        synchronized (mAppIdleLock) {
            reportNoninteractiveUsageCrossUserLocked(packageName, userId, STANDBY_BUCKET_ACTIVE,
                    REASON_SUB_USAGE_EXEMPTED_SYNC_START, elapsedRealtime,
                    mExemptedSyncStartTimeoutMillis, linkedProfiles);
        }
    }

    /**
     * Helper method to report indirect user usage of an app and handle reporting the usage
     * against cross profile connected apps. <br>
     * Use {@link #reportNoninteractiveUsageLocked(String, int, int, int, long, long)} if
     * cross profile connected apps do not need to be handled.
     */
    private void reportNoninteractiveUsageCrossUserLocked(String packageName, int userId,
            int bucket, int subReason, long elapsedRealtime, long nextCheckDelay,
            List<UserHandle> otherProfiles) {
        reportNoninteractiveUsageLocked(packageName, userId, bucket, subReason, elapsedRealtime,
                nextCheckDelay);
        final int size = otherProfiles.size();
        for (int profileIndex = 0; profileIndex < size; profileIndex++) {
            final int otherUserId = otherProfiles.get(profileIndex).getIdentifier();
            reportNoninteractiveUsageLocked(packageName, otherUserId, bucket, subReason,
                    elapsedRealtime, nextCheckDelay);
        }
    }

    /**
     * Helper method to report indirect user usage of an app. <br>
     * Use
     * {@link #reportNoninteractiveUsageCrossUserLocked(String, int, int, int, long, long, List)}
     * if cross profile connected apps need to be handled.
     */
    private void reportNoninteractiveUsageLocked(String packageName, int userId, int bucket,
            int subReason, long elapsedRealtime, long nextCheckDelay) {
        final AppUsageHistory appUsage = mAppIdleHistory.reportUsage(packageName, userId, bucket,
                subReason, 0, elapsedRealtime + nextCheckDelay);
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(MSG_CHECK_PACKAGE_IDLE_STATE, userId, -1, packageName),
                nextCheckDelay);
        maybeInformListeners(packageName, userId, elapsedRealtime, appUsage.currentBucket,
                appUsage.bucketingReason, false);
    }

    @VisibleForTesting
    void setChargingState(boolean isCharging) {
        synchronized (mAppIdleLock) {
            if (mIsCharging != isCharging) {
                if (DEBUG) Slog.d(TAG, "Setting mIsCharging to " + isCharging);
                mIsCharging = isCharging;
                postParoleStateChanged();
            }
        }
    }

    @Override
    public boolean isInParole() {
        return !mAppIdleEnabled || mIsCharging;
    }

    private void postParoleStateChanged() {
        if (DEBUG) Slog.d(TAG, "Posting MSG_PAROLE_STATE_CHANGED");
        mHandler.removeMessages(MSG_PAROLE_STATE_CHANGED);
        mHandler.sendEmptyMessage(MSG_PAROLE_STATE_CHANGED);
    }

    @Override
    public void postCheckIdleStates(int userId) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_CHECK_IDLE_STATES, userId, 0));
    }

    @Override
    public void postOneTimeCheckIdleStates() {
        if (mInjector.getBootPhase() < PHASE_SYSTEM_SERVICES_READY) {
            // Not booted yet; wait for it!
            mPendingOneTimeCheckIdleStates = true;
        } else {
            mHandler.sendEmptyMessage(MSG_ONE_TIME_CHECK_IDLE_STATES);
            mPendingOneTimeCheckIdleStates = false;
        }
    }

    @VisibleForTesting
    boolean checkIdleStates(int checkUserId) {
        if (!mAppIdleEnabled) {
            return false;
        }

        final int[] runningUserIds;
        try {
            runningUserIds = mInjector.getRunningUserIds();
            if (checkUserId != UserHandle.USER_ALL
                    && !ArrayUtils.contains(runningUserIds, checkUserId)) {
                return false;
            }
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }

        final long elapsedRealtime = mInjector.elapsedRealtime();
        for (int i = 0; i < runningUserIds.length; i++) {
            final int userId = runningUserIds[i];
            if (checkUserId != UserHandle.USER_ALL && checkUserId != userId) {
                continue;
            }
            if (DEBUG) {
                Slog.d(TAG, "Checking idle state for user " + userId);
            }
            List<PackageInfo> packages = mPackageManager.getInstalledPackagesAsUser(
                    PackageManager.MATCH_DISABLED_COMPONENTS,
                    userId);
            final int packageCount = packages.size();
            for (int p = 0; p < packageCount; p++) {
                final PackageInfo pi = packages.get(p);
                final String packageName = pi.packageName;
                checkAndUpdateStandbyState(packageName, userId, pi.applicationInfo.uid,
                        elapsedRealtime);
            }
        }
        if (DEBUG) {
            Slog.d(TAG, "checkIdleStates took "
                    + (mInjector.elapsedRealtime() - elapsedRealtime));
        }
        return true;
    }

    /** Check if we need to update the standby state of a specific app. */
    private void checkAndUpdateStandbyState(String packageName, @UserIdInt int userId,
            int uid, long elapsedRealtime) {
        if (uid <= 0) {
            try {
                uid = mPackageManager.getPackageUidAsUser(packageName, userId);
            } catch (PackageManager.NameNotFoundException e) {
                // Not a valid package for this user, nothing to do
                // TODO: Remove any history of removed packages
                return;
            }
        }
        final int minBucket = getAppMinBucket(packageName,
                UserHandle.getAppId(uid),
                userId);
        if (DEBUG) {
            Slog.d(TAG, "   Checking idle state for " + packageName
                    + " minBucket=" + minBucket);
        }
        if (minBucket <= STANDBY_BUCKET_ACTIVE) {
            // No extra processing needed for ACTIVE or higher since apps can't drop into lower
            // buckets.
            synchronized (mAppIdleLock) {
                mAppIdleHistory.setAppStandbyBucket(packageName, userId, elapsedRealtime,
                        minBucket, REASON_MAIN_DEFAULT);
            }
            maybeInformListeners(packageName, userId, elapsedRealtime,
                    minBucket, REASON_MAIN_DEFAULT, false);
        } else {
            synchronized (mAppIdleLock) {
                final AppIdleHistory.AppUsageHistory app =
                        mAppIdleHistory.getAppUsageHistory(packageName,
                        userId, elapsedRealtime);
                int reason = app.bucketingReason;
                final int oldMainReason = reason & REASON_MAIN_MASK;

                // If the bucket was forced by the user/developer, leave it alone.
                // A usage event will be the only way to bring it out of this forced state
                if (oldMainReason == REASON_MAIN_FORCED_BY_USER) {
                    return;
                }
                final int oldBucket = app.currentBucket;
                if (oldBucket == STANDBY_BUCKET_NEVER) {
                    // None of this should bring an app out of the NEVER bucket.
                    return;
                }
                int newBucket = Math.max(oldBucket, STANDBY_BUCKET_ACTIVE); // Undo EXEMPTED
                boolean predictionLate = predictionTimedOut(app, elapsedRealtime);
                // Compute age-based bucket
                if (oldMainReason == REASON_MAIN_DEFAULT
                        || oldMainReason == REASON_MAIN_USAGE
                        || oldMainReason == REASON_MAIN_TIMEOUT
                        || predictionLate) {

                    if (!predictionLate && app.lastPredictedBucket >= STANDBY_BUCKET_ACTIVE
                            && app.lastPredictedBucket <= STANDBY_BUCKET_RARE) {
                        newBucket = app.lastPredictedBucket;
                        reason = REASON_MAIN_PREDICTED | REASON_SUB_PREDICTED_RESTORED;
                        if (DEBUG) {
                            Slog.d(TAG, "Restored predicted newBucket = " + newBucket);
                        }
                    } else {
                        newBucket = getBucketForLocked(packageName, userId,
                                elapsedRealtime);
                        if (DEBUG) {
                            Slog.d(TAG, "Evaluated AOSP newBucket = " + newBucket);
                        }
                        reason = REASON_MAIN_TIMEOUT;
                    }
                }

                // Check if the app is within one of the timeouts for forced bucket elevation
                final long elapsedTimeAdjusted = mAppIdleHistory.getElapsedTime(elapsedRealtime);
                if (newBucket >= STANDBY_BUCKET_ACTIVE
                        && app.bucketActiveTimeoutTime > elapsedTimeAdjusted) {
                    newBucket = STANDBY_BUCKET_ACTIVE;
                    reason = app.bucketingReason;
                    if (DEBUG) {
                        Slog.d(TAG, "    Keeping at ACTIVE due to min timeout");
                    }
                } else if (newBucket >= STANDBY_BUCKET_WORKING_SET
                        && app.bucketWorkingSetTimeoutTime > elapsedTimeAdjusted) {
                    newBucket = STANDBY_BUCKET_WORKING_SET;
                    // If it was already there, keep the reason, else assume timeout to WS
                    reason = (newBucket == oldBucket)
                            ? app.bucketingReason
                            : REASON_MAIN_USAGE | REASON_SUB_USAGE_ACTIVE_TIMEOUT;
                    if (DEBUG) {
                        Slog.d(TAG, "    Keeping at WORKING_SET due to min timeout");
                    }
                }

                if (app.lastRestrictAttemptElapsedTime > app.lastUsedByUserElapsedTime
                        && elapsedTimeAdjusted - app.lastUsedByUserElapsedTime
                        >= mInjector.getAutoRestrictedBucketDelayMs()) {
                    newBucket = STANDBY_BUCKET_RESTRICTED;
                    reason = app.lastRestrictReason;
                    if (DEBUG) {
                        Slog.d(TAG, "Bringing down to RESTRICTED due to timeout");
                    }
                }
                if (newBucket == STANDBY_BUCKET_RESTRICTED && !mAllowRestrictedBucket) {
                    newBucket = STANDBY_BUCKET_RARE;
                    // Leave the reason alone.
                    if (DEBUG) {
                        Slog.d(TAG, "Bringing up from RESTRICTED to RARE due to off switch");
                    }
                }
                if (newBucket > minBucket) {
                    newBucket = minBucket;
                    // Leave the reason alone.
                    if (DEBUG) {
                        Slog.d(TAG, "Bringing up from " + newBucket + " to " + minBucket
                                + " due to min bucketing");
                    }
                }
                if (DEBUG) {
                    Slog.d(TAG, "     Old bucket=" + oldBucket
                            + ", newBucket=" + newBucket);
                }
                if (oldBucket != newBucket || predictionLate) {
                    mAppIdleHistory.setAppStandbyBucket(packageName, userId,
                            elapsedRealtime, newBucket, reason);
                    maybeInformListeners(packageName, userId, elapsedRealtime,
                            newBucket, reason, false);
                }
            }
        }
    }

    /** Returns true if there hasn't been a prediction for the app in a while. */
    private boolean predictionTimedOut(AppIdleHistory.AppUsageHistory app, long elapsedRealtime) {
        return app.lastPredictedTime > 0
                && mAppIdleHistory.getElapsedTime(elapsedRealtime)
                    - app.lastPredictedTime > mPredictionTimeoutMillis;
    }

    /** Inform listeners if the bucket has changed since it was last reported to listeners */
    private void maybeInformListeners(String packageName, int userId,
            long elapsedRealtime, int bucket, int reason, boolean userStartedInteracting) {
        synchronized (mAppIdleLock) {
            if (mAppIdleHistory.shouldInformListeners(packageName, userId,
                    elapsedRealtime, bucket)) {
                final StandbyUpdateRecord r = StandbyUpdateRecord.obtain(packageName, userId,
                        bucket, reason, userStartedInteracting);
                if (DEBUG) Slog.d(TAG, "Standby bucket for " + packageName + "=" + bucket);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_INFORM_LISTENERS, r));
            }
        }
    }

    /**
     * Evaluates next bucket based on time since last used and the bucketing thresholds.
     * @param packageName the app
     * @param userId the user
     * @param elapsedRealtime as the name suggests, current elapsed time
     * @return the bucket for the app, based on time since last used
     */
    @GuardedBy("mAppIdleLock")
    @StandbyBuckets
    private int getBucketForLocked(String packageName, int userId,
            long elapsedRealtime) {
        int bucketIndex = mAppIdleHistory.getThresholdIndex(packageName, userId,
                elapsedRealtime, mAppStandbyScreenThresholds, mAppStandbyElapsedThresholds);
        return THRESHOLD_BUCKETS[bucketIndex];
    }

    private void notifyBatteryStats(String packageName, int userId, boolean idle) {
        try {
            final int uid = mPackageManager.getPackageUidAsUser(packageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
            if (idle) {
                mInjector.noteEvent(BatteryStats.HistoryItem.EVENT_PACKAGE_INACTIVE,
                        packageName, uid);
            } else {
                mInjector.noteEvent(BatteryStats.HistoryItem.EVENT_PACKAGE_ACTIVE,
                        packageName, uid);
            }
        } catch (PackageManager.NameNotFoundException | RemoteException e) {
        }
    }

    @Override
    public void reportEvent(UsageEvents.Event event, int userId) {
        if (!mAppIdleEnabled) return;
        final int eventType = event.getEventType();
        if ((eventType == UsageEvents.Event.ACTIVITY_RESUMED
                || eventType == UsageEvents.Event.ACTIVITY_PAUSED
                || eventType == UsageEvents.Event.SYSTEM_INTERACTION
                || eventType == UsageEvents.Event.USER_INTERACTION
                || eventType == UsageEvents.Event.NOTIFICATION_SEEN
                || eventType == UsageEvents.Event.SLICE_PINNED
                || eventType == UsageEvents.Event.SLICE_PINNED_PRIV
                || eventType == UsageEvents.Event.FOREGROUND_SERVICE_START)) {
            final String pkg = event.getPackageName();
            final List<UserHandle> linkedProfiles = getCrossProfileTargets(pkg, userId);
            synchronized (mAppIdleLock) {
                final long elapsedRealtime = mInjector.elapsedRealtime();
                reportEventLocked(pkg, eventType, elapsedRealtime, userId);

                final int size = linkedProfiles.size();
                for (int profileIndex = 0; profileIndex < size; profileIndex++) {
                    final int linkedUserId = linkedProfiles.get(profileIndex).getIdentifier();
                    reportEventLocked(pkg, eventType, elapsedRealtime, linkedUserId);
                }
            }
        }
    }

    private void reportEventLocked(String pkg, int eventType, long elapsedRealtime, int userId) {
        // TODO: Ideally this should call isAppIdleFiltered() to avoid calling back
        // about apps that are on some kind of whitelist anyway.
        final boolean previouslyIdle = mAppIdleHistory.isIdle(
                pkg, userId, elapsedRealtime);

        final AppUsageHistory appHistory = mAppIdleHistory.getAppUsageHistory(
                pkg, userId, elapsedRealtime);
        final int prevBucket = appHistory.currentBucket;
        final int prevBucketReason = appHistory.bucketingReason;
        final long nextCheckDelay;
        final int subReason = usageEventToSubReason(eventType);
        final int reason = REASON_MAIN_USAGE | subReason;
        if (eventType == UsageEvents.Event.NOTIFICATION_SEEN
                || eventType == UsageEvents.Event.SLICE_PINNED) {
            // Mild usage elevates to WORKING_SET but doesn't change usage time.
            mAppIdleHistory.reportUsage(appHistory, pkg, userId,
                    STANDBY_BUCKET_WORKING_SET, subReason,
                    0, elapsedRealtime + mNotificationSeenTimeoutMillis);
            nextCheckDelay = mNotificationSeenTimeoutMillis;
        } else if (eventType == UsageEvents.Event.SYSTEM_INTERACTION) {
            mAppIdleHistory.reportUsage(appHistory, pkg, userId,
                    STANDBY_BUCKET_ACTIVE, subReason,
                    0, elapsedRealtime + mSystemInteractionTimeoutMillis);
            nextCheckDelay = mSystemInteractionTimeoutMillis;
        } else if (eventType == UsageEvents.Event.FOREGROUND_SERVICE_START) {
            // Only elevate bucket if this is the first usage of the app
            if (prevBucket != STANDBY_BUCKET_NEVER) return;
            mAppIdleHistory.reportUsage(appHistory, pkg, userId,
                    STANDBY_BUCKET_ACTIVE, subReason,
                    0, elapsedRealtime + mInitialForegroundServiceStartTimeoutMillis);
            nextCheckDelay = mInitialForegroundServiceStartTimeoutMillis;
        } else {
            mAppIdleHistory.reportUsage(appHistory, pkg, userId,
                    STANDBY_BUCKET_ACTIVE, subReason,
                    elapsedRealtime, elapsedRealtime + mStrongUsageTimeoutMillis);
            nextCheckDelay = mStrongUsageTimeoutMillis;
        }
        if (appHistory.currentBucket != prevBucket) {
            mHandler.sendMessageDelayed(
                    mHandler.obtainMessage(MSG_CHECK_PACKAGE_IDLE_STATE, userId, -1, pkg),
                    nextCheckDelay);
            final boolean userStartedInteracting =
                    appHistory.currentBucket == STANDBY_BUCKET_ACTIVE
                            && (prevBucketReason & REASON_MAIN_MASK) != REASON_MAIN_USAGE;
            maybeInformListeners(pkg, userId, elapsedRealtime,
                    appHistory.currentBucket, reason, userStartedInteracting);
        }

        if (previouslyIdle) {
            notifyBatteryStats(pkg, userId, false);
        }
    }

    /**
     * Note: don't call this with the lock held since it makes calls to other system services.
     */
    private @NonNull List<UserHandle> getCrossProfileTargets(String pkg, int userId) {
        synchronized (mAppIdleLock) {
            if (!mLinkCrossProfileApps) return Collections.emptyList();
        }
        return mInjector.getValidCrossProfileTargets(pkg, userId);
    }

    private int usageEventToSubReason(int eventType) {
        switch (eventType) {
            case UsageEvents.Event.ACTIVITY_RESUMED: return REASON_SUB_USAGE_MOVE_TO_FOREGROUND;
            case UsageEvents.Event.ACTIVITY_PAUSED: return REASON_SUB_USAGE_MOVE_TO_BACKGROUND;
            case UsageEvents.Event.SYSTEM_INTERACTION: return REASON_SUB_USAGE_SYSTEM_INTERACTION;
            case UsageEvents.Event.USER_INTERACTION: return REASON_SUB_USAGE_USER_INTERACTION;
            case UsageEvents.Event.NOTIFICATION_SEEN: return REASON_SUB_USAGE_NOTIFICATION_SEEN;
            case UsageEvents.Event.SLICE_PINNED: return REASON_SUB_USAGE_SLICE_PINNED;
            case UsageEvents.Event.SLICE_PINNED_PRIV: return REASON_SUB_USAGE_SLICE_PINNED_PRIV;
            case UsageEvents.Event.FOREGROUND_SERVICE_START:
                return REASON_SUB_USAGE_FOREGROUND_SERVICE_START;
            default: return 0;
        }
    }

    @VisibleForTesting
    void forceIdleState(String packageName, int userId, boolean idle) {
        if (!mAppIdleEnabled) return;

        final int appId = getAppId(packageName);
        if (appId < 0) return;
        final long elapsedRealtime = mInjector.elapsedRealtime();

        final boolean previouslyIdle = isAppIdleFiltered(packageName, appId,
                userId, elapsedRealtime);
        final int standbyBucket;
        synchronized (mAppIdleLock) {
            standbyBucket = mAppIdleHistory.setIdle(packageName, userId, idle, elapsedRealtime);
        }
        final boolean stillIdle = isAppIdleFiltered(packageName, appId,
                userId, elapsedRealtime);
        // Inform listeners if necessary
        if (previouslyIdle != stillIdle) {
            maybeInformListeners(packageName, userId, elapsedRealtime, standbyBucket,
                    REASON_MAIN_FORCED_BY_USER, false);
            if (!stillIdle) {
                notifyBatteryStats(packageName, userId, idle);
            }
        }
    }

    @Override
    public void setLastJobRunTime(String packageName, int userId, long elapsedRealtime) {
        synchronized (mAppIdleLock) {
            mAppIdleHistory.setLastJobRunTime(packageName, userId, elapsedRealtime);
        }
    }

    @Override
    public long getTimeSinceLastJobRun(String packageName, int userId) {
        final long elapsedRealtime = mInjector.elapsedRealtime();
        synchronized (mAppIdleLock) {
            return mAppIdleHistory.getTimeSinceLastJobRun(packageName, userId, elapsedRealtime);
        }
    }

    @Override
    public void onUserRemoved(int userId) {
        synchronized (mAppIdleLock) {
            mAppIdleHistory.onUserRemoved(userId);
            synchronized (mActiveAdminApps) {
                mActiveAdminApps.remove(userId);
            }
        }
    }

    private boolean isAppIdleUnfiltered(String packageName, int userId, long elapsedRealtime) {
        synchronized (mAppIdleLock) {
            return mAppIdleHistory.isIdle(packageName, userId, elapsedRealtime);
        }
    }

    @Override
    public void addListener(AppIdleStateChangeListener listener) {
        synchronized (mPackageAccessListeners) {
            if (!mPackageAccessListeners.contains(listener)) {
                mPackageAccessListeners.add(listener);
            }
        }
    }

    @Override
    public void removeListener(AppIdleStateChangeListener listener) {
        synchronized (mPackageAccessListeners) {
            mPackageAccessListeners.remove(listener);
        }
    }

    @Override
    public int getAppId(String packageName) {
        try {
            ApplicationInfo ai = mPackageManager.getApplicationInfo(packageName,
                    PackageManager.MATCH_ANY_USER
                            | PackageManager.MATCH_DISABLED_COMPONENTS);
            return ai.uid;
        } catch (PackageManager.NameNotFoundException re) {
            return -1;
        }
    }

    @Override
    public boolean isAppIdleFiltered(String packageName, int userId, long elapsedRealtime,
            boolean shouldObfuscateInstantApps) {
        if (shouldObfuscateInstantApps &&
                mInjector.isPackageEphemeral(userId, packageName)) {
            return false;
        }
        return isAppIdleFiltered(packageName, getAppId(packageName), userId, elapsedRealtime);
    }

    @StandbyBuckets
    private int getAppMinBucket(String packageName, int userId) {
        try {
            final int uid = mPackageManager.getPackageUidAsUser(packageName, userId);
            return getAppMinBucket(packageName, UserHandle.getAppId(uid), userId);
        } catch (PackageManager.NameNotFoundException e) {
            // Not a valid package for this user, nothing to do
            return STANDBY_BUCKET_NEVER;
        }
    }

    /**
     * Return the lowest bucket this app should ever enter.
     */
    @StandbyBuckets
    private int getAppMinBucket(String packageName, int appId, int userId) {
        if (packageName == null) return STANDBY_BUCKET_NEVER;
        // If not enabled at all, of course nobody is ever idle.
        if (!mAppIdleEnabled) {
            return STANDBY_BUCKET_EXEMPTED;
        }
        if (appId < Process.FIRST_APPLICATION_UID) {
            // System uids never go idle.
            return STANDBY_BUCKET_EXEMPTED;
        }
        if (packageName.equals("android")) {
            // Nor does the framework (which should be redundant with the above, but for MR1 we will
            // retain this for safety).
            return STANDBY_BUCKET_EXEMPTED;
        }
        if (mSystemServicesReady) {
            // We allow all whitelisted apps, including those that don't want to be whitelisted
            // for idle mode, because app idle (aka app standby) is really not as big an issue
            // for controlling who participates vs. doze mode.
            if (mInjector.isNonIdleWhitelisted(packageName)) {
                return STANDBY_BUCKET_EXEMPTED;
            }

            if (isActiveDeviceAdmin(packageName, userId)) {
                return STANDBY_BUCKET_EXEMPTED;
            }

            if (isActiveNetworkScorer(packageName)) {
                return STANDBY_BUCKET_EXEMPTED;
            }

            if (mAppWidgetManager != null
                    && mInjector.isBoundWidgetPackage(mAppWidgetManager, packageName, userId)) {
                return STANDBY_BUCKET_ACTIVE;
            }

            if (isDeviceProvisioningPackage(packageName)) {
                return STANDBY_BUCKET_EXEMPTED;
            }
        }

        // Check this last, as it can be the most expensive check
        if (isCarrierApp(packageName)) {
            return STANDBY_BUCKET_EXEMPTED;
        }

        if (isHeadlessSystemApp(packageName)) {
            return STANDBY_BUCKET_ACTIVE;
        }

        return STANDBY_BUCKET_NEVER;
    }

    private boolean isHeadlessSystemApp(String packageName) {
        synchronized (mHeadlessSystemApps) {
            return mHeadlessSystemApps.contains(packageName);
        }
    }

    @Override
    public boolean isAppIdleFiltered(String packageName, int appId, int userId,
            long elapsedRealtime) {
        if (getAppMinBucket(packageName, appId, userId) < AppIdleHistory.IDLE_BUCKET_CUTOFF) {
            return false;
        } else {
            synchronized (mAppIdleLock) {
                if (!mAppIdleEnabled || mIsCharging) {
                    return false;
                }
            }
            return isAppIdleUnfiltered(packageName, userId, elapsedRealtime);
        }
    }

    static boolean isUserUsage(int reason) {
        if ((reason & REASON_MAIN_MASK) == REASON_MAIN_USAGE) {
            final int subReason = reason & REASON_SUB_MASK;
            return subReason == REASON_SUB_USAGE_USER_INTERACTION
                    || subReason == REASON_SUB_USAGE_MOVE_TO_FOREGROUND;
        }
        return false;
    }

    @Override
    public int[] getIdleUidsForUser(int userId) {
        if (!mAppIdleEnabled) {
            return new int[0];
        }

        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "getIdleUidsForUser");

        final long elapsedRealtime = mInjector.elapsedRealtime();

        List<ApplicationInfo> apps;
        try {
            ParceledListSlice<ApplicationInfo> slice = AppGlobals.getPackageManager()
                    .getInstalledApplications(/* flags= */ 0, userId);
            if (slice == null) {
                return new int[0];
            }
            apps = slice.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        // State of each uid.  Key is the uid.  Value lower 16 bits is the number of apps
        // associated with that uid, upper 16 bits is the number of those apps that is idle.
        SparseIntArray uidStates = new SparseIntArray();

        // Now resolve all app state.  Iterating over all apps, keeping track of how many
        // we find for each uid and how many of those are idle.
        for (int i = apps.size() - 1; i >= 0; i--) {
            ApplicationInfo ai = apps.get(i);

            // Check whether this app is idle.
            boolean idle = isAppIdleFiltered(ai.packageName, UserHandle.getAppId(ai.uid),
                    userId, elapsedRealtime);

            int index = uidStates.indexOfKey(ai.uid);
            if (index < 0) {
                uidStates.put(ai.uid, 1 + (idle ? 1<<16 : 0));
            } else {
                int value = uidStates.valueAt(index);
                uidStates.setValueAt(index, value + 1 + (idle ? 1<<16 : 0));
            }
        }

        if (DEBUG) {
            Slog.d(TAG, "getIdleUids took " + (mInjector.elapsedRealtime() - elapsedRealtime));
        }
        int numIdle = 0;
        for (int i = uidStates.size() - 1; i >= 0; i--) {
            int value = uidStates.valueAt(i);
            if ((value&0x7fff) == (value>>16)) {
                numIdle++;
            }
        }

        int[] res = new int[numIdle];
        numIdle = 0;
        for (int i = uidStates.size() - 1; i >= 0; i--) {
            int value = uidStates.valueAt(i);
            if ((value&0x7fff) == (value>>16)) {
                res[numIdle] = uidStates.keyAt(i);
                numIdle++;
            }
        }

        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);

        return res;
    }

    @Override
    public void setAppIdleAsync(String packageName, boolean idle, int userId) {
        if (packageName == null || !mAppIdleEnabled) return;

        mHandler.obtainMessage(MSG_FORCE_IDLE_STATE, userId, idle ? 1 : 0, packageName)
                .sendToTarget();
    }

    @Override
    @StandbyBuckets public int getAppStandbyBucket(String packageName, int userId,
            long elapsedRealtime, boolean shouldObfuscateInstantApps) {
        if (!mAppIdleEnabled || (shouldObfuscateInstantApps
                && mInjector.isPackageEphemeral(userId, packageName))) {
            return STANDBY_BUCKET_ACTIVE;
        }

        synchronized (mAppIdleLock) {
            return mAppIdleHistory.getAppStandbyBucket(packageName, userId, elapsedRealtime);
        }
    }

    @VisibleForTesting
    int getAppStandbyBucketReason(String packageName, int userId, long elapsedRealtime) {
        synchronized (mAppIdleLock) {
            return mAppIdleHistory.getAppStandbyReason(packageName, userId, elapsedRealtime);
        }
    }

    @Override
    public List<AppStandbyInfo> getAppStandbyBuckets(int userId) {
        synchronized (mAppIdleLock) {
            return mAppIdleHistory.getAppStandbyBuckets(userId, mAppIdleEnabled);
        }
    }

    @Override
    public void restrictApp(@NonNull String packageName, int userId,
            @SystemForcedReasons int restrictReason) {
        // If the package is not installed, don't allow the bucket to be set.
        if (!mInjector.isPackageInstalled(packageName, 0, userId)) {
            Slog.e(TAG, "Tried to restrict uninstalled app: " + packageName);
            return;
        }

        final int reason = REASON_MAIN_FORCED_BY_SYSTEM | (REASON_SUB_MASK & restrictReason);
        final long nowElapsed = mInjector.elapsedRealtime();
        final int bucket = mAllowRestrictedBucket ? STANDBY_BUCKET_RESTRICTED : STANDBY_BUCKET_RARE;
        setAppStandbyBucket(packageName, userId, bucket, reason, nowElapsed, false);
    }

    @Override
    public void setAppStandbyBucket(@NonNull String packageName, int bucket, int userId,
            int callingUid, int callingPid) {
        setAppStandbyBuckets(
                Collections.singletonList(new AppStandbyInfo(packageName, bucket)),
                userId, callingUid, callingPid);
    }

    @Override
    public void setAppStandbyBuckets(@NonNull List<AppStandbyInfo> appBuckets, int userId,
            int callingUid, int callingPid) {
        userId = ActivityManager.handleIncomingUser(
                callingPid, callingUid, userId, false, true, "setAppStandbyBucket", null);
        final boolean shellCaller = callingUid == Process.ROOT_UID
                || callingUid == Process.SHELL_UID;
        final int reason;
        // The Settings app runs in the system UID but in a separate process. Assume
        // things coming from other processes are due to the user.
        if ((UserHandle.isSameApp(callingUid, Process.SYSTEM_UID) && callingPid != Process.myPid())
                || shellCaller) {
            reason = REASON_MAIN_FORCED_BY_USER;
        } else if (UserHandle.isCore(callingUid)) {
            reason = REASON_MAIN_FORCED_BY_SYSTEM;
        } else {
            reason = REASON_MAIN_PREDICTED;
        }
        final int packageFlags = PackageManager.MATCH_ANY_USER
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                | PackageManager.MATCH_DIRECT_BOOT_AWARE;
        final int numApps = appBuckets.size();
        final long elapsedRealtime = mInjector.elapsedRealtime();
        for (int i = 0; i < numApps; ++i) {
            final AppStandbyInfo bucketInfo = appBuckets.get(i);
            final String packageName = bucketInfo.mPackageName;
            final int bucket = bucketInfo.mStandbyBucket;
            if (bucket < STANDBY_BUCKET_ACTIVE || bucket > STANDBY_BUCKET_NEVER) {
                throw new IllegalArgumentException("Cannot set the standby bucket to " + bucket);
            }
            final int packageUid = mInjector.getPackageManagerInternal()
                    .getPackageUid(packageName, packageFlags, userId);
            // Caller cannot set their own standby state
            if (packageUid == callingUid) {
                throw new IllegalArgumentException("Cannot set your own standby bucket");
            }
            if (packageUid < 0) {
                throw new IllegalArgumentException(
                        "Cannot set standby bucket for non existent package (" + packageName + ")");
            }
            setAppStandbyBucket(packageName, userId, bucket, reason, elapsedRealtime, shellCaller);
        }
    }

    @VisibleForTesting
    void setAppStandbyBucket(String packageName, int userId, @StandbyBuckets int newBucket,
            int reason) {
        setAppStandbyBucket(
                packageName, userId, newBucket, reason, mInjector.elapsedRealtime(), false);
    }

    private void setAppStandbyBucket(String packageName, int userId, @StandbyBuckets int newBucket,
            int reason, long elapsedRealtime, boolean resetTimeout) {
        if (!mAppIdleEnabled) return;

        synchronized (mAppIdleLock) {
            // If the package is not installed, don't allow the bucket to be set.
            if (!mInjector.isPackageInstalled(packageName, 0, userId)) {
                Slog.e(TAG, "Tried to set bucket of uninstalled app: " + packageName);
                return;
            }
            if (newBucket == STANDBY_BUCKET_RESTRICTED && !mAllowRestrictedBucket) {
                newBucket = STANDBY_BUCKET_RARE;
            }
            AppIdleHistory.AppUsageHistory app = mAppIdleHistory.getAppUsageHistory(packageName,
                    userId, elapsedRealtime);
            boolean predicted = (reason & REASON_MAIN_MASK) == REASON_MAIN_PREDICTED;

            // Don't allow changing bucket if higher than ACTIVE
            if (app.currentBucket < STANDBY_BUCKET_ACTIVE) return;

            // Don't allow prediction to change from/to NEVER.
            if ((app.currentBucket == STANDBY_BUCKET_NEVER || newBucket == STANDBY_BUCKET_NEVER)
                    && predicted) {
                return;
            }

            final boolean wasForcedBySystem =
                    (app.bucketingReason & REASON_MAIN_MASK) == REASON_MAIN_FORCED_BY_SYSTEM;

            // If the bucket was forced, don't allow prediction to override
            if (predicted
                    && ((app.bucketingReason & REASON_MAIN_MASK) == REASON_MAIN_FORCED_BY_USER
                    || wasForcedBySystem)) {
                return;
            }

            final boolean isForcedBySystem =
                    (reason & REASON_MAIN_MASK) == REASON_MAIN_FORCED_BY_SYSTEM;

            if (app.currentBucket == newBucket && wasForcedBySystem && isForcedBySystem) {
                mAppIdleHistory
                        .noteRestrictionAttempt(packageName, userId, elapsedRealtime, reason);
                // Keep track of all restricting reasons
                reason = REASON_MAIN_FORCED_BY_SYSTEM
                        | (app.bucketingReason & REASON_SUB_MASK)
                        | (reason & REASON_SUB_MASK);
                mAppIdleHistory.setAppStandbyBucket(packageName, userId, elapsedRealtime,
                        newBucket, reason, resetTimeout);
                return;
            }

            final boolean isForcedByUser =
                    (reason & REASON_MAIN_MASK) == REASON_MAIN_FORCED_BY_USER;

            if (app.currentBucket == STANDBY_BUCKET_RESTRICTED) {
                if ((app.bucketingReason & REASON_MAIN_MASK) == REASON_MAIN_TIMEOUT) {
                    if (predicted && newBucket >= STANDBY_BUCKET_RARE) {
                        // Predicting into RARE or below means we don't expect the user to use the
                        // app anytime soon, so don't elevate it from RESTRICTED.
                        return;
                    }
                } else if (!isUserUsage(reason) && !isForcedByUser) {
                    // If the current bucket is RESTRICTED, only user force or usage should bring
                    // it out, unless the app was put into the bucket due to timing out.
                    return;
                }
            }

            if (newBucket == STANDBY_BUCKET_RESTRICTED) {
                mAppIdleHistory
                        .noteRestrictionAttempt(packageName, userId, elapsedRealtime, reason);

                if (isForcedByUser) {
                    // Only user force can bypass the delay restriction. If the user forced the
                    // app into the RESTRICTED bucket, then a toast confirming the action
                    // shouldn't be surprising.
                    if (Build.IS_DEBUGGABLE) {
                        Toast.makeText(mContext,
                                // Since AppStandbyController sits low in the lock hierarchy,
                                // make sure not to call out with the lock held.
                                mHandler.getLooper(),
                                mContext.getResources().getString(
                                        R.string.as_app_forced_to_restricted_bucket, packageName),
                                Toast.LENGTH_SHORT)
                                .show();
                    } else {
                        Slog.i(TAG, packageName + " restricted by user");
                    }
                } else {
                    final long timeUntilRestrictPossibleMs = app.lastUsedByUserElapsedTime
                            + mInjector.getAutoRestrictedBucketDelayMs() - elapsedRealtime;
                    if (timeUntilRestrictPossibleMs > 0) {
                        Slog.w(TAG, "Tried to restrict recently used app: " + packageName
                                + " due to " + reason);
                        mHandler.sendMessageDelayed(
                                mHandler.obtainMessage(
                                        MSG_CHECK_PACKAGE_IDLE_STATE, userId, -1, packageName),
                                timeUntilRestrictPossibleMs);
                        return;
                    }
                }
            }

            // If the bucket is required to stay in a higher state for a specified duration, don't
            // override unless the duration has passed
            if (predicted) {
                // Check if the app is within one of the timeouts for forced bucket elevation
                final long elapsedTimeAdjusted = mAppIdleHistory.getElapsedTime(elapsedRealtime);
                // In case of not using the prediction, just keep track of it for applying after
                // ACTIVE or WORKING_SET timeout.
                mAppIdleHistory.updateLastPrediction(app, elapsedTimeAdjusted, newBucket);

                if (newBucket > STANDBY_BUCKET_ACTIVE
                        && app.bucketActiveTimeoutTime > elapsedTimeAdjusted) {
                    newBucket = STANDBY_BUCKET_ACTIVE;
                    reason = app.bucketingReason;
                    if (DEBUG) {
                        Slog.d(TAG, "    Keeping at ACTIVE due to min timeout");
                    }
                } else if (newBucket > STANDBY_BUCKET_WORKING_SET
                        && app.bucketWorkingSetTimeoutTime > elapsedTimeAdjusted) {
                    newBucket = STANDBY_BUCKET_WORKING_SET;
                    if (app.currentBucket != newBucket) {
                        reason = REASON_MAIN_USAGE | REASON_SUB_USAGE_ACTIVE_TIMEOUT;
                    } else {
                        reason = app.bucketingReason;
                    }
                    if (DEBUG) {
                        Slog.d(TAG, "    Keeping at WORKING_SET due to min timeout");
                    }
                } else if (newBucket == STANDBY_BUCKET_RARE
                        && mAllowRestrictedBucket
                        && getBucketForLocked(packageName, userId, elapsedRealtime)
                        == STANDBY_BUCKET_RESTRICTED) {
                    // Prediction doesn't think the app will be used anytime soon and
                    // it's been long enough that it could just time out into restricted,
                    // so time it out there instead. Using TIMEOUT will allow prediction
                    // to raise the bucket when it needs to.
                    newBucket = STANDBY_BUCKET_RESTRICTED;
                    reason = REASON_MAIN_TIMEOUT;
                    if (DEBUG) {
                        Slog.d(TAG,
                                "Prediction to RARE overridden by timeout into RESTRICTED");
                    }
                }
            }

            // Make sure we don't put the app in a lower bucket than it's supposed to be in.
            newBucket = Math.min(newBucket, getAppMinBucket(packageName, userId));
            mAppIdleHistory.setAppStandbyBucket(packageName, userId, elapsedRealtime, newBucket,
                    reason, resetTimeout);
        }
        maybeInformListeners(packageName, userId, elapsedRealtime, newBucket, reason, false);
    }

    @VisibleForTesting
    boolean isActiveDeviceAdmin(String packageName, int userId) {
        synchronized (mActiveAdminApps) {
            final Set<String> adminPkgs = mActiveAdminApps.get(userId);
            return adminPkgs != null && adminPkgs.contains(packageName);
        }
    }

    @Override
    public void addActiveDeviceAdmin(String adminPkg, int userId) {
        synchronized (mActiveAdminApps) {
            Set<String> adminPkgs = mActiveAdminApps.get(userId);
            if (adminPkgs == null) {
                adminPkgs = new ArraySet<>();
                mActiveAdminApps.put(userId, adminPkgs);
            }
            adminPkgs.add(adminPkg);
        }
    }

    @Override
    public void setActiveAdminApps(Set<String> adminPkgs, int userId) {
        synchronized (mActiveAdminApps) {
            if (adminPkgs == null) {
                mActiveAdminApps.remove(userId);
            } else {
                mActiveAdminApps.put(userId, adminPkgs);
            }
        }
    }

    @Override
    public void onAdminDataAvailable() {
        mAdminDataAvailableLatch.countDown();
    }

    /**
     * This will only ever be called once - during device boot.
     */
    private void waitForAdminData() {
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)) {
            ConcurrentUtils.waitForCountDownNoInterrupt(mAdminDataAvailableLatch,
                    WAIT_FOR_ADMIN_DATA_TIMEOUT_MS, "Wait for admin data");
        }
    }

    @VisibleForTesting
    Set<String> getActiveAdminAppsForTest(int userId) {
        synchronized (mActiveAdminApps) {
            return mActiveAdminApps.get(userId);
        }
    }

    /**
     * Returns {@code true} if the supplied package is the device provisioning app. Otherwise,
     * returns {@code false}.
     */
    private boolean isDeviceProvisioningPackage(String packageName) {
        String deviceProvisioningPackage = mContext.getResources().getString(
                com.android.internal.R.string.config_deviceProvisioningPackage);
        return deviceProvisioningPackage != null && deviceProvisioningPackage.equals(packageName);
    }

    private boolean isCarrierApp(String packageName) {
        synchronized (mAppIdleLock) {
            if (!mHaveCarrierPrivilegedApps) {
                fetchCarrierPrivilegedAppsLocked();
            }
            if (mCarrierPrivilegedApps != null) {
                return mCarrierPrivilegedApps.contains(packageName);
            }
            return false;
        }
    }

    @Override
    public void clearCarrierPrivilegedApps() {
        if (DEBUG) {
            Slog.i(TAG, "Clearing carrier privileged apps list");
        }
        synchronized (mAppIdleLock) {
            mHaveCarrierPrivilegedApps = false;
            mCarrierPrivilegedApps = null; // Need to be refetched.
        }
    }

    @GuardedBy("mAppIdleLock")
    private void fetchCarrierPrivilegedAppsLocked() {
        TelephonyManager telephonyManager =
                mContext.getSystemService(TelephonyManager.class);
        mCarrierPrivilegedApps =
                telephonyManager.getCarrierPrivilegedPackagesForAllActiveSubscriptions();
        mHaveCarrierPrivilegedApps = true;
        if (DEBUG) {
            Slog.d(TAG, "apps with carrier privilege " + mCarrierPrivilegedApps);
        }
    }

    private boolean isActiveNetworkScorer(String packageName) {
        // Validity of network scorer cache is limited to a few seconds. Fetch it again
        // if longer since query.
        // This is a temporary optimization until there's a callback mechanism for changes to network scorer.
        final long now = SystemClock.elapsedRealtime();
        if (mCachedNetworkScorer == null
                || mCachedNetworkScorerAtMillis < now - NETWORK_SCORER_CACHE_DURATION_MILLIS) {
            mCachedNetworkScorer = mInjector.getActiveNetworkScorer();
            mCachedNetworkScorerAtMillis = now;
        }
        return packageName != null && packageName.equals(mCachedNetworkScorer);
    }

    private void informListeners(String packageName, int userId, int bucket, int reason,
            boolean userInteraction) {
        final boolean idle = bucket >= STANDBY_BUCKET_RARE;
        synchronized (mPackageAccessListeners) {
            for (AppIdleStateChangeListener listener : mPackageAccessListeners) {
                listener.onAppIdleStateChanged(packageName, userId, idle, bucket, reason);
                if (userInteraction) {
                    listener.onUserInteractionStarted(packageName, userId);
                }
            }
        }
    }

    private void informParoleStateChanged() {
        final boolean paroled = isInParole();
        synchronized (mPackageAccessListeners) {
            for (AppIdleStateChangeListener listener : mPackageAccessListeners) {
                listener.onParoleStateChanged(paroled);
            }
        }
    }


    @Override
    public void flushToDisk() {
        synchronized (mAppIdleLock) {
            mAppIdleHistory.writeAppIdleTimes();
            mAppIdleHistory.writeAppIdleDurations();
        }
    }

    private boolean isDisplayOn() {
        return mInjector.isDefaultDisplayOn();
    }

    @VisibleForTesting
    void clearAppIdleForPackage(String packageName, int userId) {
        synchronized (mAppIdleLock) {
            mAppIdleHistory.clearUsage(packageName, userId);
        }
    }

    /**
     * Remove an app from the {@link android.app.usage.UsageStatsManager#STANDBY_BUCKET_RESTRICTED}
     * bucket if it was forced into the bucket by the system because it was buggy.
     */
    @VisibleForTesting
    void maybeUnrestrictBuggyApp(String packageName, int userId) {
        synchronized (mAppIdleLock) {
            final long elapsedRealtime = mInjector.elapsedRealtime();
            final AppIdleHistory.AppUsageHistory app =
                    mAppIdleHistory.getAppUsageHistory(packageName, userId, elapsedRealtime);
            if (app.currentBucket != STANDBY_BUCKET_RESTRICTED
                    || (app.bucketingReason & REASON_MAIN_MASK) != REASON_MAIN_FORCED_BY_SYSTEM) {
                return;
            }

            final int newBucket;
            final int newReason;
            if ((app.bucketingReason & REASON_SUB_MASK) == REASON_SUB_FORCED_SYSTEM_FLAG_BUGGY) {
                // If bugginess was the only reason the app should be restricted, then lift it out.
                newBucket = STANDBY_BUCKET_RARE;
                newReason = REASON_MAIN_DEFAULT | REASON_SUB_DEFAULT_APP_UPDATE;
            } else {
                // There's another reason the app was restricted. Remove the buggy bit and call
                // it a day.
                newBucket = STANDBY_BUCKET_RESTRICTED;
                newReason = app.bucketingReason & ~REASON_SUB_FORCED_SYSTEM_FLAG_BUGGY;
            }
            mAppIdleHistory.setAppStandbyBucket(
                    packageName, userId, elapsedRealtime, newBucket, newReason);
        }
    }

    private class PackageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final String pkgName = intent.getData().getSchemeSpecificPart();
            final int userId = getSendingUserId();
            if (Intent.ACTION_PACKAGE_ADDED.equals(action)
                    || Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
                clearCarrierPrivilegedApps();
                // ACTION_PACKAGE_ADDED is called even for system app downgrades.
                evaluateSystemAppException(pkgName, userId);
                mHandler.obtainMessage(MSG_CHECK_PACKAGE_IDLE_STATE, userId, -1, pkgName)
                    .sendToTarget();
            }
            if ((Intent.ACTION_PACKAGE_REMOVED.equals(action) ||
                    Intent.ACTION_PACKAGE_ADDED.equals(action))) {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    maybeUnrestrictBuggyApp(pkgName, userId);
                } else {
                    clearAppIdleForPackage(pkgName, userId);
                }
            }
        }
    }

    private void evaluateSystemAppException(String packageName, int userId) {
        if (!mSystemServicesReady) {
            // The app will be evaluated in when services are ready.
            return;
        }
        try {
            PackageInfo pi = mPackageManager.getPackageInfoAsUser(
                    packageName, HEADLESS_APP_CHECK_FLAGS, userId);
            evaluateSystemAppException(pi);
        } catch (PackageManager.NameNotFoundException e) {
            synchronized (mHeadlessSystemApps) {
                mHeadlessSystemApps.remove(packageName);
            }
        }
    }

    /** Returns true if the exception status changed. */
    private boolean evaluateSystemAppException(@Nullable PackageInfo pkgInfo) {
        if (pkgInfo == null || pkgInfo.applicationInfo == null
                || (!pkgInfo.applicationInfo.isSystemApp()
                        && !pkgInfo.applicationInfo.isUpdatedSystemApp())) {
            return false;
        }
        synchronized (mHeadlessSystemApps) {
            if (pkgInfo.activities == null || pkgInfo.activities.length == 0) {
                // Headless system app.
                return mHeadlessSystemApps.add(pkgInfo.packageName);
            } else {
                return mHeadlessSystemApps.remove(pkgInfo.packageName);
            }
        }
    }

    /** Call on a system version update to temporarily reset system app buckets. */
    @Override
    public void initializeDefaultsForSystemApps(int userId) {
        if (!mSystemServicesReady) {
            // Do it later, since SettingsProvider wasn't queried yet for app_standby_enabled
            mPendingInitializeDefaults = true;
            return;
        }
        Slog.d(TAG, "Initializing defaults for system apps on user " + userId + ", "
                + "appIdleEnabled=" + mAppIdleEnabled);
        final long elapsedRealtime = mInjector.elapsedRealtime();
        List<PackageInfo> packages = mPackageManager.getInstalledPackagesAsUser(
                PackageManager.MATCH_DISABLED_COMPONENTS,
                userId);
        final int packageCount = packages.size();
        synchronized (mAppIdleLock) {
            for (int i = 0; i < packageCount; i++) {
                final PackageInfo pi = packages.get(i);
                String packageName = pi.packageName;
                if (pi.applicationInfo != null && pi.applicationInfo.isSystemApp()) {
                    // Mark app as used for 2 hours. After that it can timeout to whatever the
                    // past usage pattern was.
                    mAppIdleHistory.reportUsage(packageName, userId, STANDBY_BUCKET_ACTIVE,
                            REASON_SUB_USAGE_SYSTEM_UPDATE, 0,
                            elapsedRealtime + mSystemUpdateUsageTimeoutMillis);
                }
            }
            // Immediately persist defaults to disk
            mAppIdleHistory.writeAppIdleTimes(userId);
        }
    }

    /** Call on system boot to get the initial set of headless system apps. */
    private void loadHeadlessSystemAppCache() {
        Slog.d(TAG, "Loading headless system app cache. appIdleEnabled=" + mAppIdleEnabled);
        final List<PackageInfo> packages = mPackageManager.getInstalledPackagesAsUser(
                HEADLESS_APP_CHECK_FLAGS, UserHandle.USER_SYSTEM);
        final int packageCount = packages.size();
        for (int i = 0; i < packageCount; i++) {
            PackageInfo pkgInfo = packages.get(i);
            if (pkgInfo != null && evaluateSystemAppException(pkgInfo)) {
                mHandler.obtainMessage(MSG_CHECK_PACKAGE_IDLE_STATE,
                        UserHandle.USER_SYSTEM, -1, pkgInfo.packageName)
                    .sendToTarget();
            }
        }
    }

    @Override
    public void postReportContentProviderUsage(String name, String packageName, int userId) {
        ContentProviderUsageRecord record = ContentProviderUsageRecord.obtain(name, packageName,
                userId);
        mHandler.obtainMessage(MSG_REPORT_CONTENT_PROVIDER_USAGE, record)
                .sendToTarget();
    }

    @Override
    public void postReportSyncScheduled(String packageName, int userId, boolean exempted) {
        mHandler.obtainMessage(MSG_REPORT_SYNC_SCHEDULED, userId, exempted ? 1 : 0, packageName)
                .sendToTarget();
    }

    @Override
    public void postReportExemptedSyncStart(String packageName, int userId) {
        mHandler.obtainMessage(MSG_REPORT_EXEMPTED_SYNC_START, userId, 0, packageName)
                .sendToTarget();
    }

    @Override
    public void dumpUsers(IndentingPrintWriter idpw, int[] userIds, List<String> pkgs) {
        synchronized (mAppIdleLock) {
            mAppIdleHistory.dumpUsers(idpw, userIds, pkgs);
        }
    }

    @Override
    public void dumpState(String[] args, PrintWriter pw) {
        synchronized (mAppIdleLock) {
            pw.println("Carrier privileged apps (have=" + mHaveCarrierPrivilegedApps
                    + "): " + mCarrierPrivilegedApps);
        }

        pw.println();
        pw.println("Settings:");

        pw.print("  mCheckIdleIntervalMillis=");
        TimeUtils.formatDuration(mCheckIdleIntervalMillis, pw);
        pw.println();

        pw.print("  mStrongUsageTimeoutMillis=");
        TimeUtils.formatDuration(mStrongUsageTimeoutMillis, pw);
        pw.println();
        pw.print("  mNotificationSeenTimeoutMillis=");
        TimeUtils.formatDuration(mNotificationSeenTimeoutMillis, pw);
        pw.println();
        pw.print("  mSyncAdapterTimeoutMillis=");
        TimeUtils.formatDuration(mSyncAdapterTimeoutMillis, pw);
        pw.println();
        pw.print("  mSystemInteractionTimeoutMillis=");
        TimeUtils.formatDuration(mSystemInteractionTimeoutMillis, pw);
        pw.println();
        pw.print("  mInitialForegroundServiceStartTimeoutMillis=");
        TimeUtils.formatDuration(mInitialForegroundServiceStartTimeoutMillis, pw);
        pw.println();

        pw.print("  mPredictionTimeoutMillis=");
        TimeUtils.formatDuration(mPredictionTimeoutMillis, pw);
        pw.println();

        pw.print("  mExemptedSyncScheduledNonDozeTimeoutMillis=");
        TimeUtils.formatDuration(mExemptedSyncScheduledNonDozeTimeoutMillis, pw);
        pw.println();
        pw.print("  mExemptedSyncScheduledDozeTimeoutMillis=");
        TimeUtils.formatDuration(mExemptedSyncScheduledDozeTimeoutMillis, pw);
        pw.println();
        pw.print("  mExemptedSyncStartTimeoutMillis=");
        TimeUtils.formatDuration(mExemptedSyncStartTimeoutMillis, pw);
        pw.println();
        pw.print("  mUnexemptedSyncScheduledTimeoutMillis=");
        TimeUtils.formatDuration(mUnexemptedSyncScheduledTimeoutMillis, pw);
        pw.println();

        pw.print("  mSystemUpdateUsageTimeoutMillis=");
        TimeUtils.formatDuration(mSystemUpdateUsageTimeoutMillis, pw);
        pw.println();

        pw.println();
        pw.print("mAppIdleEnabled="); pw.print(mAppIdleEnabled);
        pw.print(" mAllowRestrictedBucket=");
        pw.print(mAllowRestrictedBucket);
        pw.print(" mIsCharging=");
        pw.print(mIsCharging);
        pw.println();
        pw.print("mScreenThresholds="); pw.println(Arrays.toString(mAppStandbyScreenThresholds));
        pw.print("mElapsedThresholds="); pw.println(Arrays.toString(mAppStandbyElapsedThresholds));
        pw.println();

        pw.println("mHeadlessSystemApps=[");
        synchronized (mHeadlessSystemApps) {
            for (int i = mHeadlessSystemApps.size() - 1; i >= 0; --i) {
                pw.print("  ");
                pw.print(mHeadlessSystemApps.valueAt(i));
                pw.println(",");
            }
        }
        pw.println("]");
        pw.println();

        mInjector.dump(pw);
    }

    /**
     * Injector for interaction with external code. Override methods to provide a mock
     * implementation for tests.
     * onBootPhase() must be called with at least the PHASE_SYSTEM_SERVICES_READY
     */
    static class Injector {

        private final Context mContext;
        private final Looper mLooper;
        private IBatteryStats mBatteryStats;
        private BatteryManager mBatteryManager;
        private PackageManagerInternal mPackageManagerInternal;
        private DisplayManager mDisplayManager;
        private PowerManager mPowerManager;
        private IDeviceIdleController mDeviceIdleController;
        private CrossProfileAppsInternal mCrossProfileAppsInternal;
        int mBootPhase;
        /**
         * The minimum amount of time required since the last user interaction before an app can be
         * automatically placed in the RESTRICTED bucket.
         */
        long mAutoRestrictedBucketDelayMs = ONE_DAY;
        /**
         * Cached set of apps that are power whitelisted, including those not whitelisted from idle.
         */
        @GuardedBy("mPowerWhitelistedApps")
        private final ArraySet<String> mPowerWhitelistedApps = new ArraySet<>();

        Injector(Context context, Looper looper) {
            mContext = context;
            mLooper = looper;
        }

        Context getContext() {
            return mContext;
        }

        Looper getLooper() {
            return mLooper;
        }

        void onBootPhase(int phase) {
            if (phase == PHASE_SYSTEM_SERVICES_READY) {
                mDeviceIdleController = IDeviceIdleController.Stub.asInterface(
                        ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));
                mBatteryStats = IBatteryStats.Stub.asInterface(
                        ServiceManager.getService(BatteryStats.SERVICE_NAME));
                mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
                mDisplayManager = (DisplayManager) mContext.getSystemService(
                        Context.DISPLAY_SERVICE);
                mPowerManager = mContext.getSystemService(PowerManager.class);
                mBatteryManager = mContext.getSystemService(BatteryManager.class);
                mCrossProfileAppsInternal = LocalServices.getService(
                        CrossProfileAppsInternal.class);

                final ActivityManager activityManager =
                        (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
                if (activityManager.isLowRamDevice() || ActivityManager.isSmallBatteryDevice()) {
                    mAutoRestrictedBucketDelayMs = 12 * ONE_HOUR;
                }
            }
            mBootPhase = phase;
        }

        int getBootPhase() {
            return mBootPhase;
        }

        /**
         * Returns the elapsed realtime since the device started. Override this
         * to control the clock.
         * @return elapsed realtime
         */
        long elapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }

        long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        boolean isAppIdleEnabled() {
            final boolean buildFlag = mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_enableAutoPowerModes);
            final boolean runtimeFlag = Global.getInt(mContext.getContentResolver(),
                    Global.APP_STANDBY_ENABLED, 1) == 1
                    && Global.getInt(mContext.getContentResolver(),
                    Global.ADAPTIVE_BATTERY_MANAGEMENT_ENABLED, 1) == 1;
            return buildFlag && runtimeFlag;
        }

        boolean isCharging() {
            return mBatteryManager.isCharging();
        }

        boolean isNonIdleWhitelisted(String packageName) {
            if (mBootPhase < PHASE_SYSTEM_SERVICES_READY) {
                return false;
            }
            synchronized (mPowerWhitelistedApps) {
                return mPowerWhitelistedApps.contains(packageName);
            }
        }

        private void updatePowerWhitelistCache() {
            if (mBootPhase < PHASE_SYSTEM_SERVICES_READY) {
                return;
            }
            try {
                // Don't call out to DeviceIdleController with the lock held.
                final String[] whitelistedPkgs =
                        mDeviceIdleController.getFullPowerWhitelistExceptIdle();
                synchronized (mPowerWhitelistedApps) {
                    mPowerWhitelistedApps.clear();
                    final int len = whitelistedPkgs.length;
                    for (int i = 0; i < len; ++i) {
                        mPowerWhitelistedApps.add(whitelistedPkgs[i]);
                    }
                }
            } catch (RemoteException e) {
                // Should not happen.
                Slog.wtf(TAG, "Failed to get power whitelist", e);
            }
        }

        boolean isRestrictedBucketEnabled() {
            return Global.getInt(mContext.getContentResolver(),
                    Global.ENABLE_RESTRICTED_BUCKET,
                    Global.DEFAULT_ENABLE_RESTRICTED_BUCKET) == 1;
        }

        File getDataSystemDirectory() {
            return Environment.getDataSystemDirectory();
        }

        /**
         * Return the minimum amount of time that must have passed since the last user usage before
         * an app can be automatically put into the
         * {@link android.app.usage.UsageStatsManager#STANDBY_BUCKET_RESTRICTED} bucket.
         */
        long getAutoRestrictedBucketDelayMs() {
            return mAutoRestrictedBucketDelayMs;
        }

        void noteEvent(int event, String packageName, int uid) throws RemoteException {
            mBatteryStats.noteEvent(event, packageName, uid);
        }

        PackageManagerInternal getPackageManagerInternal() {
            return mPackageManagerInternal;
        }

        boolean isPackageEphemeral(int userId, String packageName) {
            return mPackageManagerInternal.isPackageEphemeral(userId, packageName);
        }

        boolean isPackageInstalled(String packageName, int flags, int userId) {
            return mPackageManagerInternal.getPackageUid(packageName, flags, userId) >= 0;
        }

        int[] getRunningUserIds() throws RemoteException {
            return ActivityManager.getService().getRunningUserIds();
        }

        boolean isDefaultDisplayOn() {
            return mDisplayManager
                    .getDisplay(Display.DEFAULT_DISPLAY).getState() == Display.STATE_ON;
        }

        void registerDisplayListener(DisplayManager.DisplayListener listener, Handler handler) {
            mDisplayManager.registerDisplayListener(listener, handler);
        }

        String getActiveNetworkScorer() {
            NetworkScoreManager nsm = (NetworkScoreManager) mContext.getSystemService(
                    Context.NETWORK_SCORE_SERVICE);
            return nsm.getActiveScorerPackage();
        }

        public boolean isBoundWidgetPackage(AppWidgetManager appWidgetManager, String packageName,
                int userId) {
            return appWidgetManager.isBoundWidgetPackage(packageName, userId);
        }

        String getAppIdleSettings() {
            return Global.getString(mContext.getContentResolver(),
                    Global.APP_IDLE_CONSTANTS);
        }

        /** Whether the device is in doze or not. */
        public boolean isDeviceIdleMode() {
            return mPowerManager.isDeviceIdleMode();
        }

        public List<UserHandle> getValidCrossProfileTargets(String pkg, int userId) {
            final int uid = mPackageManagerInternal.getPackageUidInternal(pkg, 0, userId);
            final AndroidPackage aPkg = mPackageManagerInternal.getPackage(uid);
            if (uid < 0
                    || aPkg == null
                    || !aPkg.isCrossProfile()
                    || !mCrossProfileAppsInternal
                            .verifyUidHasInteractAcrossProfilePermission(pkg, uid)) {
                if (uid >= 0 && aPkg == null) {
                    Slog.wtf(TAG, "Null package retrieved for UID " + uid);
                }
                return Collections.emptyList();
            }
            return mCrossProfileAppsInternal.getTargetUserProfiles(pkg, userId);
        }

        void dump(PrintWriter pw) {
            pw.println("mPowerWhitelistedApps=[");
            synchronized (mPowerWhitelistedApps) {
                for (int i = mPowerWhitelistedApps.size() - 1; i >= 0; --i) {
                    pw.print("  ");
                    pw.print(mPowerWhitelistedApps.valueAt(i));
                    pw.println(",");
                }
            }
            pw.println("]");
            pw.println();
        }
    }

    class AppStandbyHandler extends Handler {

        AppStandbyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_INFORM_LISTENERS:
                    StandbyUpdateRecord r = (StandbyUpdateRecord) msg.obj;
                    informListeners(r.packageName, r.userId, r.bucket, r.reason,
                            r.isUserInteraction);
                    r.recycle();
                    break;

                case MSG_FORCE_IDLE_STATE:
                    forceIdleState((String) msg.obj, msg.arg1, msg.arg2 == 1);
                    break;

                case MSG_CHECK_IDLE_STATES:
                    if (checkIdleStates(msg.arg1) && mAppIdleEnabled) {
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(
                                MSG_CHECK_IDLE_STATES, msg.arg1, 0),
                                mCheckIdleIntervalMillis);
                    }
                    break;

                case MSG_ONE_TIME_CHECK_IDLE_STATES:
                    mHandler.removeMessages(MSG_ONE_TIME_CHECK_IDLE_STATES);
                    waitForAdminData();
                    checkIdleStates(UserHandle.USER_ALL);
                    break;

                case MSG_REPORT_CONTENT_PROVIDER_USAGE:
                    ContentProviderUsageRecord record = (ContentProviderUsageRecord) msg.obj;
                    reportContentProviderUsage(record.name, record.packageName, record.userId);
                    record.recycle();
                    break;

                case MSG_PAROLE_STATE_CHANGED:
                    if (DEBUG) Slog.d(TAG, "Parole state: " + isInParole());
                    informParoleStateChanged();
                    break;

                case MSG_CHECK_PACKAGE_IDLE_STATE:
                    checkAndUpdateStandbyState((String) msg.obj, msg.arg1, msg.arg2,
                            mInjector.elapsedRealtime());
                    break;

                case MSG_REPORT_SYNC_SCHEDULED:
                    final boolean exempted = msg.arg2 > 0 ? true : false;
                    if (exempted) {
                        reportExemptedSyncScheduled((String) msg.obj, msg.arg1);
                    } else {
                        reportUnexemptedSyncScheduled((String) msg.obj, msg.arg1);
                    }
                    break;

                case MSG_REPORT_EXEMPTED_SYNC_START:
                    reportExemptedSyncStart((String) msg.obj, msg.arg1);
                    break;

                default:
                    super.handleMessage(msg);
                    break;

            }
        }
    };

    private class DeviceStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case BatteryManager.ACTION_CHARGING:
                    setChargingState(true);
                    break;
                case BatteryManager.ACTION_DISCHARGING:
                    setChargingState(false);
                    break;
                case PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED:
                    if (mSystemServicesReady) {
                        mHandler.post(mInjector::updatePowerWhitelistCache);
                    }
                    break;
            }
        }
    }

    private final DisplayManager.DisplayListener mDisplayListener
            = new DisplayManager.DisplayListener() {

        @Override public void onDisplayAdded(int displayId) {
        }

        @Override public void onDisplayRemoved(int displayId) {
        }

        @Override public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                final boolean displayOn = isDisplayOn();
                synchronized (mAppIdleLock) {
                    mAppIdleHistory.updateDisplay(displayOn, mInjector.elapsedRealtime());
                }
            }
        }
    };

    /**
     * Observe settings changes for {@link Global#APP_IDLE_CONSTANTS}.
     */
    private class SettingsObserver extends ContentObserver {
        private static final String KEY_SCREEN_TIME_THRESHOLDS = "screen_thresholds";
        private static final String KEY_ELAPSED_TIME_THRESHOLDS = "elapsed_thresholds";
        private static final String KEY_STRONG_USAGE_HOLD_DURATION = "strong_usage_duration";
        private static final String KEY_NOTIFICATION_SEEN_HOLD_DURATION =
                "notification_seen_duration";
        private static final String KEY_SYSTEM_UPDATE_HOLD_DURATION =
                "system_update_usage_duration";
        private static final String KEY_PREDICTION_TIMEOUT = "prediction_timeout";
        private static final String KEY_SYNC_ADAPTER_HOLD_DURATION = "sync_adapter_duration";
        private static final String KEY_EXEMPTED_SYNC_SCHEDULED_NON_DOZE_HOLD_DURATION =
                "exempted_sync_scheduled_nd_duration";
        private static final String KEY_EXEMPTED_SYNC_SCHEDULED_DOZE_HOLD_DURATION =
                "exempted_sync_scheduled_d_duration";
        private static final String KEY_EXEMPTED_SYNC_START_HOLD_DURATION =
                "exempted_sync_start_duration";
        private static final String KEY_UNEXEMPTED_SYNC_SCHEDULED_HOLD_DURATION =
                "unexempted_sync_scheduled_duration";
        private static final String KEY_SYSTEM_INTERACTION_HOLD_DURATION =
                "system_interaction_duration";
        private static final String KEY_INITIAL_FOREGROUND_SERVICE_START_HOLD_DURATION =
                "initial_foreground_service_start_duration";
        private static final String KEY_AUTO_RESTRICTED_BUCKET_DELAY_MS =
                "auto_restricted_bucket_delay_ms";
        private static final String KEY_CROSS_PROFILE_APPS_SHARE_STANDBY_BUCKETS =
                "cross_profile_apps_share_standby_buckets";
        public static final long DEFAULT_STRONG_USAGE_TIMEOUT = 1 * ONE_HOUR;
        public static final long DEFAULT_NOTIFICATION_TIMEOUT = 12 * ONE_HOUR;
        public static final long DEFAULT_SYSTEM_UPDATE_TIMEOUT = 2 * ONE_HOUR;
        public static final long DEFAULT_SYSTEM_INTERACTION_TIMEOUT = 10 * ONE_MINUTE;
        public static final long DEFAULT_SYNC_ADAPTER_TIMEOUT = 10 * ONE_MINUTE;
        public static final long DEFAULT_EXEMPTED_SYNC_SCHEDULED_NON_DOZE_TIMEOUT = 10 * ONE_MINUTE;
        public static final long DEFAULT_EXEMPTED_SYNC_SCHEDULED_DOZE_TIMEOUT = 4 * ONE_HOUR;
        public static final long DEFAULT_EXEMPTED_SYNC_START_TIMEOUT = 10 * ONE_MINUTE;
        public static final long DEFAULT_UNEXEMPTED_SYNC_SCHEDULED_TIMEOUT = 10 * ONE_MINUTE;
        public static final long DEFAULT_INITIAL_FOREGROUND_SERVICE_START_TIMEOUT = 30 * ONE_MINUTE;
        public static final long DEFAULT_AUTO_RESTRICTED_BUCKET_DELAY_MS = ONE_DAY;
        public static final boolean DEFAULT_CROSS_PROFILE_APPS_SHARE_STANDBY_BUCKETS = true;

        private final KeyValueListParser mParser = new KeyValueListParser(',');

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void registerObserver() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(Global.getUriFor(Global.APP_IDLE_CONSTANTS), false, this);
            cr.registerContentObserver(Global.getUriFor(Global.APP_STANDBY_ENABLED), false, this);
            cr.registerContentObserver(Global.getUriFor(Global.ENABLE_RESTRICTED_BUCKET),
                    false, this);
            cr.registerContentObserver(Global.getUriFor(Global.ADAPTIVE_BATTERY_MANAGEMENT_ENABLED),
                    false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
            postOneTimeCheckIdleStates();
        }

        void updateSettings() {
            if (DEBUG) {
                Slog.d(TAG,
                        "appidle=" + Global.getString(mContext.getContentResolver(),
                                Global.APP_STANDBY_ENABLED));
                Slog.d(TAG,
                        "adaptivebat=" + Global.getString(mContext.getContentResolver(),
                                Global.ADAPTIVE_BATTERY_MANAGEMENT_ENABLED));
                Slog.d(TAG, "appidleconstants=" + Global.getString(
                        mContext.getContentResolver(),
                        Global.APP_IDLE_CONSTANTS));
            }

            // Look at global settings for this.
            // TODO: Maybe apply different thresholds for different users.
            try {
                mParser.setString(mInjector.getAppIdleSettings());
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Bad value for app idle settings: " + e.getMessage());
                // fallthrough, mParser is empty and all defaults will be returned.
            }

            synchronized (mAppIdleLock) {

                String screenThresholdsValue = mParser.getString(KEY_SCREEN_TIME_THRESHOLDS, null);
                mAppStandbyScreenThresholds = parseLongArray(screenThresholdsValue,
                        SCREEN_TIME_THRESHOLDS, MINIMUM_SCREEN_TIME_THRESHOLDS);

                String elapsedThresholdsValue = mParser.getString(KEY_ELAPSED_TIME_THRESHOLDS,
                        null);
                mAppStandbyElapsedThresholds = parseLongArray(elapsedThresholdsValue,
                        ELAPSED_TIME_THRESHOLDS, MINIMUM_ELAPSED_TIME_THRESHOLDS);
                mCheckIdleIntervalMillis = Math.min(mAppStandbyElapsedThresholds[1] / 4,
                        COMPRESS_TIME ? ONE_MINUTE : 4 * 60 * ONE_MINUTE); // 4 hours
                mStrongUsageTimeoutMillis = mParser.getDurationMillis(
                        KEY_STRONG_USAGE_HOLD_DURATION,
                                COMPRESS_TIME ? ONE_MINUTE : DEFAULT_STRONG_USAGE_TIMEOUT);
                mNotificationSeenTimeoutMillis = mParser.getDurationMillis(
                        KEY_NOTIFICATION_SEEN_HOLD_DURATION,
                                COMPRESS_TIME ? 12 * ONE_MINUTE : DEFAULT_NOTIFICATION_TIMEOUT);
                mSystemUpdateUsageTimeoutMillis = mParser.getDurationMillis(
                        KEY_SYSTEM_UPDATE_HOLD_DURATION,
                                COMPRESS_TIME ? 2 * ONE_MINUTE : DEFAULT_SYSTEM_UPDATE_TIMEOUT);
                mPredictionTimeoutMillis = mParser.getDurationMillis(
                        KEY_PREDICTION_TIMEOUT,
                                COMPRESS_TIME ? 10 * ONE_MINUTE : DEFAULT_PREDICTION_TIMEOUT);
                mSyncAdapterTimeoutMillis = mParser.getDurationMillis(
                        KEY_SYNC_ADAPTER_HOLD_DURATION,
                                COMPRESS_TIME ? ONE_MINUTE : DEFAULT_SYNC_ADAPTER_TIMEOUT);

                mExemptedSyncScheduledNonDozeTimeoutMillis = mParser.getDurationMillis(
                        KEY_EXEMPTED_SYNC_SCHEDULED_NON_DOZE_HOLD_DURATION,
                                COMPRESS_TIME ? (ONE_MINUTE / 2)
                                        : DEFAULT_EXEMPTED_SYNC_SCHEDULED_NON_DOZE_TIMEOUT);

                mExemptedSyncScheduledDozeTimeoutMillis = mParser.getDurationMillis(
                        KEY_EXEMPTED_SYNC_SCHEDULED_DOZE_HOLD_DURATION,
                                COMPRESS_TIME ? ONE_MINUTE
                                        : DEFAULT_EXEMPTED_SYNC_SCHEDULED_DOZE_TIMEOUT);

                mExemptedSyncStartTimeoutMillis = mParser.getDurationMillis(
                        KEY_EXEMPTED_SYNC_START_HOLD_DURATION,
                                COMPRESS_TIME ? ONE_MINUTE
                                        : DEFAULT_EXEMPTED_SYNC_START_TIMEOUT);

                mUnexemptedSyncScheduledTimeoutMillis = mParser.getDurationMillis(
                        KEY_UNEXEMPTED_SYNC_SCHEDULED_HOLD_DURATION,
                                COMPRESS_TIME
                                        ? ONE_MINUTE : DEFAULT_UNEXEMPTED_SYNC_SCHEDULED_TIMEOUT);

                mSystemInteractionTimeoutMillis = mParser.getDurationMillis(
                        KEY_SYSTEM_INTERACTION_HOLD_DURATION,
                                COMPRESS_TIME ? ONE_MINUTE : DEFAULT_SYSTEM_INTERACTION_TIMEOUT);

                mInitialForegroundServiceStartTimeoutMillis = mParser.getDurationMillis(
                        KEY_INITIAL_FOREGROUND_SERVICE_START_HOLD_DURATION,
                        COMPRESS_TIME ? ONE_MINUTE :
                                DEFAULT_INITIAL_FOREGROUND_SERVICE_START_TIMEOUT);

                mInjector.mAutoRestrictedBucketDelayMs = Math.max(
                        COMPRESS_TIME ? ONE_MINUTE : 2 * ONE_HOUR,
                        mParser.getDurationMillis(KEY_AUTO_RESTRICTED_BUCKET_DELAY_MS,
                                COMPRESS_TIME
                                        ? ONE_MINUTE : DEFAULT_AUTO_RESTRICTED_BUCKET_DELAY_MS));

                mLinkCrossProfileApps = mParser.getBoolean(
                        KEY_CROSS_PROFILE_APPS_SHARE_STANDBY_BUCKETS,
                        DEFAULT_CROSS_PROFILE_APPS_SHARE_STANDBY_BUCKETS);

                mAllowRestrictedBucket = mInjector.isRestrictedBucketEnabled();
            }

            // Check if app_idle_enabled has changed. Do this after getting the rest of the settings
            // in case we need to change something based on the new values.
            setAppIdleEnabled(mInjector.isAppIdleEnabled());
        }

        long[] parseLongArray(String values, long[] defaults, long[] minValues) {
            if (values == null) return defaults;
            if (values.isEmpty()) {
                // Reset to defaults
                return defaults;
            } else {
                String[] thresholds = values.split("/");
                if (thresholds.length == THRESHOLD_BUCKETS.length) {
                    if (minValues.length != THRESHOLD_BUCKETS.length) {
                        Slog.wtf(TAG, "minValues array is the wrong size");
                        // Use zeroes as the minimums.
                        minValues = new long[THRESHOLD_BUCKETS.length];
                    }
                    long[] array = new long[THRESHOLD_BUCKETS.length];
                    for (int i = 0; i < THRESHOLD_BUCKETS.length; i++) {
                        try {
                            if (thresholds[i].startsWith("P") || thresholds[i].startsWith("p")) {
                                array[i] = Math.max(minValues[i],
                                        Duration.parse(thresholds[i]).toMillis());
                            } else {
                                array[i] = Math.max(minValues[i], Long.parseLong(thresholds[i]));
                            }
                        } catch (NumberFormatException|DateTimeParseException e) {
                            return defaults;
                        }
                    }
                    return array;
                } else {
                    return defaults;
                }
            }
        }
    }
}
