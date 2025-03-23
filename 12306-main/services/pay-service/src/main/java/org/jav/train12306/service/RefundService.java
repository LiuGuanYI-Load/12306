package org.jav.train12306.service;

import org.jav.train12306.dto.RefundReqDTO;
import org.jav.train12306.dto.RefundRespDTO;

public interface RefundService {

    /**
     * 公共退款接口
     *
     * @param requestParam 退款请求参数
     * @return 退款返回详情
     */
    RefundRespDTO commonRefund(RefundReqDTO requestParam);
}
