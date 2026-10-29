package ru.passport.scanner.export;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/** Test-APK-only fake mail handler. It reads an attachment and never sends network traffic. */
public final class FakeEmailActivity extends Activity {
    public static final String RESULT_ACTION = "EMAIL_HANDOFF_RESULT";
    public static final String EXTRA_RESULT_ACTION = "ru.passport.scanner.export.RESULT_ACTION";
    public static final String EXTRA_RESULT_PACKAGE = "ru.passport.scanner.export.RESULT_PACKAGE";
    public static final String EXTRA_RECIPIENTS = "ru.passport.scanner.export.RECIPIENTS";
    public static final String EXTRA_RECIPIENT = "ru.passport.scanner.export.RECIPIENT";
    public static final String EXTRA_HAS_7Z_SIGNATURE = "ru.passport.scanner.export.HAS_7Z_SIGNATURE";
    public static final String EXTRA_SHA256 = "ru.passport.scanner.export.SHA256";
    public static final String EXTRA_UID = "ru.passport.scanner.export.UID";
    public static final String EXTRA_ERROR = "ru.passport.scanner.export.ERROR";

    private static final byte[] SEVEN_Z_SIGNATURE = {
        0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final String resultAction = getIntent().getStringExtra(EXTRA_RESULT_ACTION);
        final String resultPackage = getIntent().getStringExtra(EXTRA_RESULT_PACKAGE);
        final Uri attachment = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
        final String[] recipients = getIntent().getStringArrayExtra(Intent.EXTRA_EMAIL);

        new Thread(() -> {
            Intent result = new Intent(resultAction).setPackage(resultPackage);
            result.putExtra(EXTRA_RECIPIENTS, recipients);
            result.putExtra(EXTRA_RECIPIENT, recipients != null && recipients.length > 0 ? recipients[0] : "");
            result.putExtra(EXTRA_UID, Process.myUid());
            try {
                if (attachment == null) {
                    throw new IllegalStateException("No content URI attachment was supplied.");
                }
                byte[] bytes;
                try (InputStream input = getContentResolver().openInputStream(attachment)) {
                    if (input == null) {
                        throw new IllegalStateException("Content URI returned no stream.");
                    }
                    bytes = readAll(input);
                }
                result.putExtra(EXTRA_SHA256, sha256(bytes));
                result.putExtra(EXTRA_HAS_7Z_SIGNATURE, hasSevenZSignature(bytes));
            } catch (Exception exception) {
                result.putExtra(EXTRA_ERROR, exception.getClass().getSimpleName() + ": " + exception.getMessage());
            }
            sendBroadcast(result);
            runOnUiThread(this::finish);
        }, "fake-mail-attachment-reader").start();
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static boolean hasSevenZSignature(byte[] bytes) {
        if (bytes.length < SEVEN_Z_SIGNATURE.length) {
            return false;
        }
        for (int index = 0; index < SEVEN_Z_SIGNATURE.length; index++) {
            if (bytes[index] != SEVEN_Z_SIGNATURE[index]) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(Character.forDigit((value >>> 4) & 0x0F, 16));
            hex.append(Character.forDigit(value & 0x0F, 16));
        }
        return hex.toString();
    }
}
