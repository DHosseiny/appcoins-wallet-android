package com.asfoundation.wallet.ui.iab;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.appcoins.wallet.bdsbilling.Billing;
import com.appcoins.wallet.bdsbilling.WalletService;
import com.appcoins.wallet.bdsbilling.repository.entity.DeveloperPurchase;
import com.appcoins.wallet.bdsbilling.repository.entity.Purchase;
import com.asf.wallet.R;
import com.asfoundation.wallet.billing.adyen.PaymentType;
import com.asfoundation.wallet.billing.analytics.BillingAnalytics;
import com.asfoundation.wallet.entity.TransactionBuilder;
import com.asfoundation.wallet.repository.BdsPendingTransactionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.jakewharton.rxbinding2.view.RxView;
import com.squareup.picasso.Picasso;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

import dagger.android.support.DaggerFragment;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;
import kotlin.NotImplementedError;

import static com.asfoundation.wallet.billing.analytics.BillingAnalytics.PAYMENT_METHOD_CC;
import static com.asfoundation.wallet.ui.iab.IabActivity.DEVELOPER_PAYLOAD;
import static com.asfoundation.wallet.ui.iab.IabActivity.PRODUCT_NAME;
import static com.asfoundation.wallet.ui.iab.IabActivity.TRANSACTION_AMOUNT;
import static com.asfoundation.wallet.ui.iab.IabActivity.TRANSACTION_CURRENCY;
import static com.asfoundation.wallet.ui.iab.IabActivity.URI;

public class PaymentMethodsFragment extends DaggerFragment implements PaymentMethodsView {

  private static final String IS_BDS = "isBds";
  private static final String APP_PACKAGE = "app_package";
  private static final String TAG = PaymentMethodsFragment.class.getSimpleName();
  private static final String TRANSACTION = "transaction";
  private static final String INAPP_PURCHASE_DATA = "INAPP_PURCHASE_DATA";
  private static final String INAPP_DATA_SIGNATURE = "INAPP_DATA_SIGNATURE";
  private static final String INAPP_PURCHASE_ID = "INAPP_PURCHASE_ID";

  private final CompositeDisposable compositeDisposable = new CompositeDisposable();

  PaymentMethodsPresenter presenter;
  @Inject InAppPurchaseInteractor inAppPurchaseInteractor;
  @Inject BillingAnalytics analytics;
  @Inject BdsPendingTransactionService bdsPendingTransactionService;
  @Inject Billing billing;
  @Inject WalletService walletService;
  private ProgressBar loadingView;
  private View dialog;
  private View addressFooter;
  private TextView errorMessage;
  private View errorView;
  private View processingDialog;
  private ImageView appIcon;
  private Button buyButton;
  private Button cancelButton;
  private IabView iabView;
  private Button errorDismissButton;
  private PublishSubject<Boolean> setupSubject;
  private TransactionBuilder transaction;
  private double transactionValue;
  private String currency;
  private TextView appcPriceTv;
  private TextView fiatPriceTv;
  private TextView appNameTv;
  private TextView appSkuDescriptionTv;
  private TextView walletAddressTv;
  private RadioButton appcRadioButton;
  private RadioButton appcCreditsRadioButton;
  private RadioButton creditCardRadioButton;
  private RadioButton paypalRadioButton;
  private View appcView;
  private View appcCreditsView;
  private View creditCardView;
  private View paypalView;
  private String productName;
  private RadioGroup radioGroup;
  private FiatValue fiatValue;
  private String developerPayload;
  private boolean isBds;
  private String uri;

  public static Fragment newInstance(TransactionBuilder transaction, String currency,
      String productName, boolean isBds, String developerPayload, String uri) {
    Bundle bundle = new Bundle();
    bundle.putParcelable(TRANSACTION, transaction);
    bundle.putSerializable(TRANSACTION_AMOUNT, transaction.amount());
    bundle.putString(TRANSACTION_CURRENCY, currency);
    bundle.putString(APP_PACKAGE, transaction.getDomain());
    bundle.putString(PRODUCT_NAME, productName);
    bundle.putString(DEVELOPER_PAYLOAD, developerPayload);
    bundle.putString(URI, uri);
    bundle.putBoolean(IS_BDS, isBds);
    Fragment fragment = new PaymentMethodsFragment();
    fragment.setArguments(bundle);
    return fragment;
  }

  public static String serializeJson(Purchase purchase) throws IOException {
    ObjectMapper objectMapper = new ObjectMapper();
    DeveloperPurchase developerPurchase = objectMapper.readValue(new Gson().toJson(
        purchase.getSignature()
            .getMessage()), DeveloperPurchase.class);
    return objectMapper.writeValueAsString(developerPurchase);
  }

  @Override public void onAttach(Context context) {
    super.onAttach(context);
    if (!(context instanceof IabView)) {
      throw new IllegalStateException("Payment Methods Fragment must be attached to IAB activity");
    }
    iabView = ((IabView) context);
  }

  @Nullable @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.payment_methods_layout, container, false);
  }

  @Override public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setupSubject = PublishSubject.create();

    isBds = getArguments().getBoolean(IS_BDS);
    transaction = getArguments().getParcelable(TRANSACTION);
    transactionValue =
        ((BigDecimal) getArguments().getSerializable(TRANSACTION_AMOUNT)).doubleValue();
    currency = getArguments().getString(TRANSACTION_CURRENCY);
    productName = getArguments().getString(PRODUCT_NAME);
    String appPackage = getArguments().getString(APP_PACKAGE);
    developerPayload = getArguments().getString(DEVELOPER_PAYLOAD);
    uri = getArguments().getString(URI);

    presenter = new PaymentMethodsPresenter(this, appPackage, AndroidSchedulers.mainThread(),
        Schedulers.io(), new CompositeDisposable(), inAppPurchaseInteractor,
        inAppPurchaseInteractor.getBillingMessagesMapper(), bdsPendingTransactionService, billing,
        analytics, isBds, Single.just(transaction), developerPayload, uri, walletService);
  }

  @Override public void onDestroyView() {
    presenter.stop();
    compositeDisposable.clear();
    radioGroup = null;
    loadingView = null;
    dialog = null;
    addressFooter = null;
    errorView = null;
    errorMessage = null;
    processingDialog = null;
    appIcon = null;
    buyButton = null;
    cancelButton = null;
    errorDismissButton = null;
    appcPriceTv = null;
    fiatPriceTv = null;
    appNameTv = null;
    appSkuDescriptionTv = null;
    walletAddressTv = null;
    appcRadioButton = null;
    appcCreditsRadioButton = null;
    super.onDestroyView();
  }

  @Override public void onDetach() {
    super.onDetach();
    iabView = null;
  }

  @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    radioGroup = view.findViewById(R.id.payment_methods_radio_group);
    loadingView = view.findViewById(R.id.loading_view);
    dialog = view.findViewById(R.id.payment_methods);
    addressFooter = view.findViewById(R.id.address_footer);
    errorView = view.findViewById(R.id.error_message);
    errorMessage = view.findViewById(R.id.activity_iab_error_message);
    processingDialog = view.findViewById(R.id.processing_loading);
    appIcon = view.findViewById(R.id.app_icon);
    buyButton = view.findViewById(R.id.buy_button);
    cancelButton = view.findViewById(R.id.cancel_button);
    errorDismissButton = view.findViewById(R.id.activity_iab_error_ok_button);

    appcPriceTv = view.findViewById(R.id.appc_price);
    fiatPriceTv = view.findViewById(R.id.fiat_price);
    appNameTv = view.findViewById(R.id.app_name);
    appSkuDescriptionTv = view.findViewById(R.id.app_sku_description);
    walletAddressTv = view.findViewById(R.id.wallet_address_footer);

    appcRadioButton = view.findViewById(R.id.appc);
    appcCreditsRadioButton = view.findViewById(R.id.appc_credits);
    creditCardRadioButton = view.findViewById(R.id.credit_card);
    paypalRadioButton = view.findViewById(R.id.paypal);
    appcView = view.findViewById(R.id.appc_view);
    appcCreditsView = view.findViewById(R.id.appc_credits_view);
    creditCardView = view.findViewById(R.id.credit_card_view);
    paypalView = view.findViewById(R.id.paypal_view);

    setupAppNameAndIcon();

    presenter.present(transactionValue, currency, savedInstanceState);
  }

  private void setupAppNameAndIcon() {
    compositeDisposable.add(Single.defer(() -> Single.just(getAppPackage()))
        .observeOn(Schedulers.io())
        .map(packageName -> new Pair<>(getApplicationName(packageName),
            getContext().getPackageManager()
                .getApplicationIcon(packageName)))
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(pair -> {
          appNameTv.setText(pair.first);
          appIcon.setImageDrawable(pair.second);
        }, throwable -> {
          throwable.printStackTrace();
        }));
  }

  public String getAppPackage() {
    if (getArguments().containsKey(APP_PACKAGE)) {
      return getArguments().getString(APP_PACKAGE);
    }
    throw new IllegalArgumentException("previous app package name not found");
  }

  private CharSequence getApplicationName(String appPackage)
      throws PackageManager.NameNotFoundException {
    PackageManager packageManager = getContext().getPackageManager();
    ApplicationInfo packageInfo = packageManager.getApplicationInfo(appPackage, 0);
    return packageManager.getApplicationLabel(packageInfo);
  }

  @Override public void showError() {
    loadingView.setVisibility(View.GONE);
    dialog.setVisibility(View.GONE);
    addressFooter.setVisibility(View.GONE);
    errorView.setVisibility(View.VISIBLE);
    errorMessage.setText(R.string.activity_iab_error_message);
  }

  @Override public void finish(Purchase purchase) throws IOException {
    Bundle bundle = new Bundle();
    bundle.putString(INAPP_PURCHASE_DATA, serializeJson(purchase));
    bundle.putString(INAPP_DATA_SIGNATURE, purchase.getSignature()
        .getValue());
    bundle.putString(INAPP_PURCHASE_ID, purchase.getUid());
    iabView.finish(bundle);
  }

  @Override public void showLoading() {
    loadingView.setVisibility(View.VISIBLE);
    dialog.setVisibility(View.INVISIBLE);
    addressFooter.setVisibility(View.INVISIBLE);
  }

  @Override public void hideLoading() {
    loadingView.setVisibility(View.GONE);
    if (processingDialog.getVisibility() != View.VISIBLE) {
      dialog.setVisibility(View.VISIBLE);
      addressFooter.setVisibility(View.VISIBLE);
    }
  }

  @Override public Observable<Object> getCancelClick() {
    return RxView.clicks(cancelButton);
  }

  @Override public void close(Bundle data) {
    iabView.close(data);
  }

  @Override public Observable<Object> errorDismisses() {
    return RxView.clicks(errorDismissButton);
  }

  @Override public Observable<Boolean> setupUiCompleted() {
    return setupSubject;
  }

  @Override public void showProcessingLoadingDialog() {
    dialog.setVisibility(View.INVISIBLE);
    addressFooter.setVisibility(View.GONE);
    loadingView.setVisibility(View.GONE);
    processingDialog.setVisibility(View.VISIBLE);
  }

  private final Map<String, Bitmap> loadedBitmaps = new HashMap<>();

  @Override public void onDestroy() {
    super.onDestroy();

    for (Bitmap bitmap : loadedBitmaps.values()) {
      bitmap.recycle();
    }
    loadedBitmaps.clear();
  }

  @Override public void showPaymentMethods(@NotNull List<PaymentMethod> paymentMethods,
      @NotNull List<PaymentMethod> availablePaymentMethods, FiatValue fiatValue,
      boolean isDonation, String currency) {
    this.fiatValue = fiatValue;
    Formatter formatter = new Formatter();
    String valueText = formatter.format(Locale.getDefault(), "%(,.2f", transaction.amount())
        .toString() + " APPC";
    DecimalFormat decimalFormat = new DecimalFormat("0.00");
    String priceText = decimalFormat.format(fiatValue.getAmount()) + ' ' + currency;
    String finalString = priceText;
    Spannable spannable = new SpannableString(finalString);
    spannable.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.dialog_fiat_price)),
        finalString.indexOf(priceText), finalString.indexOf(priceText) + priceText.length(),
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    appcPriceTv.setText(valueText);
    fiatPriceTv.setText(spannable, TextView.BufferType.SPANNABLE);
    int buyButtonText = isDonation ? R.string.action_donate : R.string.action_buy;
    buyButton.setText(getResources().getString(buyButtonText));

    if (isDonation) {
      appSkuDescriptionTv.setText(getResources().getString(R.string.item_donation));
      appNameTv.setText(getResources().getString(R.string.item_donation));
    } else if (productName != null) {
      appSkuDescriptionTv.setText(productName);
    }

    presenter.sendPurchaseDetails(PAYMENT_METHOD_CC);

    setupPaymentMethods(paymentMethods);
    showAvailable(availablePaymentMethods);
    setupSubject.onNext(true);
  }

  public void loadIcons(PaymentMethod paymentMethod, RadioButton radioButton) {
    compositeDisposable.add(Observable.fromCallable(() -> {
      try {
          Context context = getContext();
          Bitmap bitmap = Picasso.with(context)
            .load(paymentMethod.getIconUrl())
            .get();
        loadedBitmaps.put(paymentMethod.getId(), bitmap);

          BitmapDrawable drawable = new BitmapDrawable(context.getResources(), bitmap);

        return drawable.getCurrent();
      } catch (IOException e) {
        Log.w(TAG, "setupPaymentMethods: Failed to load icons!");
        throw new RuntimeException(e);
      }
    })
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
            .doOnNext(
            drawable -> radioButton.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null,
                null))
        .subscribe(__ -> {
        }, Throwable::printStackTrace));
  }

  private void setupPaymentMethods(List<PaymentMethod> paymentMethods) {
    for (PaymentMethod paymentMethod : paymentMethods) {
      if (paymentMethod.getId()
          .equals("appcoins")) {
        loadIcons(paymentMethod, appcRadioButton);
      } else if (paymentMethod.getId()
          .equals("appcoins_credits")) {
        loadIcons(paymentMethod, appcCreditsRadioButton);
      } else if (paymentMethod.getId()
          .equals("credit_card")) {
        loadIcons(paymentMethod, creditCardRadioButton);
      } else if (paymentMethod.getId()
          .equals("paypal")) {
        loadIcons(paymentMethod, paypalRadioButton);
      }
    }
  }

  private void showAvailable(List<PaymentMethod> paymentMethods) {
    if (isBds) {
      for (PaymentMethod paymentMethod : paymentMethods) {
        if (paymentMethod.getId()
            .equals("appcoins")) {
          appcRadioButton.setEnabled(true);
        } else if (paymentMethod.getId()
            .equals("appcoins_credits")) {
          appcCreditsRadioButton.setEnabled(true);
        } else if (paymentMethod.getId()
            .equals("credit_card")) {
          creditCardRadioButton.setEnabled(true);
        } else if (paymentMethod.getId()
            .equals("paypal")) {
          paypalRadioButton.setEnabled(true);
        }
      }
    } else {
      for (PaymentMethod paymentMethod : paymentMethods) {
        hideAllPayments();

        if (paymentMethod.getId()
            .equals("appcoins")) {
          appcRadioButton.setVisibility(View.VISIBLE);
          appcView.setVisibility(View.VISIBLE);
          appcRadioButton.setEnabled(true);
        }
      }
    }
  }

  private void hideAllPayments() {
    appcRadioButton.setVisibility(View.GONE);
    appcView.setVisibility(View.GONE);
    appcCreditsRadioButton.setVisibility(View.GONE);
    appcCreditsView.setVisibility(View.GONE);
    creditCardRadioButton.setVisibility(View.GONE);
    creditCardView.setVisibility(View.GONE);
    paypalRadioButton.setVisibility(View.GONE);
    paypalView.setVisibility(View.GONE);
  }

  @Override public void setWalletAddress(String address) {
    walletAddressTv.setText(address);
  }

  @Override public Observable<SelectedPaymentMethod> getBuyClick() {
    return RxView.clicks(buyButton)
        .map(__ -> {
          switch (radioGroup.getCheckedRadioButtonId()) {
            case R.id.paypal:
              return SelectedPaymentMethod.PAYPAL;
            case R.id.credit_card:
              return SelectedPaymentMethod.CREDIT_CARD;
            case R.id.appc:
              return SelectedPaymentMethod.APPC;
            case R.id.appc_credits:
              return SelectedPaymentMethod.APPC_CREDITS;
            default:
              throw new NotImplementedError();
          }
        });
  }

  @Override public void showPaypal() {
    iabView.showAdyenPayment(BigDecimal.valueOf(fiatValue.getAmount()), currency, isBds,
        PaymentType.PAYPAL);
  }

  @Override public void showCreditCard() {
    iabView.showAdyenPayment(BigDecimal.valueOf(fiatValue.getAmount()), currency, isBds,
        PaymentType.CARD);
  }

  @Override public void showAppCoins() {
    iabView.showOnChain(transaction.amount(), isBds);
  }

  @Override public void showCredits() {
    iabView.showAppcoinsCreditsPayment(transaction.amount());
  }
}