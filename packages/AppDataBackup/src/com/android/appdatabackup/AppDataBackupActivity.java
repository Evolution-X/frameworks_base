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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.UserHandle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
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
import androidx.recyclerview.widget.ItemTouchHelper;
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
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private View mProgressRow;
    private TextView mProgressText;
    private FloatingToolbarLayout mBackupsToolbar;
    private MaterialButton mRestoreSelectedBtn;
    private MaterialButton mDeleteSelectedBtn;
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
    private final Set<String> mSelectedBackupIds = new HashSet<>();
    private final List<AppBackupInfo> mVisibleApps = new ArrayList<>();
    private final Map<String, Long> mLastBackupByPkg = new HashMap<>();
    private final Set<String> mNoBackupPkgs = new HashSet<>();
    private final Set<String> mCorruptedBackupIds = new HashSet<>();
    private final List<String> mOpResults = new ArrayList<>();
    private final SimpleDateFormat mMetaDateFormat =
            new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault());
    private String mAppQuery = "";
    private boolean mSortBySize = false;

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
        mProgressRow = findViewById(R.id.progress_row);
        mProgressText = findViewById(R.id.progress_text);
        mBackupsToolbar = findViewById(R.id.floating_toolbar_backups);
        mRestoreSelectedBtn = findViewById(R.id.btn_restore_selected);
        mDeleteSelectedBtn = findViewById(R.id.btn_delete_selected);
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
        setupBackupsToolbar();
        setupSearchAndSort();
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

    private void setupBackupsToolbar() {
        findViewById(R.id.btn_select_all_backups).setOnClickListener(v -> selectAllBackups());
        findViewById(R.id.btn_verify_all).setOnClickListener(v -> startVerifyAll());
        findViewById(R.id.btn_clear_backups).setOnClickListener(v -> clearBackupSelection());
        findViewById(R.id.btn_cancel_op).setOnClickListener(v -> cancelCurrentOperation());
        mDeleteSelectedBtn.setOnClickListener(v -> {
            if (mSelectedBackupIds.isEmpty()) {
                Toast.makeText(this, R.string.select_at_least_one_backup,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            confirmDeleteSelected();
        });
        mRestoreSelectedBtn.setOnClickListener(v -> {
            if (mSelectedBackupIds.isEmpty()) {
                Toast.makeText(this, R.string.select_at_least_one_backup,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            startBatchRestore();
        });
    }

    private void selectAll() {
        for (AppBackupInfo info : mVisibleApps) mSelectedPackages.add(info.getPackageName());
        if (mAppAdapter != null) mAppAdapter.notifyDataSetChanged();
        updateSummary();
    }

    private void selectAllBackups() {
        for (BackupRecord record : mBackups) mSelectedBackupIds.add(record.getId());
        if (mBackupAdapter != null) mBackupAdapter.notifyDataSetChanged();
        updateBackupSelectionUi();
    }

    private void clearBackupSelection() {
        mSelectedBackupIds.clear();
        if (mBackupAdapter != null) mBackupAdapter.notifyDataSetChanged();
        updateBackupSelectionUi();
    }

    private void updateBackupSelectionUi() {
        final int n = mSelectedBackupIds.size();
        mRestoreSelectedBtn.setText(n == 0
                ? getString(R.string.restore)
                : getString(R.string.fab_restore_count, n));
    }

    private void setupSearchAndSort() {
        final TextInputEditText search = findViewById(R.id.et_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence text, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable text) {
                mAppQuery = text == null
                        ? "" : text.toString().trim().toLowerCase(Locale.getDefault());
                applyAppFilter();
            }
        });
        findViewById(R.id.btn_sort).setOnClickListener(v -> {
            mSortBySize = !mSortBySize;
            Toast.makeText(this, mSortBySize ? R.string.sort_by_size : R.string.sort_by_name,
                    Toast.LENGTH_SHORT).show();
            applyAppFilter();
        });
    }

    private void applyAppFilter() {
        mVisibleApps.clear();
        for (AppBackupInfo info : mApps) {
            if (mAppQuery.isEmpty()
                    || info.getLabel().toLowerCase(Locale.getDefault()).contains(mAppQuery)
                    || info.getPackageName().toLowerCase(Locale.getDefault())
                            .contains(mAppQuery)) {
                mVisibleApps.add(info);
            }
        }
        if (mSortBySize) {
            mVisibleApps.sort((a, b) -> Long.compare(b.getDataSize(), a.getDataSize()));
        } else {
            mVisibleApps.sort((a, b) -> a.getLabel().compareToIgnoreCase(b.getLabel()));
        }
        if (mAppAdapter != null) mAppAdapter.notifyDataSetChanged();
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

        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv,
                    @NonNull RecyclerView.ViewHolder vh,
                    @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                final int pos = vh.getBindingAdapterPosition();
                if (pos < 0 || pos >= mBackups.size()) return;
                swipeDeleteBackup(pos);
            }
        }).attachToRecyclerView(backupsRv);

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
                mBackupsToolbar.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
                findViewById(R.id.search_row).setVisibility(
                        position == 0 ? View.VISIBLE : View.GONE);
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
            final Set<String> optOut = new HashSet<>();
            for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0)) {
                if ((ai.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) == 0) {
                    optOut.add(ai.packageName);
                }
            }
            if (DEBUG) {
                Log.d(TAG, "Loaded " + apps.size() + " apps for backup UI");
            }
            mMainHandler.post(() -> {
                mApps.clear();
                mApps.addAll(apps);
                mNoBackupPkgs.clear();
                mNoBackupPkgs.addAll(optOut);
                applyAppFilter();
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
                final Set<String> ids = new HashSet<>();
                mLastBackupByPkg.clear();
                for (BackupRecord r : backups) {
                    ids.add(r.getId());
                    final Long prev = mLastBackupByPkg.get(r.getPackageName());
                    if (prev == null || r.getTimestampMs() > prev) {
                        mLastBackupByPkg.put(r.getPackageName(), r.getTimestampMs());
                    }
                }
                mSelectedBackupIds.retainAll(ids);
                mCorruptedBackupIds.retainAll(ids);
                mBackupAdapter.notifyDataSetChanged();
                if (mAppAdapter != null) mAppAdapter.notifyDataSetChanged();
                updateBackupSelectionUi();
            });
        });
    }

    private void startBackup() {
        final View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_backup_options, null, false);
        final TextInputEditText passInput = dialogView.findViewById(R.id.et_passphrase);
        final TextInputEditText passConfirmInput =
                dialogView.findViewById(R.id.et_passphrase_confirm);
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
                    final String confirm = passConfirmInput.getText() != null
                            ? passConfirmInput.getText().toString() : "";
                    if (!pass.equals(confirm)) {
                        Toast.makeText(this, R.string.passphrase_mismatch,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    passInput.setText("");
                    passConfirmInput.setText("");
                    checkSpaceAndBackup(pass.isEmpty() ? null : pass, components, keep);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void checkSpaceAndBackup(String passphrase, int components, int keepVersions) {
        long needed = 0;
        for (AppBackupInfo info : mApps) {
            if (mSelectedPackages.contains(info.getPackageName())) {
                needed += Math.max(0, info.getDataSize());
            }
        }
        long free = 0;
        try {
            free = new StatFs("/data").getAvailableBytes();
        } catch (Exception e) {
            Log.w(TAG, "statfs failed", e);
        }
        if (free > 0 && needed > free) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.not_enough_space_title)
                    .setMessage(getString(R.string.not_enough_space_message,
                            formatBytes(needed), formatBytes(free)))
                    .setPositiveButton(R.string.backup_anyway,
                            (d, w) -> doBackup(passphrase, components, keepVersions))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        doBackup(passphrase, components, keepVersions);
    }

    private void doBackup(String passphrase, int components, int keepVersions) {
        final boolean excludeCache = mExcludeCacheBtn != null
                && mExcludeCacheBtn.isChecked();
        synchronized (mOpResults) {
            mOpResults.clear();
        }
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
                        recordOpResult(pkg, result);
                        advanceDeterminateProgress(pkg);
                    }

                    @Override
                    public void onBackupFinished(String token, BackupResult result) {
                        mMainHandler.post(() -> {
                            hideProgress();
                            showOpResults(R.string.results_title_backup, result.isSuccess(),
                                    result.isSuccess() ? "Backup complete" : result.getMessage());
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
        promptRestoreOptions(java.util.Collections.singletonList(record));
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

    private View buildPassphraseView() {
        final View view = LayoutInflater.from(this)
                .inflate(R.layout.dialog_backup_options, null, false);
        view.findViewById(R.id.til_passphrase_confirm).setVisibility(View.GONE);
        view.findViewById(R.id.til_keep).setVisibility(View.GONE);
        view.findViewById(R.id.chip_group_components).setVisibility(View.GONE);
        return view;
    }

    private void startBatchRestore() {
        final List<BackupRecord> selected = new ArrayList<>();
        for (BackupRecord record : mBackups) {
            if (mSelectedBackupIds.contains(record.getId())) selected.add(record);
        }
        promptRestoreOptions(selected);
    }

    private void promptRestoreOptions(List<BackupRecord> records) {
        if (records.isEmpty()) return;
        boolean anyEncrypted = false;
        for (BackupRecord r : records) {
            if (r.isEncrypted()) {
                anyEncrypted = true;
                break;
            }
        }
        final boolean encrypted = anyEncrypted;
        final View view = LayoutInflater.from(this)
                .inflate(R.layout.dialog_backup_options, null, false);
        view.findViewById(R.id.til_keep).setVisibility(View.GONE);
        view.findViewById(R.id.til_passphrase_confirm).setVisibility(View.GONE);
        if (!encrypted) view.findViewById(R.id.til_passphrase).setVisibility(View.GONE);
        final TextInputEditText passInput = view.findViewById(R.id.et_passphrase);
        final Chip apkBox = view.findViewById(R.id.chip_apk);
        final Chip ceBox = view.findViewById(R.id.chip_ce);
        final Chip deBox = view.findViewById(R.id.chip_de);
        final Chip extBox = view.findViewById(R.id.chip_ext);

        final List<String> ids = new ArrayList<>();
        for (BackupRecord r : records) ids.add(r.getId());
        final String dir = records.get(0).getBackupDir();

        final StringBuilder message =
                new StringBuilder(getString(R.string.confirm_restore_message));
        final String downgrades = downgradeWarning(records);
        if (downgrades != null) {
            message.append("\n\n")
                    .append(getString(R.string.restore_downgrade_warning, downgrades));
        }
        final String title = records.size() == 1
                ? getString(R.string.confirm_restore_title, records.get(0).getLabel())
                : getString(R.string.confirm_restore_selected_title, records.size());

        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setView(view)
                .setPositiveButton(R.string.restore, (d, w) -> {
                    int components = 0;
                    if (apkBox.isChecked()) {
                        components |= AppDataBackupRestoreManager.COMPONENT_APK;
                    }
                    if (ceBox.isChecked()) {
                        components |= AppDataBackupRestoreManager.COMPONENT_CE_DATA;
                    }
                    if (deBox.isChecked()) {
                        components |= AppDataBackupRestoreManager.COMPONENT_DE_DATA;
                    }
                    if (extBox.isChecked()) {
                        components |= AppDataBackupRestoreManager.COMPONENT_EXTERNAL;
                    }
                    if (components == 0) {
                        Toast.makeText(this, R.string.select_one_component,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final String pass = passInput.getText() != null
                            ? passInput.getText().toString() : "";
                    if (encrypted && pass.isEmpty()) {
                        Toast.makeText(this, R.string.passphrase_required,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    passInput.setText("");
                    doRestoreIds(ids, dir, pass.isEmpty() ? null : pass, components);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String downgradeWarning(List<BackupRecord> records) {
        final StringBuilder sb = new StringBuilder();
        for (BackupRecord r : records) {
            try {
                final long installed = getPackageManager()
                        .getPackageInfo(r.getPackageName(), 0).getLongVersionCode();
                if (installed > r.getVersionCode()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(r.getLabel());
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private void startVerifyAll() {
        if (mBackups.isEmpty()) {
            Toast.makeText(this, R.string.select_at_least_one_backup,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        boolean anyEncrypted = false;
        for (BackupRecord r : mBackups) {
            if (r.isEncrypted()) {
                anyEncrypted = true;
                break;
            }
        }
        if (!anyEncrypted) {
            doVerifyAll(null);
            return;
        }
        final View view = buildPassphraseView();
        final TextInputEditText input = view.findViewById(R.id.et_passphrase);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.verify_all)
                .setMessage(R.string.verify_all_message)
                .setView(view)
                .setPositiveButton(R.string.verify, (d, w) -> {
                    final String pass = input.getText() != null
                            ? input.getText().toString() : "";
                    doVerifyAll(pass.isEmpty() ? null : pass);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void doVerifyAll(String passphrase) {
        final List<BackupRecord> records = new ArrayList<>(mBackups);
        showProgress(getString(R.string.verify_all));
        mExecutor.submit(() -> {
            int valid = 0;
            int failed = 0;
            int skipped = 0;
            final Set<String> corrupted = new HashSet<>();
            for (BackupRecord r : records) {
                if (r.isEncrypted() && passphrase == null) {
                    skipped++;
                    continue;
                }
                updateProgress(getString(R.string.verify_all) + ": " + r.getLabel());
                final String error = mManager.verifyBackup(r.getId(), r.getBackupDir(),
                        r.isEncrypted() ? passphrase : null);
                if (error == null) {
                    valid++;
                } else {
                    failed++;
                    corrupted.add(r.getId());
                }
            }
            final int validCount = valid;
            final int failedCount = failed;
            final int skippedCount = skipped;
            mMainHandler.post(() -> {
                hideProgress();
                mCorruptedBackupIds.clear();
                mCorruptedBackupIds.addAll(corrupted);
                mBackupAdapter.notifyDataSetChanged();
                Toast.makeText(AppDataBackupActivity.this,
                        getString(R.string.verify_all_result,
                                validCount, failedCount, skippedCount),
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private void swipeDeleteBackup(int position) {
        final BackupRecord record = mBackups.remove(position);
        mSelectedBackupIds.remove(record.getId());
        mBackupAdapter.notifyItemRemoved(position);
        updateBackupSelectionUi();
        final Snackbar snackbar = Snackbar.make(findViewById(android.R.id.content),
                getString(R.string.deleted_backup, record.getLabel()), Snackbar.LENGTH_LONG);
        snackbar.setAction(R.string.undo, v -> {
            final int pos = Math.min(position, mBackups.size());
            mBackups.add(pos, record);
            mBackupAdapter.notifyItemInserted(pos);
        });
        snackbar.addCallback(new Snackbar.Callback() {
            @Override
            public void onDismissed(Snackbar sb, int event) {
                if (event == DISMISS_EVENT_ACTION) return;
                mExecutor.submit(() ->
                        mManager.deleteBackup(record.getId(), record.getBackupDir()));
            }
        });
        snackbar.show();
    }

    private void exportBackup(BackupRecord record) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.export)
                .setMessage(getString(R.string.export_message, record.getLabel()))
                .setPositiveButton(R.string.export, (d, w) -> {
                    mExecutor.submit(() -> {
                        final String path = mManager.exportBackup(record.getId());
                        mMainHandler.post(() -> Toast.makeText(AppDataBackupActivity.this,
                                path != null ? getString(R.string.export_done, path)
                                        : getString(R.string.export_failed),
                                Toast.LENGTH_LONG).show());
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void recordOpResult(String pkg, BackupResult result) {
        synchronized (mOpResults) {
            mOpResults.add((result.isSuccess() ? "\u2713 " : "\u2717 ") + pkg
                    + (result.isSuccess() ? "" : " \u2014 " + result.getMessage()));
        }
    }

    private void showOpResults(int titleRes, boolean allOk, String fallbackToast) {
        final List<String> lines;
        synchronized (mOpResults) {
            lines = new ArrayList<>(mOpResults);
        }
        if (allOk || lines.isEmpty()) {
            Toast.makeText(this, fallbackToast, Toast.LENGTH_LONG).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setMessage(TextUtils.join("\n", lines))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void confirmDeleteSelected() {
        final int n = mSelectedBackupIds.size();
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.confirm_delete_selected_title, n))
                .setMessage(R.string.confirm_delete_selected_message)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    final List<String> ids = new ArrayList<>(mSelectedBackupIds);
                    mExecutor.submit(() -> {
                        for (String id : ids) {
                            mManager.deleteBackup(id, mBackupDir.getAbsolutePath());
                        }
                        mMainHandler.post(() -> {
                            mSelectedBackupIds.clear();
                            updateBackupSelectionUi();
                            loadBackupsAsync();
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void doRestoreIds(List<String> ids, String backupDir, String passphrase,
            int components) {
        synchronized (mOpResults) {
            mOpResults.clear();
        }
        showProgress(getString(R.string.progress_restoring));

        mCurrentOperationToken = mManager.restorePackages(
                ids,
                backupDir,
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
                            BackupResult result) {
                        recordOpResult(pkg, result);
                    }

                    @Override
                    public void onRestoreFinished(String token, BackupResult result) {
                        mMainHandler.post(() -> {
                            hideProgress();
                            showOpResults(R.string.results_title_restore, result.isSuccess(),
                                    result.isSuccess()
                                            ? "Restore complete - relaunch the app"
                                            : "Restore failed: " + result.getMessage());
                            mSelectedBackupIds.clear();
                            updateBackupSelectionUi();
                            if (mBackupAdapter != null) mBackupAdapter.notifyDataSetChanged();
                            loadAppsAsync();
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
                }, passphrase, components);
    }

    private void showProgress(String message) {
        mMainHandler.post(() -> {
            mProgressBar.setVisibility(View.VISIBLE);
            mProgressBar.setIndeterminate(true);
            mProgressRow.setVisibility(View.VISIBLE);
            mProgressText.setText(message);
            mBackupBtn.setEnabled(false);
            mRestoreSelectedBtn.setEnabled(false);
            mDeleteSelectedBtn.setEnabled(false);
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
        mProgressRow.setVisibility(View.GONE);
        mBackupBtn.setEnabled(true);
        mRestoreSelectedBtn.setEnabled(true);
        mDeleteSelectedBtn.setEnabled(true);
        mCurrentOperationToken = null;
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
            final AppBackupInfo info = mVisibleApps.get(position);
            final boolean selected = mSelectedPackages.contains(info.getPackageName());
            holder.label.setText(info.getLabel());
            holder.pkg.setText(info.getPackageName() + "  v" + info.getVersionName());
            holder.dataSize.setText(formatBytes(info.getDataSize()));
            final Long lastMs = mLastBackupByPkg.get(info.getPackageName());
            String meta = lastMs != null
                    ? getString(R.string.last_backup, mMetaDateFormat.format(new Date(lastMs)))
                    : getString(R.string.last_backup_never);
            if (mNoBackupPkgs.contains(info.getPackageName())) {
                meta += "  \u00b7  " + getString(R.string.backup_opt_out);
            }
            holder.lastBackup.setText(meta);
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
        public int getItemCount() { return mVisibleApps.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            MaterialCardView card;
            FrameLayout avatarBox;
            ImageView icon;
            TextView avatar, label, pkg, dataSize, lastBackup;
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
                lastBackup = v.findViewById(R.id.tv_last_backup);
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
            if (mCorruptedBackupIds.contains(record.getId())) {
                holder.contents.setText("\u26a0 " + getString(R.string.verify_failed_badge)
                        + "  \u00b7  " + buildContents(record));
                holder.contents.setTextColor(
                        themeColor(android.R.attr.colorError));
            } else {
                holder.contents.setText(buildContents(record));
                holder.contents.setTextColor(mTextColorVariant);
            }
            final boolean selected = mSelectedBackupIds.contains(record.getId());
            holder.checkbox.setOnCheckedChangeListener(null);
            holder.checkbox.setChecked(selected);
            holder.card.setStrokeWidth(selected ? dp(2) : 0);
            holder.checkbox.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) mSelectedBackupIds.add(record.getId());
                else mSelectedBackupIds.remove(record.getId());
                holder.card.setStrokeWidth(checked ? dp(2) : 0);
                springTap(holder.card);
                updateBackupSelectionUi();
            });
            holder.btnRestore.setOnClickListener(v -> startRestore(record));
            holder.itemView.setOnLongClickListener(v -> {
                exportBackup(record);
                return true;
            });
            holder.btnVerify.setOnClickListener(v -> verifyBackup(record));
            holder.btnDelete.setOnClickListener(v -> confirmDelete(record));
        }

        @Override
        public int getItemCount() { return mBackups.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            MaterialCardView card;
            FrameLayout avatarBox;
            ImageView icon;
            TextView avatar, label, pkg, version, date, size, contents;
            MaterialCheckBox checkbox;
            MaterialButton btnRestore, btnVerify, btnDelete;
            ViewHolder(View v) {
                super(v);
                card = (MaterialCardView) v;
                checkbox = v.findViewById(R.id.checkbox_backup);
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
