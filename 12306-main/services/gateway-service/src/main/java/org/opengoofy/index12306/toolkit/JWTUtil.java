package org.opengoofy.index12306.toolkit;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import com.alibaba.fastjson2.JSON;
import io.jsonwebtoken.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import static org.jav.train12306.framework.starter.bases.constant.UserConstant.REAL_NAME_KEY;
import static org.jav.train12306.framework.starter.bases.constant.UserConstant.USER_ID_KEY;
import static org.jav.train12306.framework.starter.bases.constant.UserConstant.USER_NAME_KEY;
@Slf4j
public class JWTUtil {
    private final static String SECRET= "javisabighandsomemanandhehasmanydreams";
    private static final long EXPIRATION = 86400L;
    private static final String TOKEN_PREFIX="jav真的帅啊哈";
    public static String jwtGenerator(UserInfoDTO userInfoDTO){
        Map<String,Object> map=new HashMap<>();
        map.put(USER_ID_KEY, userInfoDTO.getUserId());
        map.put(REAL_NAME_KEY, userInfoDTO.getRealName());
        map.put(USER_NAME_KEY, userInfoDTO.getUsername());
        String jwtToken = Jwts.builder()
                //指定签名算法和密钥，用于对 JWT 进行签名。
                .signWith(SignatureAlgorithm.HS512, SECRET)
                //设置 JWT 的签发时间 issue发布
                .setIssuedAt(new Date())
                //设置用户信息
                .setSubject(JSON.toJSONString(map))
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION * 1000))
                .compact();
        return  TOKEN_PREFIX+jwtToken;
    }
    public  static UserInfoDTO parseJwtToken(String jwtToken) {
        if(StringUtils.hasText(jwtToken)){
            try {
                //去掉Token的前缀
                String actualJWTTOKEN=jwtToken.replace(TOKEN_PREFIX,"");
                Claims claim=Jwts.parser().setSigningKey(SECRET).parseClaimsJws(actualJWTTOKEN).getBody();
                Date expiration=claim.getExpiration();
                if (expiration.after(new Date())) {
                    //得到信息主体
                    String subject = claim.getSubject();
                    //返回UserInfo
                    return JSON.parseObject(subject, UserInfoDTO.class);
                }
            } catch (ExpiredJwtException ignored) {

            } catch (Exception e) {
                log.error("JWT Token解析失败，请检查", e);
            }


        }
        return null;
    }

}
