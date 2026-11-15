package com.foldshot.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/**
 * 100% Offline AES-256-GCM Encrypted Hidden Vault Manager for FoldShot:
 * - Stores hidden photos encrypted inside app-private storage (filesDir/vault) using Android Keystore.
 * - Salted PBKDF2-HMAC-SHA256 (10,000 iterations) PIN hashing with constant-time verification.
 * - Encrypted .foldvault backup/restore archives so hidden photos survive APK reinstalls.
 */
public class HiddenVaultManager {

    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALIAS = "FoldShotVaultKey";
    private static final String VAULT_DIR_NAME = "vault";
    private static final byte[] ITEM_MAGIC = new byte[]{'F', 'S', 'V', 'I'};
    private static final byte ITEM_VERSION = 2;
    private static final byte[] ARCHIVE_MAGIC = new byte[]{'F', 'S', 'V', '1'};
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int PBKDF2_ITERATIONS = 10000;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static volatile String pendingUnlockToken = null;
    private static volatile long pendingUnlockTsMs = 0L;

    public static class VaultItem {
        public final String fileName;
        public final long createdMs;
        public final int width;
        public final int height;
        public final String mimeType;
        public final String ext;
        public final long encryptedBytes;

        public VaultItem(String fileName, long createdMs, int width, int height, String mimeType, String ext, long encryptedBytes) {
            this.fileName = fileName;
            this.createdMs = createdMs;
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            this.mimeType = mimeType != null ? mimeType : "image/png";
            this.ext = ext != null ? ext : ".png";
            this.encryptedBytes = encryptedBytes;
        }
    }

    public static String issueUnlockToken() {
        String token = UUID.randomUUID().toString();
        pendingUnlockToken = token;
        pendingUnlockTsMs = System.currentTimeMillis();
        return token;
    }

    public static boolean consumeUnlockToken(String candidate) {
        if (candidate == null || pendingUnlockToken == null) return false;
        boolean ok = candidate.equals(pendingUnlockToken)
                && (System.currentTimeMillis() - pendingUnlockTsMs) <= 15000L;
        pendingUnlockToken = null;
        pendingUnlockTsMs = 0L;
        return ok;
    }

    private static File getVaultDir(Context context) {
        File dir = new File(context.getFilesDir(), VAULT_DIR_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static SecretKey getOrCreateVaultKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build();
        kg.init(spec);
        return kg.generateKey();
    }

    private static byte[] derivePbkdf2Key(String secret, byte[] salt) throws Exception {
        char[] chars = secret != null ? secret.toCharArray() : new char[0];
        PBEKeySpec spec = new PBEKeySpec(chars, salt, PBKDF2_ITERATIONS, 256);
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return skf.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    public static boolean setVaultPin(Context context, String pin) {
        if (pin == null || pin.trim().length() < 4) return false;
        try {
            byte[] salt = new byte[16];
            SECURE_RANDOM.nextBytes(salt);
            byte[] hash = derivePbkdf2Key(pin.trim(), salt);
            FoldShotPrefs prefs = new FoldShotPrefs(context);
            prefs.setVaultPinCredentials(
                    Base64.encodeToString(salt, Base64.NO_WRAP),
                    Base64.encodeToString(hash, Base64.NO_WRAP)
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean verifyVaultPin(Context context, String pin) {
        if (pin == null) return false;
        FoldShotPrefs prefs = new FoldShotPrefs(context);
        if (!prefs.hasVaultPin()) return false;
        try {
            byte[] salt = Base64.decode(prefs.getVaultPinSalt(), Base64.NO_WRAP);
            byte[] expected = Base64.decode(prefs.getVaultPinHash(), Base64.NO_WRAP);
            byte[] actual = derivePbkdf2Key(pin.trim(), salt);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean saveToVault(
            Context context,
            Uri sourceUri,
            Bitmap croppedOrAnnotated,
            boolean isFullCrop,
            boolean stripMetadata
    ) {
        try {
            byte[] imageBytes = null;
            String mimeType = "image/png";
            String ext = ".png";
            int width = 0;
            int height = 0;

            if (croppedOrAnnotated == null && isFullCrop && sourceUri != null && !Uri.EMPTY.equals(sourceUri)) {
                String[] mimeAndExt = MediaStoreHelper.detectSourceMimeAndExt(context, sourceUri);
                mimeType = mimeAndExt[0];
                ext = mimeAndExt[1];
                byte[] raw = MediaStoreHelper.readCleanSourceBytes(context, sourceUri, ext, stripMetadata);
                if (raw != null && raw.length > 0) {
                    imageBytes = raw;
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
                    width = Math.max(1, bounds.outWidth);
                    height = Math.max(1, bounds.outHeight);
                }
            }

            if (imageBytes == null) {
                Bitmap target = croppedOrAnnotated;
                if (target == null && sourceUri != null && !Uri.EMPTY.equals(sourceUri)) {
                    target = MediaStoreHelper.loadFullResolutionBitmap(context, sourceUri);
                }
                if (target == null) return false;
                width = target.getWidth();
                height = target.getHeight();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                target.compress(Bitmap.CompressFormat.PNG, 100, bos);
                imageBytes = bos.toByteArray();
                mimeType = "image/png";
                ext = ".png";
            }

            long nowMs = System.currentTimeMillis();
            JSONObject meta = new JSONObject();
            meta.put("createdMs", nowMs);
            meta.put("width", Math.max(1, width));
            meta.put("height", Math.max(1, height));
            meta.put("mime", mimeType);
            meta.put("ext", ext);

            return writeEncryptedItem(context, meta, imageBytes);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean writeEncryptedItem(Context context, JSONObject meta, byte[] imageBytes) throws Exception {
        SecretKey key = getOrCreateVaultKey();
        byte[] metaBytes = meta.toString().getBytes(StandardCharsets.UTF_8);

        Cipher metaCipher = Cipher.getInstance("AES/GCM/NoPadding");
        metaCipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] metaIv = metaCipher.getIV();
        byte[] encMeta = metaCipher.doFinal(metaBytes);

        Cipher dataCipher = Cipher.getInstance("AES/GCM/NoPadding");
        dataCipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] dataIv = dataCipher.getIV();
        byte[] encData = dataCipher.doFinal(imageBytes);

        String fname = "v_" + meta.optLong("createdMs", System.currentTimeMillis())
                + "_" + UUID.randomUUID().toString().substring(0, 8) + ".enc";
        File outFile = new File(getVaultDir(context), fname);

        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(outFile))) {
            dos.write(ITEM_MAGIC);
            dos.writeByte(ITEM_VERSION);
            dos.write(metaIv);
            dos.writeInt(encMeta.length);
            dos.write(encMeta);
            dos.write(dataIv);
            dos.writeInt(encData.length);
            dos.write(encData);
            dos.flush();
        }
        return true;
    }

    public static List<VaultItem> listVaultItems(Context context) {
        List<VaultItem> items = new ArrayList<>();
        File dir = getVaultDir(context);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return items;

        SecretKey key;
        try {
            key = getOrCreateVaultKey();
        } catch (Exception e) {
            return items;
        }

        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".enc")) continue;
            try (DataInputStream dis = new DataInputStream(new FileInputStream(f))) {
                byte[] magic = new byte[4];
                dis.readFully(magic);
                if (!Arrays.equals(magic, ITEM_MAGIC)) continue;
                byte ver = dis.readByte();
                if (ver != ITEM_VERSION) continue;

                byte[] metaIv = new byte[GCM_IV_LEN];
                dis.readFully(metaIv);
                int metaLen = dis.readInt();
                if (metaLen <= 0 || metaLen > 65536) continue;
                byte[] encMeta = new byte[metaLen];
                dis.readFully(encMeta);

                Cipher metaCipher = Cipher.getInstance("AES/GCM/NoPadding");
                metaCipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, metaIv));
                byte[] plainMeta = metaCipher.doFinal(encMeta);
                JSONObject meta = new JSONObject(new String(plainMeta, StandardCharsets.UTF_8));

                items.add(new VaultItem(
                        f.getName(),
                        meta.optLong("createdMs", f.lastModified()),
                        meta.optInt("width", 1),
                        meta.optInt("height", 1),
                        meta.optString("mime", "image/png"),
                        meta.optString("ext", ".png"),
                        f.length()
                ));
            } catch (Exception ignored) {
            }
        }

        Collections.sort(items, (a, b) -> Long.compare(b.createdMs, a.createdMs));
        return items;
    }

    public static byte[] decryptItemBytes(Context context, VaultItem item) {
        if (item == null || item.fileName == null) return null;
        File f = new File(getVaultDir(context), item.fileName);
        if (!f.exists()) return null;

        try (DataInputStream dis = new DataInputStream(new FileInputStream(f))) {
            SecretKey key = getOrCreateVaultKey();
            byte[] magic = new byte[4];
            dis.readFully(magic);
            if (!Arrays.equals(magic, ITEM_MAGIC)) return null;
            byte ver = dis.readByte();
            if (ver != ITEM_VERSION) return null;

            byte[] metaIv = new byte[GCM_IV_LEN];
            dis.readFully(metaIv);
            int metaLen = dis.readInt();
            if (metaLen <= 0 || metaLen > 65536) return null;
            byte[] skipped = new byte[metaLen];
            dis.readFully(skipped);

            byte[] dataIv = new byte[GCM_IV_LEN];
            dis.readFully(dataIv);
            int dataLen = dis.readInt();
            if (dataLen <= 0 || dataLen > 64 * 1024 * 1024) return null;
            byte[] encData = new byte[dataLen];
            dis.readFully(encData);

            Cipher dataCipher = Cipher.getInstance("AES/GCM/NoPadding");
            dataCipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, dataIv));
            return dataCipher.doFinal(encData);
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject decryptItemMeta(Context context, File f, SecretKey key) throws Exception {
        try (DataInputStream dis = new DataInputStream(new FileInputStream(f))) {
            byte[] magic = new byte[4];
            dis.readFully(magic);
            if (!Arrays.equals(magic, ITEM_MAGIC)) throw new IllegalStateException("Bad magic");
            byte ver = dis.readByte();
            if (ver != ITEM_VERSION) throw new IllegalStateException("Bad version");

            byte[] metaIv = new byte[GCM_IV_LEN];
            dis.readFully(metaIv);
            int metaLen = dis.readInt();
            if (metaLen <= 0 || metaLen > 65536) throw new IllegalStateException("Bad meta len");
            byte[] encMeta = new byte[metaLen];
            dis.readFully(encMeta);

            Cipher metaCipher = Cipher.getInstance("AES/GCM/NoPadding");
            metaCipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, metaIv));
            byte[] plainMeta = metaCipher.doFinal(encMeta);
            return new JSONObject(new String(plainMeta, StandardCharsets.UTF_8));
        }
    }

    public static Bitmap decryptBitmap(Context context, VaultItem item, int maxSidePx) {
        byte[] raw = decryptItemBytes(context, item);
        if (raw == null || raw.length == 0) return null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            if (maxSidePx > 0) {
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
                int longest = Math.max(opts.outWidth, opts.outHeight);
                int sample = 1;
                while ((longest / sample) > maxSidePx) {
                    sample *= 2;
                }
                opts.inJustDecodeBounds = false;
                opts.inSampleSize = Math.max(1, sample);
            }
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
        } catch (OutOfMemoryError oom) {
            System.gc();
            return null;
        }
    }

    public static boolean restoreToGallery(Context context, VaultItem item) {
        byte[] raw = decryptItemBytes(context, item);
        if (raw == null || raw.length == 0) return false;

        FoldShotPrefs prefs = new FoldShotPrefs(context);
        ContentResolver resolver = context.getContentResolver();
        String baseName = MediaStoreHelper.buildOutputBaseName(prefs, "FoldShot_Restored");
        Uri inserted = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, baseName + item.ext);
            values.put(MediaStore.Images.Media.MIME_TYPE, item.mimeType);
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/FoldShot Vault/");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            inserted = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (inserted == null) return false;

            try (OutputStream out = resolver.openOutputStream(inserted)) {
                if (out == null) {
                    resolver.delete(inserted, null, null);
                    return false;
                }
                out.write(raw);
                out.flush();
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(inserted, done, null, null);
            return true;
        } catch (Exception e) {
            if (inserted != null) {
                try {
                    resolver.delete(inserted, null, null);
                } catch (Exception ignored) {
                }
            }
            return false;
        }
    }

    public static boolean deleteItem(Context context, VaultItem item) {
        if (item == null || item.fileName == null) return false;
        File f = new File(getVaultDir(context), item.fileName);
        return !f.exists() || f.delete();
    }

    /**
     * Exports all encrypted Hidden Vault items into a portable password-protected .foldvault archive
     * so hidden photos can be moved across APK uninstall/reinstall updates offline.
     */
    public static int exportEncryptedArchive(Context context, Uri targetUri, String passphrase) {
        if (targetUri == null || passphrase == null || passphrase.isEmpty()) return -1;
        List<VaultItem> items = listVaultItems(context);
        try {
            SecretKey keystoreKey = getOrCreateVaultKey();
            ByteArrayOutputStream payloadBos = new ByteArrayOutputStream();
            DataOutputStream payloadDos = new DataOutputStream(payloadBos);
            payloadDos.writeInt(items.size());

            int written = 0;
            for (VaultItem item : items) {
                File f = new File(getVaultDir(context), item.fileName);
                JSONObject meta = decryptItemMeta(context, f, keystoreKey);
                byte[] imgBytes = decryptItemBytes(context, item);
                if (imgBytes == null) continue;

                byte[] metaBytes = meta.toString().getBytes(StandardCharsets.UTF_8);
                payloadDos.writeInt(metaBytes.length);
                payloadDos.write(metaBytes);
                payloadDos.writeInt(imgBytes.length);
                payloadDos.write(imgBytes);
                written++;
            }
            payloadDos.flush();
            byte[] plainArchive = payloadBos.toByteArray();

            byte[] salt = new byte[16];
            SECURE_RANDOM.nextBytes(salt);
            byte[] keyBytes = derivePbkdf2Key(passphrase, salt);
            SecretKeySpec backupKey = new SecretKeySpec(keyBytes, "AES");

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, backupKey);
            byte[] iv = cipher.getIV();
            byte[] ciphertext = cipher.doFinal(plainArchive);

            try (OutputStream out = context.getContentResolver().openOutputStream(targetUri, "wt")) {
                if (out == null) return -1;
                DataOutputStream dos = new DataOutputStream(out);
                dos.write(ARCHIVE_MAGIC);
                dos.write(salt);
                dos.write(iv);
                dos.write(ciphertext);
                dos.flush();
            }
            return written;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Imports a password-protected .foldvault archive back into the local Keystore-encrypted vault.
     * Returns number of restored items, or -1 if passphrase is wrong or file is invalid.
     */
    public static int importEncryptedArchive(Context context, Uri sourceUri, String passphrase) {
        if (sourceUri == null || passphrase == null || passphrase.isEmpty()) return -1;
        try (InputStream in = context.getContentResolver().openInputStream(sourceUri)) {
            if (in == null) return -1;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int len;
            while ((len = in.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            byte[] raw = bos.toByteArray();
            if (raw.length < 4 + 16 + GCM_IV_LEN + 16) return -1;

            byte[] magic = Arrays.copyOfRange(raw, 0, 4);
            if (!Arrays.equals(magic, ARCHIVE_MAGIC)) return -1;

            byte[] salt = Arrays.copyOfRange(raw, 4, 20);
            byte[] iv = Arrays.copyOfRange(raw, 20, 20 + GCM_IV_LEN);
            byte[] ciphertext = Arrays.copyOfRange(raw, 20 + GCM_IV_LEN, raw.length);

            byte[] keyBytes = derivePbkdf2Key(passphrase, salt);
            SecretKeySpec backupKey = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, backupKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plainArchive = cipher.doFinal(ciphertext);

            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(plainArchive));
            int count = dis.readInt();
            if (count < 0 || count > 10000) return -1;

            int imported = 0;
            for (int i = 0; i < count; i++) {
                int metaLen = dis.readInt();
                if (metaLen <= 0 || metaLen > 65536) break;
                byte[] metaBytes = new byte[metaLen];
                dis.readFully(metaBytes);
                JSONObject meta = new JSONObject(new String(metaBytes, StandardCharsets.UTF_8));

                int imgLen = dis.readInt();
                if (imgLen <= 0 || imgLen > 64 * 1024 * 1024) break;
                byte[] imgBytes = new byte[imgLen];
                dis.readFully(imgBytes);

                if (writeEncryptedItem(context, meta, imgBytes)) {
                    imported++;
                }
            }
            return imported;
        } catch (Exception e) {
            return -1;
        }
    }
}
