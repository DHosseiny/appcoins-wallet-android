package com.asfoundation.wallet.poa;

import com.asfoundation.wallet.entity.NetworkInfo;
import com.asfoundation.wallet.interact.DefaultTokenProvider;
import com.asfoundation.wallet.repository.EthereumNetworkRepositoryType;
import com.asfoundation.wallet.repository.GasSettingsRepositoryType;
import com.asfoundation.wallet.repository.PasswordStore;
import com.asfoundation.wallet.repository.WalletRepositoryType;
import com.asfoundation.wallet.repository.Web3jProvider;
import com.asfoundation.wallet.service.AccountKeystoreService;
import io.reactivex.Single;
import java.io.IOException;
import java.math.BigDecimal;
import org.web3j.protocol.core.DefaultBlockParameterName;

public class TransactionFactory {
  private final Web3jProvider web3jProvider;
  private final WalletRepositoryType walletRepositoryType;
  private final GasSettingsRepositoryType gasSettingsRepository;
  private final AccountKeystoreService accountKeystoreService;
  private final PasswordStore passwordStore;
  private final DefaultTokenProvider defaultTokenProvider;
  private final EthereumNetworkRepositoryType networkRepositoryType;
  private final DataMapper dataMapper;
  private final BigDecimal registerProofGasLimit;

  public TransactionFactory(Web3jProvider web3jProvider, WalletRepositoryType walletRepositoryType,
      GasSettingsRepositoryType gasSettingsRepository,
      AccountKeystoreService accountKeystoreService, PasswordStore passwordStore,
      DefaultTokenProvider defaultTokenProvider,
      EthereumNetworkRepositoryType networkRepositoryType, DataMapper dataMapper,
      BigDecimal registerProofGasLimit) {
    this.web3jProvider = web3jProvider;
    this.walletRepositoryType = walletRepositoryType;
    this.gasSettingsRepository = gasSettingsRepository;
    this.accountKeystoreService = accountKeystoreService;
    this.passwordStore = passwordStore;
    this.defaultTokenProvider = defaultTokenProvider;
    this.networkRepositoryType = networkRepositoryType;
    this.dataMapper = dataMapper;
    this.registerProofGasLimit = registerProofGasLimit;
  }

  public Single<byte[]> createTransaction(Proof proof) {
    return Single.just(networkRepositoryType.getDefaultNetwork())
        .flatMap(defaultNetworkInfo -> defaultTokenProvider.getAdsAddress(proof.getChainId())
            .doOnSubscribe(disposable -> setNetwork(proof.getChainId()))
            .flatMap(adsAddress -> walletRepositoryType.getDefaultWallet()
                .flatMap(wallet -> gasSettingsRepository.getGasSettings(true)
                    .flatMap(gasSettings -> passwordStore.getPassword(wallet)
                        .flatMap(password -> accountKeystoreService.signTransaction(wallet.address,
                            password, adsAddress, BigDecimal.ZERO, gasSettings.gasPrice,
                            registerProofGasLimit, getNonce(wallet.address),
                            dataMapper.getData(proof), proof.getChainId())))))
            .doAfterTerminate(
                () -> networkRepositoryType.setDefaultNetworkInfo(defaultNetworkInfo)));
  }

  public void setNetwork(int chainId) {
    for (NetworkInfo networkInfo : networkRepositoryType.getAvailableNetworkList()) {
      if (chainId == networkInfo.chainId) {
        networkRepositoryType.setDefaultNetworkInfo(networkInfo);
      }
    }
  }

  private long getNonce(String address) throws IOException {
    return web3jProvider.get()
        .ethGetTransactionCount(address, DefaultBlockParameterName.LATEST)
        .send()
        .getTransactionCount()
        .longValue();
  }
}
