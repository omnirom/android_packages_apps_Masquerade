package masquerade.substratum.services;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.media.RingtoneManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import masquerade.substratum.utils.IOUtils;
import masquerade.substratum.utils.SoundUtils;

import com.android.internal.statusbar.IStatusBarService;

public class JobService extends Service {
    private static final String TAG = JobService.class.getSimpleName();
    private static final boolean DEBUG = true;

    private static final String MASQUERADE_TOKEN = "masquerade_token";
    private static final String SUBSTRATUM_PACKAGE = "projekt.substratum";
    private static final String[] AUTHORIZED_CALLERS = new String[] {
            SUBSTRATUM_PACKAGE,
            "masquerade.substratum"
    };

    public static final String INTENT_STATUS_CHANGED = "masquerade.substratum.STATUS_CHANGED";

    public static final String PRIMARY_COMMAND_KEY = "primary_command_key";
    public static final String JOB_TIME_KEY = "job_time_key";
    public static final String INSTALL_LIST_KEY = "install_list";
    public static final String UNINSTALL_LIST_KEY = "uninstall_list";
    public static final String BOOTANIMATION_FILE_NAME = "bootanimation_file_name";
    public static final String FONTS_PID = "fonts_pid";
    public static final String FONTS_FILENAME = "fonts_filename";
    public static final String AUDIO_PID = "audio_pid";
    public static final String AUDIO_FILENAME = "audio_filename";
    public static final String ENABLE_LIST_KEY = "enable_list";
    public static final String DISABLE_LIST_KEY = "disable_list";
    public static final String PRIORITY_LIST_KEY = "priority_list";
    public static final String SOURCE_FILE_KEY = "source_file";
    public static final String DESTINATION_FILE_KEY = "destination_file";
    public static final String WITH_DELETE_PARENT_KEY = "delete_parent";
    public static final String PROFILE_NAME_KEY = "profile_name";
    public static final String COMMAND_VALUE_JOB_COMPLETE = "job_complete";
    public static final String COMMAND_VALUE_INSTALL = "install";
    public static final String COMMAND_VALUE_UNINSTALL = "uninstall";
    public static final String COMMAND_VALUE_RESTART_UI = "restart_ui";
    public static final String COMMAND_VALUE_CONFIGURATION_SHIM = "configuration_shim";
    public static final String COMMAND_VALUE_BOOTANIMATION = "bootanimation";
    public static final String COMMAND_VALUE_FONTS = "fonts";
    public static final String COMMAND_VALUE_AUDIO = "audio";
    public static final String COMMAND_VALUE_ENABLE = "enable";
    public static final String COMMAND_VALUE_DISABLE = "disable";
    public static final String COMMAND_VALUE_PRIORITY = "priority";
    public static final String COMMAND_VALUE_COPY = "copy";
    public static final String COMMAND_VALUE_MOVE = "move";
    public static final String COMMAND_VALUE_DELETE = "delete";
    public static final String COMMAND_VALUE_PROFILE = "profile";

    private static IOverlayManager mOMS;
    private static IPackageManager mPM;

    private HandlerThread mWorker;
    private JobHandler mJobHandler;
    private MainHandler mMainHandler;
    private final List<Runnable> mJobQueue = new ArrayList<>(0);
    private long mLastJobTime;
    private boolean mIsRunning;

    @Override
    public void onCreate() {
        mWorker = new HandlerThread("BackgroundWorker", Process.THREAD_PRIORITY_BACKGROUND);
        mWorker.start();
        mJobHandler = new JobHandler(mWorker.getLooper());
        mMainHandler = new MainHandler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // verify identity
        if (!isCallerAuthorized(intent)) {
            log("caller not authorized, aborting");
            return START_NOT_STICKY;
        }

        // Don't run job if there is another running job
        mIsRunning = false;
        if (isProcessing()) mIsRunning = true;

        // filter out duplicate intents
        long jobTime = intent.getLongExtra(JOB_TIME_KEY, 1);
        if (jobTime == 1 || jobTime == mLastJobTime) {
            log("got empty jobtime or duplicate job time, aborting");
            return START_NOT_STICKY;
        }
        mLastJobTime = jobTime;

        // must have a primary command
        String command = intent.getStringExtra(PRIMARY_COMMAND_KEY);
        if (TextUtils.isEmpty(command)) {
            log("Got empty primary command, aborting");
            return START_NOT_STICKY;
        }

        // queue up the job

        List<Runnable> jobs_to_add = new ArrayList<>(0);

        log("Starting job with primary command " + command + " With job time " + jobTime);
        if (TextUtils.equals(command, COMMAND_VALUE_INSTALL)) {
            List<String> paths = intent.getStringArrayListExtra(INSTALL_LIST_KEY);
            for (String path : paths) {
                jobs_to_add.add(new Installer(path));
            }
        } else if (TextUtils.equals(command, COMMAND_VALUE_UNINSTALL)) {
            List<String> packages = intent.getStringArrayListExtra(UNINSTALL_LIST_KEY);
            for (String _package : packages) {
                jobs_to_add.add(new Remover(_package));
            }
            if (shouldRestartUi(packages)) jobs_to_add.add(new UiResetJob());
        } else if (TextUtils.equals(command, COMMAND_VALUE_RESTART_UI)) {
            jobs_to_add.add(new UiResetJob());
        } else if (TextUtils.equals(command, COMMAND_VALUE_CONFIGURATION_SHIM)) {
            jobs_to_add.add(new LocaleChanger(getApplicationContext(), mMainHandler));
        } else if (TextUtils.equals(command, COMMAND_VALUE_BOOTANIMATION)) {
            String fileName = intent.getStringExtra(BOOTANIMATION_FILE_NAME);
            if (TextUtils.isEmpty(fileName)) {
                jobs_to_add.add(new BootAnimationJob(true));
            } else {
                jobs_to_add.add(new BootAnimationJob(fileName));
            }
        } else if (TextUtils.equals(command, COMMAND_VALUE_FONTS)) {
            String pid = intent.getStringExtra(FONTS_PID);
            String fileName = intent.getStringExtra(FONTS_FILENAME);
            jobs_to_add.add(new FontsJob(pid, fileName));
            jobs_to_add.add(new UiResetJob());
        } else if (TextUtils.equals(command, COMMAND_VALUE_AUDIO)) {
            String pid = intent.getStringExtra(AUDIO_PID);
            String fileName = intent.getStringExtra(AUDIO_FILENAME);
            jobs_to_add.add(new SoundsJob(pid, fileName));
            jobs_to_add.add(new UiResetJob());
        } else if (TextUtils.equals(command, COMMAND_VALUE_ENABLE)) {
            List<String> packages = intent.getStringArrayListExtra(ENABLE_LIST_KEY);
            for (String _package : packages) {
                jobs_to_add.add(new Enabler(_package));
            }
            if (shouldRestartUi(packages)) jobs_to_add.add(new UiResetJob());
        } else if (TextUtils.equals(command, COMMAND_VALUE_DISABLE)) {
            List<String> packages = intent.getStringArrayListExtra(DISABLE_LIST_KEY);
            for (String _package : packages) {
                jobs_to_add.add(new Disabler(_package));
            }
            if (shouldRestartUi(packages)) jobs_to_add.add(new UiResetJob());
        } else if (TextUtils.equals(command, COMMAND_VALUE_PRIORITY)) {
            List<String> packages = intent.getStringArrayListExtra(PRIORITY_LIST_KEY);
            jobs_to_add.add(new PriorityJob(packages));
            if (shouldRestartUi(packages)) jobs_to_add.add(new UiResetJob());
        } else if (TextUtils.equals(command, COMMAND_VALUE_COPY)) {
            String source = intent.getStringExtra(SOURCE_FILE_KEY);
            String destination = intent.getStringExtra(DESTINATION_FILE_KEY);
            jobs_to_add.add(new CopyJob(source, destination));
        } else if (TextUtils.equals(command, COMMAND_VALUE_MOVE)) {
            String source = intent.getStringExtra(SOURCE_FILE_KEY);
            String destination = intent.getStringExtra(DESTINATION_FILE_KEY);
            jobs_to_add.add(new MoveJob(source, destination));
        } else if (TextUtils.equals(command, COMMAND_VALUE_DELETE)) {
            String dir = intent.getStringExtra(SOURCE_FILE_KEY);
            if (intent.getBooleanExtra(WITH_DELETE_PARENT_KEY, true)) {
                jobs_to_add.add(new DeleteJob(dir));
            } else {
                for (File child : new File(dir).listFiles()) {
                    jobs_to_add.add(new DeleteJob(child.getAbsolutePath()));
                }
            }
        } else if (TextUtils.equals(command, COMMAND_VALUE_PROFILE)) {
            List<String> enable = intent.getStringArrayListExtra(ENABLE_LIST_KEY);
            List<String> disable = intent.getStringArrayListExtra(DISABLE_LIST_KEY);
            String profile = intent.getStringExtra(PROFILE_NAME_KEY);
            jobs_to_add.add(new ProfileJob(profile, disable, enable));
        }

        if (jobs_to_add.size() > 0) {
            log("Adding new jobs to job queue");
            synchronized (mJobQueue) {
                mJobQueue.addAll(jobs_to_add);
            }
            if (!mJobHandler.hasMessages(JobHandler.MESSAGE_CHECK_QUEUE)) {
                mJobHandler.sendEmptyMessage(JobHandler.MESSAGE_CHECK_QUEUE);
            }
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {

    }

    private boolean isProcessing() {
        return mJobQueue.size() > 0;
    }

    private class LocalService extends Binder {
        public JobService getService() {
            return JobService.this;
        }
    }

    private static IOverlayManager getOMS() {
        if (mOMS == null) {
            mOMS = IOverlayManager.Stub.asInterface(
                    ServiceManager.getService("overlay"));
        }
        return mOMS;
    }

    private static IPackageManager getPM() {
        if (mPM == null) {
            mPM = IPackageManager.Stub.asInterface(
                    ServiceManager.getService("package"));
        }
        return mPM;
    }

    private void install(String path, IPackageInstallObserver2 observer) {
        try {
            getPM().installPackageAsUser(path, observer,
                    PackageManager.INSTALL_REPLACE_EXISTING,
                    null,
                    UserHandle.USER_SYSTEM);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void uninstall(String packageName, IPackageDeleteObserver observer) {
        try {
            getPM().deletePackageAsUser(packageName, observer, 0, UserHandle.USER_SYSTEM);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void switchOverlay(String packageName, boolean enable) {
        try {
            getOMS().setEnabled(packageName, enable, UserHandle.USER_SYSTEM, false);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private boolean isOverlayEnabled(String packageName) {
        boolean enabled = false;
        try {
            OverlayInfo info = getOMS().getOverlayInfo(packageName, UserHandle.USER_SYSTEM);
            if (info != null) {
                enabled = info.isEnabled();
            } else {
                log("info is null");
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return enabled;
    }

    private boolean shouldRestartUi(List<String> overlays) {
        for (String o : overlays) {
            if (o.startsWith("com.android.systemui")) return true;
        }
        return false;
    }

    private void copyFonts(String pid, String zipFileName) {
        // prepare local cache dir for font package assembly
        log("Copy Fonts - Package ID = " + pid + " filename = " + zipFileName);
        File cacheDir = new File(getCacheDir(), "/FontCache/");
        if (cacheDir.exists()) {
            IOUtils.deleteRecursive(cacheDir);
        }
        cacheDir.mkdir();

        // copy system fonts into our cache dir
        IOUtils.copyFolder("/system/fonts", cacheDir.getAbsolutePath());

        // append zip to filename since it is probably removed
        // for list presentation
        if (!zipFileName.endsWith(".zip")) {
            zipFileName = zipFileName + ".zip";
        }

        // copy target themed fonts zip to our cache dir
        Context themeContext = getAppContext(pid);
        AssetManager am = themeContext.getAssets();
        try {
            InputStream inputStream = am.open("fonts/" + zipFileName);
            OutputStream outputStream = new FileOutputStream(getCacheDir()
                    .getAbsolutePath() + "/FontCache/" + zipFileName);
            IOUtils.bufferedCopy(inputStream, outputStream);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // unzip new fonts and delete zip file, overwriting any system fonts
        File fontZip = new File(getCacheDir(), "/FontCache/" + zipFileName);
        IOUtils.unzip(fontZip.getAbsolutePath(), cacheDir.getAbsolutePath());
        fontZip.delete();

        // check if theme zip included a fonts.xml. If not, Substratum
        // is kind enough to provide one for us in it's assets
        try {
            File testConfig = new File(getCacheDir(), "/FontCache/" + "fonts.xml");
            if (!testConfig.exists()) {
                Context subContext = getSubsContext();
                AssetManager subsAm = subContext.getAssets();
                InputStream inputStream = subsAm.open("fonts.xml");
                OutputStream outputStream = new FileOutputStream(testConfig);
                IOUtils.bufferedCopy(inputStream, outputStream);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // prepare system theme fonts folder and copy new fonts folder from our cache
        IOUtils.deleteThemedFonts();
        IOUtils.createFontDirIfNotExists();
        IOUtils.copyFolder(cacheDir.getAbsolutePath(), IOUtils.SYSTEM_THEME_FONT_PATH);

        // let system know it's time for a font change
        refreshFonts();
    }

    private void clearFonts() {
        IOUtils.deleteThemedFonts();
        refreshFonts();
    }

    private void refreshFonts() {
        // set permissions on font files and config xml
        File themeFonts = new File(IOUtils.SYSTEM_THEME_FONT_PATH);
        if (themeFonts.exists()) {
            // Set permissions
            IOUtils.setPermissionsRecursive(themeFonts,
                    FileUtils.S_IRWXU | FileUtils.S_IRGRP | FileUtils.S_IRWXO,
                    FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IROTH | FileUtils.S_IXOTH);
        }

        // let system know it's time for a font change
        SystemProperties.set("sys.refresh_theme", "1");
        Typeface.recreateDefaults();
        float fontSize = Float.valueOf(Settings.System.getString(
                getContentResolver(), Settings.System.FONT_SCALE));
        Settings.System.putString(getContentResolver(),
                Settings.System.FONT_SCALE, String.valueOf(fontSize + 0.0000001));
    }

    private void applyThemedSounds(String pid, String zipFileName) {
        // prepare local cache dir for font package assembly
        log("Copy sounds - Package ID = " + pid + " filename = " + zipFileName);
        File cacheDir = new File(getCacheDir(), "/SoundsCache/");
        if (cacheDir.exists()) {
            IOUtils.deleteRecursive(cacheDir);
        }
        cacheDir.mkdir();

        // append zip to filename since it is probably removed
        // for list presentation
        if (!zipFileName.endsWith(".zip")) {
            zipFileName = zipFileName + ".zip";
        }

        // copy target themed sounds zip to our cache dir
        Context themeContext = getAppContext(pid);
        AssetManager am = themeContext.getAssets();
        try {
            InputStream inputStream = am.open("audio/" + zipFileName);
            OutputStream outputStream = new FileOutputStream(getCacheDir()
                    .getAbsolutePath() + "/SoundsCache/" + zipFileName);
            IOUtils.bufferedCopy(inputStream, outputStream);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // unzip new sounds and delete zip file
        File soundsZip = new File(getCacheDir(), "/SoundsCache/" + zipFileName);
        IOUtils.unzip(soundsZip.getAbsolutePath(), cacheDir.getAbsolutePath());
        soundsZip.delete();

        clearSounds(this);
        IOUtils.createAudioDirIfNotExists();

        File uiSoundsCache = new File(getCacheDir(), "/SoundsCache/ui/");
        if (uiSoundsCache.exists() && uiSoundsCache.isDirectory()) {
            IOUtils.createUiSoundsDirIfNotExists();
            File effect_tick_mp3 = new File(getCacheDir(), "/SoundsCache/ui/Effect_Tick.mp3");
            File effect_tick_ogg = new File(getCacheDir(), "/SoundsCache/ui/Effect_Tick.ogg");
            if (effect_tick_ogg.exists()) {
                IOUtils.bufferedCopy(effect_tick_ogg, new File(IOUtils.SYSTEM_THEME_UI_SOUNDS_PATH + File.separator + "Effect_Tick.ogg"));
            } else if (effect_tick_mp3.exists()) {
                IOUtils.bufferedCopy(effect_tick_mp3, new File(IOUtils.SYSTEM_THEME_UI_SOUNDS_PATH + File.separator + "Effect_Tick.mp3"));
            }
            File new_lock_mp3 = new File(getCacheDir(), "/SoundsCache/ui/Lock.mp3");
            File new_lock_ogg = new File(getCacheDir(), "/SoundsCache/ui/Lock.ogg");
            if (new_lock_ogg.exists()) {
                IOUtils.bufferedCopy(new_lock_ogg, new File(IOUtils.SYSTEM_THEME_UI_SOUNDS_PATH + File.separator + "Lock.ogg"));
            } else if (new_lock_mp3.exists()) {
                IOUtils.bufferedCopy(new_lock_mp3, new File(IOUtils.SYSTEM_THEME_UI_SOUNDS_PATH + File.separator + "Lock.mp3"));
            }
            File new_unlock_mp3 = new File(getCacheDir(), "/SoundsCache/ui/Unlock.mp3");
            File new_unlock_ogg = new File(getCacheDir(), "/SoundsCache/ui/Unlock.ogg");
            if (new_unlock_ogg.exists()) {
                IOUtils.bufferedCopy(new_unlock_ogg, new File(IOUtils.SYSTEM_THEME_UI_SOUNDS_PATH + File.separator + "Unlock.ogg"));
            } else if (new_unlock_mp3.exists()) {
                IOUtils.bufferedCopy(new_unlock_mp3, new File(IOUtils.SYSTEM_THEME_UI_SOUNDS_PATH + File.separator + "Unlock.mp3"));
            }
            File new_lowbattery_mp3 = new File(getCacheDir(), "/SoundsCache/ui/LowBattery.mp3");
            File new_lowbattery_ogg = new File(getCacheDir(), "/SoundsCache/ui/LowBattery.ogg");
            if (new_lowbattery_ogg.exists()) {
                IOUtils.bufferedCopy(new_lowbattery_ogg, new File(IOUtils.SYSTEM_THEME_UI_SOUNDS_PATH + File.separator + "LowBattery.ogg"));
            } else if (new_lowbattery_mp3.exists()) {
                IOUtils.bufferedCopy(new_lowbattery_mp3, new File(IOUtils.SYSTEM_THEME_UI_SOUNDS_PATH + File.separator + "LowBattery.mp3"));
            }
        }
        File alarmCache = new File(getCacheDir(), "/SoundsCache/alarms/");
        if (alarmCache.exists() && alarmCache.isDirectory()) {
            IOUtils.createAlarmDirIfNotExists();
            File new_alarm_mp3 = new File(getCacheDir(), "/SoundsCache/alarms/alarm.mp3");
            File new_alarm_ogg = new File(getCacheDir(), "/SoundsCache/alarms/alarm.ogg");
            if (new_alarm_ogg.exists()) {
                IOUtils.bufferedCopy(new_alarm_ogg, new File(IOUtils.SYSTEM_THEME_ALARM_PATH + File.separator + "alarm.ogg"));
            } else if (new_alarm_mp3.exists()) {
                IOUtils.bufferedCopy(new_alarm_mp3, new File(IOUtils.SYSTEM_THEME_ALARM_PATH + File.separator + "alarm.mp3"));
            }
        }
        File notifCache = new File(getCacheDir(), "/SoundsCache/notifications/");
        if (notifCache.exists() && notifCache.isDirectory()) {
            IOUtils.createNotificationDirIfNotExists();
            File new_notif_mp3 = new File(getCacheDir(), "/SoundsCache/notifications/notification.mp3");
            File new_notif_ogg = new File(getCacheDir(), "/SoundsCache/notifications/notification.ogg");
            if (new_notif_ogg.exists()) {
                IOUtils.bufferedCopy(new_notif_ogg, new File(IOUtils.SYSTEM_THEME_NOTIFICATION_PATH + File.separator + "notification.ogg"));
            } else if (new_notif_mp3.exists()) {
                IOUtils.bufferedCopy(new_notif_mp3, new File(IOUtils.SYSTEM_THEME_NOTIFICATION_PATH + File.separator + "notification.mp3"));
            }
        }
        File ringtoneCache = new File(getCacheDir(), "/SoundsCache/ringtones/");
        if (ringtoneCache.exists() && ringtoneCache.isDirectory()) {
            IOUtils.createRingtoneDirIfNotExists();
            File new_ring_mp3 = new File(getCacheDir(), "/SoundsCache/ringtones/ringtone.mp3");
            File new_ring_ogg = new File(getCacheDir(), "/SoundsCache/ringtones/ringtone.ogg");
            if (new_ring_ogg.exists()) {
                IOUtils.bufferedCopy(new_ring_ogg, new File(IOUtils.SYSTEM_THEME_RINGTONE_PATH + File.separator + "ringtone.ogg"));
            } else if (new_ring_mp3.exists()) {
                IOUtils.bufferedCopy(new_ring_mp3, new File(IOUtils.SYSTEM_THEME_RINGTONE_PATH + File.separator + "ringtone.mp3"));
            }
        }

        // let system know it's time for a sound change
        refreshSounds();
    }

    private void clearSounds(Context ctx) {
        IOUtils.deleteThemedAudio();
        SoundUtils.setDefaultAudible(JobService.this, RingtoneManager.TYPE_ALARM);
        SoundUtils.setDefaultAudible(JobService.this, RingtoneManager.TYPE_NOTIFICATION);
        SoundUtils.setDefaultAudible(JobService.this, RingtoneManager.TYPE_RINGTONE);
        SoundUtils.setDefaultUISounds(getContentResolver(), "lock_sound", "Lock.ogg");
        SoundUtils.setDefaultUISounds(getContentResolver(), "unlock_sound", "Unlock.ogg");
        SoundUtils.setDefaultUISounds(getContentResolver(), "low_battery_sound",
                "LowBattery.ogg");
    }

    private void refreshSounds () {
        File soundsDir = new File(IOUtils.SYSTEM_THEME_AUDIO_PATH);
        if (soundsDir.exists()) {
            // Set permissions
            IOUtils.setPermissionsRecursive(soundsDir,
                    FileUtils.S_IRWXU | FileUtils.S_IRGRP | FileUtils.S_IRWXO,
                    FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IROTH | FileUtils.S_IXOTH);

            File uiDir = new File(IOUtils.SYSTEM_THEME_UI_SOUNDS_PATH);
            if (uiDir.exists()) {
                File effect_tick_mp3 = new File(uiDir, "Effect_Tick.mp3");
                File effect_tick_ogg = new File(uiDir, "Effect_Tick.ogg");
                if (effect_tick_mp3.exists()) {
                    SoundUtils.setUIAudible(this, effect_tick_mp3,
                            effect_tick_mp3, RingtoneManager.TYPE_RINGTONE,
                            "Effect_Tick");
                } else if (effect_tick_ogg.exists()) {
                    SoundUtils.setUIAudible(this, effect_tick_ogg,
                            effect_tick_ogg, RingtoneManager.TYPE_RINGTONE,
                            "Effect_Tick");
                } else {
                    SoundUtils.setDefaultUISounds(getContentResolver(),
                            "Effect_Tick",
                            "Effect_Tick.ogg");
                }

                File lock_mp3 = new File(uiDir, "Lock.mp3");
                File lock_ogg = new File(uiDir, "Lock.ogg");
                if (lock_mp3.exists()) {
                    SoundUtils.setUISounds(getContentResolver(), "lock_sound",
                            lock_mp3.getAbsolutePath());
                } else if (lock_ogg.exists()) {
                    SoundUtils.setUISounds(getContentResolver(), "lock_sound",
                            lock_ogg.getAbsolutePath());
                } else {
                    SoundUtils.setDefaultUISounds(getContentResolver(),
                            "lock_sound", "Lock.ogg");
                }

                File unlock_mp3 = new File(uiDir, "Unlock.mp3");
                File unlock_ogg = new File(uiDir, "Unlock.ogg");
                if (unlock_mp3.exists()) {
                    SoundUtils.setUISounds(getContentResolver(), "unlock_sound",
                            unlock_mp3.getAbsolutePath());
                } else if (unlock_ogg.exists()) {
                    SoundUtils.setUISounds(getContentResolver(), "unlock_sound",
                            unlock_ogg.getAbsolutePath());
                } else {
                    SoundUtils.setDefaultUISounds(getContentResolver(),
                            "unlock_sound", "Unlock.ogg");
                }

                File lowbattery_mp3 = new File(uiDir, "LowBattery.mp3");
                File lowbattery_ogg = new File(uiDir, "LowBattery.ogg");
                if (lowbattery_mp3.exists()) {
                    SoundUtils.setUISounds(getContentResolver(), "low_battery_sound",
                            lowbattery_mp3.getAbsolutePath());
                } else if (lowbattery_ogg.exists()) {
                    SoundUtils.setUISounds(getContentResolver(), "low_battery_sound",
                            lowbattery_ogg.getAbsolutePath());
                } else {
                    SoundUtils.setDefaultUISounds(getContentResolver(),
                            "low_battery_sound", "LowBattery.ogg");
                }
            }

            File notifDir = new File(IOUtils.SYSTEM_THEME_NOTIFICATION_PATH);
            if (notifDir.exists()) {
                int metaDataId = getSubsContext().getResources().getIdentifier(
                        "content_resolver_notification_metadata",
                        "string", SUBSTRATUM_PACKAGE);

                File notification_mp3 = new File(notifDir, "notification.mp3");
                File notification_ogg = new File(notifDir, "notification.ogg");
                if (notification_mp3.exists()) {
                    SoundUtils.setAudible(this, notification_mp3,
                            notification_mp3,
                            RingtoneManager.TYPE_NOTIFICATION,
                            getSubsContext().getString(metaDataId));
                } else if (notification_ogg.exists()) {
                    SoundUtils.setAudible(this, notification_ogg,
                            notification_ogg,
                            RingtoneManager.TYPE_NOTIFICATION,
                            getSubsContext().getString(metaDataId));
                } else {
                    SoundUtils.setDefaultAudible(this,
                            RingtoneManager.TYPE_NOTIFICATION);
                }
            }

            File ringtoneDir = new File(IOUtils.SYSTEM_THEME_RINGTONE_PATH);
            if (ringtoneDir.exists()) {
                int metaDataId = getSubsContext().getResources().getIdentifier(
                        "content_resolver_notification_metadata",
                        "string", SUBSTRATUM_PACKAGE);

                File ringtone_mp3 = new File(ringtoneDir, "ringtone.mp3");
                File ringtone_ogg = new File(ringtoneDir, "ringtone.ogg");
                if (ringtone_mp3.exists()) {
                    SoundUtils.setAudible(this, ringtone_mp3,
                            ringtone_mp3,
                            RingtoneManager.TYPE_RINGTONE,
                            getSubsContext().getString(metaDataId));
                } else if (ringtone_ogg.exists()) {
                    SoundUtils.setAudible(this, ringtone_ogg,
                            ringtone_ogg,
                            RingtoneManager.TYPE_RINGTONE,
                            getSubsContext().getString(metaDataId));
                } else {
                    SoundUtils.setDefaultAudible(this,
                            RingtoneManager.TYPE_RINGTONE);
                }
            }
        }
    }
    private void copyBootAnimation(String fileName) {
        try {
            clearBootAnimation();
            File source = new File(fileName);
            File dest = new File(IOUtils.SYSTEM_THEME_BOOTANIMATION_PATH);
            IOUtils.bufferedCopy(source, dest);
            source.delete();
            IOUtils.setPermissions(dest,
                    FileUtils.S_IRWXU | FileUtils.S_IRGRP | FileUtils.S_IROTH);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void clearBootAnimation() {
        try {
            File f = new File(IOUtils.SYSTEM_THEME_BOOTANIMATION_PATH);
            if (f.exists()) {
                f.delete();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void restartUi() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            Class ActivityManagerNative = Class.forName("android.app.ActivityManagerNative");
            Method getDefault = ActivityManagerNative.getDeclaredMethod("getDefault", null);
            Object amn = getDefault.invoke(null, null);
            Method killApplicationProcess = amn.getClass().getDeclaredMethod("killApplicationProcess", String.class, int.class);
            stopService(new Intent().setComponent(new ComponentName("com.android.systemui", "com.android.systemui.SystemUIService")));
            am.killBackgroundProcesses("com.android.systemui");
            for (ActivityManager.RunningAppProcessInfo app : am.getRunningAppProcesses()) {
                if ("com.android.systemui".equals(app.processName)) {
                    killApplicationProcess.invoke(amn, app.processName, app.uid);
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void killPackage(String packageName) {
        try {
            ActivityManagerNative.getDefault().forceStopPackage(packageName,
                    UserHandle.USER_SYSTEM);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private Context getSubsContext() {
        return getAppContext(SUBSTRATUM_PACKAGE);
    }

    private Context getAppContext(String packageName) {
        Context ctx = null;
        try {
            ctx = getApplicationContext().createPackageContext(packageName,
                    Context.CONTEXT_IGNORE_SECURITY);
        } catch (NameNotFoundException e) {
            e.printStackTrace();
        }
        return ctx;
    }

    public static void log(String msg) {
        if (DEBUG) {
            Log.e(TAG, msg);
        }
    }

    private boolean isCallerAuthorized(Intent intent) {
        PendingIntent token = null;
        try {
            token = (PendingIntent) intent.getParcelableExtra(MASQUERADE_TOKEN);
        } catch (Exception e) {
            log("Attempt to start serivce without a token, unauthorized");
        }
        if (token == null) {
            return false;
        }
        // SECOND: we got a token, validate originating package
        // if not in our whitelist, return null
        String callingPackage = token.getCreatorPackage();
        boolean isValidPackage = false;
        for (int i = 0; i < AUTHORIZED_CALLERS.length; i++) {
            if (TextUtils.equals(callingPackage, AUTHORIZED_CALLERS[i])) {
                log(callingPackage
                        + " is an authorized calling package, next validate calling package perms");
                isValidPackage = true;
                break;
            }
        }
        if (!isValidPackage) {
            log(callingPackage + " is not an authorized calling package");
            return false;
        }
        return true;
    }

    private class MainHandler extends Handler {
        public static final int MSG_JOB_QUEUE_EMPTY = 1;

        public MainHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_JOB_QUEUE_EMPTY:
                    Intent intent = new Intent(INTENT_STATUS_CHANGED);
                    intent.putExtra(PRIMARY_COMMAND_KEY, COMMAND_VALUE_JOB_COMPLETE);
                    sendBroadcastAsUser(intent, UserHandle.ALL);
                    break;
            }
        }
    }

    private class JobHandler extends Handler {
        private static final int MESSAGE_CHECK_QUEUE = 1;
        private static final int MESSAGE_DEQUEUE = 2;

        public JobHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_CHECK_QUEUE:
                    Runnable job;
                    synchronized (mJobQueue) {
                        job = mJobQueue.get(0);
                    }
                    if (job != null && !mIsRunning) {
                        job.run();
                    }
                    break;
                case MESSAGE_DEQUEUE:
                    Runnable toRemove = (Runnable) msg.obj;
                    synchronized (mJobQueue) {
                        mJobQueue.remove(toRemove);
                        if (mJobQueue.size() > 0) {
                            mIsRunning = false;
                            this.sendEmptyMessage(MESSAGE_CHECK_QUEUE);
                        } else {
                            mIsRunning = false;
                            log("Job queue empty! All done");
                            mMainHandler.sendEmptyMessage(MainHandler.MSG_JOB_QUEUE_EMPTY);
                        }
                    }
                    break;
                default:
                    log("Unknown message " + msg.what);
                    break;
            }
        }
    }

    private class StopPackageJob implements Runnable {
        String mPackage;

        public void StopPackageJob(String _package) {
            mPackage = _package;
        }

        @Override
        public void run() {
            killPackage(mPackage);
            log("Killed package " + mPackage);
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE,
                    StopPackageJob.this);
            mJobHandler.sendMessage(message);
        }
    }

    private class UiResetJob implements Runnable {
        @Override
        public void run() {
            restartUi();
            log("Restarting SystemUI...");
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE,
                    UiResetJob.this);
            mJobHandler.sendMessage(message);
        }
    }

    private class FontsJob implements Runnable {
        boolean mClear;
        String mPid;
        String mFileName;

        public FontsJob(String pid, String fileName) {
            if (pid == null) {
                mClear = true;
            } else {
                mPid = pid;
                mFileName = fileName;
            }
        }

        @Override
        public void run() {
            if (mClear) {
                log("Resetting system font");
                clearFonts();
            } else {
                log("Setting theme font");
                copyFonts(mPid, mFileName);
            }
            Intent intent = new Intent(INTENT_STATUS_CHANGED);
            intent.putExtra(PRIMARY_COMMAND_KEY, COMMAND_VALUE_FONTS);
            sendBroadcastAsUser(intent, UserHandle.ALL);
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE,
                    FontsJob.this);
            mJobHandler.sendMessage(message);
        }
    }

    private class SoundsJob implements Runnable {
        boolean mClear;
        String mPid;
        String mFileName;

        public SoundsJob(String pid, String fileName) {
            if (pid == null) {
                mClear = true;
            } else {
                mPid = pid;
                mFileName = fileName;
            }
        }

        @Override
        public void run() {
            if (mClear) {
                log("Resetting system sounds");
                clearSounds(JobService.this);
            } else {
                log("Setting theme sounds");
                applyThemedSounds(mPid, mFileName);
            }
            Intent intent = new Intent(INTENT_STATUS_CHANGED);
            intent.putExtra(PRIMARY_COMMAND_KEY, COMMAND_VALUE_AUDIO);
            sendBroadcastAsUser(intent, UserHandle.ALL);
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE,
                    SoundsJob.this);
            mJobHandler.sendMessage(message);
        }
    }

    private class BootAnimationJob implements Runnable {
        String mFileName;
        final boolean mClear;

        public BootAnimationJob(boolean clear) {
            mClear = true;
        }

        public BootAnimationJob(String fileName) {
            mFileName = fileName;
            mClear = false;
        }

        @Override
        public void run() {
            if (mClear) {
                log("Resetting system boot animation");
                clearBootAnimation();
            } else {
                log("Setting themed boot animation");
                copyBootAnimation(mFileName);
            }
            Intent intent = new Intent(INTENT_STATUS_CHANGED);
            intent.putExtra(PRIMARY_COMMAND_KEY, COMMAND_VALUE_BOOTANIMATION);
            sendBroadcastAsUser(intent, UserHandle.ALL);
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE,
                    BootAnimationJob.this);
            mJobHandler.sendMessage(message);
        }
    }

    private class Installer implements Runnable {
        String mPath;

        public Installer(String path) {
            mPath = path;
        }

        @Override
        public void run() {
            log("Installer - installing " + mPath);
            PackageInstallObserver observer = new PackageInstallObserver(Installer.this);
            install(mPath, observer);
        }
    }

    private class PackageInstallObserver extends IPackageInstallObserver2.Stub {
        Object mObject;

        public PackageInstallObserver(Object _object) {
            mObject = _object;
        }

        public void onUserActionRequired(Intent intent) throws RemoteException {
            log("Installer - user action required callback");
        }

        public void onPackageInstalled(String packageName, int returnCode, String msg, Bundle extras) {
            log("Installer - successfully installed " + packageName);
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE, mObject);
            mJobHandler.sendMessage(message);
        }
    }

    private class Remover implements Runnable {
        String mPackage;

        public Remover(String _package) {
            mPackage = _package;
        }

        @Override
        public void run() {

            // TODO: Fix isOverlayEnabled function, for now it's causing NPE
            if (isOverlayEnabled(mPackage)) {
                log("Remover - disabling overlay for " + mPackage);
                switchOverlay(mPackage, false);
            }

            log("Remover - uninstalling " + mPackage);
            PackageDeleteObserver observer = new PackageDeleteObserver(Remover.this);
            uninstall(mPackage, observer);
        }
    }

    private class PackageDeleteObserver extends IPackageDeleteObserver.Stub {
        Object mObject;

        public PackageDeleteObserver(Object _object) {
            mObject = _object;
        }

        public void packageDeleted(String packageName, int returnCode) {
            log("Remover - successfully removed " + packageName);
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE, mObject);
            mJobHandler.sendMessage(message);
        }
    }

    private class Enabler implements Runnable {
        String mPackage;

        public Enabler(String _package) {
            mPackage = _package;
        }

        @Override
        public void run() {
            log("Enabler - enabling overlay for " + mPackage);
            switchOverlay(mPackage, true);
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE,
                    Enabler.this);
            mJobHandler.sendMessage(message);
        }
    }

    private class Disabler implements Runnable {
        String mPackage;

        public Disabler(String _package) {
            mPackage = _package;
        }

        @Override
        public void run() {
            log("Disabler - disabling overlay for " + mPackage);
            switchOverlay(mPackage, false);
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE,
                    Disabler.this);
            mJobHandler.sendMessage(message);
        }
    }

    private class PriorityJob implements Runnable {
        List<String> mPackages;

        public PriorityJob(List<String> _packages) {
            mPackages = _packages;
        }

        @Override
        public void run() {
            log("PriorityJob - processing priority changes");
            try {
                int size = mPackages.size();
                for (int i = 0; i < size-1; i++) {
                    String parentName = mPackages.get(i);
                    String packageName = mPackages.get(i+1);
                    getOMS().setPriority(packageName, parentName, UserHandle.USER_SYSTEM);
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE,
                    PriorityJob.this);
            mJobHandler.sendMessage(message);
        }
    }

    private class CopyJob implements Runnable {
        String mSource;
        String mDestination;

        public CopyJob(String _source, String _destination) {
            mSource = _source;
            mDestination = _destination;
        }

        @Override
        public void run() {
            log("CopyJob - copying " + mSource + " to " + mDestination);
            File sourceFile = new File(mSource);
            if (sourceFile.exists()) {
                if (sourceFile.isFile()) {
                    IOUtils.bufferedCopy(mSource, mDestination);
                } else {
                    IOUtils.copyFolder(mSource, mDestination);
                }
            } else {
                log("CopyJob - " + mSource + " is not exist! aborting...");
            }
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE,
                    CopyJob.this);
            mJobHandler.sendMessage(message);
        }
    }

    private class MoveJob implements Runnable {
        String mSource;
        String mDestination;

        public MoveJob(String _source, String _destination) {
            mSource = _source;
            mDestination = _destination;
        }

        @Override
        public void run() {
            log("MoveJob - moving " + mSource + " to " + mDestination);
            File sourceFile = new File(mSource);
            if (sourceFile.exists()) {
                if (sourceFile.isFile()) {
                    IOUtils.bufferedCopy(mSource, mDestination);
                } else {
                    IOUtils.copyFolder(mSource, mDestination);
                }
                IOUtils.deleteRecursive(sourceFile);
            } else {
                log("MoveJob - " + mSource + " is not exist! aborting...");
            }
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE,
                    MoveJob.this);
            mJobHandler.sendMessage(message);
        }
    }

    private class DeleteJob implements Runnable {
        String mFileOrDirectory;

        public DeleteJob(String _directory) {
            mFileOrDirectory = _directory;
        }

        @Override
        public void run() {
            log("DeleteJob - deleting " + mFileOrDirectory);
            File file = new File(mFileOrDirectory);
            if (file.exists()) {
                IOUtils.deleteRecursive(file);
            } else {
                log("DeleteJob - " + mFileOrDirectory + " is already deleted!");
            }
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE,
                    DeleteJob.this);
            mJobHandler.sendMessage(message);
        }
    }

    private class ProfileJob implements Runnable {
        String mProfileName;
        List<String> mToBeDisabled;
        List<String> mToBeEnabled;

        public ProfileJob(String _name, List<String> _toBeDisabled, List<String> _toBeEnabled) {
            mProfileName = _name;
            mToBeDisabled = _toBeDisabled;
            mToBeEnabled = _toBeEnabled;
        }

        @Override
        public void run() {
            boolean restartUi = false;
            log("Applying profile...");

            // Need to restart SystemUI?
            restartUi = shouldRestartUi(mToBeEnabled) || shouldRestartUi(mToBeDisabled);

            // Clear system theme folder content
            File themeDir = new File(IOUtils.SYSTEM_THEME_PATH);
            for (File f : themeDir.listFiles()) {
                IOUtils.deleteRecursive(f);
            }

            // Process theme folder
            File profileDir = new File(Environment.getExternalStorageDirectory()
                    .getAbsolutePath() + "/substratum/profiles/" +
                    mProfileName + "/theme");

            if (profileDir.exists()) {
                File profileFonts = new File(profileDir, "fonts");
                if (profileFonts.exists()) {
                    IOUtils.copyFolder(profileFonts, new File(IOUtils.SYSTEM_THEME_FONT_PATH));
                    refreshFonts();
                    restartUi = true;
                } else {
                    clearFonts();
                }

                File profileSounds = new File(profileDir, "audio");
                if (profileSounds.exists()) {
                    IOUtils.copyFolder(profileSounds, new File(IOUtils.SYSTEM_THEME_AUDIO_PATH));
                    refreshSounds();
                    restartUi = true;
                } else {
                    clearSounds(JobService.this);
                }
            }

            // Disable all overlays installed
            for (String overlay : mToBeDisabled) {
                switchOverlay(overlay, false);
            }

            // Enable provided overlays
            for (String overlay : mToBeEnabled) {
                switchOverlay(overlay, true);
            }

            // Restart SystemUI when needed
            if (restartUi) {
                synchronized(mJobQueue) {
                    mJobQueue.add(new UiResetJob());
                }
            }

            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE,
                    ProfileJob.this);
            mJobHandler.sendMessage(message);
        }
    }

    private class LocaleChanger extends BroadcastReceiver implements Runnable {
        private boolean mIsRegistered;
        private boolean mDoRestore;
        private Context mContext;
        private Handler mHandler;
        private Locale mCurrentLocale;

        public LocaleChanger(Context context, Handler mainHandler) {
            mContext = context;
            mHandler = mainHandler;
        }

        @Override
        public void run() {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            mContext.startActivity(i);
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    spoofLocale();
                }
            }, 500);
        }

        private void register() {
            if (!mIsRegistered) {
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
                mContext.registerReceiver(LocaleChanger.this, filter);
                mIsRegistered = true;
            }
        }

        private void unregister() {
            if (mIsRegistered) {
                mContext.unregisterReceiver(LocaleChanger.this);
                mIsRegistered = false;
            }
        }

        private void spoofLocale() {
            Configuration config;
            log("LocaleChanger - spoofing locale for configuation change shim");
            try {
                register();
                config = ActivityManagerNative.getDefault().getConfiguration();
                mCurrentLocale = config.locale;
                Locale toSpoof = Locale.JAPAN;
                if (mCurrentLocale == Locale.JAPAN) {
                    toSpoof = Locale.CHINA;
                }
                config.setLocale(toSpoof);
                config.userSetLocale = true;
                ActivityManagerNative.getDefault().updateConfiguration(config);
            } catch (RemoteException e) {
                e.printStackTrace();
                return;
            }
        }

        private void restoreLocale() {
            Configuration config;
            log("LocaleChanger - restoring original locale for configuation change shim");
            try {
                unregister();
                config = ActivityManagerNative.getDefault().getConfiguration();
                config.setLocale(mCurrentLocale);
                config.userSetLocale = true;
                ActivityManagerNative.getDefault().updateConfiguration(config);
            } catch (RemoteException e) {
                e.printStackTrace();
                return;
            }
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE,
                    LocaleChanger.this);
            mJobHandler.sendMessage(message);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    restoreLocale();
                }
            }, 500);
        }
    }

    private static class LocalIntentReceiver {
        private final SynchronousQueue<Intent> mResult = new SynchronousQueue<>();

        private IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType,
                    IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
                try {
                    mResult.offer(intent, 5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        public IntentSender getIntentSender() {
            return new IntentSender((IIntentSender) mLocalSender);
        }

        public Intent getResult() {
            try {
                return mResult.take();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
