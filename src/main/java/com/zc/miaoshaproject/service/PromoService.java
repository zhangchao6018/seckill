package com.zc.miaoshaproject.service;

import com.zc.miaoshaproject.service.model.PromoModel;

/**
 */
public interface PromoService {
    //根据itemid获取即将进行的或正在进行的秒杀活动
    PromoModel getPromoByItemId(Integer itemId);
}
