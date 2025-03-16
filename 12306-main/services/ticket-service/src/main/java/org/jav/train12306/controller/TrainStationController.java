package org.jav.train12306.controller;

import lombok.RequiredArgsConstructor;
import org.jav.train12306.dto.resp.TrainStationQueryRespDTO;
import org.jav.train12306.framework.starter.convention.result.Result;
import org.jav.train12306.framework.starter.web.Results;
import org.jav.train12306.service.TrainStationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/ticket-service/train-station")
public class TrainStationController {
    private final TrainStationService trainStationService;

    /**
     * 根据列车 ID 查询站点信息
     */
    @GetMapping("/query")
    public Result<List<TrainStationQueryRespDTO>> listTrainStationQuery(String trainId) {
        return Results.success(trainStationService.listTrainStationQuery(trainId));
    }
}
