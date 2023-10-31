package com.hmdp.service.impl;



import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.config.SecKillMQConfig;
import com.hmdp.dto.Result;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;

import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;


import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;

import org.springframework.core.io.ClassPathResource;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import javax.annotation.Resource;
import java.util.Collections;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillService;

    @Resource
    private RedisIdWorker idWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RabbitTemplate rabbitTemplate;


//    //线程池
//    private static final ExecutorService  SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

//    @PostConstruct       //代表在当前类初始化完毕之后再执行
//    private void init(){
//       SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
//    }
//    private class  VoucherOrderHandler implements  Runnable{
//       // String queueName = "stream.orders";
//
//
//        @Override
//        public void run() {
//           while (true){
//               try {
//                   // 获取消息队列中的订单信息 XREADEGROUP GROUP g1 c1  COUNT 1 BLOCK 2000 STREAMS stream.orders >
//                   List<MapRecord<String, Object, Object>> orders = stringRedisTemplate.opsForStream().read(
//                           Consumer.from("g1", "c1"),
//                          StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
//                          StreamOffset.create(queueName, ReadOffset.lastConsumed())
//                  );
//                  // 判断是否获取成功
//                   // 没有，继续循环等待获取
//                   if (orders == null || orders.isEmpty()) {
//                      continue;
//                  }
//                   // 有，解析消息中的订单信息
//                   MapRecord<String, Object, Object> record = orders.get(0); //key 为消息id，value为消息键值对
//                   Map<Object, Object> values = record.getValue();
//                   VoucherOrder order = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
//
//                   //处理订单
//                   handleVoucherOrder();
//                  //ACK确认  SACK stream.orders g1 id
//                  stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
//               } catch (Exception e) {
//                log.error("处理订单异常",e);
//               //异常，去pending队列中处理并确认
//                handlePendingList();
//               }
//
//           }
//        }


//        private void handlePendingList() {
//            while (true){
//                try {
////    获取Pending-list中的订单信息 XREADEGROUP GROUP g1 c1  COUNT 1 BLOCK 2000 STREAMS stream.orders 0
////                    List<MapRecord<String, Object, Object>> orders = stringRedisTemplate.opsForStream().read(
////                            Consumer.from("g1", "c1"),
////                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
////                            StreamOffset.create(queueName, ReadOffset.from("0"))
////                    );
////                    // 判断是否获取成功
////                    // 没有，继续循环等待获取
////                    if (orders == null || orders.isEmpty()) {
////                        break;
////                    }
////                    // 有，解析消息中的订单信息
////                    MapRecord<String, Object, Object> record = orders.get(0); //key 为消息id，value为消息键值对
////                    Map<Object, Object> values = record.getValue();
////                    VoucherOrder order = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
//
//                     处理订单
//                    handleVoucherOrder(order);
////                    //ACK确认  SACK stream.orders g1 id
////                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
//                } catch (Exception e) {
//                    log.error("处理pending-list异常",e);
//                    try {
//                        Thread.sleep(20);
//                    }catch (InterruptedException interruptedException){
//                        interruptedException.printStackTrace();
//                    }
//                }
//        }
//        }



    @RabbitListener(queues = SecKillMQConfig.QUEUE_NAME, containerFactory = "customContainerFactory")
    public void handleVoucherOrder(String message) {
       VoucherOrder order = JSONUtil.toBean(message,VoucherOrder.class);
        redissonClient = SpringUtil.getBean("redissonClient", RedissonClient.class);

        //创建锁对象
        RLock lock = redissonClient.getLock("order:" + order.getUserId());

        //获取锁
        boolean isLock = lock.tryLock(); //默认失败不等待（无参）
        if (!isLock){
            log.error("不允许重复下单");
           return;
        }
        try {
           proxy.createVoucherOrder(order);
        } finally {
            //释放锁
            lock.unlock();
        }

    }

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT ;     //Long为脚本的返回值类型
    static {
        SECKILL_SCRIPT =  new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 代理对象
    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //订单id
        long orderId = idWorker.nextId("order");
        // 1. 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(), //占位，不传递
                voucherId.toString(),
                userId.toString()
               // String.valueOf(orderId)
        );
        // 2.判断结果是否为0
        int r = result.intValue();
        if ( r != 0){
            // 2.1. 不为0，代表没有购买资格
            return  Result.fail(r == 1 ? "库存不足" : "重复下单");
        }

        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 发送消息到MQ队列
        VoucherOrder order = new VoucherOrder();
        order.setVoucherId(voucherId);
        order.setUserId(userId);
        order.setId(orderId);
        String jsonStr = JSONUtil.toJsonStr(order);
        rabbitTemplate.convertAndSend(SecKillMQConfig.EXCHANGE_NAME,"",jsonStr);

        // 3. 返回订单id
        return Result.ok(orderId);
    }

    /*
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
       // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock(); //默认失败不等待（无参）
        if (!isLock){
            return Result.fail("不允许重复下单!");
        }
        try {
            //这里需要用代理对象(事务是Spring通过代理对象实现的，而不是当前对象this.的调用)，否则事务会失效
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            lock.unlock();
        }
    }
 */

    @Transactional
    public void createVoucherOrder(VoucherOrder order){
        //4.5 一人一单
        //用户id
        Long userId = order.getUserId();
    // 查询订单
    int count = query().eq("user_id", userId)
            .eq("voucher_id", order.getVoucherId()).count();
        if (count > 0) {
            log.error("用户已经购买过了!");
        return;
    }
    //5.扣减库存
    boolean success = seckillService.update()
            .setSql("stock = stock - 1")
            .eq("voucher_id", order.getVoucherId())
            .gt("stock",0) //乐观锁判断(这里用gt，防止失败线程过多)
            .update();
    //6.创建订单
        if (!success){
            log.error("库存不足!)");
        return;
    }
    save(order);
}

}
