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

package org.jav.train12306.userservice.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jav.train12306.framework.starter.cache.DistributedCache;
import org.jav.train12306.framework.starter.common.toolkit.BeanUtil;
import org.jav.train12306.framework.starter.convention.exception.ClientException;
import org.jav.train12306.framework.starter.convention.exception.ServiceException;
import org.jav.train12306.framework.starter.designpattern.chain.AbstractChainContext;
import org.jav.train12306.frameworks.starter.user.core.UserContext;
import org.jav.train12306.frameworks.starter.user.core.UserInfoDTO;
import org.jav.train12306.frameworks.starter.user.toolkit.JWTUtil;
import org.jav.train12306.userservice.common.enums.UserChainMarkEnum;
import org.jav.train12306.userservice.dao.entity.UserDO;
import org.jav.train12306.userservice.dao.entity.UserDeletionDO;
import org.jav.train12306.userservice.dao.entity.UserMailDO;
import org.jav.train12306.userservice.dao.entity.UserPhoneDO;
import org.jav.train12306.userservice.dao.entity.UserReuseDO;
import org.jav.train12306.userservice.dao.mapper.UserDeletionMapper;
import org.jav.train12306.userservice.dao.mapper.UserMailMapper;
import org.jav.train12306.userservice.dao.mapper.UserMapper;
import org.jav.train12306.userservice.dao.mapper.UserPhoneMapper;
import org.jav.train12306.userservice.dao.mapper.UserReuseMapper;
import org.jav.train12306.userservice.dto.req.UserDeletionReqDTO;
import org.jav.train12306.userservice.dto.req.UserLoginReqDTO;
import org.jav.train12306.userservice.dto.req.UserRegisterReqDTO;
import org.jav.train12306.userservice.dto.resp.UserLoginRespDTO;
import org.jav.train12306.userservice.dto.resp.UserQueryRespDTO;
import org.jav.train12306.userservice.dto.resp.UserRegisterRespDTO;
import org.jav.train12306.userservice.service.UserLoginService;
import org.jav.train12306.userservice.service.UserService;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.jav.train12306.userservice.common.constant.RedisKeyConstant.LOCK_USER_REGISTER;
import static org.jav.train12306.userservice.common.constant.RedisKeyConstant.USER_DELETION;
import static org.jav.train12306.userservice.common.constant.RedisKeyConstant.USER_REGISTER_REUSE_SHARDING;
import static org.jav.train12306.userservice.common.enums.UserRegisterErrorCodeEnum.HAS_USERNAME_NOTNULL;
import static org.jav.train12306.userservice.common.enums.UserRegisterErrorCodeEnum.MAIL_REGISTERED;
import static org.jav.train12306.userservice.common.enums.UserRegisterErrorCodeEnum.PHONE_REGISTERED;
import static org.jav.train12306.userservice.common.enums.UserRegisterErrorCodeEnum.USER_REGISTER_FAIL;
import static org.jav.train12306.userservice.toolkit.UserReuseUtil.hashShardingIdx;

/**
 * 用户登录接口实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserLoginServiceImpl implements UserLoginService {

    private final UserService userService;
    private final UserMapper userMapper;
    private final UserReuseMapper userReuseMapper;
    private final UserDeletionMapper userDeletionMapper;
    private final UserPhoneMapper userPhoneMapper;
    private final UserMailMapper userMailMapper;
    private final RedissonClient redissonClient;
    private final DistributedCache distributedCache;
    private final AbstractChainContext<UserRegisterReqDTO> abstractChainContext;
    private final RBloomFilter<String> userRegisterCachePenetrationBloomFilter;
    private final String EXIST_USER_STATUS="0";
    private final String NO_EXIST_USER_STATUS="1";
    @Override
    public UserLoginRespDTO login(UserLoginReqDTO requestParam) {
        //这一段的处理 为什么不让前端去检测 然后使用变量标记是什么类型不就行了吗，可以省去遍历，但是增加字段
        //但是为什么要设置user_mail user_phone这两个表？？ 直接一个用户表 记录两个信息不就行了吗
        String usernameOrMailOrPhone = requestParam.getUsernameOrMailOrPhone();
        boolean mailFlag = false;
        // 时间复杂度最佳 O(1)。indexOf or contains 时间复杂度为 O(n)
        for (char c : usernameOrMailOrPhone.toCharArray()) {
            if (c == '@') {
                mailFlag = true;
                break;
            }
        }
        String username;
        //通过手机号  或者邮箱查出 用户名
        if (mailFlag) {
            LambdaQueryWrapper<UserMailDO> queryWrapper = Wrappers.lambdaQuery(UserMailDO.class)
                    .eq(UserMailDO::getMail, usernameOrMailOrPhone);
            username = Optional.ofNullable(userMailMapper.selectOne(queryWrapper))
                    .map(UserMailDO::getUsername)
                    .orElseThrow(() -> new ClientException("用户名/手机号/邮箱不存在"));
        } else {
            LambdaQueryWrapper<UserPhoneDO> queryWrapper = Wrappers.lambdaQuery(UserPhoneDO.class)
                    .eq(UserPhoneDO::getPhone, usernameOrMailOrPhone);
            username = Optional.ofNullable(userPhoneMapper.selectOne(queryWrapper))
                    .map(UserPhoneDO::getUsername)
                    .orElse(null);
        }
        username = Optional.ofNullable(username).orElse(requestParam.getUsernameOrMailOrPhone());


        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, username)
                .eq(UserDO::getPassword, requestParam.getPassword())
                .select(UserDO::getId, UserDO::getUsername, UserDO::getRealName);


        UserDO userDO = userMapper.selectOne(queryWrapper);
        if (userDO != null) {
            UserInfoDTO userInfo = UserInfoDTO.builder()
                    .userId(String.valueOf(userDO.getId()))
                    .username(userDO.getUsername())
                    .realName(userDO.getRealName())
                    .build();
            String accessToken = JWTUtil.generateAccessToken(userInfo);
            UserLoginRespDTO actual = new UserLoginRespDTO(userInfo.getUserId(), requestParam.getUsernameOrMailOrPhone(), userDO.getRealName(), accessToken);
            distributedCache.put(accessToken, JSON.toJSONString(actual), 30, TimeUnit.MINUTES);
            return actual;
        }
        throw new ServiceException("账号不存在或密码错误");
    }

    @Override
    public UserLoginRespDTO checkLogin(String accessToken) {
        return distributedCache.get(accessToken, UserLoginRespDTO.class);
    }

    @Override
    public void logout(String accessToken) {
        if (StrUtil.isNotBlank(accessToken)) {
            distributedCache.delete(accessToken);
        }
    }

    @Override
    public Boolean hasUsername(String username) {
        boolean hasUsername = userRegisterCachePenetrationBloomFilter.contains(username);
        if (hasUsername) {
            StringRedisTemplate instance = (StringRedisTemplate) distributedCache.getInstance();
            //这里是查询 这个名字是不是 可复用的set集合 如果是 说明是这个名字注册过 但是又注销了
            return instance.opsForSet().isMember(USER_REGISTER_REUSE_SHARDING + hashShardingIdx(username), username);
        }
        //到这说明 bloom是没有的 一定可用
        return true;
    }

    // 开启 Spring 事务管理，任何 Exception 抛出时回滚，确保多表操作一致性
    @Transactional(rollbackFor = Exception.class)
    @Override
    public UserRegisterRespDTO register(UserRegisterReqDTO requestParam) {
        // 调用责任链模式，执行注册前的过滤逻辑（如参数校验、黑名单检查）
        abstractChainContext.handler(UserChainMarkEnum.USER_REGISTER_FILTER.name(), requestParam);

        // 获取 Redisson 分布式锁，锁键为 "LOCK_USER_REGISTER" + 用户名，防止并发注册同一用户
        RLock lock = redissonClient.getLock(LOCK_USER_REGISTER + requestParam.getUsername());
        // 尝试获取锁，默认无等待时间，失败则返回 false
        boolean tryLock = lock.tryLock();
        // 未获取到锁，说明有并发注册，抛出用户名已存在异常
        if (!tryLock) {
            throw new ServiceException(HAS_USERNAME_NOTNULL);
        }

        try {
            try {
                // 将请求 DTO 转为 DO，插入用户表，返回影响行数
                int inserted = userMapper.insert(BeanUtil.convert(requestParam, UserDO.class));
                // 插入失败（结果行数 < 1），记录日志并抛出注册失败异常
                if (inserted < 1) {
                    log.error("用户 [{}] 注册失败，插入用户表无影响行", requestParam.getUsername());
                    throw new ServiceException(USER_REGISTER_FAIL);
                }
            } catch (DuplicateKeyException dke) {
                // 捕获用户名重复异常，记录日志并抛出用户名已存在异常
                log.error("用户名 [{}] 重复注册", requestParam.getUsername());
                throw new ServiceException(HAS_USERNAME_NOTNULL);
            }

            // 构建用户手机号 DO，包含手机号和用户名
            UserPhoneDO userPhoneDO = UserPhoneDO.builder()
                    .phone(requestParam.getPhone())
                    .username(requestParam.getUsername())
                    .build();
            try {
                // 插入手机号记录
                userPhoneMapper.insert(userPhoneDO);
            } catch (DuplicateKeyException dke) {
                // 捕获手机号重复异常，记录日志并抛出手机号已注册异常
                log.error("用户 [{}] 注册手机号 [{}] 重复", requestParam.getUsername(), requestParam.getPhone());
                throw new ServiceException(PHONE_REGISTERED);
            }

            // 检查邮箱是否非空，非空则插入邮箱记录
            if (StrUtil.isNotBlank(requestParam.getMail())) {
                UserMailDO userMailDO = UserMailDO.builder()
                        .mail(requestParam.getMail())
                        .username(requestParam.getUsername())
                        .build();
                try {
                    // 插入邮箱记录
                    userMailMapper.insert(userMailDO);
                } catch (DuplicateKeyException dke) {
                    // 捕获邮箱重复异常，记录日志并抛出邮箱已注册异常
                    log.error("用户 [{}] 注册邮箱 [{}] 重复", requestParam.getUsername(), requestParam.getMail());
                    throw new ServiceException(MAIL_REGISTERED);
                }
            }

            // 获取用户名，清理复用表和 Redis 缓存
            String username = requestParam.getUsername();
            // 删除用户复用表中的记录
            userReuseMapper.delete(Wrappers.update(new UserReuseDO(username)));
            // 获取 Redis 操作实例，从分片 Set 中移除用户名
            StringRedisTemplate instance = (StringRedisTemplate) distributedCache.getInstance();
            instance.opsForSet().remove(USER_REGISTER_REUSE_SHARDING + hashShardingIdx(username), username);
            // 将用户名添加到布隆过滤器，防止缓存穿透
            userRegisterCachePenetrationBloomFilter.add(username);

        } finally {
            // 确保分布式锁释放，避免死锁
            lock.unlock();
        }

        // 将请求参数转为响应 DTO 返回
        return BeanUtil.convert(requestParam, UserRegisterRespDTO.class);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void deletion(UserDeletionReqDTO requestParam) {
        String username = UserContext.getUsername();
        if (!Objects.equals(username, requestParam.getUsername())) {
            // 此处严谨来说，需要上报风控中心进行异常检测
            throw new ClientException("注销账号与登录账号不一致");
        }
        RLock lock = redissonClient.getLock(USER_DELETION + requestParam.getUsername());
        // 加锁为什么放在 try 语句外？https://www.yuque.com/magestack/12306/pu52u29i6eb1c5wh
        //防止 没上锁之前 出现空指针引用等 因为锁的资源是必须拿到才能进行的 或者lock.lock放在try第一个语句
        lock.lock();
        try {
            UserQueryRespDTO userQueryRespDTO = userService.queryUserByUsername(username);
            UserDeletionDO userDeletionDO = UserDeletionDO.builder()
                    .idType(userQueryRespDTO.getIdType())
                    .idCard(userQueryRespDTO.getIdCard())
                    .build();
            userDeletionMapper.insert(userDeletionDO);
            UserDO userDO = new UserDO();
            userDO.setDeletionTime(System.currentTimeMillis());
            userDO.setUsername(username);
            //原来的
            userMapper.deletionUser(userDO);
//          userMapper.update(userDO, Wrappers.<UserDO>lambdaUpdate()
//                    .eq(UserDO::getUsername, username)
//                    .eq(UserDO::getDelFlag, EXIST_USER_STATUS)
//                    .set(UserDO::getDelFlag, NO_EXIST_USER_STATUS));
            UserPhoneDO userPhoneDO = UserPhoneDO.builder()
                    .phone(userQueryRespDTO.getPhone())
                    .deletionTime(System.currentTimeMillis())
                    .build();
            userPhoneMapper.deletionUser(userPhoneDO);
            //未定义userphonemapper 和usermailmapper  所以放弃
//            userMapper.update(userPhoneDO, Wrappers.<UserDO>lambdaUpdate()
//                    .eq(UserPhoneDO::getPhone,userQueryRespDTO.getPhone())
//                    .eq(UserPhoneDO::getDelFlag, EXIST_USER_STATUS)
//                    .set(UserPhoneDO::getDelFlag, NO_EXIST_USER_STATUS));
            if (StrUtil.isNotBlank(userQueryRespDTO.getMail())) {
                UserMailDO userMailDO = UserMailDO.builder()
                        .mail(userQueryRespDTO.getMail())
                        .deletionTime(System.currentTimeMillis())
                        .build();
//                userMapper.update(userMailDO, Wrappers.<UserMailDO>lambdaUpdate()
//                    .eq(UserMailDO::getMail,userQueryRespDTO.getMail())
//                    .eq(UserMailDO::getDelFlag, EXIST_USER_STATUS)
//                    .set(UserMailDO::getDelFlag, NO_EXIST_USER_STATUS));
                userMailMapper.deletionUser(userMailDO);
            }
            distributedCache.delete(UserContext.getToken());
            userReuseMapper.insert(new UserReuseDO(username));
            StringRedisTemplate instance = (StringRedisTemplate) distributedCache.getInstance();
            instance.opsForSet().add(USER_REGISTER_REUSE_SHARDING + hashShardingIdx(username), username);
        } finally {
            lock.unlock();
        }
    }
}
