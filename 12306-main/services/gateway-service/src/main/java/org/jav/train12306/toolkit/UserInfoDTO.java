package org.jav.train12306.toolkit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
/*
    @Data 提供了 getter、setter、toString、equals 和 hashCode 方法。
    @AllArgsConstructor 提供了一个包含所有字段的构造函数。
    @NoArgsConstructor 提供了一个无参构造函数。
    @Builder 提供了构建器模式，允许你通过链式调用来创建 User 对象。
 */
public class UserInfoDTO {
    private String userId;
    private String username;
    private String realName;
}
