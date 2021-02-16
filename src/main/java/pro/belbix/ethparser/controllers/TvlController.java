package pro.belbix.ethparser.controllers;

import static pro.belbix.ethparser.utils.CommonUtils.parseLong;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pro.belbix.ethparser.entity.HarvestTvlEntity;
import pro.belbix.ethparser.model.TvlHistory;
import pro.belbix.ethparser.repositories.HarvestTvlRepository;
import pro.belbix.ethparser.service.HarvestTvlDBService;

@ConditionalOnExpression("!${ethparser.onlyParse:false}")
@RestController
public class TvlController {

    private final HarvestTvlDBService harvestTvlDBService;
    private final HarvestTvlRepository harvestTvlRepository;

    public TvlController(HarvestTvlDBService harvestTvlDBService,
                         HarvestTvlRepository harvestTvlRepository) {
        this.harvestTvlDBService = harvestTvlDBService;
        this.harvestTvlRepository = harvestTvlRepository;
    }

    @RequestMapping(value = "api/transactions/history/tvl/{name}", method = RequestMethod.GET)
    public Iterable<TvlHistory> tvlHistoryByVault(@PathVariable("name") String name,
                                                  @RequestParam(value = "start", required = false) String start,
                                                  @RequestParam(value = "end", required = false) String end) {
        return harvestTvlDBService.fetchTvlByVault(name, parseLong(start, 0), parseLong(end, Long.MAX_VALUE));
    }

    @RequestMapping(value = "api/transactions/history/alltvl", method = RequestMethod.GET)
    public Iterable<HarvestTvlEntity> allTvlHistoryData(@RequestParam(value = "start", required = false) String start,
                                                        @RequestParam(value = "end", required = false) String end) {
        return harvestTvlRepository.getHistoryOfAllTvl(parseLong(start, 0), parseLong(end, Long.MAX_VALUE));
    }

}
