package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import io.reactivex.Completable;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号(使用工具类)
        if (RegexUtils.isPhoneInvalid(phone)) {
          //2.如果不符合，发送错误信息
            return Result.fail("手机号格式错误");
        }
        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6); //6位验证码

        /*
         *      //4.保存验证码到Session
         *         session.setAttribute("code",code);
         */

       //4. 保存验证码到Redis
        stringRedisTemplate
                .opsForValue()
                .set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES); //加前缀区分业务(设置为两分钟超时)

        //5.发送验证码
        log.debug("发送验证码: "+code);
        //6.返回
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            //如果不符合，发送错误信息
            return Result.fail("手机号格式错误");
        }
        //2.校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+loginForm.getPhone());
        String code = loginForm.getCode();
     if (cacheCode == null ||  !cacheCode.equals(code)){
         //3.不一致，报错
         return  Result.fail("验证码错误");
     }
        //4.一致，根据手机号查找用户
        //Mybatis-plus 提供的快捷查询
        User user = query().eq("phone", loginForm.getPhone()).one();
         ;

        //5.判断用户是否存在
        if (user == null) {
            //6.不存在，创建用户并保存
            user = createUserWithPhone(loginForm.getPhone());
        }
        //7.保存用户信息到Redis中（存储DTO，防止暴露过多数据）
        //7.1 随机生成token作为登录令牌
        String token = UUID.randomUUID().toString(true); //(true):不带中横线 这里用的hotool的工具类
        //7.2 将User转换为hash结构存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap =
                BeanUtil.beanToMap(userDTO,
                        new HashMap<>(),
                        CopyOptions.create()
                                .setIgnoreNullValue(true) //设置忽略空值字段
                                .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()) //Redis Hash 键值对均为String类型，否则会报错
                        ); //Hotool
        //7.3 存储到Redis中
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //7.4设置token有效期 (还要在拦截器中配置：即用户继续访问刷新超时时间)
        stringRedisTemplate.expire(tokenKey,30, TimeUnit.MINUTES);

        //8.返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
           save(user);
        return  user;
    }

    @Override
    public Result sign() {
        // 获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        // 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();   // 1-31 存的时候从0开始所以要减一
        // 写入redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth - 1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {

        // 获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        // 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();   // 1-31 存的时候从0开始所以要减一

        // 1.获取本月截至今天为止的所有签到记录,返回的是一个十进制的数字   BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );

        if (result == null || result.isEmpty()){
            //没有任何签到结果
            return  Result.ok(0);
        }

        Long num = result.get(0);
        if ( num == null || num == 0){
            return Result.ok(0);
        }

        // 2.循环遍历
        int count = 0;
           while (true){
               // 3.让这个数字与1做与运算，得到数字的最后一个bit位,判断这个bit位是否为0
               if ((num & 1) == 0) {
                   // 5.如果为0，说明未签到，结束
                   break;
               }else {
                   // 6.如果不为0，说明已签到，计数器+1
              count++;
               }
               // 7.把数字右移一位,抛弃最后一位
               num >>>= 1;
           }
        return Result.ok(count);
    }
}
