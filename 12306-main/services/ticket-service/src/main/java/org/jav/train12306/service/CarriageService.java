package org.jav.train12306.service;

import java.util.List;
/**
 * 列车车厢接口层
 */
public interface CarriageService {

    List<String> listCarriageNumber(String trainId, Integer carriageType);
}
