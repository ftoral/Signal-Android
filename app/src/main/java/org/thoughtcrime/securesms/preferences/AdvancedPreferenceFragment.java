package org.thoughtcrime.securesms.preferences;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.preference.CheckBoxPreference;
import androidx.preference.Preference;

import com.google.firebase.iid.FirebaseInstanceId;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.ApplicationPreferencesActivity;
import org.thoughtcrime.securesms.BuildConfig;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.contacts.ContactAccessor;
import org.thoughtcrime.securesms.contacts.ContactIdentityManager;
import org.thoughtcrime.securesms.delete.DeleteAccountFragment;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.logsubmit.SubmitDebugLogActivity;
import org.thoughtcrime.securesms.phonenumbers.PhoneNumberFormatter;
import org.thoughtcrime.securesms.registration.RegistrationNavigationActivity;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.task.ProgressDialogAsyncTask;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.push.exceptions.AuthorizationFailedException;

import java.io.IOException;

//-----------------------------------------------------------------------------
// JW: added
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.pm.PackageManager;
import org.thoughtcrime.securesms.ExitActivity;
import org.thoughtcrime.securesms.jobs.FcmRefreshJob;
import org.thoughtcrime.securesms.jobs.RefreshAttributesJob;
import org.thoughtcrime.securesms.messages.IncomingMessageObserver;
import org.thoughtcrime.securesms.util.PlayServicesUtil;
//-----------------------------------------------------------------------------

public class AdvancedPreferenceFragment extends CorrectedPreferenceFragment {
  private static final String TAG = AdvancedPreferenceFragment.class.getSimpleName();

  private static final String PUSH_MESSAGING_PREF   = "pref_toggle_push_messaging";
  private static final String SUBMIT_DEBUG_LOG_PREF = "pref_submit_debug_logs";
  private static final String INTERNAL_PREF         = "pref_internal";
  private static final String ADVANCED_PIN_PREF     = "pref_advanced_pin_settings";
  private static final String DELETE_ACCOUNT        = "pref_delete_account";
  private static final String FCM_PREF              = "pref_toggle_fcm"; // JW: added

  private static final int PICK_IDENTITY_CONTACT = 1;

  @Override
  public void onCreate(Bundle paramBundle) {
    super.onCreate(paramBundle);

    initializeIdentitySelection();

    Preference submitDebugLog = this.findPreference(SUBMIT_DEBUG_LOG_PREF);
    submitDebugLog.setOnPreferenceClickListener(new SubmitDebugLogListener());
    submitDebugLog.setSummary(getVersion(getActivity()));

    Preference pinSettings = this.findPreference(ADVANCED_PIN_PREF);
    pinSettings.setOnPreferenceClickListener(preference -> {
      getApplicationPreferencesActivity().pushFragment(new AdvancedPinPreferenceFragment()); // JW: fix in 5.0.1
      return false;
    });

    Preference internalPreference = this.findPreference(INTERNAL_PREF);
    internalPreference.setVisible(FeatureFlags.internalUser());
    internalPreference.setOnPreferenceClickListener(preference -> {
      if (FeatureFlags.internalUser()) {
        getApplicationPreferencesActivity().pushFragment(new InternalOptionsPreferenceFragment());
        return true;
      } else {
        return false;
      }
    });

    Preference deleteAccount = this.findPreference(DELETE_ACCOUNT);
    deleteAccount.setOnPreferenceClickListener(preference -> {
      getApplicationPreferencesActivity().pushFragment(new DeleteAccountFragment());
      return false;
    });
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    view.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.signal_background_tertiary));

    View                   list   = view.findViewById(R.id.recycler_view);
    ViewGroup.LayoutParams params = list.getLayoutParams();

    params.height = ActionBar.LayoutParams.WRAP_CONTENT;
    list.setLayoutParams(params);
    list.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.signal_background_primary));
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
    addPreferencesFromResource(R.xml.preferences_advanced);
  }

  @Override
  public void onResume() {
    super.onResume();
    getApplicationPreferencesActivity().getSupportActionBar().setTitle(R.string.preferences__advanced);

    initializePushMessagingToggle();
  }

  @Override
  public void onActivityResult(int reqCode, int resultCode, Intent data) {
    super.onActivityResult(reqCode, resultCode, data);

    Log.i(TAG, "Got result: " + resultCode + " for req: " + reqCode);
    if (resultCode == Activity.RESULT_OK && reqCode == PICK_IDENTITY_CONTACT) {
      handleIdentitySelection(data);
    }
  }

  private @NonNull ApplicationPreferencesActivity getApplicationPreferencesActivity() {
    return (ApplicationPreferencesActivity) requireActivity();
  }

  private void initializePushMessagingToggle() {
    CheckBoxPreference preference = (CheckBoxPreference)this.findPreference(PUSH_MESSAGING_PREF);

    if (TextSecurePreferences.isPushRegistered(getActivity())) {
      preference.setChecked(true);
      preference.setSummary(PhoneNumberFormatter.prettyPrint(TextSecurePreferences.getLocalNumber(getActivity())));
    } else {
      preference.setChecked(false);
      preference.setSummary(R.string.preferences__free_private_messages_and_calls);
    }

    preference.setOnPreferenceChangeListener(new PushMessagingClickListener());
    initializeFcmToggle(); // JW: added
  }

  // JW: added method
  private void initializeFcmToggle() {
    CheckBoxPreference preference = (CheckBoxPreference)this.findPreference(FCM_PREF);

    preference.setEnabled(TextSecurePreferences.isPushRegistered(getActivity()));
    preference.setChecked(!TextSecurePreferences.isFcmDisabled(getActivity()));
    preference.setOnPreferenceChangeListener(new FcmClickListener());
  }

  private void initializeIdentitySelection() {
    ContactIdentityManager identity = ContactIdentityManager.getInstance(getActivity());

    Preference preference = this.findPreference(TextSecurePreferences.IDENTITY_PREF);

    if (identity.isSelfIdentityAutoDetected()) {
      this.getPreferenceScreen().removePreference(preference);
    } else {
      Uri contactUri = identity.getSelfIdentityUri();

      if (contactUri != null) {
        String contactName = ContactAccessor.getInstance().getNameFromContact(getActivity(), contactUri);
        preference.setSummary(String.format(getString(R.string.ApplicationPreferencesActivity_currently_s),
                                            contactName));
      }

      preference.setOnPreferenceClickListener(new IdentityPreferenceClickListener());
    }
  }

  private @NonNull String getVersion(@Nullable Context context) {
    if (context == null) return "";

    String app     = context.getString(R.string.app_name);
    String version = BuildConfig.VERSION_NAME;

    return String.format("%s %s", app, version);
  }

  private class IdentityPreferenceClickListener implements Preference.OnPreferenceClickListener {
    @Override
    public boolean onPreferenceClick(Preference preference) {
      Intent intent = new Intent(Intent.ACTION_PICK);
      intent.setType(ContactsContract.Contacts.CONTENT_TYPE);
      startActivityForResult(intent, PICK_IDENTITY_CONTACT);
      return true;
    }
  }

  private void handleIdentitySelection(Intent data) {
    Uri contactUri = data.getData();

    if (contactUri != null) {
      TextSecurePreferences.setIdentityContactUri(getActivity(), contactUri.toString());
      initializeIdentitySelection();
    }
  }

  private class SubmitDebugLogListener implements Preference.OnPreferenceClickListener {
    @Override
    public boolean onPreferenceClick(Preference preference) {
      final Intent intent = new Intent(getActivity(), SubmitDebugLogActivity.class);
      startActivity(intent);
      return true;
    }
  }

  private class PushMessagingClickListener implements Preference.OnPreferenceChangeListener {
    private static final int SUCCESS       = 0;
    private static final int NETWORK_ERROR = 1;

    private class DisablePushMessagesTask extends ProgressDialogAsyncTask<Void, Void, Integer> {
      private final CheckBoxPreference checkBoxPreference;

      public DisablePushMessagesTask(final CheckBoxPreference checkBoxPreference) {
        super(getActivity(), R.string.ApplicationPreferencesActivity_unregistering, R.string.ApplicationPreferencesActivity_unregistering_from_signal_messages_and_calls);
        this.checkBoxPreference = checkBoxPreference;
      }

      @Override
      protected void onPostExecute(Integer result) {
        super.onPostExecute(result);
        switch (result) {
        case NETWORK_ERROR:
          Toast.makeText(getActivity(),
                         R.string.ApplicationPreferencesActivity_error_connecting_to_server,
                         Toast.LENGTH_LONG).show();
          break;
        case SUCCESS:
          TextSecurePreferences.setPushRegistered(getActivity(), false);
          SignalStore.registrationValues().clearRegistrationComplete();
          initializePushMessagingToggle();
          break;
        }
      }

      @Override
      protected Integer doInBackground(Void... params) {
        try {
          Context                     context        = getActivity();
          SignalServiceAccountManager accountManager = ApplicationDependencies.getSignalServiceAccountManager();

          try {
            accountManager.setGcmId(Optional.<String>absent());
          } catch (AuthorizationFailedException e) {
            Log.w(TAG, e);
          }

          if (!TextSecurePreferences.isFcmDisabled(context)) {
            FirebaseInstanceId.getInstance().deleteInstanceId();
          }

          return SUCCESS;
        } catch (IOException ioe) {
          Log.w(TAG, ioe);
          return NETWORK_ERROR;
        }
      }
    }

    @Override
    public boolean onPreferenceChange(final Preference preference, Object newValue) {
      if (((CheckBoxPreference)preference).isChecked()) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setIcon(R.drawable.ic_info_outline);
        builder.setTitle(R.string.ApplicationPreferencesActivity_disable_signal_messages_and_calls);
        builder.setMessage(R.string.ApplicationPreferencesActivity_disable_signal_messages_and_calls_by_unregistering);
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            new DisablePushMessagesTask((CheckBoxPreference)preference).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
          }
        });
        builder.show();
      } else {
        startActivity(RegistrationNavigationActivity.newIntentForReRegistration(requireContext()));
      }

      return false;
    }
  }

  // JW: added class
  private class FcmClickListener implements Preference.OnPreferenceChangeListener {

    private void cleanGcmId() {
      try {
        SignalServiceAccountManager accountManager = ApplicationDependencies.getSignalServiceAccountManager();
        accountManager.setGcmId(Optional.<String>absent());
      } catch (IOException e) {
        Log.w(TAG, e.getMessage());
        Toast.makeText(getActivity(), R.string.ApplicationPreferencesActivity_error_connecting_to_server, Toast.LENGTH_LONG).show();
      }
    }

    private void exitAndRestart(Context context) {
      // JW: Restart after OK press
      AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
      builder.setMessage(context.getString(R.string.preferences_advanced__need_to_restart))
        .setCancelable(false)
        .setPositiveButton(context.getString(R.string.ImportFragment_restore_ok), new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            restartApp(context);
          }
        });
      AlertDialog alert = builder.create();
      alert.show();
    }

    // Create a pending intent to restart Signal
    private void restartApp(Context context) {
      try {
        if (context != null) {
          PackageManager pm = context.getPackageManager();

          if (pm != null) {
            Intent startActivity = pm.getLaunchIntentForPackage(context.getPackageName());
            if (startActivity != null) {
              startActivity.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
              int pendingIntentId = 223344;
              PendingIntent pendingIntent = PendingIntent.getActivity(context, pendingIntentId, startActivity, PendingIntent.FLAG_CANCEL_CURRENT);
              AlarmManager mgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
              mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 100, pendingIntent);
              System.exit(0);
              //ExitActivity.exitAndRemoveFromRecentApps(getActivity());
            } else {
              Log.e(TAG, "restartApp: unable to restart application, startActivity == null");
              System.exit(0);
            }
          } else {
            Log.e(TAG, "restartApp: unable to restart application, Package manager == null");
            System.exit(0);
          }
        } else {
          Log.e(TAG, "restartApp: unable to restart application, Context == null");
          System.exit(0);
        }
      } catch (Exception e) {
        Log.e(TAG, "restartApp: unable to restart application: " + e.getMessage());
        System.exit(0);
      }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
      final Context context = preference.getContext();

      boolean enabled = (boolean) newValue;

      if (enabled) {
        PlayServicesUtil.PlayServicesStatus status = PlayServicesUtil.getPlayServicesStatus(context);

        if (status == PlayServicesUtil.PlayServicesStatus.SUCCESS) {
          TextSecurePreferences.setFcmDisabled(context, false);
          ApplicationDependencies.getJobManager().startChain(new FcmRefreshJob())
            .then(new RefreshAttributesJob())
            .enqueue();

          context.stopService(new Intent(context, IncomingMessageObserver.ForegroundService.class));

          Log.i(TAG, "FcmClickListener.onPreferenceChange: enabled fcm");
          exitAndRestart(context);
        } else {
          // No Play Services found
          Toast.makeText(getActivity(), R.string.preferences_advanced__play_services_not_found, Toast.LENGTH_LONG).show();
          preference.setEnabled(false);
          return false;
        }
      } else { // switch to websockets
        TextSecurePreferences.setFcmDisabled(context, true);
        TextSecurePreferences.setFcmToken(context, null);
        cleanGcmId();
        ApplicationDependencies.getJobManager().add(new RefreshAttributesJob());
        Log.i(TAG, "FcmClickListener.onPreferenceChange: disabled fcm");
        exitAndRestart(context);
      }
      return true;
    }
  }
}
