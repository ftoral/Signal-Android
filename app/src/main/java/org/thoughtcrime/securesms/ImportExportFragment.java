package org.thoughtcrime.securesms;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.appcompat.app.AlertDialog;

import android.os.Environment;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.conversationlist.ConversationListFragment;
import org.thoughtcrime.securesms.database.EncryptedBackupExporter;
import org.thoughtcrime.securesms.database.NoExternalStorageException;
import org.thoughtcrime.securesms.database.PlaintextBackupExporter;
import org.thoughtcrime.securesms.database.PlaintextBackupImporter;
import org.thoughtcrime.securesms.database.WhatsappBackupImporter;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.service.ApplicationMigrationService;

import java.io.IOException;


public class ImportExportFragment extends Fragment {

  private static final String TAG = ImportExportFragment.class.getSimpleName();

  private static final int SUCCESS    = 0;
  private static final int NO_SD_CARD = 1;
  private static final int ERROR_IO   = 2;

  private ProgressDialog progressDialog;

  @Override
  public void onCreate(Bundle bundle) {
    super.onCreate(bundle);
  }

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle bundle) {
    View layout              = inflater.inflate(R.layout.import_export_fragment, container, false);
    View importSmsView       = layout.findViewById(R.id.import_sms             );
    View importPlaintextView = layout.findViewById(R.id.import_plaintext_backup);
    View importWhatsappView = layout.findViewById(R.id.import_whatsapp_backup);
    View importEncryptedView = layout.findViewById(R.id.import_encrypted_backup); // JW
    View exportPlaintextView = layout.findViewById(R.id.export_plaintext_backup); // JW
    View exportEncryptedView = layout.findViewById(R.id.export_encrypted_backup); // JW

    importSmsView.setOnClickListener(v -> handleImportSms());
    importPlaintextView.setOnClickListener(v -> handleImportPlaintextBackup());
    importWhatsappView.setOnClickListener(v -> handleImportWhatsappBackup());
    importEncryptedView.setOnClickListener(v -> handleImportEncryptedBackup()); // JW
    exportPlaintextView.setOnClickListener(v -> handleExportPlaintextBackup());
    exportEncryptedView.setOnClickListener(v -> handleExportEncryptedBackup()); // JW

    return layout;
  }

  @Override
  public void onDestroy() {
    super.onDestroy();

    if (progressDialog != null && progressDialog.isShowing()) {
      progressDialog.dismiss();
      progressDialog = null;
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  @SuppressWarnings("CodeBlock2Expr")
  private void handleImportSms() {
    AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
    builder.setIcon(R.drawable.ic_info_outline);
    builder.setTitle(getActivity().getString(R.string.ImportFragment_import_system_sms_database));
    builder.setMessage(getActivity().getString(R.string.ImportFragment_this_will_import_messages_from_the_system));
    builder.setPositiveButton(getActivity().getString(R.string.ImportFragment_import), (dialog, which) -> {
      Permissions.with(this)
                 .request(Manifest.permission.READ_SMS)
                 .ifNecessary()
                 .withPermanentDenialDialog(getString(R.string.ImportExportFragment_signal_needs_the_sms_permission_in_order_to_import_sms_messages))
                 .onAllGranted(() -> {
                   Intent intent = new Intent(getActivity(), ApplicationMigrationService.class);
                   intent.setAction(ApplicationMigrationService.MIGRATE_DATABASE);
                   getActivity().startService(intent);

                   Intent nextIntent = new Intent(getActivity(), ConversationListFragment.class);

                   Intent activityIntent = new Intent(getActivity(), DatabaseMigrationActivity.class);
                   activityIntent.putExtra("next_intent", nextIntent);
                   getActivity().startActivity(activityIntent);
                 })
                 .onAnyDenied(() -> Toast.makeText(getContext(), R.string.ImportExportFragment_signal_needs_the_sms_permission_in_order_to_import_sms_messages_toast, Toast.LENGTH_LONG).show())
                 .execute();
    });
    builder.setNegativeButton(getActivity().getString(R.string.ImportFragment_cancel), null);
    builder.show();
  }

  @SuppressWarnings("CodeBlock2Expr")
  @SuppressLint("InlinedApi")
  private void handleImportPlaintextBackup() {
    AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
    builder.setIcon(R.drawable.ic_warning);
    builder.setTitle(getActivity().getString(R.string.ImportFragment_import_plaintext_backup));
    builder.setMessage(getActivity().getString(R.string.ImportFragment_this_will_import_messages_from_a_plaintext_backup));
    builder.setPositiveButton(getActivity().getString(R.string.ImportFragment_import), (dialog, which) -> {
      Permissions.with(ImportExportFragment.this)
                 .request(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
                 .ifNecessary()
                 .withPermanentDenialDialog(getString(R.string.ImportExportFragment_signal_needs_the_storage_permission_in_order_to_read_from_external_storage_but_it_has_been_permanently_denied))
                 .onAllGranted(() -> new ImportPlaintextBackupTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR))
                 .onAnyDenied(() -> Toast.makeText(getContext(), R.string.ImportExportFragment_signal_needs_the_storage_permission_in_order_to_read_from_external_storage, Toast.LENGTH_LONG).show())
                 .execute();
    });
    builder.setNegativeButton(getActivity().getString(R.string.ImportFragment_cancel), null);
    builder.show();
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
  }

  @SuppressWarnings("CodeBlock2Expr")
  @SuppressLint("InlinedApi")
  private void handleImportWhatsappBackup() {
    if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.R){
      if (!Environment.isExternalStorageManager()) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setIcon(R.drawable.ic_warning);
        builder.setTitle(getActivity().getString(R.string.grat_storage_manager_permission));
        builder.setMessage(getActivity().getString(R.string.please_grant_storage_manager_permission));
        builder.setPositiveButton(getActivity().getString(R.string.ImportFragment_ok), (dialog, which) -> {
          Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
          getActivity().startActivity(intent);
        });
        builder.show();
      } else {
        AlertDialog.Builder builder = ImportWhatsappDialog.getWhatsappBackupDialog(getActivity());
        builder.setPositiveButton(getActivity().getString(R.string.ImportFragment_import), (dialog, which) -> {
          new ImportWhatsappBackupTask(ImportWhatsappDialog.isImportGroups(), ImportWhatsappDialog.isAvoidDuplicates(), ImportWhatsappDialog.isImportMedia()).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        });
      }
    } else {
      AlertDialog.Builder builder = ImportWhatsappDialog.getWhatsappBackupDialog(getActivity());
      builder.setPositiveButton(getActivity().getString(R.string.ImportFragment_import), (dialog, which) -> {
        Permissions.with(ImportExportFragment.this)
                .request(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
                .ifNecessary()
                .withPermanentDenialDialog(getString(R.string.ImportExportFragment_signal_needs_the_storage_permission_in_order_to_read_from_external_storage_but_it_has_been_permanently_denied))
                .onAllGranted(() -> new ImportWhatsappBackupTask(ImportWhatsappDialog.isImportGroups(), ImportWhatsappDialog.isAvoidDuplicates(), ImportWhatsappDialog.isImportMedia()).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR))
                .onAnyDenied(() -> Toast.makeText(getContext(), R.string.ImportExportFragment_signal_needs_the_storage_permission_in_order_to_read_from_external_storage, Toast.LENGTH_LONG).show())
                .execute();
      });
      builder.setNegativeButton(getActivity().getString(R.string.ImportFragment_cancel), null);
      builder.show();
    }
  }

  @SuppressWarnings("CodeBlock2Expr")
  @SuppressLint("InlinedApi")
  private void handleExportPlaintextBackup() {
    AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
    builder.setIcon(R.drawable.ic_warning);
    builder.setTitle(getActivity().getString(R.string.ExportFragment_export_plaintext_to_storage));
    builder.setMessage(getActivity().getString(R.string.ExportFragment_warning_this_will_export_the_plaintext_contents));
    builder.setPositiveButton(getActivity().getString(R.string.ExportFragment_export), (dialog, which) -> {
      Permissions.with(ImportExportFragment.this)
                 .request(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
                 .ifNecessary()
                 .withPermanentDenialDialog(getString(R.string.ImportExportFragment_signal_needs_the_storage_permission_in_order_to_write_to_external_storage_but_it_has_been_permanently_denied))
                 .onAllGranted(() -> new ExportPlaintextTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR))
                 .onAnyDenied(() -> Toast.makeText(getContext(), R.string.ImportExportFragment_signal_needs_the_storage_permission_in_order_to_write_to_external_storage, Toast.LENGTH_LONG).show())
                 .execute();
    });
    builder.setNegativeButton(getActivity().getString(R.string.ExportFragment_cancel), null);
    builder.show();
  }

  @SuppressLint("StaticFieldLeak")
  private class ImportPlaintextBackupTask extends AsyncTask<Void, Void, Integer> {

    @Override
    protected void onPreExecute() {
      progressDialog = ProgressDialog.show(getActivity(),
                                           getActivity().getString(R.string.ImportFragment_importing),
                                           getActivity().getString(R.string.ImportFragment_import_plaintext_backup_elipse),
                                           true, false);
    }

    protected void onPostExecute(Integer result) {
      Context context = getActivity();

      if (progressDialog != null)
        progressDialog.dismiss();

      if (context == null)
        return;

      switch (result) {
        case NO_SD_CARD:
          Toast.makeText(context,
                         context.getString(R.string.ImportFragment_no_plaintext_backup_found),
                         Toast.LENGTH_LONG).show();
          break;
        case ERROR_IO:
          Toast.makeText(context,
                         context.getString(R.string.ImportFragment_error_importing_backup),
                         Toast.LENGTH_LONG).show();
          break;
        case SUCCESS:
          Toast.makeText(context,
                         context.getString(R.string.ImportFragment_import_complete),
                         Toast.LENGTH_LONG).show();
          break;
      }
    }

    @Override
    protected Integer doInBackground(Void... params) {
      try {
        PlaintextBackupImporter.importPlaintextFromSd(getActivity());
        return SUCCESS;
      } catch (NoExternalStorageException e) {
        Log.w(TAG, e);
        return NO_SD_CARD;
      } catch (IOException e) {
        Log.w(TAG, e);
        return ERROR_IO;
      }
    }
  }

  @SuppressLint("StaticFieldLeak")
  public class ImportWhatsappBackupTask extends AsyncTask<Void, Void, Integer> {

    private final boolean importGroups;
    private final boolean importMedia;
    private final boolean avoidDuplicates;

    public ImportWhatsappBackupTask(boolean importGroups, boolean avoidDuplicates, boolean importMedia) {
      this.importGroups = importGroups;
      this.avoidDuplicates = avoidDuplicates;
      this.importMedia = importMedia;
    }

    @Override
    protected void onPreExecute() {
      progressDialog = new ProgressDialog(getActivity());
      progressDialog.setTitle(getActivity().getString(R.string.ImportFragment_importing));
      progressDialog.setMessage(getActivity().getString(R.string.ImportFragment_import_whatsapp_backup_elipse));
      progressDialog.setCancelable(false);
      progressDialog.setIndeterminate(false);
      progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
      progressDialog.show();
    }

    protected void onPostExecute(Integer result) {
      Context context = getActivity();

      if (progressDialog != null)
        progressDialog.dismiss();

      if (context == null)
        return;

      switch (result) {
        case NO_SD_CARD:
          Toast.makeText(context,
                  context.getString(R.string.ImportFragment_no_whatsapp_backup_found),
                  Toast.LENGTH_LONG).show();
          break;
        case ERROR_IO:
          Toast.makeText(context,
                  context.getString(R.string.ImportFragment_error_importing_backup),
                  Toast.LENGTH_LONG).show();
          break;
        case SUCCESS:
          Toast.makeText(context,
                  context.getString(R.string.ImportFragment_import_complete),
                  Toast.LENGTH_LONG).show();
          break;
      }
    }

    @Override
    protected Integer doInBackground(Void... params) {
      try {
        WhatsappBackupImporter.importWhatsappFromSd(getActivity(), progressDialog, importGroups, avoidDuplicates, importMedia);
        return SUCCESS;
      } catch (NoExternalStorageException e) {
        Log.w(TAG, e);
        return NO_SD_CARD;
      } catch (IOException e) {
        Log.w(TAG, e);
        return ERROR_IO;
      }
    }
  }

  @SuppressLint("StaticFieldLeak")
  private class ExportPlaintextTask extends AsyncTask<Void, Void, Integer> {
    private ProgressDialog dialog;

    @Override
    protected void onPreExecute() {
      dialog = ProgressDialog.show(getActivity(),
                                   getActivity().getString(R.string.ExportFragment_exporting),
                                   getActivity().getString(R.string.ExportFragment_exporting_plaintext_to_storage),
                                   true, false);
    }

    @Override
    protected Integer doInBackground(Void... params) {
      try {
        PlaintextBackupExporter.exportPlaintextToSd(getActivity());
        return SUCCESS;
      } catch (NoExternalStorageException e) {
        Log.w(TAG, e);
        return NO_SD_CARD;
      } catch (IOException e) {
        Log.w(TAG, e);
        return ERROR_IO;
      }
    }

    @Override
    protected void onPostExecute(Integer result) {
      Context context = getActivity();

      if (dialog != null)
        dialog.dismiss();

      if (context == null)
        return;

      switch (result) {
        case NO_SD_CARD:
          Toast.makeText(context,
                         context.getString(R.string.ExportFragment_error_unable_to_write_to_storage),
                         Toast.LENGTH_LONG).show();
          break;
        case ERROR_IO:
          Toast.makeText(context,
                         context.getString(R.string.ExportFragment_error_while_writing_to_storage),
                         Toast.LENGTH_LONG).show();
          break;
        case SUCCESS:
          Toast.makeText(context,
                         context.getString(R.string.ExportFragment_export_successful),
                         Toast.LENGTH_LONG).show();
          break;
      }
    }
  }

  // JW: Encrypted backup import/export.
  public void handleImportEncryptedBackup() {
    AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
    builder.setIcon(R.drawable.ic_warning);
    builder.setTitle(getActivity().getString(R.string.ImportFragment_restore_encrypted_backup));
    builder.setMessage(getActivity().getString(R.string.ImportFragment_restoring_an_encrypted_backup_will_completely_replace_your_existing_keys));
    builder.setPositiveButton(getActivity().getString(R.string.ImportFragment_restore), (dialog, which) -> {
      Permissions.with(ImportExportFragment.this)
                 .request(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
                 .ifNecessary()
                 .withPermanentDenialDialog(getString(R.string.ImportExportFragment_signal_needs_the_storage_permission_in_order_to_read_from_external_storage_but_it_has_been_permanently_denied))
                 .onAllGranted(() -> new ImportEncryptedBackupTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR))
                 .onAnyDenied(() -> Toast.makeText(getContext(), R.string.ImportExportFragment_signal_needs_the_storage_permission_in_order_to_read_from_external_storage, Toast.LENGTH_LONG).show())
                 .execute();
    });
    builder.setNegativeButton(getActivity().getString(R.string.ImportFragment_cancel), null);
    builder.show();
  }

  @SuppressLint("StaticFieldLeak")
  private class ImportEncryptedBackupTask extends AsyncTask<Void, Void, Integer> {

    @Override
    protected void onPreExecute() {
      progressDialog = ProgressDialog.show(getActivity(),
                                           getActivity().getString(R.string.ImportFragment_restoring),
                                           getActivity().getString(R.string.ImportFragment_restoring_encrypted_backup),
                                           true, false);
    }

    protected void onPostExecute(Integer result) {
      Context context = getActivity();

      if (progressDialog != null)
        progressDialog.dismiss();

      if (context == null)
        return;

      switch (result) {
        case NO_SD_CARD:
          Toast.makeText(context,
                         context.getString(R.string.ImportFragment_no_encrypted_backup_found),
                         Toast.LENGTH_LONG).show();
          break;
        case ERROR_IO:
          Toast.makeText(context,
                         context.getString(R.string.ImportFragment_error_importing_backup),
                         Toast.LENGTH_LONG).show();
          break;
        case SUCCESS:
          //DatabaseFactory.getInstance(context).reset(context);
          //Intent intent = new Intent(context, KeyCachingService.class);
          //intent.setAction(KeyCachingService.CLEAR_KEY_ACTION);
          //context.startService(intent);

          // JW: Restart after OK press
          AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
          builder.setMessage(context.getString(R.string.ImportFragment_restore_complete))
                  .setCancelable(false)
                  .setPositiveButton(context.getString(R.string.ImportFragment_restore_ok), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                      ExitActivity.exitAndRemoveFromRecentApps(getActivity());
                    }
                  });
          AlertDialog alert = builder.create();
          alert.show();
      }
    }

    @Override
    protected Integer doInBackground(Void... params) {
      try {
        EncryptedBackupExporter.importFromSd(getActivity());
        return SUCCESS;
      } catch (NoExternalStorageException e) {
        Log.w(TAG, e);
        return NO_SD_CARD;
      } catch (IOException e) {
        Log.w(TAG, e);
        return ERROR_IO;
      }
    }
  }

  private void handleExportEncryptedBackup() {
    AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
    builder.setIcon(R.drawable.ic_info_outline);
    builder.setTitle(getActivity().getString(R.string.ExportFragment_export_to_sd_card));
    builder.setMessage(getActivity().getString(R.string.ExportFragment_this_will_export_your_encrypted_keys_settings_and_messages));
    builder.setPositiveButton(getActivity().getString(R.string.ExportFragment_export), (dialog, which) -> {
      Permissions.with(ImportExportFragment.this)
                 .request(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
                 .ifNecessary()
                 .withPermanentDenialDialog(getString(R.string.ImportExportFragment_signal_needs_the_storage_permission_in_order_to_write_to_external_storage_but_it_has_been_permanently_denied))
                 .onAllGranted(() -> new ExportEncryptedTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR))
                 .onAnyDenied(() -> Toast.makeText(getContext(), R.string.ImportExportFragment_signal_needs_the_storage_permission_in_order_to_write_to_external_storage, Toast.LENGTH_LONG).show())
                 .execute();
    });    
    builder.setNegativeButton(getActivity().getString(R.string.ExportFragment_cancel), null);
    builder.show();
  }

  private class ExportEncryptedTask extends AsyncTask<Void, Void, Integer> {
    private ProgressDialog dialog;

    @Override
    protected void onPreExecute() {
      dialog = ProgressDialog.show(getActivity(),
                                   getActivity().getString(R.string.ExportFragment_exporting),
                                   getActivity().getString(R.string.ExportFragment_exporting_keys_settings_and_messages),
                                   true, false);
    }

    @Override
    protected void onPostExecute(Integer result) {
      Context context = getActivity();

      if (dialog != null) dialog.dismiss();

      if (context == null) return;

      switch (result) {
        case NO_SD_CARD:
          Toast.makeText(context,
                         context.getString(R.string.ExportFragment_error_unable_to_write_to_storage),
                         Toast.LENGTH_LONG).show();
          break;
        case ERROR_IO:
          Toast.makeText(context,
                         context.getString(R.string.ExportFragment_error_while_writing_to_storage),
                         Toast.LENGTH_LONG).show();
          break;
        case SUCCESS:
          Toast.makeText(context,
                         context.getString(R.string.ExportFragment_export_successful),
                         Toast.LENGTH_LONG).show();
          break;
      }
    }

    @Override
    protected Integer doInBackground(Void... params) {
      try {
        EncryptedBackupExporter.exportToSd(getActivity());
        return SUCCESS;
      } catch (NoExternalStorageException e) {
        Log.w(TAG, e);
        return NO_SD_CARD;
      } catch (IOException e) {
        Log.w(TAG, e);
        return ERROR_IO;
      }
    }
  }

}
