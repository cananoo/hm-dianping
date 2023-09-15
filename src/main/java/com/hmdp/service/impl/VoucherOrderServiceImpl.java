package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.User;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.Synchronized;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillService;

    @Resource
    private RedisIdWorker idWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher voucher = seckillService.getById(voucherId);
        //2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始!");
        }
        //3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束!");
        }
        //4.判断库存是否充足
        if (voucher.getStock()<1) {
            return  Result.fail("库存不足!");
        }
        //用户id
        Long userId = UserHolder.getUser().getId();
           //创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //获取锁
        boolean isLock = lock.tryLock(100);
        if (!isLock){
            return Result.fail("不允许重复下单!");
        }

        try {
            //这里需要用代理对象(事务是Spring通过代理对象实现的，而不是当前对象this.的调用)，否则事务会失效
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            lock.unLock();
        }
             }


    @Transactional
    public Result createVoucherOrder(Long voucherId){
        //4.5 一人一单
        //用户id
        Long userId = UserHolder.getUser().getId();
    // 查询订单
    int count = query().eq("user_id", userId)
            .eq("voucher_id", voucherId).count();
        if (count > 0) {
        return Result.fail("用户已经购买过了!");
    }
    //5.扣减库存
    boolean success = seckillService.update()
            .setSql("stock = stock - 1")
            .eq("voucher_id", voucherId)
            .gt("stock",0) //乐观锁判断(这里用gt，防止失败线程过多)
            .update();
    //6.创建订单
        if (!success){
        return Result.fail("库存不足!");
    }
    VoucherOrder order = new VoucherOrder();
    //6.1订单id
    long orderId = idWorker.nextId("order");
       order.setId(orderId);
        order.setUserId(userId);
    //6.3代金券id
        order.setVoucherId(voucherId);
    save(order);
    //7.返回订单id
        return Result.ok(orderId);

}

}
