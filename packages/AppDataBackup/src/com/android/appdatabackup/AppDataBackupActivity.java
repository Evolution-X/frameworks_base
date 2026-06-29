/*
 * Copyright (C) 2026 VoltageOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.appdatabackup;

import android.app.Activity;
import android.app.appbackup.AppBackupInfo;
import android.app.appbackup.AppDataBackupRestoreManager;
import android.app.appbackup.BackupRecord;
import android.app.appbackup.BackupResult;
import android.app.appbackup.IBackupProgressCallback;
import android.app.appbackup.IRestoreProgressCallback;
import android.content.pm.PackageManager;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingtoolbar.FloatingToolbarLayout;
import com.google.android.material.loadingindicator.LoadingIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppDataBackupActivity extends Activity {

    private static final String TAG = "AppDataBackupUI";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG)
            || Log.isLoggable(TAG, Log.VERBOSE);

    private AppDataBackupRestoreManager mManager;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private File mBackupDir;
    private String mCurrentOperationToken;

    private TabLayout mTabLayout;
    private ViewPager2 mViewPager;
    private FloatingToolbarLayout mFloatingToolbar;
    private MaterialButton mBackupBtn;
    private MaterialButton mExcludeCacheBtn;
    private LoadingIndicator mLoadingIndicator;
    private LinearProgressIndicator mProgressBar;
    private TextView mProgressText;
    private int mBackupTotal;
    private int mBackupDone;
    private long mBackupBytesTotal;
    private long mBackupBytesDone;
    private TextView mSummaryCount;
    private TextView mSummarySize;
    private RecyclerView mAppsRv;
    private MaterialCardView mSummaryCard;
    private int mLastSummaryCount = 0;
    private int mCardColorDefault;
    private int mCardColorSelected;
    private int mTextColorDefault;
    private int mTextColorVariant;
    private int mTextColorSelected;

    private final List<AppBackupInfo> mApps = new ArrayList<>();
    private final Set<String> mSelectedPackages = new HashSet<>();
    private final List<BackupRecord> mBackups = new ArrayList<>();

    private AppListAdapter mAppAdapter;
    private BackupListAdapter mBackupAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_backup);

        mManager = (AppDataBackupRestoreManager)
                getSystemService(APP_DATA_BACKUP_SERVICE);

        mBackupDir = new File("/data/media/" + UserHandle.myUserId() + "/AppDataBackup");

        mTabLayout = findViewById(R.id.tab_layout);
        mViewPager = findViewById(R.id.view_pager);
        mFloatingToolbar = findViewById(R.id.floating_toolbar);
        mBackupBtn = findViewById(R.id.btn_backup);
        mExcludeCacheBtn = findViewById(R.id.btn_exclude_cache);
        mLoadingIndicator = findViewById(R.id.loading_indicator);
        mProgressBar = findViewById(R.id.progress_bar);
        mProgressText = findViewById(R.id.progress_text);
        mSummaryCount = findViewById(R.id.tv_summary_count);
        mSummarySize = findViewById(R.id.tv_summary_size);
        mSummaryCard = findViewById(R.id.summary_card);

        mCardColorDefault = themeColor(com.google.android.material.R.attr.colorSurfaceVariant);
        mCardColorSelected = themeColor(com.google.android.material.R.attr.colorPrimaryContainer);
        mTextColorDefault = themeColor(com.google.android.material.R.attr.colorOnSurface);
        mTextColorVariant = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant);
        mTextColorSelected = themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer);
        setupHeroSurfaces();

        setupFloatingToolbar();
        setupViewPager();
        updateSummary();
        loadAppsAsync();
        loadBackupsAsync();
    }

    private void setupFloatingToolbar() {
        findViewById(R.id.btn_select_all).setOnClickListener(v -> selectAll());
        findViewById(R.id.btn_clear).setOnClickListener(v -> clearSelection());
        mBackupBtn.setOnClickListener(v -> {
            if (mSelectedPackages.isEmpty()) {
                Toast.makeText(this, R.string.select_at_least_one, Toast.LENGTH_SHORT).show();
                return;
            }
            startBackup();
        });
    }

    private void selectAll() {
        for (AppBackupInfo info : mApps) mSelectedPackages.add(info.getPackageName());
        if (mAppAdapter != null) mAppAdapter.notifyDataSetChanged();
        updateSummary();
    }

    private void clearSelection() {
        mSelectedPackages.clear();
        if (mAppAdapter != null) mAppAdapter.notifyDataSetChanged();
        updateSummary();
    }

    private void setupViewPager() {
        mAppsRv = new RecyclerView(this);
        mAppsRv.setLayoutManager(new LinearLayoutManager(this));
        mAppsRv.setClipToPadding(false);
        mAppsRv.setPadding(0, dp(4),
                0, getResources().getDimensionPixelSize(R.dimen.list_bottom_inset));
        mAppsRv.setLayoutAnimation(AnimationUtils.loadLayoutAnimation(
                this, R.anim.layout_animation_fall_down));
        mAppAdapter = new AppListAdapter();
        mAppsRv.setAdapter(mAppAdapter);

        final RecyclerView backupsRv = new RecyclerView(this);
        backupsRv.setLayoutManager(new LinearLayoutManager(this));
        backupsRv.setClipToPadding(false);
        backupsRv.setPadding(0, dp(4), 0, dp(24));
        backupsRv.setLayoutAnimation(AnimationUtils.loadLayoutAnimation(
                this, R.anim.layout_animation_fall_down));
        mBackupAdapter = new BackupListAdapter();
        backupsRv.setAdapter(mBackupAdapter);

        mViewPager.setAdapter(new TabPagerAdapter(mAppsRv, backupsRv));

        new TabLayoutMediator(mTabLayout, mViewPager, (tab, position) -> {
            if (position == 0) {
                tab.setText(R.string.tab_apps);
                tab.setIcon(R.drawable.ic_apps_24);
            } else {
                tab.setText(R.string.tab_backups);
                tab.setIcon(R.drawable.ic_archive_24);
            }
        }).attach();

        mViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                mFloatingToolbar.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void updateSummary() {
        final int n = mSelectedPackages.size();
        long total = 0;
        for (AppBackupInfo info : mApps) {
            if (mSelectedPackages.contains(info.getPackageName())) {
                total += Math.max(0, info.getDataSize());
            }
        }
        if (n == 0) {
            mSummaryCount.setText(R.string.summary_empty_count);
            mSummarySize.setText(R.string.summary_empty_size);
            mBackupBtn.setText(R.string.action_back_up);
        } else {
            mSummarySize.setText(getString(R.string.summary_size, formatBytes(total)));
            mBackupBtn.setText(getString(R.string.fab_backup_count, n));
            animateSummaryCount(mLastSummaryCount, n);
        }
        if (n != mLastSummaryCount) popSummaryCard();
        mLastSummaryCount = n;
    }

    private int themeColor(int attr) {
        return MaterialColors.getColor(this, attr, Color.MAGENTA);
    }

    private void setupHeroSurfaces() {
        final int surface = themeColor(com.google.android.material.R.attr.colorPrimaryContainer);

        final View summaryGradient = findViewById(R.id.summary_gradient);
        if (summaryGradient != null) {
            final GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.RECTANGLE);
            g.setCornerRadius(dp(20));
            g.setColor(surface);
            summaryGradient.setBackground(g);
        }
        if (mSummaryCard != null) {
            mSummaryCard.setCardBackgroundColor(Color.TRANSPARENT);
        }
    }

    private void animateSummaryCount(int from, int to) {
        final ValueAnimator anim = ValueAnimator.ofInt(from, to);
        anim.setDuration(420);
        anim.addUpdateListener(a -> {
            final int v = (int) a.getAnimatedValue();
            mSummaryCount.setText(v == 1
                    ? getString(R.string.summary_count_one)
                    : getString(R.string.summary_count, v));
        });
        anim.start();
    }

    private void popSummaryCard() {
        if (mSummaryCard == null) return;
        mSummaryCard.animate().cancel();
        mSummaryCard.setScaleX(0.97f);
        mSummaryCard.setScaleY(0.97f);
        mSummaryCard.animate()
                .scaleX(1f).scaleY(1f)
                .setInterpolator(new DecelerateInterpolator())
                .setDuration(220)
                .start();
    }

    private void springTap(View v) {
        v.animate().cancel();
        v.setScaleX(0.94f);
        v.setScaleY(0.94f);
        v.animate().scaleX(1f).scaleY(1f)
                .setInterpolator(new DecelerateInterpolator())
                .setDuration(200)
                .start();
    }

    private void loadAppsAsync() {
        mLoadingIndicator.setVisibility(View.VISIBLE);
        mExecutor.submit(() -> {
            final List<AppBackupInfo> apps = mManager.getInstalledApps();
            if (DEBUG) {
                Log.d(TAG, "Loaded " + apps.size() + " apps for backup UI");
            }
            mMainHandler.post(() -> {
                mApps.clear();
                mApps.addAll(apps);
                mAppAdapter.notifyDataSetChanged();
                mLoadingIndicator.setVisibility(View.GONE);
                if (mAppsRv != null) mAppsRv.scheduleLayoutAnimation();
                updateSummary();
            });
        });
    }

    private void loadBackupsAsync() {
        mExecutor.submit(() -> {
            mBackupDir.mkdirs();
            final List<BackupRecord> backups =
                    mManager.getAvailableBackups(mBackupDir.getAbsolutePath());
            if (DEBUG) {
                Log.d(TAG, "Loaded " + backups.size() + " backups from " + mBackupDir);
            }
            mMainHandler.post(() -> {
                mBackups.clear();
                mBackups.addAll(backups);
                mBackupAdapter.notifyDataSetChanged();
            });
        });
    }

    private void startBackup() {
        final View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_backup_options, null, false);
        final TextInputEditText passInput = dialogView.findViewById(R.id.et_passphrase);
        final TextInputEditText keepInput = dialogView.findViewById(R.id.et_keep);
        final Chip apkBox = dialogView.findViewById(R.id.chip_apk);
        final Chip ceBox = dialogView.findViewById(R.id.chip_ce);
        final Chip deBox = dialogView.findViewById(R.id.chip_de);
        final Chip extBox = dialogView.findViewById(R.id.chip_ext);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.backup_options_title)
                .setView(dialogView)
                .setPositiveButton(R.string.action_back_up, (d, w) -> {
                    int components = 0;
                    if (apkBox.isChecked()) components |= AppDataBackupRestoreManager.COMPONENT_APK;
                    if (ceBox.isChecked()) components |= AppDataBackupRestoreManager.COMPONENT_CE_DATA;
                    if (deBox.isChecked()) components |= AppDataBackupRestoreManager.COMPONENT_DE_DATA;
                    if (extBox.isChecked()) components |= AppDataBackupRestoreManager.COMPONENT_EXTERNAL;
                    if (components == 0) {
                        Toast.makeText(this, R.string.select_one_component,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int keep = 0;
                    final String keepText = keepInput.getText() != null
                            ? keepInput.getText().toString().trim() : "";
                    if (!keepText.isEmpty()) {
                        try {
                            keep = Integer.parseInt(keepText);
                        } catch (NumberFormatException e) {
                            keep = 0;
                        }
                    }
                    final String pass = passInput.getText() != null
                            ? passInput.getText().toString() : "";
                    doBackup(pass.isEmpty() ? null : pass, components, keep);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void doBackup(String passphrase, int components, int keepVersions) {
        final boolean excludeCache = mExcludeCacheBtn != null
                && mExcludeCacheBtn.isChecked();
        showProgress("Preparing backup...");

        mCurrentOperationToken = mManager.backupPackages(
                new ArrayList<>(mSelectedPackages),
                mBackupDir.getAbsolutePath(),
                excludeCache,
                new IBackupProgressCallback.Stub() {
                    @Override
                    public void onBackupStarted(String token, int total) {
                        beginDeterminateProgress(total);
                        updateProgress("Backing up 0 / " + total + " apps...");
                    }

                    @Override
                    public void onPackageBackupStarted(String token, String pkg,
                            int idx, int total) {
                        updateProgress("Backing up " + pkg + " (" + idx + "/" + total + ")");
                    }

                    @Override
                    public void onPackageBackupFinished(String token, String pkg,
                            BackupResult result) {
                        if (!result.isSuccess()) {
                            Log.w(TAG, "Backup failed for " + pkg + ": " + result.getMessage());
                        }
                        advanceDeterminateProgress(pkg);
                    }

                    @Override
                    public void onBackupFinished(String token, BackupResult result) {
                        mMainHandler.post(() -> {
                            hideProgress();
                            Toast.makeText(AppDataBackupActivity.this,
                                    result.isSuccess() ? "Backup complete" : result.getMessage(),
                                    Toast.LENGTH_LONG).show();
                            loadBackupsAsync();
                        });
                    }

                    @Override
                    public void onBackupCancelled(String token) {
                        mMainHandler.post(() -> {
                            hideProgress();
                            Toast.makeText(AppDataBackupActivity.this,
                                    "Backup cancelled", Toast.LENGTH_SHORT).show();
                        });
                    }
                }, passphrase, components, keepVersions);
    }

    private void cancelCurrentOperation() {
        if (mCurrentOperationToken != null) {
            mManager.cancelOperation(mCurrentOperationToken);
        }
    }

    private void startRestore(BackupRecord record) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.confirm_restore_title, record.getLabel()))
                .setMessage(R.string.confirm_restore_message)
                .setPositiveButton(R.string.restore, (d, w) -> confirmRestorePassphrase(record))
                .setNeutralButton(R.string.verify, (d, w) -> verifyBackup(record))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void verifyBackup(BackupRecord record) {
        if (!record.isEncrypted()) {
            doVerify(record, null);
            return;
        }
        final View view = buildPassphraseView();
        final TextInputEditText input = view.findViewById(R.id.et_passphrase);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Verify encrypted backup")
                .setMessage("This backup is encrypted. Enter its passphrase to verify.")
                .setView(view)
                .setPositiveButton(R.string.verify, (d, w) -> {
                    final String pass = input.getText() != null
                            ? input.getText().toString() : "";
                    doVerify(record, pass.isEmpty() ? null : pass);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void doVerify(BackupRecord record, String passphrase) {
        showProgress("Verifying " + record.getLabel() + "...");
        mExecutor.submit(() -> {
            final String error = mManager.verifyBackup(
                    record.getId(), record.getBackupDir(), passphrase);
            mMainHandler.post(() -> {
                hideProgress();
                Toast.makeText(AppDataBackupActivity.this,
                        error == null ? "Backup is valid" : ("Invalid: " + error),
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private void confirmRestorePassphrase(BackupRecord record) {
        if (!record.isEncrypted()) {
            doRestore(record, null);
            return;
        }
        final View view = buildPassphraseView();
        final TextInputEditText input = view.findViewById(R.id.et_passphrase);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Encrypted backup")
                .setMessage("This backup is encrypted. Enter its passphrase to restore.")
                .setView(view)
                .setPositiveButton(R.string.restore, (d, w) -> {
                    final String pass = input.getText() != null
                            ? input.getText().toString() : "";
                    if (pass.isEmpty()) {
                        Toast.makeText(this, "Passphrase required",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    doRestore(record, pass);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private View buildPassphraseView() {
        final View view = LayoutInflater.from(this)
                .inflate(R.layout.dialog_backup_options, null, false);
        view.findViewById(R.id.til_keep).setVisibility(View.GONE);
        view.findViewById(R.id.chip_group_components).setVisibility(View.GONE);
        return view;
    }

    private void doRestore(BackupRecord record, String passphrase) {
        showProgress("Restoring " + record.getLabel() + "...");

        final List<String> ids = java.util.Collections.singletonList(record.getId());
        mCurrentOperationToken = mManager.restorePackages(
                ids,
                record.getBackupDir(),
                new IRestoreProgressCallback.Stub() {
                    @Override
                    public void onRestoreStarted(String token, int total) {}

                    @Override
                    public void onPackageRestoreStarted(String token, String pkg,
                            int idx, int total) {
                        updateProgress("Installing APK for " + pkg + "...");
                        setDeterminateProgress(idx, total);
                    }

                    @Override
                    public void onPackageDataRestoring(String token, String pkg) {
                        updateProgress("Restoring data for " + pkg + "...");
                    }

                    @Override
                    public void onPackageRestoreFinished(String token, String pkg,
                            BackupResult result) {}

                    @Override
                    public void onRestoreFinished(String token, BackupResult result) {
                        mMainHandler.post(() -> {
                            hideProgress();
                            Toast.makeText(AppDataBackupActivity.this,
                                    result.isSuccess()
                                            ? "Restore complete - relaunch the app"
                                            : "Restore failed: " + result.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        });
                    }

                    @Override
                    public void onRestoreCancelled(String token) {
                        mMainHandler.post(() -> {
                            hideProgress();
                            Toast.makeText(AppDataBackupActivity.this,
                                    "Restore cancelled", Toast.LENGTH_SHORT).show();
                        });
                    }
                }, passphrase);
    }

    private void showProgress(String message) {
        mMainHandler.post(() -> {
            mProgressBar.setVisibility(View.VISIBLE);
            mProgressBar.setIndeterminate(true);
            mProgressText.setVisibility(View.VISIBLE);
            mProgressText.setText(message);
            mBackupBtn.setEnabled(false);
        });
    }

    private void updateProgress(String message) {
        mMainHandler.post(() -> mProgressText.setText(message));
    }

    private void beginDeterminateProgress(int total) {
        mMainHandler.post(() -> {
            long bytes = 0;
            for (AppBackupInfo info : mApps) {
                if (mSelectedPackages.contains(info.getPackageName())) {
                    bytes += Math.max(0, info.getDataSize());
                }
            }
            mBackupTotal = total;
            mBackupDone = 0;
            mBackupBytesTotal = bytes;
            mBackupBytesDone = 0;
            if (mProgressBar.isIndeterminate()) mProgressBar.setIndeterminate(false);
            mProgressBar.setProgressCompat(0, false);
        });
    }

    private void advanceDeterminateProgress(String pkg) {
        mMainHandler.post(() -> {
            long size = 0;
            for (AppBackupInfo info : mApps) {
                if (info.getPackageName().equals(pkg)) {
                    size = Math.max(0, info.getDataSize());
                    break;
                }
            }
            mBackupDone++;
            mBackupBytesDone += size;
            final int pct;
            if (mBackupBytesTotal > 0) {
                pct = (int) Math.max(0, Math.min(100,
                        Math.round(mBackupBytesDone * 100f / mBackupBytesTotal)));
            } else if (mBackupTotal > 0) {
                pct = Math.max(0, Math.min(100,
                        Math.round(mBackupDone * 100f / mBackupTotal)));
            } else {
                pct = 0;
            }
            if (mProgressBar.isIndeterminate()) mProgressBar.setIndeterminate(false);
            mProgressBar.setProgressCompat(pct, true);
        });
    }

    private void setDeterminateProgress(int idx, int total) {
        if (total <= 0) return;
        final int pct = Math.max(0, Math.min(100, Math.round(idx * 100f / total)));
        mMainHandler.post(() -> {
            if (mProgressBar.isIndeterminate()) mProgressBar.setIndeterminate(false);
            mProgressBar.setProgressCompat(pct, true);
        });
    }

    private void hideProgress() {
        mProgressBar.setVisibility(View.GONE);
        mProgressText.setVisibility(View.GONE);
        mBackupBtn.setEnabled(true);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void bindAvatar(FrameLayout box, ImageView icon, TextView initial,
            String label, String key) {
        final Drawable appIcon = loadAppIcon(key);
        if (appIcon != null) {
            box.setBackground(null);
            initial.setVisibility(View.GONE);
            icon.setImageDrawable(appIcon);
            icon.setVisibility(View.VISIBLE);
            return;
        }
        icon.setVisibility(View.GONE);
        icon.setImageDrawable(null);
        initial.setVisibility(View.VISIBLE);
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(14));
        bg.setColor(themeColor(com.google.android.material.R.attr.colorPrimaryContainer));
        box.setBackground(bg);
        final String text = (label == null || label.isEmpty())
                ? "?" : label.substring(0, 1).toUpperCase(Locale.getDefault());
        initial.setText(text);
    }

    private Drawable loadAppIcon(String packageName) {
        if (packageName == null || packageName.isEmpty()) return null;
        try {
            return getPackageManager().getApplicationIcon(packageName);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private final class AppListAdapter
            extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final AppBackupInfo info = mApps.get(position);
            final boolean selected = mSelectedPackages.contains(info.getPackageName());
            holder.label.setText(info.getLabel());
            holder.pkg.setText(info.getPackageName() + "  v" + info.getVersionName());
            holder.dataSize.setText(formatBytes(info.getDataSize()));
            bindAvatar(holder.avatarBox, holder.icon, holder.avatar, info.getLabel(), info.getPackageName());
            holder.checkbox.setOnCheckedChangeListener(null);
            holder.checkbox.setChecked(selected);
            holder.card.setStrokeWidth(selected ? dp(2) : 0);
            holder.card.setCardBackgroundColor(selected ? mCardColorSelected : mCardColorDefault);
            holder.label.setTextColor(selected ? mTextColorSelected : mTextColorDefault);
            holder.pkg.setTextColor(selected ? mTextColorSelected : mTextColorVariant);
            holder.checkbox.setOnCheckedChangeListener((btn, checked) ->
                    toggle(info, holder, checked));
            holder.itemView.setOnClickListener(v ->
                    toggle(info, holder, !holder.checkbox.isChecked()));
        }

        private void toggle(AppBackupInfo info, ViewHolder holder, boolean checked) {
            if (checked) mSelectedPackages.add(info.getPackageName());
            else mSelectedPackages.remove(info.getPackageName());
            holder.checkbox.setChecked(checked);
            holder.card.setStrokeWidth(checked ? dp(2) : 0);
            holder.card.setCardBackgroundColor(checked ? mCardColorSelected : mCardColorDefault);
            holder.label.setTextColor(checked ? mTextColorSelected : mTextColorDefault);
            holder.pkg.setTextColor(checked ? mTextColorSelected : mTextColorVariant);
            springTap(holder.card);
            updateSummary();
        }

        @Override
        public int getItemCount() { return mApps.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            MaterialCardView card;
            FrameLayout avatarBox;
            ImageView icon;
            TextView avatar, label, pkg, dataSize;
            MaterialCheckBox checkbox;
            ViewHolder(View v) {
                super(v);
                card = (MaterialCardView) v;
                avatarBox = v.findViewById(R.id.avatar_box);
                icon = v.findViewById(R.id.img_avatar);
                avatar = v.findViewById(R.id.tv_avatar);
                label = v.findViewById(R.id.tv_label);
                pkg = v.findViewById(R.id.tv_package);
                dataSize = v.findViewById(R.id.tv_data_size);
                checkbox = v.findViewById(R.id.checkbox);
            }
        }
    }

    private final class BackupListAdapter
            extends RecyclerView.Adapter<BackupListAdapter.ViewHolder> {

        private final SimpleDateFormat mDateFormat =
                new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault());

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_backup, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final BackupRecord record = mBackups.get(position);
            holder.label.setText(record.getLabel());
            holder.pkg.setText(record.getPackageName());
            bindAvatar(holder.avatarBox, holder.icon, holder.avatar, record.getLabel(), record.getPackageName());
            holder.version.setText("v" + record.getVersionName());
            holder.date.setText(mDateFormat.format(new Date(record.getTimestampMs())));
            holder.size.setText(formatBytes(record.getTotalSize()));
            holder.contents.setText(buildContents(record));
            holder.btnRestore.setOnClickListener(v -> startRestore(record));
            holder.btnVerify.setOnClickListener(v -> verifyBackup(record));
            holder.btnDelete.setOnClickListener(v -> confirmDelete(record));
        }

        @Override
        public int getItemCount() { return mBackups.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            FrameLayout avatarBox;
            ImageView icon;
            TextView avatar, label, pkg, version, date, size, contents;
            MaterialButton btnRestore, btnVerify, btnDelete;
            ViewHolder(View v) {
                super(v);
                avatarBox = v.findViewById(R.id.avatar_box);
                icon = v.findViewById(R.id.img_avatar);
                avatar = v.findViewById(R.id.tv_avatar);
                label = v.findViewById(R.id.tv_label);
                pkg = v.findViewById(R.id.tv_package);
                version = v.findViewById(R.id.tv_version);
                date = v.findViewById(R.id.tv_date);
                size = v.findViewById(R.id.tv_size);
                contents = v.findViewById(R.id.tv_contents);
                btnRestore = v.findViewById(R.id.btn_restore);
                btnVerify = v.findViewById(R.id.btn_verify);
                btnDelete = v.findViewById(R.id.btn_delete);
            }
        }

        private void confirmDelete(BackupRecord record) {
            new MaterialAlertDialogBuilder(AppDataBackupActivity.this)
                    .setTitle(R.string.confirm_delete_title)
                    .setMessage(getString(R.string.confirm_delete_message, record.getLabel()))
                    .setPositiveButton(R.string.delete, (d, w) -> {
                        mExecutor.submit(() -> {
                            mManager.deleteBackup(record.getId(), record.getBackupDir());
                            mMainHandler.post(() -> loadBackupsAsync());
                        });
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }
    }

    private static final class TabPagerAdapter extends RecyclerView.Adapter<TabPagerAdapter.VH> {

        private final RecyclerView[] mPages;

        TabPagerAdapter(RecyclerView... pages) { mPages = pages; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final RecyclerView page = mPages[viewType];
            page.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            return new VH(page);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {}

        @Override
        public int getItemCount() { return mPages.length; }

        @Override
        public int getItemViewType(int position) { return position; }

        static class VH extends RecyclerView.ViewHolder {
            VH(RecyclerView rv) { super(rv); }
        }
    }

    private String buildContents(BackupRecord record) {
        final int components = record.getComponents();
        final List<String> parts = new ArrayList<>();
        if ((components & AppDataBackupRestoreManager.COMPONENT_APK) != 0) {
            parts.add(getString(R.string.content_apk));
        }
        if ((components & AppDataBackupRestoreManager.COMPONENT_CE_DATA) != 0) {
            parts.add(getString(R.string.content_app_data));
        }
        if ((components & AppDataBackupRestoreManager.COMPONENT_DE_DATA) != 0) {
            parts.add(getString(R.string.content_device_data));
        }
        if ((components & AppDataBackupRestoreManager.COMPONENT_EXTERNAL) != 0) {
            parts.add(getString(R.string.content_external));
        }
        parts.add(getString(R.string.content_permissions));
        String includes = getString(R.string.content_includes,
                TextUtils.join("  \u00b7  ", parts));
        if (record.isEncrypted()) {
            includes += "  \u00b7  " + getString(R.string.content_encrypted);
        }
        return includes;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024)
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
