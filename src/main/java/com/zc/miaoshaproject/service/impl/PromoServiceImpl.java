package com.zc.miaoshaproject.service.impl;

import com.zc.miaoshaproject.dao.PromoDOMapper;
import com.zc.miaoshaproject.dataobject.PromoDO;
import com.zc.miaoshaproject.service.ItemService;
import com.zc.miaoshaproject.service.PromoService;
import com.zc.miaoshaproject.service.UserService;
import com.zc.miaoshaproject.service.model.ItemModel;
import com.zc.miaoshaproject.service.model.PromoModel;
import com.zc.miaoshaproject.service.model.UserModel;
import org.joda.time.DateTime;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 */
@Service
public class PromoServiceImpl implements PromoService {

    @Autowired
    private PromoDOMapper promoDOMapper;

    @Autowired
    private ItemService itemService;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private UserService userService;

    @Override
    public PromoModel getPromoByItemId(Integer itemId) {
        //获取对应商品的秒杀活动信息
        PromoDO promoDO = promoDOMapper.selectByItemId(itemId);

        //dataobject->model
        PromoModel promoModel = convertFromDataObject(promoDO);
        if(promoModel == null){
            return null;
        }

        //判断当前时间是否秒杀活动即将开始或正在进行
        if(promoModel.getStartDate().isAfterNow()){
            promoModel.setStatus(1);
        }else if(promoModel.getEndDate().isBeforeNow()){
            promoModel.setStatus(3);
        }else{
            promoModel.setStatus(2);
        }
        return promoModel;
    }

    @Override
    public void publishPromo(Integer promoId) {
        //通过活动id获取活动
        PromoDO promoDO = promoDOMapper.selectByPrimaryKey(promoId);
        if(promoDO.getItemId() == null || promoDO.getItemId().intValue() == 0){
            return;
        }
        ItemModel itemModel = itemService.getItemById(promoDO.getItemId());

        //将库存同步到redis内
        redisTemplate.opsForValue().set("promo_item_stock_"+itemModel.getId(), itemModel.getStock());

        //将大闸的限制数字设置到redis(令牌分发时使用)  具体规则需要根据实际情况调整
        redisTemplate.opsForValue().set("promo_door_count_"+promoId,itemModel.getStock().intValue()*5);
    }

    private PromoModel convertFromDataObject(PromoDO promoDO){
        if(promoDO == null){
            return null;
        }
        PromoModel promoModel = new PromoModel();
        BeanUtils.copyProperties(promoDO,promoModel);
        promoModel.setPromoItemPrice(new BigDecimal(promoDO.getPromoItemPrice()));
        promoModel.setStartDate(new DateTime(promoDO.getStartDate()));
        promoModel.setEndDate(new DateTime(promoDO.getEndDate()));
        return promoModel;
    }

    @Override
    public String generateSecondKillToken(Integer promoId, Integer itemId, Integer userId) {
        //0.校验库存
        //缓存查询是否售罄
        if(redisTemplate.hasKey("promo_item_stock_invalid_"+itemId)){
           return null;
        }
        //1.校验活动
        PromoDO promoDO = promoDOMapper.selectByPrimaryKey(promoId);

        //dataobject->model
        PromoModel promoModel = convertFromDataObject(promoDO);
        if(promoModel == null){
            return null;
        }
        //判断当前时间是否秒杀活动即将开始或正在进行
        if(promoModel.getStartDate().isAfterNow()){
            promoModel.setStatus(1);
        }else if(promoModel.getEndDate().isBeforeNow()){
            promoModel.setStatus(3);
        }else{
            promoModel.setStatus(2);
        }
        //判断活动是否正在进行
        if(promoModel.getStatus().intValue() != 2){
            return null;
        }

        //2.校验商品
        ItemModel itemModel = itemService.getItemByIdInCache(itemId);
        if(itemModel == null){
            return null;
        }

        //3.校验用户
        UserModel userModel = userService.getUserByIdInCache(userId);
        if(userModel == null){
            return null;
        }

        //4.校验秒杀大闸的count数量
        Long count = redisTemplate.opsForValue().increment("promo_door_count_" + promoId, -1);
        // 边界数值需要根据实际业务场景定,比如:红包雨红包是随机金额,这个界限
        if (count<0){
            return null;
        }
        //生成token
        String cacheKey ="promo_token_"+promoId+"_userid_"+userId+"_itemid_"+itemId;
        String token = UUID.randomUUID().toString().replace("-","");
        redisTemplate.opsForValue().set(cacheKey,token);
        redisTemplate.expire(cacheKey,5, TimeUnit.MINUTES);

        return token;
    }
}
