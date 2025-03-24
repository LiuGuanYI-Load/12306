package org.jav.train12306.remote;

import jakarta.validation.constraints.NotEmpty;
import org.jav.train12306.framework.starter.convention.result.Result;
import org.jav.train12306.remote.dto.UserQueryActualRespDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
/**
 * 用户远程服务调用
 */
//@FeignClient(value = "jav12306-user${unique-name:}-service", url = "${aggregation.remote-url:}")
@FeignClient(value = "index12306-user${unique-name:}-service", url = "${aggregation.remote-url:}")
public interface UserRemoteService {
    /**
     根据乘车人 ID 集合查询乘车人列表
     */
    /**
     * 根据乘车人 ID 集合查询乘车人列表
     */
    @GetMapping("/api/user-service/actual/query")
    Result<UserQueryActualRespDTO> queryActualUserByUsername(@RequestParam("username") @NotEmpty String username);
}
