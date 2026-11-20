package com.yenaly.han1meviewer.ui.screen.settings.backup;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

/** Test APK runs in its own process without the target APK's Kotlin runtime. */
public class BackupTestGrantReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Uri tree = DocumentsContract.buildTreeDocumentUri("com.yenaly.han1meviewer.backup.test.documents", "root");
        int modes = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if (intent.getBooleanExtra("revoke", false)) context.revokeUriPermission(tree, modes);
        else {
            String target = intent.getStringExtra("target");
            if (!"com.yenaly.han1meviewer.debug".equals(target)) throw new IllegalArgumentException();
            context.grantUriPermission(target, tree, modes | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        setResultCode(Activity.RESULT_OK);
    }
}
