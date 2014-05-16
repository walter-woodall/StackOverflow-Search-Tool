// SOURCE: https://raw.githubusercontent.com/coinbase/coinbase-android/master/coinbase-android/src/com/coinbase/android/TransferFragment.java
package com.coinbase.android;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.coinbase.android.delayedtx.DelayedTransaction;
import com.coinbase.android.delayedtx.DelayedTransactionDialogFragment;
import com.coinbase.android.Utils.CurrencyType;
import com.coinbase.android.pin.PINManager;
import com.coinbase.api.RpcManager;

import org.acra.ACRA;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TransferFragment extends Fragment implements CoinbaseFragment {

  protected enum TransferType {
    SEND(R.string.transfer_send_money, "send"),
    REQUEST(R.string.transfer_request_money, "request");

    private int mFriendlyName;
    private String mRequestName;

    private TransferType(int friendlyName, String requestName) {

      mFriendlyName = friendlyName;
      mRequestName = requestName;
    }

    public int getName() {

      return mFriendlyName;
    }

    public String getRequestName() {

      return mRequestName;
    }
  }

  private enum TransferButton {
    SEND, EMAIL, QR, NFC;
  }

  private class DoTransferTask extends AsyncTask<Object, Void, Object[]> {

    private ProgressDialog mDialog;
    private Object[] params;

    @Override
    protected void onPreExecute() {

      super.onPreExecute();
      mDialog = ProgressDialog.show(mParent, null, getString(R.string.transfer_progress));
    }

    protected Object[] doInBackground(Object... params) {

      this.params = params;
      return doTransfer((TransferType) params[0], (String) params[1], (String) params[2], (String) params[3], (String) params[4], (String) params[5]);
    }

    protected void onPostExecute(Object[] result) {

      try {
        mDialog.dismiss();
      } catch (Exception e) {
        // ProgressDialog has been destroyed already
      }

      boolean success = (Boolean) result[0];
      if(success) {

        TransferType type = (TransferType) result[2];

        int messageId = type == TransferType.SEND ? R.string.transfer_success_send : R.string.transfer_success_request;
        String text = String.format(getString(messageId), (String) result[1], (String) result[3]);
        Toast.makeText(mParent, text, Toast.LENGTH_SHORT).show();

        // Clear form
        clearForm();
        
        // Add new transaction to transactions screen
        JSONObject tx = (JSONObject) result[4];
        mParent.getTransactionsFragment().insertTransactionAnimated(0, tx, type == TransferType.SEND ? "tx" : "request", tx.optString("status"));
        Utils.incrementPrefsInt(mParent, Constants.KEY_ACCOUNT_APP_USAGE);
        mParent.switchTo(MainActivity.FRAGMENT_INDEX_TRANSACTIONS);
      } else {

        String neededFee = (String) result[2];
        if (neededFee == null) {
          Utils.showMessageDialog(getFragmentManager(), (String) result[1]);
        } else {

          // Show confirm dialog with fee
          ConfirmTransferFragment dialog = new ConfirmTransferFragment();

          Bundle b = new Bundle();

          b.putSerializable("type", (TransferType) params[0]);
          b.putString("amount", (String) params[1]);
          b.putString("currency", (String) params[2]);
          b.putString("notes", (String) params[3]);
          b.putString("toFrom", (String) params[4]);
          b.putString("feeAmount", neededFee);

          dialog.setArguments(b);

          dialog.show(getFragmentManager(), "confirm");
        }
      }
    }
  }

  public static class ConfirmTransferFragment extends DialogFragment {

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {

      final TransferType type = (TransferType) getArguments().getSerializable("type");
      final String amount = getArguments().getString("amount"),
          currency = getArguments().getString("currency"),
          toFrom = getArguments().getString("toFrom"),
          notes = getArguments().getString("notes");
      final String feeAmount = getArguments().getString("feeAmount");

      int messageResource;

      if(type == TransferType.REQUEST) {
        messageResource =  R.string.transfer_confirm_message_request;
      } else {
        if(feeAmount != null) {
          messageResource =  R.string.transfer_confirm_message_send_fee;
        } else {
          messageResource =  R.string.transfer_confirm_message_send;
        }
      }

      String message = String.format(getString(messageResource), Utils.formatCurrencyAmount(amount), toFrom, currency, feeAmount);

      AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
      builder.setMessage(message)
      .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int id) {

          // Complete transfer
          TransferFragment parent = getActivity() == null ? null : ((MainActivity) getActivity()).getTransferFragment();

          if(parent != null) {
            parent.startTransferTask(type, amount, currency, notes, toFrom, feeAmount);
          }
        }
      })
      .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int id) {
          // User cancelled the dialog
        }
      });

      return builder.create();
    }
  }

  private class RefreshExchangeRateTask extends AsyncTask<Void, Void, JSONObject> {

    @Override
    protected JSONObject doInBackground(Void... params) {

      try {

        JSONObject exchangeRates = RpcManager.getInstance().callGet(mParent, "currencies/exchange_rates");
        return exchangeRates;
      } catch (IOException e) {
        e.printStackTrace();
      } catch (JSONException e) {
        ACRA.getErrorReporter().handleException(new RuntimeException("RefreshExchangeRate", e));
        e.printStackTrace();
      }

      return null;
    }

    @Override
    protected void onPostExecute(JSONObject result) {

      mNativeExchangeTask = null;

      if(result != null) {
        mNativeExchangeRates = result;
        mNativeExchangeRateTime = System.currentTimeMillis();
        doNativeCurrencyUpdate();
      } else {
        mNativeAmount.setText(R.string.transfer_fxrate_failure);
      }
    }


  }

  public static final int EXCHANGE_RATE_EXPIRE_TIME = 60000 * 5; // Expires in 5 minutes

  private MainActivity mParent;

  private Spinner mTransferTypeView, mTransferCurrencyView;
  private Button mSubmitSend, mSubmitEmail, mSubmitQr, mSubmitNfc, mClearButton;
  private EditText mAmountView, mNotesView;
  private AutoCompleteTextView mRecipientView;
  private View mRequestDivider, mRecipientDivider;

  private Utils.ContactsAutoCompleteAdapter mAutocompleteAdapter;

  private int mTransferType;
  private String mAmount, mNotes, mRecipient, mTransferCurrency;
  private TransferButton mLastPressedButton = null;

  private TextView mNativeAmount;
  private long mNativeExchangeRateTime;
  private JSONObject mNativeExchangeRates;
  private RefreshExchangeRateTask mNativeExchangeTask;
  private String[] mCurrenciesArray = new String[] { "BTC" };
  private SharedPreferences.OnSharedPreferenceChangeListener mPreferenceChangeListener = null;

  @Override
  public void onCreate(Bundle savedInstanceState) {

    super.onCreate(savedInstanceState);
    if (Utils.getPrefsBool(mParent, Constants.KEY_ACCOUNT_TRANSFER_CURRENCY_BTC, false)) {
      mTransferCurrency = "BTC";
    }
  }

  @Override
  public void onAttach(Activity activity) {

    super.onAttach(activity);
    mParent = (MainActivity) activity;
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mParent);
    prefs.unregisterOnSharedPreferenceChangeListener(mPreferenceChangeListener);
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container,
                           Bundle savedInstanceState) {

    // Inflate the layout for this fragment
    View view = inflater.inflate(R.layout.fragment_transfer, container, false);

    mTransferTypeView = (Spinner) view.findViewById(R.id.transfer_money_type);
    mTransferTypeView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {

      @Override
      public void onItemSelected(AdapterView<?> arg0, View arg1, int arg2,
                                 long arg3) {

        onTypeChanged();
      }

      @Override
      public void onNothingSelected(AdapterView<?> arg0) {
        // Will never happen.
        throw new RuntimeException("onNothingSelected triggered on transfer type spinner");
      }
    });
    initializeTypeSpinner();

    mTransferCurrencyView = (Spinner) view.findViewById(R.id.transfer_money_currency);
    mTransferCurrencyView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {

      @Override
      public void onItemSelected(AdapterView<?> arg0, View arg1, int arg2,
                                 long arg3) {

        onCurrencyChanged();
      }

      @Override
      public void onNothingSelected(AdapterView<?> arg0) {
        // Will never happen.
        throw new RuntimeException("onNothingSelected triggered on transfer currency spinner");
      }
    });
    initializeCurrencySpinner();

    mSubmitSend = (Button) view.findViewById(R.id.transfer_money_button_send);
    mSubmitEmail = (Button) view.findViewById(R.id.transfer_money_button_email);
    mSubmitQr = (Button) view.findViewById(R.id.transfer_money_button_qrcode);
    mSubmitNfc = (Button) view.findViewById(R.id.transfer_money_button_nfc);
    mClearButton = (Button) view.findViewById(R.id.transfer_money_button_clear);

    for(Button b : new Button[] { mSubmitSend, mSubmitEmail, mSubmitNfc, mSubmitQr }) {
      b.setTypeface(FontManager.getFont(mParent, "Roboto-Light"));
    }

    mAmountView = (EditText) view.findViewById(R.id.transfer_money_amt);
    mNotesView = (EditText) view.findViewById(R.id.transfer_money_notes);
    mRecipientView = (AutoCompleteTextView) view.findViewById(R.id.transfer_money_recipient);
    mRecipientDivider = view.findViewById(R.id.transfer_divider_1);
    mRequestDivider = view.findViewById(R.id.transfer_divider_3);

    mAutocompleteAdapter = Utils.getEmailAutocompleteAdapter(mParent);
    mRecipientView.setAdapter(mAutocompleteAdapter);
    mRecipientView.setThreshold(0);

    mNativeAmount = (TextView) view.findViewById(R.id.transfer_money_native);
    mNativeAmount.setText(null);

    mAmountView.addTextChangedListener(new TextWatcher() {

      @Override
      public void afterTextChanged(Editable s) {
      }

      @Override
      public void beforeTextChanged(CharSequence s, int start, int count,
                                    int after) {
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        mAmount = s.toString();

        // Update native currency
        updateNativeCurrency();
      }
    });

    mNotesView.addTextChangedListener(new TextWatcher() {

      @Override
      public void afterTextChanged(Editable s) {
      }

      @Override
      public void beforeTextChanged(CharSequence s, int start, int count,
                                    int after) {
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        mNotes = s.toString();
      }
    });

    mRecipientView.addTextChangedListener(new TextWatcher() {

      @Override
      public void afterTextChanged(Editable s) {
      }

      @Override
      public void beforeTextChanged(CharSequence s, int start, int count,
                                    int after) {
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        mRecipient = s.toString();
      }
    });

    int currencyIndex = Arrays.asList(mCurrenciesArray).indexOf(mTransferCurrency);
    mTransferCurrencyView.setSelection(currencyIndex == -1 ? 0 : currencyIndex);

    switchType(mTransferType);
    mAmountView.setText(mAmount);
    mNotesView.setText(mNotes);
    mRecipientView.setText(mRecipient);

    mSubmitSend.setOnClickListener(new View.OnClickListener() {

      @Override
      public void onClick(View v) {

        mLastPressedButton = TransferButton.SEND;
        submitSend();
      }
    });

    mSubmitEmail.setOnClickListener(new View.OnClickListener() {

      @Override
      public void onClick(View v) {

        mLastPressedButton = TransferButton.EMAIL;
        submitEmail();
      }
    });

    mSubmitQr.setOnClickListener(new View.OnClickListener() {

      @Override
      public void onClick(View v) {

        mLastPressedButton = TransferButton.QR;
        submitQr();
      }
    });

    mSubmitNfc.setOnClickListener(new View.OnClickListener() {

      @Override
      public void onClick(View v) {

        mLastPressedButton = TransferButton.NFC;
        submitNfc();
      }
    });

    mClearButton.setOnClickListener(new View.OnClickListener() {

      @Override
      public void onClick(View v) {

        clearForm();
      }
    });

    onTypeChanged();

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mParent);
    mPreferenceChangeListener = new SharedPreferences.OnSharedPreferenceChangeListener() {

      @Override
      public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                                            String key) {

        int activeAccount = sharedPreferences.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
        if(key.equals(String.format(Constants.KEY_ACCOUNT_NATIVE_CURRENCY, activeAccount))) {
          // Refresh native currency dropdown
          initializeCurrencySpinner();
        }
      }
    };
    prefs.registerOnSharedPreferenceChangeListener(mPreferenceChangeListener);

    return view;
  }

  private void submitSend() {

    if("".equals(mAmount) || ".".equals(mAmount)) {

      // No amount entered
      Toast.makeText(mParent, R.string.transfer_amt_empty, Toast.LENGTH_SHORT).show();
      return;
    } else if("".equals(mRecipient)) {

      // No recipient entered
      Toast.makeText(mParent, R.string.transfer_recipient_empty, Toast.LENGTH_SHORT).show();
      return;
    }

    if(!PINManager.getInstance().checkForEditAccess(getActivity())) {
      return;
    }
    mLastPressedButton = null;

    if(!Utils.isConnectedOrConnecting(mParent)) {
      // Internet is not available
      // Show error message and display option to do a delayed transaction
      new DelayedTransactionDialogFragment(
              new DelayedTransaction(DelayedTransaction.Type.SEND, getAmount(), mTransferCurrency, mRecipient, mNotes))
              .show(getFragmentManager(), "delayed_send");
      return;
    }

    ConfirmTransferFragment dialog = new ConfirmTransferFragment();

    Bundle b = new Bundle();

    b.putSerializable("type", TransferType.values()[mTransferType]);
    b.putString("amount", getAmount());
    b.putString("currency", mTransferCurrency);
    b.putString("notes", mNotes);
    b.putString("toFrom", mRecipient);
    b.putString("feeAmount", null);

    dialog.setArguments(b);

    dialog.show(getFragmentManager(), "confirm");
  }

  private void submitEmail() {

    if("".equals(mAmount) || ".".equals(mAmount)) {

      // No amount entered
      Toast.makeText(mParent, R.string.transfer_amt_empty, Toast.LENGTH_SHORT).show();
      return;
    }

    if(!PINManager.getInstance().checkForEditAccess(getActivity())) {
      return;
    }
    mLastPressedButton = null;

    TransferEmailPromptFragment dialog = new TransferEmailPromptFragment();

    Bundle b = new Bundle();

    b.putSerializable("type", TransferType.values()[mTransferType]);
    b.putString("amount", getAmount());
    b.putString("currency", mTransferCurrency);
    b.putString("notes", mNotes);

    dialog.setArguments(b);

    dialog.show(getFragmentManager(), "requestEmail");
  }

  private void submitQr() {

    if(!PINManager.getInstance().checkForEditAccess(getActivity())) {
      return;
    }
    mLastPressedButton = null;

    String requestUri = generateRequestUri();
    Object btcAmount = getBtcAmount();
    if(btcAmount == Boolean.FALSE) {
      return;
    }

    DisplayQrOrNfcFragment f = new DisplayQrOrNfcFragment();
    Bundle args = new Bundle();
    args.putString("data", requestUri);
    args.putBoolean("isNfc", false);
    args.putString("desiredAmount", btcAmount == null ? null : btcAmount.toString());
    f.setArguments(args);
    f.show(getFragmentManager(), "qrrequest");

    // After using a receive address, generate a new one for next time.
    mParent.getAccountSettingsFragment().regenerateReceiveAddress();
  }

  private void submitNfc() {

    if(!PINManager.getInstance().checkForEditAccess(getActivity())) {
      return;
    }
    mLastPressedButton = null;

    String requestUri = generateRequestUri();
    Object btcAmount = getBtcAmount();
    if(btcAmount == Boolean.FALSE) {
      return;
    }

    DisplayQrOrNfcFragment f = new DisplayQrOrNfcFragment();
    Bundle args = new Bundle();
    args.putString("data", requestUri);
    args.putBoolean("isNfc", true);
    args.putString("desiredAmount", btcAmount == null ? null : btcAmount.toString());
    f.setArguments(args);
    f.show(getFragmentManager(), "nfcrequest");

    // After using a receive address, generate a new one for next time.
    mParent.getAccountSettingsFragment().regenerateReceiveAddress();
  }

  public void clearForm() {
    mAmountView.setText("");
    mNotesView.setText("");
    mRecipientView.setText("");
  }

  private void updateNativeCurrency() {

    if(mNativeExchangeRates == null ||
        (System.currentTimeMillis() - mNativeExchangeRateTime) > EXCHANGE_RATE_EXPIRE_TIME) {

      // Need to fetch exchange rate again
      if(mNativeExchangeTask != null) {
        return;
      }

      if (Utils.isConnectedOrConnecting(mParent)) {
        mNativeAmount.setText(R.string.transfer_fxrate_loading);
        refreshExchangeRate();
      } else {
        // No point in trying to load FX rate
        mNativeAmount.setText(R.string.transfer_fxrate_failure);
      }
    } else {
      doNativeCurrencyUpdate();
    }
  }

  private void doNativeCurrencyUpdate() {

    if(mTransferCurrency == null) {
      mNativeAmount.setText(null);
      return;
    }

    String amountS = mAmount;

    if(mAmount == null || "".equals(mAmount) || ".".equals(mAmount)) {
      amountS = "0.00";
    }

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mParent);
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
    String nativeCurrency = prefs.getString(String.format(Constants.KEY_ACCOUNT_NATIVE_CURRENCY, activeAccount),
        "usd").toLowerCase(Locale.CANADA);

    boolean fromBitcoin = "BTC".equalsIgnoreCase(mTransferCurrency);
    String format = fromBitcoin ? "%s_to_" + nativeCurrency.toLowerCase(Locale.CANADA) : "%s_to_btc";
    String key = String.format(format, mTransferCurrency.toLowerCase(Locale.CANADA));
    String resultCurrency = fromBitcoin ? nativeCurrency : "BTC";

    BigDecimal amount = new BigDecimal(amountS);
    BigDecimal result = amount.multiply(new BigDecimal(mNativeExchangeRates.optString(key, "0")));
    CurrencyType currencyType = fromBitcoin ? CurrencyType.TRADITIONAL : CurrencyType.BTC;
    mNativeAmount.setText(String.format(mParent.getString(R.string.transfer_amt_native), Utils.formatCurrencyAmount(result, false, currencyType),
      resultCurrency.toUpperCase(Locale.CANADA)));
  }

  @TargetApi(Build.VERSION_CODES.HONEYCOMB)
  private void refreshExchangeRate() {
    mNativeExchangeTask = new RefreshExchangeRateTask();

    if (PlatformUtils.hasHoneycomb()) {
      mNativeExchangeTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    } else {
      mNativeExchangeTask.execute();
    }
  }

  public void startQrNfcRequest(boolean isNfc, String amt, String notes) {

    String requestUri = generateRequestUri(amt, notes);

    DisplayQrOrNfcFragment f = new DisplayQrOrNfcFragment();
    Bundle args = new Bundle();
    args.putString("data", requestUri);
    args.putBoolean("isNfc", isNfc);
    args.putString("desiredAmount", amt);
    f.setArguments(args);
    f.show(getFragmentManager(), "qrrequest");

    // After using a receive address, generate a new one for next time.
    mParent.getAccountSettingsFragment().regenerateReceiveAddress();
  }

  public void startEmailRequest(String amt, String notes) {

    TransferEmailPromptFragment dialog = new TransferEmailPromptFragment();

    Bundle b = new Bundle();

    b.putSerializable("type", TransferType.REQUEST);
    b.putString("amount", amt);
    b.putString("notes", notes);

    dialog.setArguments(b);

    dialog.show(getFragmentManager(), "requestEmail");
  }

  private String getAmount() {

    if(mAmount == null || "".equals(mAmount) || ".".equals(mAmount)) {
      return "0";
    } else {
      return mAmount;
    }
  }

  private Object getBtcAmount() {

    String amount = getAmount();
    boolean fromBitcoin = "BTC".equalsIgnoreCase(mTransferCurrency);
    if (fromBitcoin) {
      return new BigDecimal(amount);
    } else {
      String key = String.format("%s_to_btc", mTransferCurrency.toLowerCase(Locale.CANADA));

      if (mNativeExchangeRates == null) {
        Toast.makeText(mParent, R.string.exchange_rate_error, Toast.LENGTH_SHORT).show();
        return Boolean.FALSE;
      }

      BigDecimal result = new BigDecimal(amount).multiply(new BigDecimal(mNativeExchangeRates.optString(key, "0")));
      return result;
    }
  }

  private String generateRequestUri() {

    Object btc = getBtcAmount();
    String s;
    if(btc == null || btc == Boolean.FALSE) {
      s = null;
    } else {
      s = ((BigDecimal) btc).toPlainString();
    }

    return generateRequestUri(s, mNotes);
  }

  private String generateRequestUri(String amt, String notes) {

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mParent);
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
    String receiveAddress = prefs.getString(String.format(Constants.KEY_ACCOUNT_RECEIVE_ADDRESS, activeAccount), null);
    String requestUri = String.format("bitcoin:%s", receiveAddress);

    boolean hasAmount = false;

    if(amt != null && !"".equals(amt)) {
      requestUri += "?amount=" + amt;
      hasAmount = true;
    }

    if(notes != null && !"".equals(notes)) {
      if(hasAmount) {
        requestUri += "&";
      } else {
        requestUri += "?";
      }

      requestUri += "message=" + notes;
    }

    return requestUri;
  }

  private void initializeTypeSpinner() {

    ArrayAdapter<TransferType> arrayAdapter = new ArrayAdapter<TransferType>(
        mParent, R.layout.fragment_transfer_type, Arrays.asList(TransferType.values())) {

      @Override
      public View getView(int position, View convertView, ViewGroup parent) {

        TextView view = (TextView) super.getView(position, convertView, parent);
        view.setText(mParent.getString(TransferType.values()[position].getName()));
        return view;
      }

      @Override
      public View getDropDownView(int position, View convertView, ViewGroup parent) {

        TextView view = (TextView) super.getDropDownView(position, convertView, parent);
        view.setText(mParent.getString(TransferType.values()[position].getName()));
        return view;
      }
    };
    arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    mTransferTypeView.setAdapter(arrayAdapter);
  }

  private void initializeCurrencySpinner() {

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mParent);
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
    String nativeCurrency = prefs.getString(String.format(Constants.KEY_ACCOUNT_NATIVE_CURRENCY, activeAccount),
        "usd").toUpperCase(Locale.CANADA);

    mCurrenciesArray = new String[] {
            nativeCurrency,
            "BTC",
    };

    ArrayAdapter<String> arrayAdapter = new ArrayAdapter<String>(
        mParent, R.layout.fragment_transfer_currency, Arrays.asList(mCurrenciesArray)) {

      @Override
      public View getView(int position, View convertView, ViewGroup parent) {

        TextView view = (TextView) super.getView(position, convertView, parent);
        view.setText(mCurrenciesArray[position]);
        return view;
      }

      @Override
      public View getDropDownView(int position, View convertView, ViewGroup parent) {

        TextView view = (TextView) super.getDropDownView(position, convertView, parent);
        view.setText(mCurrenciesArray[position]);
        return view;
      }
    };
    arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    mTransferCurrencyView.setAdapter(arrayAdapter);
  }

  private void onTypeChanged() {

    TransferType type = (TransferType) mTransferTypeView.getSelectedItem();
    mTransferType = mTransferTypeView.getSelectedItemPosition();
    boolean isSend = type == TransferType.SEND;

    mSubmitSend.setVisibility(isSend ? View.VISIBLE : View.GONE);
    mSubmitEmail.setVisibility(isSend ? View.GONE : View.VISIBLE);
    mSubmitQr.setVisibility(isSend ? View.GONE : View.VISIBLE);
    mSubmitNfc.setVisibility(isSend ? View.GONE : View.VISIBLE);
    mRecipientView.setVisibility(isSend ? View.VISIBLE : View.GONE);
    mRequestDivider.setVisibility(isSend ? View.GONE : View.VISIBLE);
    mRecipientDivider.setVisibility(isSend ? View.VISIBLE : View.GONE);

    RelativeLayout.LayoutParams clearParams = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
    clearParams.addRule(RelativeLayout.BELOW, isSend ? R.id.transfer_money_recipient : R.id.transfer_money_notes);
    clearParams.addRule(RelativeLayout.ALIGN_LEFT, isSend ? R.id.transfer_money_recipient : R.id.transfer_money_notes);
    clearParams.topMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, getResources().getDisplayMetrics());
    mClearButton.setLayoutParams(clearParams);

    doFocus();
  }

  private void onCurrencyChanged() {

    String currency = (String) mTransferCurrencyView.getSelectedItem();
    mTransferCurrency = currency;
    Utils.putPrefsBool(mParent, Constants.KEY_ACCOUNT_TRANSFER_CURRENCY_BTC, mTransferCurrency.equals("BTC"));

    updateNativeCurrency();
  }

  protected void startTransferTask(TransferType type, String amount, String currency, String notes, String toFrom, String feeAmount) {

    Utils.runAsyncTaskConcurrently(new DoTransferTask(), type, amount, currency, notes, toFrom, feeAmount);
  }

  private Object[] doTransfer(TransferType type, String amount, String currency, String notes, String toFrom, String feeAmount) {

    List<BasicNameValuePair> params = new ArrayList<BasicNameValuePair>();
    params.add(new BasicNameValuePair("transaction[amount_string]", amount));
    params.add(new BasicNameValuePair("transaction[amount_currency_iso]", currency));

    if(notes != null && !"".equals(notes)) {
      params.add(new BasicNameValuePair("transaction[notes]", notes));
    }

    if(feeAmount != null) {
      params.add(new BasicNameValuePair("transaction[user_fee]", feeAmount));
    }

    params.add(new BasicNameValuePair(
      String.format("transaction[%s]", type == TransferType.SEND ? "to" : "from"), toFrom));

    try {
      JSONObject response = RpcManager.getInstance().callPost(mParent,
        String.format("transactions/%s_money", type.getRequestName()), params);

      boolean success = response.getBoolean("success");
      JSONObject transaction = response.getJSONObject("transaction");

      if(success) {

        String amountBtc = transaction.optJSONObject("amount").optString("amount");
        return new Object[] { true, Utils.formatCurrencyAmount(amountBtc, true), type, toFrom, transaction };
      } else {

        JSONArray errors = response.getJSONArray("errors");
        String errorMessage = "";
        Pattern feeRegex = Pattern.compile("This transaction requires a ([0-9\\.]+) fee to be accepted");
        Matcher matcher = null;
        String feeNeeded = null;

        for(int i = 0; i < errors.length(); i++) {
          String error = errors.getString(i);
          if (matcher == null) {
            matcher = feeRegex.matcher(error);
          } else {
            matcher.reset(error);
          }

          boolean isFeeMessage = matcher.find();
          if (isFeeMessage) {
            feeNeeded = matcher.group(1);
          } else {
            errorMessage += (errorMessage.equals("") ? "" : "\n") + error;
          }
        }
        return new Object[] { false, String.format(getString(R.string.transfer_error_api), errorMessage), feeNeeded };
      }
    } catch (IOException e) {
      e.printStackTrace();
    } catch (JSONException e) {
      ACRA.getErrorReporter().handleException(new RuntimeException("doTransfer", e));
      e.printStackTrace();
    }

    // There was an exception
    return new Object[] { false, getString(R.string.transfer_error_exception), null };
  }

  public void fillFormForBitcoinUri(String content) {

    String amount = null, label = null, message = null, address = null;

    if(content.startsWith("bitcoin:")) {

      Uri uri = Uri.parse(content);
      address = uri.getSchemeSpecificPart().split("\\?")[0];

      // Parse query
      String query = uri.getQuery();
      if(query != null) {
        try {
          for (String param : query.split("&")) {
            String pair[] = param.split("=");
            String key;
            key = URLDecoder.decode(pair[0], "UTF-8");
            String value = null;
            if (pair.length > 1) {
              value = URLDecoder.decode(pair[1], "UTF-8");
            }

            if("amount".equals(key)) {
              amount = value;
            } else if("label".equals(key)) {
              label = value;
            } else if("message".equals(key)) {
              message = value;
            }
          }
        } catch (UnsupportedEncodingException e) {
          // Will never happen
          throw new RuntimeException(e);
        }
      }
    } else {
      // Assume barcode consisted of a bitcoin address only (not a URI)
      address = content;
    }

    if(address == null) {

      Log.e("Coinbase", "Could not parse URI! (" + content + ")");
      return;
    }

    if(amount != null) {
      amount = amount.replaceAll("[^0-9\\.]", "");
    }

    mAmount = amount;
    mNotes = message;
    mRecipient = address;
    mTransferType = 0;
    mTransferCurrency = "BTC";

    if(mTransferTypeView != null) {
      switchType(TransferType.SEND.ordinal()); // Send
      mTransferCurrencyView.setSelection(1); // BTC is always second
      mAmountView.setText(amount);
      mNotesView.setText(message);
      mRecipientView.setText(address);
    }
  }

  public void switchType(int type) {

    mTransferType = type;

    if(mTransferTypeView != null) {
      mTransferTypeView.setSelection(mTransferType);
      onTypeChanged();
    }
  }

  public void refresh() {

  }

  @Override
  public void onSwitchedTo() {

    doFocus();
  }

  private void doFocus() {
    if (mTransferType == TransferType.REQUEST.ordinal()) {
      mAmountView.requestFocus();
    } else {
      mRecipientView.requestFocus();
    }
  }

  @Override
  public void onPINPromptSuccessfulReturn() {

    if (mLastPressedButton != null) {

      switch (mLastPressedButton) {
        case QR:
          submitQr();
          break;
        case NFC:
          submitNfc();
          break;
        case EMAIL:
          submitEmail();
          break;
        case SEND:
          submitSend();
          break;
      }
    }
  }
}
