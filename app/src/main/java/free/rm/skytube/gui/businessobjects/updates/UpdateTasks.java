package free.rm.skytube.gui.businessobjects.updates;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.Objects;

import free.rm.skytube.BuildConfig;
import free.rm.skytube.R;
import free.rm.skytube.app.SkyTubeApp;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class UpdateTasks {
    private static final String TAG = UpdateTasks.class.getSimpleName();

    private UpdateTasks() { }

    /**
     * A task that will check if any SkyTube updates have been published.  If there are, then the
     * user will be notified.
     *
     * @param activity                  The activity from which the update check is performed.
     * @param displayUpToDateMessage    If set to true, it will display the "SkyTube is up to date"
     *                                  message; otherwise the message will not be displayed.
     */
    public static Disposable checkForUpdates(@NonNull Activity activity, boolean displayUpToDateMessage) {
        final String currentVersionNumber = BuildConfig.FLAVOR.equalsIgnoreCase("extra")
                ? BuildConfig.VERSION_NAME.split("\\s+")[0] : BuildConfig.VERSION_NAME;
        final String displayedReleaseNotesVersion = SkyTubeApp.getSettings().getDisplayedReleaseNoteTag();
        final boolean showReleaseNotes = !Objects.equals(currentVersionNumber, displayedReleaseNotesVersion)
                && !displayUpToDateMessage;
        final CompositeDisposable compositeDisposable = new CompositeDisposable();
        compositeDisposable.add(
                Single.fromCallable(() -> {
                    Log.d(TAG, String.format("Current Version Number: %s, last displayed: %s, show release notes: %s",
                            currentVersionNumber, displayedReleaseNotesVersion, showReleaseNotes));
                    UpdatesChecker updatesChecker = new UpdatesChecker(showReleaseNotes, currentVersionNumber);
                    updatesChecker.checkForUpdates();
                    return updatesChecker;
                })
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(updatesChecker -> {
                            // if there is an update available...
                            if (updatesChecker.isUpdateAvailable() && updatesChecker.getLatestApkUrl() != null) {
                                // ask the user whether he wants to update or not
                                new AlertDialog.Builder(activity)
                                        .setTitle(R.string.update_available)
                                        .setMessage(String.format(activity.getString(R.string.update_dialog_msg), updatesChecker.getLatestApkVersion()))
                                        .setPositiveButton(R.string.update, (dialog, which) ->
                                                compositeDisposable.add(upgradeApp(updatesChecker.getLatestApkUrl(), activity))
                                        )
                                        .setNegativeButton(R.string.later, null)
                                        .show();
                            } else if (displayUpToDateMessage) {
                                // inform the user that there is no update available (app is up-to-date)
                                new AlertDialog.Builder(activity)
                                        .setTitle(R.string.up_to_date)
                                        .setMessage(R.string.up_to_date_msg)
                                        .setNeutralButton(R.string.ok, null)
                                        .show();
                            } else if (showReleaseNotes && updatesChecker.getReleaseNotes() != null) {
                                new AlertDialog.Builder(activity)
                                        .setTitle(String.format(activity.getString(R.string.release_notes),
                                                updatesChecker.getLatestApkVersion()))
                                        .setMessage(updatesChecker.getReleaseNotes())
                                        .setNeutralButton(R.string.ok, (dialog, button) ->
                                                SkyTubeApp.getSettings().setDisplayedReleaseNoteTag(currentVersionNumber))
                                        .show();
                            }
                        })
        );
        return compositeDisposable;
    }

    /**
     * This task will download the remote APK file and install it for the user (provided that the user
     * accepts the installation).
     */
    private static Disposable upgradeApp(@NonNull URL apkUrl, @NonNull Activity activity) {
        // setup the download dialog and display it
        final File apkDir = activity.getCacheDir();
        final ProgressDialog downloadDialog = new ProgressDialog(activity);
        downloadDialog.setMessage(activity.getString(R.string.downloading));
        downloadDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        downloadDialog.setProgress(0);
        downloadDialog.setMax(100);
        downloadDialog.setCancelable(false);
        downloadDialog.setProgressNumberFormat(null);
        downloadDialog.show();

        return Single.fromCallable(() -> {
            // get all previously downloaded APK files
            File[] apkFiles = apkDir.listFiles((dir, filename) -> filename.endsWith(".apk"));

            // delete the previously downloaded APK files
            if (apkFiles != null) {
                for (File apkFile : apkFiles) {
                    if (apkFile.delete()) {
                        Log.i(TAG, "Deleted " + apkFile.getAbsolutePath());
                    } else {
                        Log.e(TAG, "Cannot delete " + apkFile.getAbsolutePath());
                    }
                }
            }

            File apkFile = File.createTempFile("skytube-upgrade", ".apk", apkDir);

            // set the APK file to readable to every user so that this file can be read by Android's
            // package manager program
            apkFile.setReadable(true /*set file to readable*/,
                    false /*set readable to every user on the system*/);
            try (WebStream webStream = new WebStream(apkUrl);
                 OutputStream out = new FileOutputStream(apkFile)) {
                // download the file by transferring bytes from in to out
                byte[] buf = new byte[1024];
                int totalBytesRead = 0;
                for (int bytesRead; (bytesRead = webStream.getStream().read(buf)) > 0; ) {
                    out.write(buf, 0, bytesRead);

                    // update the progressbar of the downloadDialog
                    totalBytesRead += bytesRead;
                    int finalTotalBytesRead = totalBytesRead;
                    activity.runOnUiThread(() -> {
                        float percentageDownloaded = (finalTotalBytesRead / (float) webStream.getStreamSize()) * 100f;

                        downloadDialog.setProgress((int) percentageDownloaded);
                    });
                }
            }

            return apkFile;
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(apkFile -> {
                    // hide the download dialog
                    downloadDialog.dismiss();
                    Uri apkFileURI = (android.os.Build.VERSION.SDK_INT >= 24)
                            ? FileProvider.getUriForFile(activity,
                            activity.getApplicationContext().getPackageName() + ".provider",
                            apkFile)  // we now need to call FileProvider.getUriForFile() due to security changes in Android 7.0+
                            : Uri.fromFile(apkFile);
                    Intent intent = new Intent(Intent.ACTION_VIEW);

                    intent.setDataAndType(apkFileURI, "application/vnd.android.package-archive");
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK /* asks the user to open the newly updated app */
                            | Intent.FLAG_GRANT_READ_URI_PERMISSION /* to avoid a crash due to security changes in Android 7.0+ */);
                    activity.startActivity(intent);
                }, throwable -> {
                    // hide the download dialog
                    downloadDialog.dismiss();
                    Log.e(TAG, "Unable to upgrade app", throwable);
                    Toast.makeText(activity, R.string.update_failure, Toast.LENGTH_LONG).show();
                });
    }
}
