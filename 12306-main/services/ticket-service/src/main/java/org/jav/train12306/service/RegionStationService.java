package org.jav.train12306.service;

import org.jav.train12306.dto.req.RegionStationQueryReqDTO;
import org.jav.train12306.dto.resp.RegionStationQueryRespDTO;
import org.jav.train12306.dto.resp.StationQueryRespDTO;

import java.util.List;

public interface RegionStationService {

    /**
     * 查询车站&城市站点集合信息
     *
     * @param requestParam 车站&站点查询参数
     * @return 车站&站点返回数据集合
     */
    List<RegionStationQueryRespDTO> listRegionStation(RegionStationQueryReqDTO requestParam);

    /**
     * 查询所有车站&城市站点集合信息
     *
     * @return 车站返回数据集合
     */
    List<StationQueryRespDTO> listAllStation();
}
