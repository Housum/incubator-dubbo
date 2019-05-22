package org.apache.dubbo.demo;

/**
 * 设为true，表示使用缺省Mock类名，即：接口名 + Mock后缀，服务接口调用失败Mock实现类，该Mock类必须有一个无参构造函数，
 * 与Local的区别在于，Local总是被执行，而Mock只在出现非业务异常(比如超时，网络异常等)时执行，Local在远程调用之前执行，
 * Mock在远程调用后执行。
 *
 * @author qibao
 * @since 2019-04-30
 */
public class DemoServiceMock implements DemoService {

    @Override
    public String sayHello(String name) {
        return "mock";
    }
}
