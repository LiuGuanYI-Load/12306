package org.jav.train12306.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.jav.train12306.dao.entity.TrainStationDO;
import org.jav.train12306.dao.mapper.TrainStationMapper;
import org.jav.train12306.dto.domain.RouteDTO;
import org.jav.train12306.dto.resp.TrainStationQueryRespDTO;
import org.jav.train12306.framework.starter.common.toolkit.BeanUtil;
import org.jav.train12306.service.TrainStationService;
import org.jav.train12306.toolkit.StationCalculateUtil;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

// 使用 Lombok 的 @RequiredArgsConstructor 注解，自动生成包含所有 final 变量的构造方法
@RequiredArgsConstructor
// 将该类标记为 Spring 的 Service 组件
@Service
public class TrainStationServiceImpl implements TrainStationService {
    // 注入 TrainStationMapper，用于操作数据库
    private final TrainStationMapper trainStationMapper;

/*  优化掉没用的查询方案
    @Override
    public List<RouteDTO> listTakeoutTrainStationRoute(String trainId, String departure, String arrival) {
        // 创建 LambdaQueryWrapper，用于构建查询条件
        LambdaQueryWrapper<TrainStationDO> queryWrapper = Wrappers.lambdaQuery(TrainStationDO.class)
                .eq(TrainStationDO::getTrainId, trainId)
                // 指定查询字段：只查询 departure 字段
                .select(TrainStationDO::getDeparture);

        // 执行查询，获取符合条件的 TrainStationDO 列表
        List<TrainStationDO> trainStationDOList = trainStationMapper.selectList(queryWrapper);
        // 将 TrainStationDO 列表转换为出发站名称的列表
        List<String> trainStationAllList = trainStationDOList.stream()
                .map(TrainStationDO::getDeparture)
                .collect(Collectors.toList());
        // 调用 StationCalculateUtil 工具类，计算并返回站点路线信息
        return StationCalculateUtil.takeoutStation(trainStationAllList, departure, arrival);
    }*/

    /**
     * 查询列车站点信息
     *
     * @param trainId 列车 ID
     * @return 列车站点信息列表
     */
    @Override
    public List<TrainStationQueryRespDTO> listTrainStationQuery(String trainId) {
        // 创建 LambdaQueryWrapper，用于构建查询条件
        LambdaQueryWrapper<TrainStationDO> queryWrapper = Wrappers.lambdaQuery(TrainStationDO.class)
                .eq(TrainStationDO::getTrainId, trainId);
        // 执行查询，获取符合条件的 TrainStationDO 列表
        List<TrainStationDO> trainStationDOList = trainStationMapper.selectList(queryWrapper);
        // 使用 BeanUtil 工具类，将 TrainStationDO 列表转换为 TrainStationQueryRespDTO 列表
        return BeanUtil.convert(trainStationDOList, TrainStationQueryRespDTO.class);
    }

    /**
     * 计算列车站点路线关系
     * 获取开始站点和目的站点及中间站点信息
     *
     * @param trainId   列车 ID
     * @param departure 出发站
     * @param arrival   到达站
     * @return 站点路线信息列表
     */
    @Override
    public List<RouteDTO> listTrainStationRoute(String trainId, String departure, String arrival) {
        // 创建 LambdaQueryWrapper，用于构建查询条件
        LambdaQueryWrapper<TrainStationDO> queryWrapper = Wrappers.lambdaQuery(TrainStationDO.class)
                .eq(TrainStationDO::getTrainId, trainId)
                .select(TrainStationDO::getDeparture);
        // 执行查询，获取符合条件的 TrainStationDO 列表
        List<TrainStationDO> trainStationDOList = trainStationMapper.selectList(queryWrapper);
        // 将 TrainStationDO 列表转换为出发站名称的列表
        List<String> trainStationAllList = trainStationDOList.stream()
                .map(TrainStationDO::getDeparture)
                .collect(Collectors.toList());
        // 调用 StationCalculateUtil 工具类，计算并返回站点路线信息
        //计算中间经过的站点 并返回 List 站点对
        return StationCalculateUtil.throughStation(trainStationAllList, departure, arrival);
    }
}