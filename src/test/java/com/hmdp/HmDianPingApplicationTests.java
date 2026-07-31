package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private RedisWorker redisWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    public void tsetshop() throws InterruptedException {
        shopService.save2Shop(1L,2L);

    }

    @Test
    void testIdWorker(){
        // 你启动100个任务，计数器写100！不要写300
        CountDownLatch latch = new CountDownLatch(100);

        Runnable task = ()->{
            try {
                for (int i = 0; i < 100; i++) {
                    long id = redisWorker.nextId("order");
                    System.out.println("id=" + id);
                }
            }finally {
                // ✅任务执行完毕，计数器-1，放到finally保证一定会执行
                latch.countDown();
            }
        };

        long begin = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            es.submit(task);
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        long end = System.currentTimeMillis();
        System.out.println("time=" + (end-begin));

        // ✅关闭线程池，释放线程，测试正常终止
        es.shutdown();
    }

}
