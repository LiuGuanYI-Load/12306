package org.jav.train12306.controller;

import lombok.RequiredArgsConstructor;
import org.jav.train12306.dto.req.RegionStationQueryReqDTO;
import org.jav.train12306.dto.resp.RegionStationQueryRespDTO;
import org.jav.train12306.dto.resp.StationQueryRespDTO;
import org.jav.train12306.framework.starter.convention.result.Result;
import org.jav.train12306.framework.starter.web.Results;
import org.jav.train12306.service.RegionStationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 地区以及车站查询控制层
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ticket-service")
public class RegionStationController {

    private final RegionStationService regionStationService;

    /**
     * 查询车站&城市站点集合信息
     */
    @GetMapping("/region-station/query")
    public Result<List<RegionStationQueryRespDTO>> listRegionStation(RegionStationQueryReqDTO requestParam) {
        return Results.success(regionStationService.listRegionStation(requestParam));
    }

    /**
     * 查询车站站点集合信息
     */
    @GetMapping("/station/all")
    public Result<List<StationQueryRespDTO>> listAllStation() {
        return Results.success(regionStationService.listAllStation());
    }
}
