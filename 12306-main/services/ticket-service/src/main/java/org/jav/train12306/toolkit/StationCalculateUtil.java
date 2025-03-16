/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jav.train12306.toolkit;

import org.jav.train12306.dto.domain.RouteDTO;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class StationCalculateUtil {

    /**
     * 计算出发站和终点站中间的站点（包含出发站和终点站）
     *
     * @param stations     所有站点数据
     * @param startStation 出发站
     * @param endStation   终点站
     * @return 出发站和终点站中间的站点（包含出发站和终点站）
     */
    public static List<RouteDTO> throughStation(List<String> stations, String startStation, String endStation) {
        // 结果路由列表
        List<RouteDTO> routesToDeduct = new ArrayList<>();
        // 出发站索引
        int startIndex = stations.indexOf(startStation);
        // 终点站索引
        int endIndex = stations.indexOf(endStation);
        if (startIndex < 0 || endIndex < 0 || startIndex >= endIndex) { // 校验输入
            // 无效输入返回空列表
            return routesToDeduct;
        }
        // 从出发站到终点站前
        for (int i = startIndex; i < endIndex; i++) {
            // 生成所有站点对
            for (int j = i + 1; j <= endIndex; j++) {
                String currentStation = stations.get(i);
                String nextStation = stations.get(j);
                RouteDTO routeDTO = new RouteDTO(currentStation, nextStation); // 创建路由对象
                routesToDeduct.add(routeDTO); // 添加到结果
            }
        }
        return routesToDeduct;
    }

    /**
     * 计算出发站和终点站需要扣减余票的站点（包含出发站和终点站）
     *
     * @param stations     所有站点数据
     * @param startStation 出发站
     * @param endStation   终点站
     * @return 出发站和终点站需要扣减余票的站点（包含出发站和终点站）
     */
    public static List<RouteDTO> takeoutStation(List<String> stations, String startStation, String endStation) {
        List<RouteDTO> takeoutStationList = new ArrayList<>(); // 结果路由列表
        int startIndex = stations.indexOf(startStation); // 出发站索引
        int endIndex = stations.indexOf(endStation); // 终点站索引
        if (startIndex == -1 || endIndex == -1 || startIndex >= endIndex) { // 校验输入
            return takeoutStationList; // 无效输入返回空列表
        }
        if (startIndex != 0) { // 如果出发站不是起点
            for (int i = 0; i < startIndex; i++) { // 从前面站点
                for (int j = 1; j < stations.size() - startIndex; j++) { // 到出发站后站点
                    takeoutStationList.add(new RouteDTO(stations.get(i), stations.get(startIndex + j)));
                }
            }
        }
        for (int i = startIndex; i <= endIndex; i++) { // 从出发站到终点站
            for (int j = i + 1; j < stations.size() && i < endIndex; j++) { // 生成后续站点对
                takeoutStationList.add(new RouteDTO(stations.get(i), stations.get(j)));
            }
        }
        return takeoutStationList;
    }

    public static void main(String[] args) {
        // 测试用例
        List<String> stations = Arrays.asList("北京南", "济南西", "南京南", "杭州东", "宁波"); // 站点列表
        String startStation = "北京南"; // 出发站
        String endStation = "南京南"; // 终点站
        StationCalculateUtil.takeoutStation(stations, startStation, endStation).forEach(System.out::println); // 打印结果
        // 输出示例：
        // 北京南 -> 济南西
        // 北京南 -> 南京南
        // 北京南 -> 杭州东
        // 北京南 -> 宁波
        // 济南西 -> 南京南
        // 济南西 -> 杭州东
        // 济南西 -> 宁波
    }
}