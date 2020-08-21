package com.zc.miaoshaproject.service;

/**
 * 描述:
 * 封装本地缓存操作类
 * @Author: zhangchao
 * @Date: 8/20/20 5:26 下午
 **/
public interface CacheService {
    //存方法
    void setCommonCache(String key,Object value);

    //取方法
    Object getFromCommonCache(String key);
}
