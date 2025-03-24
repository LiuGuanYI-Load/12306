package org.jav.train12306.filter;
import com.alibaba.nacos.client.naming.utils.CollectionUtils;
import org.jav.train12306.framework.starter.bases.constant.UserConstant;
import org.jav.train12306.toolkit.JWTUtil;
import org.jav.train12306.toolkit.UserInfoDTO;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.jav.train12306.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
//filters: - TokenValidate，Gateway 会去找一个叫 TokenValidateGatewayFilterFactory 的 Bean
@Component
public class TokenValidateGatewayFilterFactory extends AbstractGatewayFilterFactory<Config>{
    public TokenValidateGatewayFilterFactory() {
        super(Config.class);
    }
    // 忘记加COmponent就会 Failed to start bean 'webServerStartStop'和: Unable to find GatewayFilterFactory with name TokenValidate
    /**
     * 注销用户时需要传递 Token
     */
    public static final String DELETION_PATH = "/api/user-service/deletion";
    @Override
    public GatewayFilter apply(Config config) {
        //springgateway的 的过滤器模式  层层传递  层层处理
        //和责任链模式 不同的是 springgatewayfilter中间可以停下来  责任链模式是不会停下来 层层转交
        //   **exchange**: 在请求到达网关时，Spring Cloud Gateway 会创建 ServerWebExchange 对象，并将其传递给第一个过滤器。
        //**chain**: Spring Cloud Gateway 会创建 GatewayFilterChain 对象，并将其传递给每个过滤器。
        //获得请求
/*        exchange：请求的上下文，包含请求和响应。
        chain：过滤器链，负责把请求传给下一个过滤器*/
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String requestPath = request.getPath().toString();
            if (isPathInBlackPreList(requestPath, config.getBlackPathPre())) {
                String token = request.getHeaders().getFirst("Authorization");
                // TODO 需要验证 Token 是否有效，有可能用户注销了账户，但是 Token 有效期还未过
                UserInfoDTO userInfo = JWTUtil.parseJwtToken(token);
                if (!validateToken(userInfo)) {
                    ServerHttpResponse response = exchange.getResponse();
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    return response.setComplete();
                }

                ServerHttpRequest.Builder builder = exchange.getRequest().mutate().headers(httpHeaders -> {
                    httpHeaders.set(UserConstant.USER_ID_KEY, userInfo.getUserId());
                    httpHeaders.set(UserConstant.USER_NAME_KEY, userInfo.getUsername());
                    httpHeaders.set(UserConstant.REAL_NAME_KEY, URLEncoder.encode(userInfo.getRealName(), StandardCharsets.UTF_8));
                    if (Objects.equals(requestPath, DELETION_PATH)) {
                        httpHeaders.set(UserConstant.USER_TOKEN_KEY, token);
                    }
                });
                // 响应式编程的产物，设计上不可变，每次改动得用mutate()生成新对象
                //修改请求，并将修改后的请求设置到 exchange 中
                return chain.filter(exchange.mutate().request(builder.build()).build());
            }
            return chain.filter(exchange);
        };
    }

    private boolean isPathInBlackPreList(String requestPath, List<String> blackPathPre) {
        if (CollectionUtils.isEmpty(blackPathPre)) {
            return false;
        }
        return blackPathPre.stream().anyMatch(requestPath::startsWith);
    }

    private boolean validateToken(UserInfoDTO userInfo) {
        return userInfo != null;
    }
}
