package org.jav.train12306.userservice.dto.req;

import lombok.Data;
/**
* @Author: Jav
* @Date: 2025/3/25
* @Description: 乘车人信息修改参数
* @Param: id phone username
* @return:
*
*/
@Data
public class PassengerUpdateDTO {
    private String id;
    private String phone;
    private String username;
}
