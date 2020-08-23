package com.zc.miaoshaproject.controller;

import com.zc.miaoshaproject.error.BusinessException;
import com.zc.miaoshaproject.error.EmBusinessError;
import com.zc.miaoshaproject.mq.MqProducer;
import com.zc.miaoshaproject.response.CommonReturnType;
import com.zc.miaoshaproject.service.ItemService;
import com.zc.miaoshaproject.service.OrderService;
import com.zc.miaoshaproject.service.PromoService;
import com.zc.miaoshaproject.service.model.UserModel;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.concurrent.*;

/**
 */
@Controller("order")
@RequestMapping("/order")
@CrossOrigin(origins = {"*"},allowCredentials = "true")
public class OrderController extends BaseController {
    @Autowired
    private OrderService orderService;

    @Autowired
    private HttpServletRequest httpServletRequest;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private MqProducer mqProducer;

    @Autowired
    private ItemService itemService;

    @Autowired
    private PromoService promoService;

    private ExecutorService executorService;

    //初始化线程队列 size即为工作队列数,等待队列为无界队列(并无影响)
    @PostConstruct
    public void initThreadPool(){
        executorService = Executors.newFixedThreadPool(20);
    }

    //生成秒杀令牌
    @RequestMapping(value = "/generatetoken",method = {RequestMethod.POST},consumes={CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType generatetoken(@RequestParam(name="itemId")Integer itemId,
                                          @RequestParam(name="promoId")Integer promoId) throws BusinessException {
        //根据token获取用户信息
        String token = httpServletRequest.getParameterMap().get("token")[0];
        if(StringUtils.isEmpty(token)){
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN,"用户还未登陆，不能下单");
        }
        //获取用户的登陆信息
        UserModel userModel = (UserModel) redisTemplate.opsForValue().get(token);
        if(userModel == null){
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN,"用户还未登陆，不能下单");
        }
        //获取秒杀访问令牌
        String promoToken = promoService.generateSecondKillToken(promoId,itemId,userModel.getId());

        if(promoToken == null){
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"生成令牌失败");
        }
        //返回对应的结果
        return CommonReturnType.create(promoToken);
    }

    //封装下单请求
    @RequestMapping(value = "/createorder",method = {RequestMethod.POST},consumes={CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType createOrder(@RequestParam(name="itemId")Integer itemId,
                                        @RequestParam(name="amount")Integer amount,
                                        @RequestParam(name="promoId",required = false)Integer promoId,
                                        @RequestParam(name="promoToken",required = false)String promoToken) throws BusinessException {
        //1.校验token及缓存中的用户登录信息
        UserModel userModel = validLoginInfo();

        //2.验证活动令牌
        if (StringUtils.isNotBlank(promoToken)){
            String cacheKey ="promo_token_"+promoId+"_userid_"+userModel.getId()+"_itemid_"+itemId;
            String cachePromoToken = (String) redisTemplate.opsForValue().get(cacheKey);
            if(cachePromoToken == null){
                throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"秒杀令牌校验失败");
            }
            if (!cachePromoToken.equals(promoToken)){
                throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"秒杀令牌校验失败");
            }
        }

        // OrderModel orderModel = orderService.createOrder(userModel.getId(),itemId,promoId,amount);

        //3.线程池实现阻塞队列处理高并发流量(保证mysql不死,同时需要不断压测合理调整线程数以保证最高效率)
        this.handleTaskInThreadQueue(itemId, amount, promoId, userModel);

        //3.缓存查询是否售罄
//        if (redisTemplate.hasKey("promo_item_stock_invalid_" + itemId)) {
//            throw new BusinessException(EmBusinessError.STOCK_NOT_ENOUGH, "库存不足");
//        }
//
//        //4.初始化库存流水
//        String stockLogId = itemService.initStockLog(itemId, amount);
//
//        //5.异步事务消息
//        boolean b = mqProducer.transactionAsyncReduceStock(userModel.getId(), itemId, promoId, amount, stockLogId);
//        if (!b) {
//            throw new BusinessException(EmBusinessError.UNKNOWN_ERROR, "下单失败");
//        }
//
        return CommonReturnType.create(null);
    }

    private UserModel validLoginInfo() throws BusinessException {
        //        Boolean isLogin = (Boolean) httpServletRequest.getSession().getAttribute("IS_LOGIN");
        //此处getParameterMap应该非空处理
        String token = httpServletRequest.getParameterMap().get("token")[0];

        if(StringUtils.isEmpty(token)){
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN,"用户还未登陆，不能下单");
        }

        //获取用户的登陆信息    //查询数据库改为缓存中取
//        UserModel userModel = (UserModel)httpServletRequest.getSession().getAttribute("LOGIN_USER");
        UserModel userModel = (UserModel) redisTemplate.opsForValue().get(token);
        if (Objects.isNull(userModel)){
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN,"用户还未登陆，不能下单");
        }
        return userModel;
    }

    private void handleTaskInThreadQueue(@RequestParam(name = "itemId") Integer itemId, @RequestParam(name = "amount") Integer amount, @RequestParam(name = "promoId", required = false) Integer promoId, UserModel userModel) throws BusinessException {
        Future<Object> future = executorService.submit(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                //3.缓存查询是否售罄
                if (redisTemplate.hasKey("promo_item_stock_invalid_" + itemId)) {
                    throw new BusinessException(EmBusinessError.STOCK_NOT_ENOUGH, "库存不足");
                }

                //4.初始化库存流水
                String stockLogId = itemService.initStockLog(itemId, amount);

                //5.异步事务消息
                boolean b = mqProducer.transactionAsyncReduceStock(userModel.getId(), itemId, promoId, amount, stockLogId);
                if (!b) {
                    throw new BusinessException(EmBusinessError.UNKNOWN_ERROR, "下单失败");
                }
                return null;
            }
        });

        try {
            future.get();
        } catch (InterruptedException e) {
            e.printStackTrace();
            throw new BusinessException(EmBusinessError.UNKNOWN_ERROR);
        } catch (ExecutionException e) {
            e.printStackTrace();
            throw new BusinessException(EmBusinessError.UNKNOWN_ERROR);
        }
    }
}
