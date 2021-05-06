package pro.belbix.ethparser.web3.contracts.db;

import static pro.belbix.ethparser.web3.contracts.ContractConstants.CONTROLLERS;
import static pro.belbix.ethparser.web3.contracts.ContractConstants.ZERO_ADDRESS;
import static pro.belbix.ethparser.web3.contracts.ContractUtils.getBaseAddressInsteadOfZero;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.web3j.tuples.generated.Tuple2;
import pro.belbix.ethparser.entity.contracts.ContractEntity;
import pro.belbix.ethparser.entity.contracts.PoolEntity;
import pro.belbix.ethparser.entity.contracts.TokenEntity;
import pro.belbix.ethparser.entity.contracts.TokenToUniPairEntity;
import pro.belbix.ethparser.entity.contracts.UniPairEntity;
import pro.belbix.ethparser.entity.contracts.VaultEntity;
import pro.belbix.ethparser.repositories.eth.ContractRepository;
import pro.belbix.ethparser.repositories.eth.PoolRepository;
import pro.belbix.ethparser.repositories.eth.TokenRepository;
import pro.belbix.ethparser.repositories.eth.TokenToUniPairRepository;
import pro.belbix.ethparser.repositories.eth.UniPairRepository;
import pro.belbix.ethparser.repositories.eth.VaultRepository;
import pro.belbix.ethparser.web3.contracts.ContractConstants;
import pro.belbix.ethparser.web3.contracts.ContractType;
import pro.belbix.ethparser.web3.contracts.ContractUtils;

@Service
public class ContractDbService {

  private final ContractRepository contractRepository;
  private final PoolRepository poolRepository;
  private final VaultRepository vaultRepository;
  private final UniPairRepository uniPairRepository;
  private final TokenRepository tokenRepository;
  private final TokenToUniPairRepository tokenToUniPairRepository;


  public ContractDbService(
      ContractRepository contractRepository,
      PoolRepository poolRepository,
      VaultRepository vaultRepository,
      UniPairRepository uniPairRepository,
      TokenRepository tokenRepository,
      TokenToUniPairRepository tokenToUniPairRepository) {
    this.contractRepository = contractRepository;
    this.poolRepository = poolRepository;
    this.vaultRepository = vaultRepository;
    this.uniPairRepository = uniPairRepository;
    this.tokenRepository = tokenRepository;
    this.tokenToUniPairRepository = tokenToUniPairRepository;
  }

  public Optional<ContractEntity> getContractByAddress(String address, String network) {
    return Optional.ofNullable(contractRepository
        .findFirstByAddress(address.toLowerCase(), network));
  }

  public Optional<ContractEntity> getContractByAddressAndType(
      String address,
      ContractType type,
      String network) {
    return Optional.ofNullable(contractRepository
        .findFirstByAddressAndType(address.toLowerCase(), type.getId(), network));
  }

  public Optional<ContractEntity> getContractByNameAndType(
      String name, ContractType type, String network) {
    return Optional.ofNullable(contractRepository
        .findFirstByName(name, type.getId(), network));
  }

  public Optional<String> getNameByAddress(String address, String network) {
    return getContractByAddress(address.toLowerCase(), network)
        .map(ContractEntity::getName);
  }

  public Optional<String> getAddressByName(String name, ContractType type, String network) {
    return getContractByNameAndType(name, type, network)
        .map(ContractEntity::getAddress);
  }

  public Optional<ContractEntity> getPoolContractByVaultAddress(String address, String network) {
    return Optional.ofNullable(contractRepository
        .findPoolByVaultAddress(address.toLowerCase(), network));
  }

  public Tuple2<String, String> tokenAddressesByUniPairAddress(String address, String network) {
    UniPairEntity uniPair = Optional.ofNullable(uniPairRepository
        .findFirstByAddress(address.toLowerCase(), network))
        .orElseThrow(() -> new IllegalStateException("Not found uni pair by " + address));
    return new Tuple2<>(
        getBaseAddressInsteadOfZero(uniPair.getToken0().getAddress(), network),
        getBaseAddressInsteadOfZero(uniPair.getToken1().getAddress(), network)
    );
  }

  public double parseAmount(BigInteger amount, String address, String network) {
    if (amount == null || ZERO_ADDRESS.equalsIgnoreCase(address)) {
      return 0.0;
    }
    return new BigDecimal(amount)
        .divide(getDividerByAddress(address, network), 99, RoundingMode.HALF_UP)
        .doubleValue();
  }

  public BigDecimal getDividerByAddress(String address, String network) {
    address = address.toLowerCase();
    long decimals;
    // unique addresses
    if (ContractUtils.isPsAddress(address)) {
      decimals = 18L;
    } else {
      ContractEntity contract = getContractByAddress(address, network).orElseThrow();
      if (contract.getType() == ContractType.VAULT.getId()) {
        decimals = vaultRepository.findFirstByContract(address, network).getDecimals();
      } else if (contract.getType() == ContractType.POOL.getId()) {
        decimals = 18L;
      } else if (contract.getType() == ContractType.UNI_PAIR.getId()) {
        decimals = uniPairRepository.findFirstByAddress(address, network).getDecimals();
      } else if (contract.getType() == ContractType.TOKEN.getId()) {
        decimals = tokenRepository.findFirstByAddress(address, network).getDecimals();
      } else {
        throw new IllegalStateException("Unknown address " + address);
      }
    }
    return new BigDecimal(10L).pow((int) decimals);
  }

  public Optional<UniPairEntity> findLpForTokens(String token0, String token1, String network) {
    List<UniPairEntity> lps = uniPairRepository
        .findLpsForTokenPair(token0.toLowerCase(), token1.toLowerCase(), network);
    if (lps == null || lps.isEmpty()) {
      return Optional.empty();
    }
    UniPairEntity latestLp = lps.get(0);
    for (UniPairEntity lp : lps) {
      if (latestLp.getContract().getCreated() < lp.getContract().getCreated()) {
        latestLp = lp;
      }
    }
    return Optional.of(latestLp);
  }

  public Optional<UniPairEntity> findLpByAddress(String address, String network) {
    return Optional.ofNullable(uniPairRepository
        .findFirstByAddress(address.toLowerCase(), network));
  }

  public Optional<TokenToUniPairEntity> findPairByToken(String tokenAddress, long block,
      String network) {
    List<TokenToUniPairEntity> pairs = tokenToUniPairRepository
        .findByToken(tokenAddress.toLowerCase(), network);
    if (pairs == null || pairs.isEmpty()) {
      return Optional.empty();
    }
    TokenToUniPairEntity result = null;
    for (TokenToUniPairEntity pair : pairs) {
      if (pair.getBlockStart() > block) {
        continue;
      }
      if (result == null || result.getBlockStart() < pair.getBlockStart()) {
        result = pair;
      }
    }
    return Optional.ofNullable(result);
  }

  public List<VaultEntity> getAllVaults(String network) {
    return vaultRepository.fetchAllByNetwork(network);
  }

  public List<PoolEntity> getAllPools(String network) {
    return poolRepository.fetchAllByNetwork(network);
  }

  public List<TokenEntity> getAllTokens(String network) {
    return tokenRepository.fetchAllByNetwork(network);
  }

  public Optional<PoolEntity> getPoolByAddress(String address, String network) {
    return Optional.ofNullable(poolRepository
        .findFirstByAddress(address.toLowerCase(), network));
  }

  public List<String> getSubscriptions() {
    Set<String> contracts = new HashSet<>(Set.of(
        ContractConstants.FARM_TOKEN
    ));
    contracts.addAll(CONTROLLERS.values());
    contracts.addAll(
        contractRepository.findAll().stream()
            .map(ContractEntity::getAddress)
            .collect(Collectors.toList())
    );
    return new ArrayList<>(contracts);
  }

}
