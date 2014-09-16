/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.telecom;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.telecom.CallState;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;

import com.android.internal.telecom.ITelecomService;

import java.util.List;

/**
 * Implementation of the ITelecom interface.
 */
public class TelecomServiceImpl extends ITelecomService.Stub {
    private static final String REGISTER_PROVIDER_OR_SUBSCRIPTION =
            "com.android.server.telecom.permission.REGISTER_PROVIDER_OR_SUBSCRIPTION";

    /** ${inheritDoc} */
    @Override
    public IBinder asBinder() {
        return super.asBinder();
    }

 /**
     * A request object for use with {@link MainThreadHandler}. Requesters should wait() on the
     * request after sending. The main thread will notify the request when it is complete.
     */
    private static final class MainThreadRequest {
        /** The result of the request that is run on the main thread */
        public Object result;
    }

    /**
     * A handler that processes messages on the main thread in the phone process. Since many
     * of the Phone calls are not thread safe this is needed to shuttle the requests from the
     * inbound binder threads to the main thread in the phone process.
     */
    private final class MainThreadHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (msg.obj instanceof MainThreadRequest) {
                MainThreadRequest request = (MainThreadRequest) msg.obj;
                Object result = null;
                switch (msg.what) {
                    case MSG_SILENCE_RINGER:
                        mCallsManager.getRinger().silence();
                        break;
                    case MSG_SHOW_CALL_SCREEN:
                        mCallsManager.getInCallController().bringToForeground(msg.arg1 == 1);
                        break;
                    case MSG_END_CALL:
                        result = endCallInternal();
                        break;
                    case MSG_ACCEPT_RINGING_CALL:
                        acceptRingingCallInternal();
                        break;
                    case MSG_CANCEL_MISSED_CALLS_NOTIFICATION:
                        mMissedCallNotifier.clearMissedCalls();
                        break;
                    case MSG_IS_TTY_SUPPORTED:
                        result = mCallsManager.isTtySupported();
                        break;
                    case MSG_GET_CURRENT_TTY_MODE:
                        result = mCallsManager.getCurrentTtyMode();
                        break;
                }

                if (result != null) {
                    request.result = result;
                    synchronized(request) {
                        request.notifyAll();
                    }
                }
            }
        }
    }

    /** Private constructor; @see init() */
    private static final String TAG = TelecomServiceImpl.class.getSimpleName();

    private static final String SERVICE_NAME = "telecom";

    private static final int MSG_SILENCE_RINGER = 1;
    private static final int MSG_SHOW_CALL_SCREEN = 2;
    private static final int MSG_END_CALL = 3;
    private static final int MSG_ACCEPT_RINGING_CALL = 4;
    private static final int MSG_CANCEL_MISSED_CALLS_NOTIFICATION = 5;
    private static final int MSG_IS_TTY_SUPPORTED = 6;
    private static final int MSG_GET_CURRENT_TTY_MODE = 7;

    /** The singleton instance. */
    private static TelecomServiceImpl sInstance;

    private final MainThreadHandler mMainThreadHandler = new MainThreadHandler();
    private final CallsManager mCallsManager = CallsManager.getInstance();
    private final MissedCallNotifier mMissedCallNotifier;
    private final PhoneAccountRegistrar mPhoneAccountRegistrar;
    private final AppOpsManager mAppOpsManager;

    private TelecomServiceImpl(
            MissedCallNotifier missedCallNotifier, PhoneAccountRegistrar phoneAccountRegistrar) {
        mMissedCallNotifier = missedCallNotifier;
        mPhoneAccountRegistrar = phoneAccountRegistrar;
        mAppOpsManager =
                (AppOpsManager) TelecomApp.getInstance().getSystemService(Context.APP_OPS_SERVICE);

        publish();
    }

    /**
     * Initialize the singleton TelecommServiceImpl instance.
     * This is only done once, at startup, from TelecommApp.onCreate().
     */
    static TelecomServiceImpl init(
            MissedCallNotifier missedCallNotifier, PhoneAccountRegistrar phoneAccountRegistrar) {
        synchronized (TelecomServiceImpl.class) {
            if (sInstance == null) {
                sInstance = new TelecomServiceImpl(missedCallNotifier, phoneAccountRegistrar);
            } else {
                Log.wtf(TAG, "init() called multiple times!  sInstance %s", sInstance);
            }
            return sInstance;
        }
    }

    //
    // Implementation of the ITelecomService interface.
    //

    @Override
    public PhoneAccountHandle getDefaultOutgoingPhoneAccount(String uriScheme) {
        try {
            return mPhoneAccountRegistrar.getDefaultOutgoingPhoneAccount(uriScheme);
        } catch (Exception e) {
            Log.e(this, e, "getDefaultOutgoingPhoneAccount");
            throw e;
        }
    }

    @Override
    public PhoneAccountHandle getUserSelectedOutgoingPhoneAccount() {
        try {
            return mPhoneAccountRegistrar.getUserSelectedOutgoingPhoneAccount();
        } catch (Exception e) {
            Log.e(this, e, "getUserSelectedOutgoingPhoneAccount");
            throw e;
        }
    }

    @Override
    public void setUserSelectedOutgoingPhoneAccount(PhoneAccountHandle accountHandle) {
        enforceModifyPermission();

        try {
            mPhoneAccountRegistrar.setUserSelectedOutgoingPhoneAccount(accountHandle);
        } catch (Exception e) {
            Log.e(this, e, "setUserSelectedOutgoingPhoneAccount");
            throw e;
        }
    }

    @Override
    public List<PhoneAccountHandle> getEnabledPhoneAccounts() {
        try {
            return mPhoneAccountRegistrar.getEnabledPhoneAccounts();
        } catch (Exception e) {
            Log.e(this, e, "getEnabledPhoneAccounts");
            throw e;
        }
    }

    @Override
    public List<PhoneAccountHandle> getPhoneAccountsSupportingScheme(String uriScheme) {
        try {
            return mPhoneAccountRegistrar.getEnabledPhoneAccounts(uriScheme);
        } catch (Exception e) {
            Log.e(this, e, "getPhoneAccountsSupportingScheme");
            throw e;
        }
    }

    @Override
    public PhoneAccount getPhoneAccount(PhoneAccountHandle accountHandle) {
        try {
            return mPhoneAccountRegistrar.getPhoneAccount(accountHandle);
        } catch (Exception e) {
            Log.e(this, e, "getPhoneAccount %s", accountHandle);
            throw e;
        }
    }

    @Override
    public int getAllPhoneAccountsCount() {
        try {
            return mPhoneAccountRegistrar.getAllPhoneAccountsCount();
        } catch (Exception e) {
            Log.e(this, e, "getAllPhoneAccountsCount");
            throw e;
        }
    }

    @Override
    public List<PhoneAccount> getAllPhoneAccounts() {
        try {
            return mPhoneAccountRegistrar.getAllPhoneAccounts();
        } catch (Exception e) {
            Log.e(this, e, "getAllPhoneAccounts");
            throw e;
        }
    }

    @Override
    public List<PhoneAccountHandle> getAllPhoneAccountHandles() {
        try {
            return mPhoneAccountRegistrar.getAllPhoneAccountHandles();
        } catch (Exception e) {
            Log.e(this, e, "getAllPhoneAccounts");
            throw e;
        }
    }

    @Override
    public PhoneAccountHandle getSimCallManager() {
        try {
            return mPhoneAccountRegistrar.getSimCallManager();
        } catch (Exception e) {
            Log.e(this, e, "getSimCallManager");
            throw e;
        }
    }

    @Override
    public void setSimCallManager(PhoneAccountHandle accountHandle) {
        enforceModifyPermission();

        try {
            mPhoneAccountRegistrar.setSimCallManager(accountHandle);
        } catch (Exception e) {
            Log.e(this, e, "setSimCallManager");
            throw e;
        }
    }

    @Override
    public List<PhoneAccountHandle> getSimCallManagers() {
        try {
            return mPhoneAccountRegistrar.getConnectionManagerPhoneAccounts();
        } catch (Exception e) {
            Log.e(this, e, "getSimCallManagers");
            throw e;
        }
    }

    @Override
    public void registerPhoneAccount(PhoneAccount account) {
        try {
            enforcePhoneAccountModificationForPackage(
                    account.getAccountHandle().getComponentName().getPackageName());
            if (account.hasCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER) ||
                account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {
                enforceRegisterProviderOrSubscriptionPermission();
            }

            // If the account is marked as enabled or has CAPABILITY_ALWAYS_ENABLED set, check to
            // ensure the caller has modify permission.  If they do not, set the account to be
            // disabled and remove CAPABILITY_ALWAYS_ENABLED.
            if (account.isEnabled() ||
                    account.hasCapabilities(PhoneAccount.CAPABILITY_ALWAYS_ENABLED)) {
                try {
                    enforceModifyPermission();
                } catch (SecurityException e) {
                    // Caller does not have modify permission, so change account to disabled by
                    // default and remove the CAPABILITY_ALWAYS_ENABLED capability.
                    int capabilities = account.getCapabilities() &
                            ~PhoneAccount.CAPABILITY_ALWAYS_ENABLED;
                    account = account.toBuilder()
                            .setEnabled(false)
                            .setCapabilities(capabilities)
                            .build();
                }
            }

            mPhoneAccountRegistrar.registerPhoneAccount(account);
        } catch (Exception e) {
            Log.e(this, e, "registerPhoneAccount %s", account);
            throw e;
        }
    }

    @Override
    public void setPhoneAccountEnabled(PhoneAccountHandle account, boolean isEnabled) {
        try {
            enforceModifyPermission();
            mPhoneAccountRegistrar.setPhoneAccountEnabled(account, isEnabled);
        } catch (Exception e) {
            Log.e(this, e, "setPhoneAccountEnabled %s %d", account, isEnabled ? 1 : 0);
            throw e;
        }
    }

    @Override
    public void unregisterPhoneAccount(PhoneAccountHandle accountHandle) {
        try {
            enforcePhoneAccountModificationForPackage(
                    accountHandle.getComponentName().getPackageName());
            mPhoneAccountRegistrar.unregisterPhoneAccount(accountHandle);
        } catch (Exception e) {
            Log.e(this, e, "unregisterPhoneAccount %s", accountHandle);
            throw e;
        }
    }

    @Override
    public void clearAccounts(String packageName) {
        try {
            enforcePhoneAccountModificationForPackage(packageName);
            mPhoneAccountRegistrar.clearAccounts(packageName);
        } catch (Exception e) {
            Log.e(this, e, "clearAccounts %s", packageName);
            throw e;
        }
    }

    /**
     * @see android.telecom.TelecomManager#silenceRinger
     */
    @Override
    public void silenceRinger() {
        Log.d(this, "silenceRinger");
        enforceModifyPermission();
        sendRequestAsync(MSG_SILENCE_RINGER, 0);
    }

    /**
     * @see android.telecom.TelecomManager#getDefaultPhoneApp
     */
    @Override
    public ComponentName getDefaultPhoneApp() {
        Resources resources = TelecomApp.getInstance().getResources();
        return new ComponentName(
                resources.getString(R.string.ui_default_package),
                resources.getString(R.string.dialer_default_class));
    }

    /**
     * @see android.telecom.TelecomManager#isInCall
     */
    @Override
    public boolean isInCall() {
        enforceReadPermission();
        // Do not use sendRequest() with this method since it could cause a deadlock with
        // audio service, which we call into from the main thread: AudioManager.setMode().
        final int callState = mCallsManager.getCallState();
        return callState == TelephonyManager.CALL_STATE_OFFHOOK
                || callState == TelephonyManager.CALL_STATE_RINGING;
    }

    /**
     * @see android.telecom.TelecomManager#isRinging
     */
    @Override
    public boolean isRinging() {
        enforceReadPermission();
        return mCallsManager.getCallState() == TelephonyManager.CALL_STATE_RINGING;
    }

    /**
     * @see TelecomManager#getCallState
     */
    @Override
    public int getCallState() {
        enforceReadPermission();
        return mCallsManager.getCallState();
    }

    /**
     * @see android.telecom.TelecomManager#endCall
     */
    @Override
    public boolean endCall() {
        enforceModifyPermission();
        return (boolean) sendRequest(MSG_END_CALL);
    }

    /**
     * @see android.telecom.TelecomManager#acceptRingingCall
     */
    @Override
    public void acceptRingingCall() {
        enforceModifyPermission();
        sendRequestAsync(MSG_ACCEPT_RINGING_CALL, 0);
    }

    /**
     * @see android.telecom.TelecomManager#showInCallScreen
     */
    @Override
    public void showInCallScreen(boolean showDialpad) {
        enforceReadPermissionOrDefaultDialer();
        sendRequestAsync(MSG_SHOW_CALL_SCREEN, showDialpad ? 1 : 0);
    }

    /**
     * @see android.telecom.TelecomManager#cancelMissedCallsNotification
     */
    @Override
    public void cancelMissedCallsNotification() {
        enforceModifyPermissionOrDefaultDialer();
        sendRequestAsync(MSG_CANCEL_MISSED_CALLS_NOTIFICATION, 0);
    }

    /**
     * @see android.telecom.TelecomManager#handleMmi
     */
    @Override
    public boolean handlePinMmi(String dialString) {
        enforceModifyPermissionOrDefaultDialer();

        // Switch identity so that TelephonyManager checks Telecom's permissions instead.
        long token = Binder.clearCallingIdentity();
        boolean retval = getTelephonyManager().handlePinMmi(dialString);
        Binder.restoreCallingIdentity(token);

        return retval;
    }

    /**
     * @see android.telecom.TelecomManager#isTtySupported
     */
    @Override
    public boolean isTtySupported() {
        enforceReadPermission();
        return (boolean) sendRequest(MSG_IS_TTY_SUPPORTED);
    }

    /**
     * @see android.telecom.TelecomManager#getCurrentTtyMode
     */
    @Override
    public int getCurrentTtyMode() {
        enforceReadPermission();
        return (int) sendRequest(MSG_GET_CURRENT_TTY_MODE);
    }

    /**
     * @see android.telecom.TelecomManager#addNewIncomingCall
     */
    @Override
    public void addNewIncomingCall(PhoneAccountHandle phoneAccountHandle, Bundle extras) {
        if (phoneAccountHandle != null && phoneAccountHandle.getComponentName() != null) {
            mAppOpsManager.checkPackage(
                    Binder.getCallingUid(), phoneAccountHandle.getComponentName().getPackageName());

            Intent intent = new Intent(TelecomManager.ACTION_INCOMING_CALL);
            intent.setPackage(TelecomApp.getInstance().getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle);
            if (extras != null) {
                intent.putExtra(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, extras);
            }

            long token = Binder.clearCallingIdentity();
            TelecomApp.getInstance().startActivityAsUser(intent, UserHandle.CURRENT);
            Binder.restoreCallingIdentity(token);
        }
    }

    //
    // Supporting methods for the ITelecomService interface implementation.
    //

    private void acceptRingingCallInternal() {
        Call call = mCallsManager.getFirstCallWithState(CallState.RINGING);
        if (call != null) {
            call.answer(call.getVideoState());
        }
    }

    private boolean endCallInternal() {
        // Always operate on the foreground call if one exists, otherwise get the first call in
        // priority order by call-state.
        Call call = mCallsManager.getForegroundCall();
        if (call == null) {
            call = mCallsManager.getFirstCallWithState(
                    CallState.ACTIVE,
                    CallState.DIALING,
                    CallState.RINGING,
                    CallState.ON_HOLD);
        }

        if (call != null) {
            if (call.getState() == CallState.RINGING) {
                call.reject(false /* rejectWithMessage */, null);
            } else {
                call.disconnect();
            }
            return true;
        }

        return false;
    }

    private void enforcePhoneAccountModificationForPackage(String packageName) {
        // TODO: Use a new telecomm permission for this instead of reusing modify.

        int result = TelecomApp.getInstance().checkCallingOrSelfPermission(
                Manifest.permission.MODIFY_PHONE_STATE);

        // Callers with MODIFY_PHONE_STATE can use the PhoneAccount mechanism to implement
        // built-in behavior even when PhoneAccounts are not exposed as a third-part API. They
        // may also modify PhoneAccounts on behalf of any 'packageName'.

        if (result != PackageManager.PERMISSION_GRANTED) {
            // Other callers are only allowed to modify PhoneAccounts if the relevant system
            // feature is enabled ...
            enforceConnectionServiceFeature();
            // ... and the PhoneAccounts they refer to are for their own package.
            enforceCallingPackage(packageName);
        }
    }

    private void enforceReadPermissionOrDefaultDialer() {
        if (!isDefaultDialerCalling()) {
            enforceReadPermission();
        }
    }

    private void enforceModifyPermissionOrDefaultDialer() {
        if (!isDefaultDialerCalling()) {
            enforceModifyPermission();
        }
    }

    private void enforceCallingPackage(String packageName) {
        mAppOpsManager.checkPackage(Binder.getCallingUid(), packageName);
    }

    private void enforceConnectionServiceFeature() {
        enforceFeature(PackageManager.FEATURE_CONNECTION_SERVICE);
    }

    private void enforceRegisterProviderOrSubscriptionPermission() {
        enforcePermission(REGISTER_PROVIDER_OR_SUBSCRIPTION);
    }

    private void enforceReadPermission() {
        enforcePermission(Manifest.permission.READ_PHONE_STATE);
    }

    private void enforceModifyPermission() {
        enforcePermission(Manifest.permission.MODIFY_PHONE_STATE);
    }

    private void enforcePermission(String permission) {
        TelecomApp.getInstance().enforceCallingOrSelfPermission(permission, null);
    }

    private void enforceFeature(String feature) {
        PackageManager pm = TelecomApp.getInstance().getPackageManager();
        if (!pm.hasSystemFeature(feature)) {
            throw new UnsupportedOperationException(
                    "System does not support feature " + feature);
        }
    }

    private boolean isDefaultDialerCalling() {
        ComponentName defaultDialerComponent = getDefaultPhoneApp();
        if (defaultDialerComponent != null) {
            try {
                mAppOpsManager.checkPackage(
                        Binder.getCallingUid(), defaultDialerComponent.getPackageName());
                return true;
            } catch (SecurityException e) {
                Log.e(TAG, e, "Could not get default dialer.");
            }
        }
        return false;
    }

    private TelephonyManager getTelephonyManager() {
        return (TelephonyManager)
                TelecomApp.getInstance().getSystemService(Context.TELEPHONY_SERVICE);
    }

    private void publish() {
        Log.d(this, "publish: %s", this);
        ServiceManager.addService(SERVICE_NAME, this);
    }

    private MainThreadRequest sendRequestAsync(int command, int arg1) {
        MainThreadRequest request = new MainThreadRequest();
        mMainThreadHandler.obtainMessage(command, arg1, 0, request).sendToTarget();
        return request;
    }

    /**
     * Posts the specified command to be executed on the main thread, waits for the request to
     * complete, and returns the result.
     */
    private Object sendRequest(int command) {
        if (Looper.myLooper() == mMainThreadHandler.getLooper()) {
            MainThreadRequest request = new MainThreadRequest();
            mMainThreadHandler.handleMessage(mMainThreadHandler.obtainMessage(command, request));
            return request.result;
        } else {
            MainThreadRequest request = sendRequestAsync(command, 0);

            // Wait for the request to complete
            synchronized (request) {
                while (request.result == null) {
                    try {
                        request.wait();
                    } catch (InterruptedException e) {
                        // Do nothing, go back and wait until the request is complete
                    }
                }
            }
            return request.result;
        }
    }
}
