package com.foldshot.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Encrypted Offline Hidden Vault Activity:
 * - Protected by WindowManager.LayoutParams.FLAG_SECURE (blocks screenshots & Recents previews).
 * - Requires ephemeral unlock token from PIN verification and auto-locks on background.
 * - Supports decrypt-in-memory viewing, Copy, Restore to Gallery, Delete Forever, and .foldvault backup/restore.
 */
public class HiddenVaultActivity extends Activity {

    public static final String EXTRA_UNLOCK_TOKEN = "extra_vault_unlock_token";
    private static final int REQ_EXPORT_VAULT = 401;
    private static final int REQ_IMPORT_VAULT = 402;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private float dp;
    private boolean suppressAutoLockForPicker = false;
    private String pendingBackupPassphrase = null;
    private LinearLayout rootContent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        super.onCreate(savedInstanceState);

        String token = getIntent() != null ? getIntent().getStringExtra(EXTRA_UNLOCK_TOKEN) : null;
        if (!HiddenVaultManager.consumeUnlockToken(token)) {
            finishAndRemoveTask();
            return;
        }

        dp = getResources().getDisplayMetrics().density;
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF000000);
        scroll.setFillViewport(true);

        rootContent = new LinearLayout(this);
        rootContent.setOrientation(LinearLayout.VERTICAL);
        int pad = dpInt(16);
        rootContent.setPadding(pad, dpInt(42), pad, dpInt(40));
        scroll.addView(rootContent);
        setContentView(scroll);

        renderVaultUi();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!suppressAutoLockForPicker && !isFinishing()) {
            finishAndRemoveTask();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void renderVaultUi() {
        if (rootContent == null) return;
        rootContent.removeAllViews();

        List<HiddenVaultManager.VaultItem> items = HiddenVaultManager.listVaultItems(this);

        // Header row
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        TextView title = new TextView(this);
        title.setText("Hidden Vault");
        title.setTextColor(0xFFF5F5F7);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        titleCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText(items.size() + (items.size() == 1 ? " encrypted item" : " encrypted items") + " • AES-256 Offline");
        sub.setTextColor(0xFF8E8E93);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        titleCol.addView(sub);

        header.addView(titleCol, tLp);

        TextView lockBtn = makePillButton("Lock", 0xFF2B2B31, Color.WHITE, v -> finishAndRemoveTask());
        header.addView(lockBtn);
        rootContent.addView(header);

        // Encrypted Backup / Restore action strip
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        actLp.topMargin = dpInt(14);
        actLp.bottomMargin = dpInt(18);

        TextView backupBtn = makePillButton("Backup (.foldvault)", 0xFF1C2942, 0xFF6BA5FF, v -> promptBackupPassphraseAndExport());
        TextView restoreBtn = makePillButton("Restore (.foldvault)", 0xFF1F2229, 0xFFE4E4E7, v -> launchImportArchivePicker());

        LinearLayout.LayoutParams b1Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b1Lp.rightMargin = dpInt(8);
        LinearLayout.LayoutParams b2Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        actionRow.addView(backupBtn, b1Lp);
        actionRow.addView(restoreBtn, b2Lp);
        rootContent.addView(actionRow, actLp);

        if (items.isEmpty()) {
            LinearLayout emptyCard = new LinearLayout(this);
            emptyCard.setOrientation(LinearLayout.VERTICAL);
            emptyCard.setBackground(roundedRect(0xFF17171A, 22f, 1, 0xFF26262C));
            emptyCard.setPadding(dpInt(22), dpInt(28), dpInt(22), dpInt(28));

            TextView emptyTitle = new TextView(this);
            emptyTitle.setText("Vault is empty");
            emptyTitle.setTextColor(0xFFF5F5F7);
            emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            emptyTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            emptyCard.addView(emptyTitle);

            TextView emptyBody = new TextView(this);
            emptyBody.setText("When taking a screenshot:\n1. Tap the expand button on the left of your folders.\n2. Hold the minimize arrow for 3 seconds.\n\nFoldShot encrypts the full-resolution image directly here and deletes the Gallery copy.");
            emptyBody.setTextColor(0xFF8E8E93);
            emptyBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            LinearLayout.LayoutParams ebLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            ebLp.topMargin = dpInt(8);
            emptyCard.addView(emptyBody, ebLp);

            rootContent.addView(emptyCard);
            return;
        }

        int screenWidthDp = Math.round(getResources().getDisplayMetrics().widthPixels / Math.max(1f, dp));
        int cols = screenWidthDp >= 560 ? 3 : 2;
        SimpleDateFormat sdf = new SimpleDateFormat("MMM d, HH:mm", Locale.US);

        for (int i = 0; i < items.size(); i += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rLp.bottomMargin = dpInt(10);

            for (int c = 0; c < cols; c++) {
                int idx = i + c;
                LinearLayout cellWrap = new LinearLayout(this);
                cellWrap.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (c < cols - 1) cellLp.rightMargin = dpInt(10);

                if (idx < items.size()) {
                    final HiddenVaultManager.VaultItem item = items.get(idx);
                    cellWrap.setBackground(roundedRect(0xFF17171A, 18f, 1, 0xFF26262C));
                    cellWrap.setPadding(dpInt(6), dpInt(6), dpInt(6), dpInt(8));

                    final ImageView thumb = new ImageView(this);
                    thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    thumb.setBackgroundColor(0xFF101014);
                    LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dpInt(165)
                    );
                    cellWrap.addView(thumb, imgLp);

                    TextView meta = new TextView(this);
                    meta.setText(sdf.format(new Date(item.createdMs)) + " • " + item.width + "×" + item.height);
                    meta.setTextColor(0xFFA1A1AA);
                    meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                    meta.setPadding(dpInt(6), dpInt(6), dpInt(6), 0);
                    cellWrap.addView(meta);

                    cellWrap.setOnClickListener(v -> showItemPreviewDialog(item));

                    executor.execute(() -> {
                        final Bitmap bmp = HiddenVaultManager.decryptBitmap(getApplicationContext(), item, 480);
                        runOnUiThread(() -> {
                            if (bmp != null && !isFinishing()) {
                                thumb.setImageBitmap(bmp);
                            }
                        });
                    });
                }
                row.addView(cellWrap, cellLp);
            }
            rootContent.addView(row, rLp);
        }
    }

    private void showItemPreviewDialog(final HiddenVaultManager.VaultItem item) {
        final Dialog dlg = new Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar);
        Window w = dlg.getWindow();
        if (w != null) {
            w.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            w.setStatusBarColor(0xFF000000);
            w.setNavigationBarColor(0xFF000000);
        }

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(0xFF000000);
        wrap.setPadding(dpInt(14), dpInt(34), dpInt(14), dpInt(24));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView dimLabel = new TextView(this);
        dimLabel.setText(item.width + " × " + item.height + " px");
        dimLabel.setTextColor(0xFF8E8E93);
        dimLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        topBar.addView(dimLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        topBar.addView(makePillButton("Close", 0xFF252529, Color.WHITE, v -> dlg.dismiss()));
        wrap.addView(topBar);

        final ImageView fullView = new ImageView(this);
        fullView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams fvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        );
        fvLp.topMargin = dpInt(10);
        fvLp.bottomMargin = dpInt(12);
        wrap.addView(fullView, fvLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        TextView copyBtn = makePillButton("Copy Clean", 0xFF387AFF, Color.WHITE, v -> executor.execute(() -> {
            Bitmap full = HiddenVaultManager.decryptBitmap(getApplicationContext(), item, 2560);
            runOnUiThread(() -> {
                if (full != null) {
                    MediaStoreHelper.copyBitmapToClipboard(getApplicationContext(), full);
                    Toast.makeText(this, "Copied cleanly from Vault", Toast.LENGTH_SHORT).show();
                }
            });
        }));

        TextView restoreBtn = makePillButton("Restore", 0xFF252529, 0xFF2AD181, v -> executor.execute(() -> {
            boolean ok = HiddenVaultManager.restoreToGallery(getApplicationContext(), item);
            runOnUiThread(() -> Toast.makeText(
                    this,
                    ok ? "Restored to DCIM/FoldShot Vault/" : "Restore failed",
                    Toast.LENGTH_SHORT
            ).show());
        }));

        TextView delBtn = makePillButton("Delete", 0xFF3A1D22, 0xFFFF6B6B, v -> {
            AlertDialog confirm = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Delete permanently?")
                    .setMessage("This encrypted item cannot be recovered unless you have a .foldvault backup.")
                    .setPositiveButton("Delete Forever", (d, which) -> {
                        HiddenVaultManager.deleteItem(this, item);
                        dlg.dismiss();
                        renderVaultUi();
                    })
                    .setNegativeButton("Cancel", null)
                    .create();
            if (confirm.getWindow() != null) {
                confirm.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
            confirm.show();
        });

        LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        a1.rightMargin = dpInt(6);
        LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        a2.rightMargin = dpInt(6);
        LinearLayout.LayoutParams a3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        actions.addView(copyBtn, a1);
        actions.addView(restoreBtn, a2);
        actions.addView(delBtn, a3);
        wrap.addView(actions);

        dlg.setContentView(wrap);
        dlg.show();

        executor.execute(() -> {
            final Bitmap full = HiddenVaultManager.decryptBitmap(getApplicationContext(), item, 2560);
            runOnUiThread(() -> {
                if (full != null && dlg.isShowing()) {
                    fullView.setImageBitmap(full);
                }
            });
        });
    }

    private void promptBackupPassphraseAndExport() {
        List<HiddenVaultManager.VaultItem> items = HiddenVaultManager.listVaultItems(this);
        if (items.isEmpty()) {
            Toast.makeText(this, "Vault is empty — nothing to back up", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText passInput = new EditText(this);
        passInput.setHint("Backup encryption passphrase (min 4 chars)");
        passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passInput.setSingleLine(true);

        LinearLayout wrap = new LinearLayout(this);
        int p = dpInt(20);
        wrap.setPadding(p, p / 2, p, p / 2);
        wrap.addView(passInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Encrypt & Export .foldvault")
                .setMessage("Choose a passphrase to protect your encrypted vault archive across reinstalls.")
                .setView(wrap)
                .setPositiveButton("Choose Save File", (d, w) -> {
                    String pass = passInput.getText().toString();
                    if (pass.length() < 4) {
                        Toast.makeText(this, "Enter at least 4 characters", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    pendingBackupPassphrase = pass;
                    suppressAutoLockForPicker = true;
                    try {
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/octet-stream");
                        intent.putExtra(Intent.EXTRA_TITLE, "FoldShot_Vault_Backup.foldvault");
                        startActivityForResult(intent, REQ_EXPORT_VAULT);
                    } catch (Exception e) {
                        suppressAutoLockForPicker = false;
                        pendingBackupPassphrase = null;
                        Toast.makeText(this, "File picker unavailable", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        dlg.show();
    }

    private void launchImportArchivePicker() {
        suppressAutoLockForPicker = true;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_IMPORT_VAULT);
        } catch (Exception e) {
            suppressAutoLockForPicker = false;
            Toast.makeText(this, "File picker unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        suppressAutoLockForPicker = false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            pendingBackupPassphrase = null;
            return;
        }
        final Uri uri = data.getData();
        if (requestCode == REQ_EXPORT_VAULT) {
            final String pass = pendingBackupPassphrase;
            pendingBackupPassphrase = null;
            if (pass == null || pass.isEmpty()) return;
            Toast.makeText(this, "Encrypting vault archive...", Toast.LENGTH_SHORT).show();
            executor.execute(() -> {
                int count = HiddenVaultManager.exportEncryptedArchive(getApplicationContext(), uri, pass);
                runOnUiThread(() -> Toast.makeText(
                        this,
                        count >= 0 ? ("Exported " + count + " encrypted items") : "Backup export failed",
                        Toast.LENGTH_LONG
                ).show());
            });
        } else if (requestCode == REQ_IMPORT_VAULT) {
            promptPassphraseAndImport(uri);
        }
    }

    private void promptPassphraseAndImport(final Uri archiveUri) {
        final EditText passInput = new EditText(this);
        passInput.setHint("Enter archive passphrase");
        passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passInput.setSingleLine(true);

        LinearLayout wrap = new LinearLayout(this);
        int p = dpInt(20);
        wrap.setPadding(p, p / 2, p, p / 2);
        wrap.addView(passInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Restore .foldvault Archive")
                .setView(wrap)
                .setPositiveButton("Decrypt & Import", (d, w) -> {
                    final String pass = passInput.getText().toString();
                    executor.execute(() -> {
                        int count = HiddenVaultManager.importEncryptedArchive(getApplicationContext(), archiveUri, pass);
                        runOnUiThread(() -> {
                            if (count >= 0) {
                                Toast.makeText(this, "Imported " + count + " vault items", Toast.LENGTH_LONG).show();
                                renderVaultUi();
                            } else {
                                Toast.makeText(this, "Wrong passphrase or invalid .foldvault file", Toast.LENGTH_LONG).show();
                            }
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        dlg.show();
    }

    private TextView makePillButton(String text, int bgColor, int textColor, View.OnClickListener click) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(textColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dpInt(12), dpInt(8), dpInt(12), dpInt(8));
        btn.setBackground(roundedRect(bgColor, 16f, 0, 0));
        btn.setOnClickListener(click);
        return btn;
    }

    private GradientDrawable roundedRect(int fillColor, float radiusDp, int strokeWidthPx, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(fillColor);
        gd.setCornerRadius(radiusDp * dp);
        if (strokeWidthPx > 0) {
            gd.setStroke(strokeWidthPx, strokeColor);
        }
        return gd;
    }

    private int dpInt(float val) {
        return Math.round(val * dp);
    }
}
