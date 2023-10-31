package com.hmdp.config;



import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



@Configuration
public class SecKillMQConfig {
    // 秒杀队列名称
    public static final String QUEUE_NAME = "rabbitmq.orders";

    // 交换机名称
    public static final String EXCHANGE_NAME = "rabbitmq.exchange";

    //并发数量
    public static final int DEFAULT_CONCURRENT = 20;



    // 声名队列
    @Bean("orders_queue")
    public Queue ordersQueue(){

        return new Queue(QUEUE_NAME);
    }


    // 声名交换机
    @Bean("orders_exchange")
    public DirectExchange ordersExchange(){

        return new DirectExchange(EXCHANGE_NAME);
    }

    @Bean
    public Binding Binding(   @Qualifier("orders_queue") Queue queue,
                              @Qualifier("orders_exchange") DirectExchange exchange){

        return BindingBuilder.bind(queue).to(exchange).with("");
    }

   // 配置异步消费多线程
    @Bean("customContainerFactory")
    public SimpleRabbitListenerContainerFactory containerFactory(SimpleRabbitListenerContainerFactoryConfigurer configurer,
                                                                 ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConcurrentConsumers(DEFAULT_CONCURRENT);
        factory.setMaxConcurrentConsumers(DEFAULT_CONCURRENT);
        configurer.configure(factory, connectionFactory);
        return factory;
    }
}


