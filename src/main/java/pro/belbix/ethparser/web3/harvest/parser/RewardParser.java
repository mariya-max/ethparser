package pro.belbix.ethparser.web3.harvest.parser;

import static pro.belbix.ethparser.web3.ContractConstants.D18;
import static pro.belbix.ethparser.web3.MethodDecoder.parseAmount;
import static pro.belbix.ethparser.web3.erc20.Tokens.FARM_TOKEN;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.web3j.protocol.core.methods.response.Log;
import pro.belbix.ethparser.dto.DtoI;
import pro.belbix.ethparser.dto.RewardDTO;
import pro.belbix.ethparser.model.HarvestTx;
import pro.belbix.ethparser.properties.AppProperties;
import pro.belbix.ethparser.web3.EthBlockService;
import pro.belbix.ethparser.web3.Functions;
import pro.belbix.ethparser.web3.ParserInfo;
import pro.belbix.ethparser.web3.Web3Parser;
import pro.belbix.ethparser.web3.Web3Service;
import pro.belbix.ethparser.web3.harvest.contracts.StakeContracts;
import pro.belbix.ethparser.web3.harvest.contracts.Vaults;
import pro.belbix.ethparser.web3.harvest.db.RewardsDBService;
import pro.belbix.ethparser.web3.harvest.decoder.HarvestVaultLogDecoder;

@Service
@Log4j2
public class RewardParser implements Web3Parser {

    private static final AtomicBoolean run = new AtomicBoolean(true);
    private final Set<String> notWaitNewBlock = Set.of("reward-download", "new-strategy-download");
    private final BlockingQueue<Log> logs = new ArrayBlockingQueue<>(1000);
    private final BlockingQueue<DtoI> output = new ArrayBlockingQueue<>(100);
    private final HarvestVaultLogDecoder harvestVaultLogDecoder = new HarvestVaultLogDecoder();
    private final Functions functions;
    private final Web3Service web3Service;
    private final EthBlockService ethBlockService;
    private final RewardsDBService rewardsDBService;
    private final AppProperties appProperties;
    private final ParserInfo parserInfo;
    private Instant lastTx = Instant.now();
    private boolean waitNewBlock = true;

    public RewardParser(Functions functions,
                        Web3Service web3Service,
                        EthBlockService ethBlockService,
                        RewardsDBService rewardsDBService, AppProperties appProperties,
                        ParserInfo parserInfo) {
        this.functions = functions;
        this.web3Service = web3Service;
        this.ethBlockService = ethBlockService;
        this.rewardsDBService = rewardsDBService;
        this.appProperties = appProperties;
        this.parserInfo = parserInfo;
    }

    @Override
    public void startParse() {
        log.info("Start parse Rewards logs");
        parserInfo.addParser(this);
        web3Service.subscribeOnLogs(logs);
        new Thread(() -> {
            while (run.get()) {
                Log ethLog = null;
                try {
                    ethLog = logs.poll(1, TimeUnit.SECONDS);
                    RewardDTO dto = parseLog(ethLog);
                    if (dto != null) {
                        lastTx = Instant.now();
                        boolean saved = rewardsDBService.saveRewardDTO(dto);
                        if (saved) {
                            output.put(dto);
                        }
                    }
                } catch (Exception e) {
                    log.error("Error parse reward from " + ethLog, e);
                }
            }
        }).start();
    }

    public RewardDTO parseLog(Log ethLog) throws InterruptedException {
        if (ethLog == null || !StakeContracts.hashToName.containsKey(ethLog.getAddress())) {
            return null;
        }

        HarvestTx tx;
        try {
            tx = harvestVaultLogDecoder.decode(ethLog);
        } catch (Exception e) {
            log.error("Error decode " + ethLog, e);
            return null;
        }
        if (tx == null || !"RewardAdded".equals(tx.getMethodName())) {
            return null;
        }
        if (!notWaitNewBlock.contains(appProperties.getStartUtil())
            && waitNewBlock
            && tx.getBlock().longValue() > ethBlockService.getLastBlock()
        ) {
            log.info("Wait new block for correct parsing rewards");
            Thread.sleep(60 * 1000 * 5); //wait until new block created
        }
        //todo if it is the last block it will be not safe, create another logic
        long nextBlock = tx.getBlock().longValue() + 1;
        String vault = tx.getVault().getValue();
        long periodFinish = functions.callPeriodFinish(vault, nextBlock).longValue();
        double rewardRate = functions.callRewardRate(vault, nextBlock).doubleValue();
        if (periodFinish == 0 || rewardRate == 0) {
            log.error("Wrong values for " + ethLog);
            return null;
        }
        long blockTime = ethBlockService.getTimestampSecForBlock(tx.getBlockHash(), nextBlock);

        double farmRewardsForPeriod = 0.0;
        if (periodFinish > blockTime) {
            farmRewardsForPeriod = (rewardRate / D18) * (periodFinish - blockTime);
        }

        double farmBalance = parseAmount(functions.callBalanceOf(vault, FARM_TOKEN, nextBlock), FARM_TOKEN);

        RewardDTO dto = new RewardDTO();
        dto.setId(tx.getHash() + "_" + tx.getLogId());
        dto.setBlock(tx.getBlock().longValue());
        dto.setBlockDate(blockTime);
        dto.setVault(StakeContracts.hashToName.get(vault)
            .replaceFirst("ST__", "")
            .replaceFirst("ST_", ""));
        dto.setReward(farmRewardsForPeriod);
        dto.setPeriodFinish(periodFinish);
        dto.setFarmBalance(farmBalance);
        log.info("Parsed " + dto);
        return dto;
    }

    public void setWaitNewBlock(boolean waitNewBlock) {
        this.waitNewBlock = waitNewBlock;
    }

    @Override
    public BlockingQueue<DtoI> getOutput() {
        return output;
    }

    @Override
    public Instant getLastTx() {
        return lastTx;
    }
}
