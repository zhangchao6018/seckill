package com.zc.miaoshaproject.config;

import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.stereotype.Component;

/**
 * 描述:
 *
 * @Author: zhangchao
 * @Date: 8/20/20 10:59 上午
 **/
@Component
//3600s的session过期时间
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 3600)
public class RedisConfig {

}
