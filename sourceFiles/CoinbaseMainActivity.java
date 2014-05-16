// SOURCE: https://raw.githubusercontent.com/coinbase/coinbase-android/master/coinbase-android/src/com/coinbase/android/MainActivity.java
package com.coinbase.android;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.ActionBarDrawerToggle;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.widget.DrawerLayout;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.HeaderViewListAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.coinbase.android.CoinbaseActivity.RequiresAuthentication;
import com.coinbase.android.CoinbaseActivity.RequiresPIN;
import com.coinbase.android.merchant.MerchantKioskHomeActivity;
import com.coinbase.android.merchant.MerchantKioskModeService;
import com.coinbase.android.merchant.MerchantToolsFragment;
import com.coinbase.android.merchant.PointOfSaleFragment;
import com.coinbase.android.pin.PINSettingDialogFragment;
import com.coinbase.api.LoginManager;
import com.google.zxing.client.android.Intents;
import com.justinschultz.pusherclient.Pusher;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@RequiresAuthentication
@RequiresPIN
public class MainActivity extends CoinbaseActivity implements AccountsFragment.ParentActivity {

  public static final String ACTION_SCAN = "com.siriusapplications.coinbase.MainActivity.ACTION_SCAN";
  public static final String ACTION_TRANSFER = "com.siriusapplications.coinbase.MainActivity.ACTION_TRANSFER";
  public static final String ACTION_TRANSACTIONS = "com.siriusapplications.coinbase.MainActivity.ACTION_TRANSACTIONS";

  private static final String KEY_VISIBLE_FRAGMENT = "KEY_VISIBLE_FRAGMENT";
  private static final String KEY_IN_TRANSACTION_DETAILS_MODE = "KEY_IN_TRANSACTION_DETAILS_MODE";

  public static final int REQUEST_CODE_PIN = 2;

  private static final long RESUME_REFRESH_INTERVAL = 1 * 60 * 1000;

  public static class SignOutFragment extends DialogFragment {


    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {

      AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
      builder.setMessage(R.string.sign_out_confirm);

      builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int id) {
          // Sign out
          ((MainActivity) getActivity()).changeAccount(-1);
        }
      });

      builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int id) {
          // Dismiss
        }
      });

      return builder.create();
    }
  }

  public static final int NUM_FRAGMENTS = 6;
  public static final int FRAGMENT_INDEX_TRANSACTIONS = 0;
  public static final int FRAGMENT_INDEX_TRANSFER = 1;
  public static final int FRAGMENT_INDEX_BUYSELL = 2;
  public static final int FRAGMENT_INDEX_ACCOUNT = 3;
  public static final int FRAGMENT_INDEX_MERCHANT_TOOLS = 4;
  public static final int FRAGMENT_INDEX_POINT_OF_SALE = 5;

  private int[] mFragmentTitles = new int[] {
                                             R.string.title_transactions,
                                             R.string.title_transfer,
                                             R.string.title_buysell,
                                             R.string.title_account,
                                             R.string.title_merchant_tools,
                                             R.string.title_point_of_sale,
  };
  private int[] mFragmentIcons = new int[] {
                                            R.drawable.ic_action_transactions,
                                            R.drawable.ic_action_transfer,
                                            R.drawable.ic_action_buysell,
                                            R.drawable.ic_action_account,
                                            R.drawable.ic_action_merchant_tools,
                                            R.drawable.ic_action_point_of_sale,
  };
  private boolean[] mFragmentHasSpacerAfter = new boolean[] {
                                                             false,
                                                             false,
                                                             false,
                                                             false,
                                                             false,
                                                             false,
  };
  private boolean[] mFragmentKeyboardPreferredStatus = new boolean[] {
                                                                      false,
                                                                      true,
                                                                      true,
                                                                      false,
                                                                      false,
                                                                      true,
  };
  private boolean[] mFragmentVisible = new boolean[] {
          BuildConfig.type == BuildType.CONSUMER,
          BuildConfig.type == BuildType.CONSUMER,
          BuildConfig.type == BuildType.CONSUMER,
          BuildConfig.type == BuildType.CONSUMER,
          false,
          BuildConfig.type == BuildType.MERCHANT,
  };
  private CoinbaseFragment[] mFragments = new CoinbaseFragment[NUM_FRAGMENTS];

  ViewFlipper mViewFlipper;
  TransactionsFragment mTransactionsFragment;
  BuySellFragment mBuySellFragment;
  TransferFragment mTransferFragment;
  AccountSettingsFragment mSettingsFragment;
  MerchantToolsFragment mMerchantToolsFragment;
  PointOfSaleFragment mPointOfSaleFragment;
  OnSharedPreferenceChangeListener mSharedPreferenceChangeListener;
  DrawerLayout mSlidingMenu;
  ActionBarDrawerToggle mDrawerToggle;
  Pusher mPusher;
  MenuItem mRefreshItem;
  ListView mMenuListView;
  View mMenuProfileView;
  boolean mRefreshItemState = false;
  boolean mPinSlidingMenu = false;
  long mLastRefreshTime = -1;
  boolean mInTransactionDetailsMode = false;
  boolean mPendingPinReturn = false;
  Utils.AndroidBug5497Workaround mBugWorkaround = new Utils.AndroidBug5497Workaround();

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    int currentAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
    String firstLaunchKey = String.format(Constants.KEY_ACCOUNT_FIRST_LAUNCH, currentAccount);
    boolean firstLaunch = prefs.getBoolean(firstLaunchKey, true);
    prefs.edit().putBoolean(firstLaunchKey, false).commit();

    // Set up the ViewFlipper
    mViewFlipper = (ViewFlipper) findViewById(R.id.flipper);

    // Configure the DrawerLayout (never pin menu on POS app)
    mPinSlidingMenu = getResources().getBoolean(R.bool.pin_sliding_menu);
    getSupportActionBar().setHomeButtonEnabled(!mPinSlidingMenu);

    if(!mPinSlidingMenu) {
      mSlidingMenu = (DrawerLayout) findViewById(R.id.main_layout);
      mSlidingMenu.setDrawerShadow(R.drawable.drawer_shadow, Gravity.LEFT);
      mDrawerToggle = new ActionBarDrawerToggle(this, mSlidingMenu,
        R.drawable.ic_drawer_white, R.string.drawer_open, R.string.drawer_close) {

        int lastTimeIndex = -1;

        @Override
        public void onDrawerClosed(View drawerView) {
          onSlidingMenuClosed(lastTimeIndex != mViewFlipper.getDisplayedChild());
          lastTimeIndex = -1;
          updateTitle();
          updateBackButton();
        }

        @Override
        public void onDrawerOpened(View drawerView) {
          updateTitle();
          updateBackButton();
          lastTimeIndex = mViewFlipper.getDisplayedChild();
        }

      };
      mSlidingMenu.setDrawerListener(mDrawerToggle);
      mSlidingMenu.setDrawerLockMode(BuildConfig.type == BuildType.CONSUMER ? DrawerLayout.LOCK_MODE_UNLOCKED : DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
      if (BuildConfig.type == BuildType.CONSUMER && firstLaunch) {
        mSlidingMenu.openDrawer(Gravity.LEFT); // Open drawer on first sign in
      }
    }

    // Set up Sliding Menu list
    mMenuListView = (ListView) findViewById(R.id.drawer);
    if (BuildConfig.type == BuildType.MERCHANT && mPinSlidingMenu) {
      mMenuListView.setVisibility(View.GONE);
      findViewById(R.id.activity_main_divider).setVisibility(View.GONE);
    }
    createProfileView();
    int shortestWidth = Math.min(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
    int dWidthCalc = (int) (shortestWidth * (3.0/4.0)),
            dWidthMax = getResources().getDimensionPixelSize(R.dimen.drawer_max_width);
    mMenuListView.getLayoutParams().width = Math.min(dWidthCalc, dWidthMax);
    mMenuListView.addHeaderView(mMenuProfileView);
    mMenuListView.setAdapter(new SectionsListAdapter());
    mMenuListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {

      @Override
      public void onItemClick(AdapterView<?> arg0, View arg1, int arg2,
                              long arg3) {

        if(arg2 == 0) {
          // Switch account
          new AccountsFragment().show(getSupportFragmentManager(), "accounts");
          return;
        }

        int fragment = (Integer) mMenuListView.getAdapter().getItem(arg2);
        if(fragment != -1) {
          switchTo(fragment);
        }
      }
    });

    // Refresh everything on app launch
    new Thread(new Runnable() {
      public void run() {
        runOnUiThread(new Runnable() {
          public void run() {
            // refresh();
          }
        });
      }
    }).start();

    switchTo(BuildConfig.type == BuildType.CONSUMER ? FRAGMENT_INDEX_TRANSACTIONS : FRAGMENT_INDEX_POINT_OF_SALE);
    updateKioskMode(Utils.inKioskMode(this));

    onNewIntent(getIntent());
  }

  /*
   * http://www.curious-creature.org/2012/12/11/android-recipe-1-image-with-rounded-corners/
   */
  public static class AvatarDrawable extends Drawable {

    private final RectF mRect = new RectF();
    private final Bitmap mOriginalBitmap;
    private final Paint mPaint;

    AvatarDrawable(Bitmap bitmap) {

      mOriginalBitmap = bitmap;

      mPaint = new Paint();
      mPaint.setAntiAlias(true);

    }

    @Override
    protected void onBoundsChange(Rect bounds) {
      super.onBoundsChange(bounds);

      BitmapShader shader = new BitmapShader(Bitmap.createScaledBitmap(mOriginalBitmap, bounds.width(), bounds.height(), true),
              Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
      mPaint.setShader(shader);

      mRect.set(0, 0, bounds.width(), bounds.height());
    }

    @Override
    public void draw(Canvas canvas) {
      canvas.drawRoundRect(mRect, mRect.width() / 2, mRect.height() / 2, mPaint);
    }

    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void setAlpha(int alpha) {
      mPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
      mPaint.setColorFilter(cf);
    }       
  }

  private class LoadAvatarTask extends AsyncTask<String, Void, Bitmap> {

    @Override
    protected Bitmap doInBackground(String... arg0) {

      try {
        String url = String.format("https://secure.gravatar.com/avatar/%1$s?s=100&d=https://coinbase.com/assets/avatar.png",
                Utils.md5(arg0[0].toLowerCase(Locale.CANADA).trim()));
        Log.i("Coinbase", "Loading avatar " + url);
        return BitmapFactory.decodeStream(new URL(url).openStream());
      } catch (Exception e) {
        Log.i("Coinbase", "Could not load avatar!");
        e.printStackTrace();
        return null;
      }
    }

    @Override
    protected void onPostExecute(Bitmap result) {
      if(result != null) {
        ((ImageView) mMenuProfileView.findViewById(R.id.drawer_profile_avatar)).setImageDrawable(new AvatarDrawable(result));
      }
    }
  }

  private void createProfileView() {
    mMenuProfileView = View.inflate(this, R.layout.activity_main_drawer_profile, null);
    ImageView photo = (ImageView) mMenuProfileView.findViewById(R.id.drawer_profile_avatar);

    photo.setImageDrawable(new AvatarDrawable(BitmapFactory.decodeResource(getResources(), R.drawable.no_avatar)));

    refreshProfileView();
  }

  public void refreshProfileView() {

    TextView name = (TextView) mMenuProfileView.findViewById(R.id.drawer_profile_name);
    TextView email = (TextView) mMenuProfileView.findViewById(R.id.drawer_profile_account);

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
    String emailText = prefs.getString(String.format(Constants.KEY_ACCOUNT_NAME, activeAccount), "");
    name.setText(prefs.getString(String.format(Constants.KEY_ACCOUNT_FULL_NAME, activeAccount), null));

    boolean emailChanged = !emailText.equals(email.getText().toString());
    if (emailChanged) {
      email.setText(emailText);
      new LoadAvatarTask().execute(emailText);
    }
  }

  @Override
  protected void onPostCreate(Bundle savedInstanceState) {
    super.onPostCreate(savedInstanceState);

    // Sync the toggle state after onRestoreInstanceState has occurred.
    if (!mPinSlidingMenu) {
      mDrawerToggle.syncState();
    }
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    if (!mPinSlidingMenu) {
      mDrawerToggle.onConfigurationChanged(newConfig);
    }
  }

  private BaseAdapter getAdapter() {

    Adapter adapter = mMenuListView.getAdapter();

    if(adapter instanceof HeaderViewListAdapter) {
      return (BaseAdapter) ((HeaderViewListAdapter) adapter).getWrappedAdapter();
    } else {
      return (BaseAdapter) adapter;
    }
  }

  @Override
  public void onAttachFragment(Fragment fragment) {
    super.onAttachFragment(fragment);

    if(fragment instanceof TransactionsFragment) {
      mFragments[FRAGMENT_INDEX_TRANSACTIONS] = (CoinbaseFragment) fragment;
      mTransactionsFragment = (TransactionsFragment) fragment;
    } else if(fragment instanceof BuySellFragment) {
      mFragments[FRAGMENT_INDEX_BUYSELL] = (CoinbaseFragment) fragment;
      mBuySellFragment = (BuySellFragment) fragment;
    } else if(fragment instanceof TransferFragment) {
      mFragments[FRAGMENT_INDEX_TRANSFER] = (CoinbaseFragment) fragment;
      mTransferFragment = (TransferFragment) fragment;
    } else if(fragment instanceof AccountSettingsFragment) {
      mFragments[FRAGMENT_INDEX_ACCOUNT] = (CoinbaseFragment) fragment;
      mSettingsFragment = (AccountSettingsFragment) fragment;
    } else if(fragment instanceof MerchantToolsFragment) {
      mFragments[FRAGMENT_INDEX_MERCHANT_TOOLS] = (CoinbaseFragment) fragment;
      mMerchantToolsFragment = (MerchantToolsFragment) fragment;
    } else if(fragment instanceof PointOfSaleFragment) {
      mFragments[FRAGMENT_INDEX_POINT_OF_SALE] = (CoinbaseFragment) fragment;
      mPointOfSaleFragment = (PointOfSaleFragment) fragment;
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);

    outState.putInt(KEY_VISIBLE_FRAGMENT, mViewFlipper.getDisplayedChild());
    outState.putBoolean(KEY_IN_TRANSACTION_DETAILS_MODE, mInTransactionDetailsMode);
  }

  @Override
  protected void onRestoreInstanceState(Bundle savedInstanceState) {
    super.onRestoreInstanceState(savedInstanceState);

    // Update title, in case restoring the instance state has changed the current fragment
    switchTo(savedInstanceState.getInt(KEY_VISIBLE_FRAGMENT));
    setInTransactionDetailsMode(savedInstanceState.getBoolean(KEY_IN_TRANSACTION_DETAILS_MODE));
  }

  /**
   * Switch visible fragment.
   * @param index See the FRAGMENT_INDEX constants.
   */
  public void switchTo(int index) {

    boolean fragmentChanged = mViewFlipper.getDisplayedChild() != index;

    mViewFlipper.setDisplayedChild(index);
    updateTitle();
    getAdapter().notifyDataSetChanged();

    if(mFragments[index] != null) {
      mFragments[index].onSwitchedTo();
    }

    if (mSlidingMenu == null || mPinSlidingMenu || !mSlidingMenu.isDrawerOpen(Gravity.LEFT)) {
      Log.i("Coinbase", "Keyboard changing immediately");
      makeKeyboardObeyVisibleFragment();
    } else {
      // keyboard will be forced to obey after sliding menu closes
      Log.i("Coinbase", "Keyboard: will be changed once menu closes");
      hideSlidingMenu(fragmentChanged);
    }

    if(mInTransactionDetailsMode) {
      mTransactionsFragment.hideDetails(false);
    }
    updateBackButton();
  }

  /** Called when close animation is complete */
  private void onSlidingMenuClosed(boolean fragmentChanged) {

    if(fragmentChanged) {

      makeKeyboardObeyVisibleFragment();
    } else {
      Log.i("Coinbase", "Fragment was not changed when sliding menu closed.");
    }
  }

  private void makeKeyboardObeyVisibleFragment() {

    boolean keyboardPreferredStatus = mFragmentKeyboardPreferredStatus[mViewFlipper.getDisplayedChild()];
    InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

    if(keyboardPreferredStatus) {
      Log.i("Coinbase", "Opening keyboard");
      inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY);
    } else {
      Log.i("Coinbase", "Closing keyboard");
      View focus = getCurrentFocus();
      inputMethodManager.hideSoftInputFromWindow((focus == null ? findViewById(android.R.id.content) : focus).getWindowToken(), 0);
      getWindow().setSoftInputMode(
              WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }
  }

  private boolean isSlidingMenuShowing() {

    return mPinSlidingMenu ? true : mSlidingMenu.isDrawerOpen(Gravity.LEFT);
  }

  private void toggleSlidingMenu() {
    if(isSlidingMenuShowing()) {
      hideSlidingMenu(false);
    } else {
      showSlidingMenu();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);

    if(!hasFocus && Utils.inKioskMode(this)) {
      // In kiosk mode, prevent any system dialogs from being opened.
      Intent closeDialog = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
      sendBroadcast(closeDialog);
    }
  }

  @Override
  public void onBackPressed() {

    if(isSlidingMenuShowing() && !mPinSlidingMenu) {
      hideSlidingMenu(false);
    } else {

      if (BuildConfig.type == BuildType.MERCHANT) {
        // Quit app if not in kiosk mode; otherwise, do nothing
        if (!Utils.inKioskMode(this)) {
          super.onBackPressed();
        }
      } else if (mViewFlipper.getDisplayedChild() == FRAGMENT_INDEX_TRANSACTIONS) {
        if (mTransactionsFragment.onBackPressed()) {
          // Give transactions fragment an opportunity to handle back
          return;
        } else {
          // Quit app
          super.onBackPressed();
        }
      } else {
        // Switch to transactions
        switchTo(FRAGMENT_INDEX_TRANSACTIONS);
      }
    }
  }

  private void showSlidingMenu() {

    if(mPinSlidingMenu) {
      return;
    }

    mSlidingMenu.openDrawer(Gravity.LEFT);
  }

  private void hideSlidingMenu(boolean fragmentChanged) {

    if(mSlidingMenu != null && !mPinSlidingMenu) {
      mSlidingMenu.closeDrawers();
    }
  }

  private void updateTitle() {

    if ((mSlidingMenu != null && isSlidingMenuShowing()) || mPinSlidingMenu) {
      // Sliding menu mode
      getSupportActionBar().setTitle(R.string.app_name);

    } else if (mInTransactionDetailsMode) {
      getSupportActionBar().setTitle(R.string.transactiondetails_title);
    } else {
      getSupportActionBar().setTitle(mFragmentTitles[mViewFlipper.getDisplayedChild()]);
    }

    supportInvalidateOptionsMenu();
  }

  private void updateBackButton() {

    if(mSlidingMenu != null) {
      mDrawerToggle.setDrawerIndicatorEnabled(isSlidingMenuShowing() ||
              !(mInTransactionDetailsMode || mViewFlipper.getDisplayedChild() == FRAGMENT_INDEX_POINT_OF_SALE));
      getSupportActionBar().setDisplayHomeAsUpEnabled(isSlidingMenuShowing() ||
              mViewFlipper.getDisplayedChild() != FRAGMENT_INDEX_POINT_OF_SALE);
    } else {
      getSupportActionBar().setDisplayHomeAsUpEnabled(false);
    }
  }

  public void setInTransactionDetailsMode(boolean inTransactionDetailsMode) {
    mInTransactionDetailsMode = inTransactionDetailsMode;
    updateTitle();
    updateBackButton();
  }

  private void setKioskModeEnabled(boolean enabled) {

    // Enable / disable home screen component
    PackageManager pm = getPackageManager();
    ComponentName homeScreenActivity = new ComponentName(this, MerchantKioskHomeActivity.class);
    int newState = enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    pm.setComponentEnabledSetting(homeScreenActivity, newState, PackageManager.DONT_KILL_APP);

    // Save in preferences
    SharedPreferences.Editor e = PreferenceManager.getDefaultSharedPreferences(this).edit();
    e.putBoolean(Constants.KEY_KIOSK_MODE, enabled);
    e.commit();

    updateKioskMode(enabled);
  }

  private void updateKioskMode(boolean enabled) {

    // 1. Notifications bar
    if (enabled) {
      getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
              WindowManager.LayoutParams.FLAG_FULLSCREEN);
      mBugWorkaround.startAssistingActivity(this);
    } else {
      getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
      // Clear bug workaround
      mBugWorkaround.stopAssistingActivity();
    }

    // 2. Manage service
    Intent service = new Intent(this, MerchantKioskModeService.class);
    if (enabled) {
      startService(service);
    } else {
      stopService(service);
    }

    // 3. Keyguard
    if (enabled) {
      getWindow().setFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
              WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
      getWindow().setFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
              WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
    } else {
      getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
      getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
    }
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {

    MenuItem[] toHide = new MenuItem[] {
            menu.findItem(R.id.menu_refresh),
            menu.findItem(R.id.menu_barcode)
    };

    boolean hide = mInTransactionDetailsMode || // Transaction details showing
            (mSlidingMenu != null && isSlidingMenuShowing()) || // Sliding menu showing
            mPinSlidingMenu || // Tablet mode
            mViewFlipper.getDisplayedChild() == FRAGMENT_INDEX_POINT_OF_SALE; // Point of sale

    for(MenuItem item : toHide) {
      item.setVisible(!hide);
    }

    boolean pinSet = Utils.getPrefsString(this, Constants.KEY_ACCOUNT_PIN, null) != null;
    menu.findItem(R.id.menu_merchant_pin).setVisible(BuildConfig.type == BuildType.MERCHANT);
    menu.findItem(R.id.menu_merchant_pin).setTitle(pinSet ? R.string.menu_merchant_pin_already_set : R.string.menu_merchant_pin);

    boolean kioskMode = Utils.inKioskMode(this);
    menu.findItem(R.id.menu_kiosk_mode).setVisible(BuildConfig.type == BuildType.MERCHANT);
    menu.findItem(R.id.menu_kiosk_mode).setTitle(kioskMode ? R.string.menu_kiosk_mode_disable : R.string.menu_kiosk_mode_enable);

    boolean enableTipping = Utils.getPrefsBool(this, Constants.KEY_ACCOUNT_ENABLE_TIPPING, false);
    menu.findItem(R.id.menu_tip).setVisible(BuildConfig.type == BuildType.MERCHANT);
    menu.findItem(R.id.menu_tip).setTitle(enableTipping ? R.string.menu_tip_disable : R.string.menu_tip_enable);

    menu.findItem(R.id.menu_help).setVisible(!kioskMode);
    menu.findItem(R.id.menu_about).setVisible(!kioskMode);
    menu.findItem(R.id.menu_sign_out).setVisible(!kioskMode);
    menu.findItem(R.id.menu_accounts).setVisible(!kioskMode);

    return true;
  }

  @Override
  protected void onNewIntent(Intent intent) {

    super.onNewIntent(intent);
    setIntent(intent);

    if(intent.getData() != null && "bitcoin".equals(intent.getData().getScheme())) {
      // Handle bitcoin: URI
      switchTo(FRAGMENT_INDEX_TRANSFER);
      mTransferFragment.fillFormForBitcoinUri(getIntent().getData().toString());
    } else if(ACTION_SCAN.equals(intent.getAction())) {
      // Scan barcode
      startBarcodeScan();
    } else if(ACTION_TRANSFER.equals(intent.getAction())) {

      switchTo(FRAGMENT_INDEX_TRANSFER);
    } else if(ACTION_TRANSACTIONS.equals(intent.getAction())) {

      switchTo(FRAGMENT_INDEX_TRANSACTIONS);
    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getSupportMenuInflater().inflate(R.menu.activity_main, menu);
    mRefreshItem = menu.findItem(R.id.menu_refresh);
    setRefreshButtonAnimated(mRefreshItemState);
    return true;
  }

  @Override
  public void onResume() {
    super.onResume();

    // Connect to pusher
    new Thread(new Runnable() {
      public void run() {

        // mPusher = new Pusher(CoinbasePusherListener.API_KEY);
        // mPusher.setPusherListener(new CoinbasePusherListener(mPusher, MainActivity.this));
        // mPusher.connect();
      }
    }).start();

    // Refresh
    if (mTransactionsFragment != null) {
      mTransactionsFragment.loadTransactionsList();
    }
    ((CoinbaseApplication) getApplication()).addMainActivity(this);
    if((System.currentTimeMillis() - mLastRefreshTime) > RESUME_REFRESH_INTERVAL) {
      refresh();
    }

    // Legacy support:
    // If the old Point of Sale is enabled, show a dialog directing them to the Play Store
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
    String key = String.format(Constants.KEY_ACCOUNT_ENABLE_MERCHANT_TOOLS, activeAccount);
    if (prefs.getBoolean(key, false)) {
      new MerchantToolsMovedDialogFragment().show(getSupportFragmentManager(), "poslegacy");
    }
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    prefs.unregisterOnSharedPreferenceChangeListener(mSharedPreferenceChangeListener);
  }

  @Override
  protected void onPause() {
    super.onPause();

    if(mPusher != null) {
      mPusher.disconnect();
      mPusher = null;
    }

    mPendingPinReturn = false;
    ((CoinbaseApplication) getApplication()).removeMainActivity(this);

    // Since we manually opened the keyboard, we must close it when switching
    // away from the app
    InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
    inputMethodManager.hideSoftInputFromWindow(
      findViewById(android.R.id.content).getWindowToken(), 0);
  }

  public void openTransferMenu(boolean isRequest) {

    switchTo(FRAGMENT_INDEX_TRANSFER);
    mTransferFragment.switchType(isRequest ? TransferFragment.TransferType.REQUEST.ordinal() : TransferFragment.TransferType.SEND.ordinal());
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {

    switch(item.getItemId()) {
      case R.id.menu_accounts:
        new AccountsFragment().show(getSupportFragmentManager(), "accounts");
        return true;
      case R.id.menu_sign_out:
        new SignOutFragment().show(getSupportFragmentManager(), "signOut");
        return true;
      case R.id.menu_about:
        startActivity(new Intent(this, AboutActivity.class));
        return true;
      case R.id.menu_barcode:
        startBarcodeScan();
        return true;
      case R.id.menu_refresh:
        if(isSlidingMenuShowing()){
          hideSlidingMenu(false);
        }

        refresh();
        return true;
      case R.id.menu_help:
        Intent helpIntent = new Intent(Intent.ACTION_VIEW);
        helpIntent.setData(Uri.parse("http://support.coinbase.com/"));
        startActivity(helpIntent);
        return true;
      case R.id.menu_merchant_pin:
        new PINSettingDialogFragment().show(getSupportFragmentManager(), "pin");
        return true;
      case R.id.menu_kiosk_mode:
        if (Utils.inKioskMode(MainActivity.this)) {
          setKioskModeEnabled(false);
        } else {
          new AlertDialog.Builder(this).setTitle(R.string.kiosk_dialog_title)
            .setMessage(R.string.kiosk_dialog_text)
            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int which) {

                setKioskModeEnabled(true);
                Toast.makeText(MainActivity.this, R.string.kiosk_setup_toast, Toast.LENGTH_LONG).show();
                // Start the home screen so the user can set Coinbase as the default
                Intent startMain = new Intent(Intent.ACTION_MAIN);
                startMain.addCategory(Intent.CATEGORY_HOME);
                startMain.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(startMain);
              }
            })
            .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int which) {
                // Nothing
              }
            }).create().show();
        }
        return true;
      case R.id.menu_tip:
        new AlertDialog.Builder(this).setTitle(R.string.pos_tip_enable_title)
                .setMessage(R.string.pos_tip_enable_text)
                .setPositiveButton(R.string.pos_tip_enable_yes, new DialogInterface.OnClickListener() {
                  @Override
                  public void onClick(DialogInterface dialog, int which) {

                    Utils.putPrefsBool(MainActivity.this,
                            Constants.KEY_ACCOUNT_ENABLE_TIPPING, true);
                  }
                })
                .setNegativeButton(R.string.pos_tip_enable_no, new DialogInterface.OnClickListener() {
                  @Override
                  public void onClick(DialogInterface dialog, int which) {

                    Utils.putPrefsBool(MainActivity.this,
                            Constants.KEY_ACCOUNT_ENABLE_TIPPING, false);
                  }
                }).create().show();
        return true;
      case android.R.id.home:
        if(mInTransactionDetailsMode) {
          mTransactionsFragment.onBackPressed();
        } else if (BuildConfig.type == BuildType.CONSUMER) {
          toggleSlidingMenu();
        }
        return true;
    }

    return super.onOptionsItemSelected(item);
  }

  public class SectionsListAdapter extends BaseAdapter {

    private Integer[] items = null;

    public SectionsListAdapter() {
      buildData();
    }

    @Override
    public void notifyDataSetChanged() {
      buildData();
      super.notifyDataSetChanged();
    }

    private void buildData() {

      List<Integer> itemsList = new ArrayList<Integer>();
      for(int i = 0; i < NUM_FRAGMENTS; i++) {

        if(mFragmentVisible[i]) {
          itemsList.add(i);
        }

        if(mFragmentHasSpacerAfter[i]) {
          itemsList.add(-1);
        }
      }
      if(itemsList.get(itemsList.size() - 1) == -1) {
        itemsList.remove(itemsList.size() - 1); // Do not end in a divider
      }
      items = itemsList.toArray(new Integer[0]);
    }

    @Override
    public int getCount() {

      return items.length;
    }

    @Override
    public Object getItem(int position) {
      return items[position];
    }

    @Override
    public long getItemId(int position) {
      return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {

      if(convertView == null) {
        convertView = View.inflate(MainActivity.this, R.layout.activity_main_menu_item, null);
      }

      TextView title = (TextView) convertView.findViewById(R.id.main_menu_item_title);

      int fragment = items[position];

      if(fragment == -1) {
        // Spacer
        title.setText(null);
        return convertView;
      }

      String font = mViewFlipper.getDisplayedChild() == position ? "Bold" : "Light";

      String name = getString(mFragmentTitles[fragment]);
      title.setText(name);
      title.setTypeface(FontManager.getFont(MainActivity.this, "Roboto-" + font));

      return convertView;
    }

  }

  public void onAccountChosen(int account) {
    changeAccount(account);
  }

  public void changeAccount(int account) {

    if(account == -1) {

      // Delete current account
      LoginManager.getInstance().deleteCurrentAccount(this);
    } else {

      // Change active account
      LoginManager.getInstance().switchActiveAccount(this, account);
    }

    finish();
    startActivity(new Intent(this, MainActivity.class));
  }

  public void onAddAccount() {

    Intent intent = new Intent(this, LoginActivity.class);
    intent.putExtra(LoginActivity.EXTRA_SHOW_INTRO, false);
    startActivity(intent);
    finish();
  }

  public void startBarcodeScan() {

    Intent intent = new Intent(this, com.google.zxing.client.android.CaptureActivity.class);
    intent.setAction(Intents.Scan.ACTION);
    intent.putExtra("SCAN_MODE", "QR_CODE_MODE");
    startActivityForResult(intent, 0);
  }

  public void onActivityResult(int requestCode, int resultCode, Intent intent) {
    if (requestCode == 0) {
      /*
       * Barcode scan
       */
      if (resultCode == RESULT_OK) {

        String contents = intent.getStringExtra("SCAN_RESULT");
        String format = intent.getStringExtra("SCAN_RESULT_FORMAT");

        switchTo(FRAGMENT_INDEX_TRANSFER); // Switch to transfer fragment
        mTransferFragment.fillFormForBitcoinUri(contents);

      } else if (resultCode == RESULT_CANCELED) {
        // Barcode scan was cancelled
      }
    } else if(requestCode == 1) {
      /*
       * Transaction details
       */
      if(resultCode == RESULT_OK) {
        // Refresh needed
        refresh();
      }
    } else if (requestCode == REQUEST_CODE_PIN && resultCode == RESULT_OK) {
      // PIN was successfully entered
      mPendingPinReturn = true;
    }
  }

  @Override
  public void onPostResume() {
    super.onPostResume();

    if (mPendingPinReturn) {
      mFragments[mViewFlipper.getDisplayedChild()].onPINPromptSuccessfulReturn();
      mPendingPinReturn = false;
    }
  }

  public BuySellFragment getBuySellFragment() {
    return mBuySellFragment;
  }

  public TransferFragment getTransferFragment() {
    return mTransferFragment;
  }

  public TransactionsFragment getTransactionsFragment() {
    return mTransactionsFragment;
  }

  public AccountSettingsFragment getAccountSettingsFragment() {
    return mSettingsFragment;
  }

  public void setRefreshButtonAnimated(boolean animated) {

    mRefreshItemState = animated;

    if(mRefreshItem == null) {
      return;
    }

    if(animated) {
      mRefreshItem.setEnabled(false);
      mRefreshItem.setActionView(R.layout.actionbar_indeterminate_progress);
    } else {
      mRefreshItem.setEnabled(true);
      mRefreshItem.setActionView(null);

      if (mTransactionsFragment != null) {
        mTransactionsFragment.refreshComplete();
      }
    }
  }

  public void refresh() {

    mLastRefreshTime = System.currentTimeMillis();

    if (BuildConfig.type == BuildType.CONSUMER) {
      mTransactionsFragment.refresh();
      mBuySellFragment.refresh();
      mTransferFragment.refresh();
      mSettingsFragment.refresh();
    } else {
      mPointOfSaleFragment.refresh();
    }
  }
}
