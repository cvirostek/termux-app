package com.termux.filepicker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.Patterns;

import com.termux.R;
import com.termux.app.DialogUtils;
import com.termux.app.TermuxService;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class TermuxFileReceiverActivity extends Activity {

    static final String TERMUX_RECEIVEDIR = TermuxService.PREFIX_PATH + "/tmp";

    // These values may be overridden by TermuxBackgroundFileReceiverActivity.
    String EDITOR_PROGRAM_BASE = "/bin/termux-file-editor";
    String EDITOR_PROGRAM = TermuxService.HOME_PATH + EDITOR_PROGRAM_BASE;
    String URL_OPENER_PROGRAM_BASE = "/bin/termux-url-opener";
    String URL_OPENER_PROGRAM = TermuxService.HOME_PATH + URL_OPENER_PROGRAM_BASE;
    boolean IS_TASK = false;

    /**
     * If the activity should be finished when the name input dialog is dismissed. This is disabled
     * before showing an error dialog, since the act of showing the error dialog will cause the
     * name input dialog to be implicitly dismissed, and we do not want to finish the activity directly
     * when showing the error dialog.
     */
    boolean mFinishOnDismissNameDialog = true;

    @Override
    protected void onResume() {
        super.onResume();

        final Intent intent = getIntent();
        final String action = intent.getAction();
        final String type = intent.getType();
        final String scheme = intent.getScheme();

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            final String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            final Uri sharedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);

            if (sharedText != null) {
                if (Patterns.WEB_URL.matcher(sharedText).matches()) {
                    handleUrlAndFinish(sharedText);
                } else {
                    String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
                    if (subject == null) subject = intent.getStringExtra(Intent.EXTRA_TITLE);
                    if (subject != null) subject += ".txt";
                    promptNameAndSave(new ByteArrayInputStream(sharedText.getBytes(StandardCharsets.UTF_8)), subject);
                }
            } else if (sharedUri != null) {
                handleContentUri(sharedUri, intent.getStringExtra(Intent.EXTRA_TITLE));
            } else {
                showErrorDialogAndQuit("Send action without content - nothing to save.");
            }
        } else if ("content".equals(scheme)) {
            handleContentUri(intent.getData(), intent.getStringExtra(Intent.EXTRA_TITLE));
        } else if ("file".equals(scheme)) {
            // When e.g. clicking on a downloaded apk:
            String path = intent.getData().getPath();
            File file = new File(path);
            try {
                FileInputStream in = new FileInputStream(file);
                promptNameAndSave(in, file.getName());
            } catch (FileNotFoundException e) {
                showErrorDialogAndQuit("Cannot open file: " + e.getMessage() + ".");
            }
        } else {
            showErrorDialogAndQuit("Unable to receive any file or URL.");
        }
    }

    void showErrorDialogAndQuit(String message) {
        mFinishOnDismissNameDialog = false;
        new AlertDialog.Builder(this).setMessage(message).setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                finish();
            }
        }).setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                finish();
            }
        }).show();
    }

    void handleContentUri(final Uri uri, String subjectFromIntent) {
        try {
            String attachmentFileName = null;

            String[] projection = new String[]{OpenableColumns.DISPLAY_NAME};
            try (Cursor c = getContentResolver().query(uri, projection, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    final int fileNameColumnId = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (fileNameColumnId >= 0) attachmentFileName = c.getString(fileNameColumnId);
                }
            }

            if (attachmentFileName == null) attachmentFileName = subjectFromIntent;

            InputStream in = getContentResolver().openInputStream(uri);
            promptNameAndSave(in, attachmentFileName);
        } catch (Exception e) {
            showErrorDialogAndQuit("Unable to handle shared content:\n\n" + e.getMessage());
            Log.e("termux", "handleContentUri(uri=" + uri + ") failed", e);
        }
    }

    void promptNameAndSave(final InputStream in, final String attachmentFileName) {
        if (IS_TASK) {
            openFileEditor(in, attachmentFileName);
            return;
        }
        DialogUtils.textInput(this, R.string.file_received_title, attachmentFileName, R.string.file_received_edit_button, new DialogUtils.TextSetListener() {
                @Override
                public void onTextSet(String text) {
                    openFileEditor(in, text);
                }
            },
            R.string.file_received_open_folder_button, new DialogUtils.TextSetListener() {
                @Override
                public void onTextSet(String text) {
                    if (saveStreamWithName(in, text) == null) return;

                    Intent executeIntent = new Intent(TermuxService.ACTION_EXECUTE);
                    executeIntent.putExtra(TermuxService.EXTRA_CURRENT_WORKING_DIRECTORY, TERMUX_RECEIVEDIR);
                    executeIntent.setClass(TermuxFileReceiverActivity.this, TermuxService.class);
                    startService(executeIntent);
                    finish();
                }
            },
            android.R.string.cancel, new DialogUtils.TextSetListener() {
                @Override
                public void onTextSet(final String text) {
                    finish();
                }
            }, new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    if (mFinishOnDismissNameDialog) finish();
                }
            });
    }

    void openFileEditor(InputStream in, String text) {
        File outFile = saveStreamWithName(in, text);
        if (outFile == null) return;

        final File editorProgramFile = new File(EDITOR_PROGRAM);
        if (!editorProgramFile.isFile()) {
            showErrorDialogAndQuit("The following file does not exist:\n$HOME" + EDITOR_PROGRAM_BASE
                + "\n\nCreate this file as a script or a symlink - it will be called with the received file as only argument.");
            return;
        }

        // Do this for the user if necessary:
        //noinspection ResultOfMethodCallIgnored
        editorProgramFile.setExecutable(true);

        final Uri scriptUri = new Uri.Builder().scheme("file").path(EDITOR_PROGRAM).build();

        Intent executeIntent = new Intent(TermuxService.ACTION_EXECUTE, scriptUri);
        executeIntent.setClass(TermuxFileReceiverActivity.this, TermuxService.class);
        executeIntent.putExtra(TermuxService.EXTRA_ARGUMENTS, new String[]{outFile.getAbsolutePath()});
        executeIntent.putExtra(TermuxService.EXTRA_EXECUTE_IN_BACKGROUND, IS_TASK);
        startService(executeIntent);
        finish();
    }

    public File saveStreamWithName(InputStream in, String attachmentFileName) {
        File receiveDir = new File(TERMUX_RECEIVEDIR);
        if (!receiveDir.isDirectory() && !receiveDir.mkdirs()) {
            showErrorDialogAndQuit("Cannot create directory: " + receiveDir.getAbsolutePath());
            return null;
        }
        try {
            final File outFile = new File(receiveDir, attachmentFileName);
            try (FileOutputStream f = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[4096];
                int readBytes;
                while ((readBytes = in.read(buffer)) > 0) {
                    f.write(buffer, 0, readBytes);
                }
            }
            return outFile;
        } catch (IOException e) {
            showErrorDialogAndQuit("Error saving file:\n\n" + e);
            Log.e("termux", "Error saving file", e);
            return null;
        }
    }

    void handleUrlAndFinish(final String url) {
        final File urlOpenerProgramFile = new File(URL_OPENER_PROGRAM);
        if (!urlOpenerProgramFile.isFile()) {
            showErrorDialogAndQuit("The following file does not exist:\n$HOME" + URL_OPENER_PROGRAM_BASE
                + "\n\nCreate this file as a script or a symlink - it will be called with the shared URL as only argument.");
            return;
        }

        // Do this for the user if necessary:
        //noinspection ResultOfMethodCallIgnored
        urlOpenerProgramFile.setExecutable(true);

        final Uri urlOpenerProgramUri = new Uri.Builder().scheme("file").path(URL_OPENER_PROGRAM).build();

        Intent executeIntent = new Intent(TermuxService.ACTION_EXECUTE, urlOpenerProgramUri);
        executeIntent.setClass(TermuxFileReceiverActivity.this, TermuxService.class);
        executeIntent.putExtra(TermuxService.EXTRA_ARGUMENTS, new String[]{url});
        executeIntent.putExtra(TermuxService.EXTRA_EXECUTE_IN_BACKGROUND, IS_TASK);
        startService(executeIntent);
        finish();
    }

}
