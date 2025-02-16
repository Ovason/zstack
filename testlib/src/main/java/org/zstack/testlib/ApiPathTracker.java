package org.zstack.testlib;

import org.apache.logging.log4j.ThreadContext;
import org.zstack.core.Platform;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.header.Constants;
import org.zstack.header.message.*;
import org.zstack.header.rest.BeforeAsyncJsonPostInterceptor;
import org.zstack.header.rest.RESTFacade;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Created by xing5 on 2017/3/24.
 */
public class ApiPathTracker {
    private CloudBus bus;
    private RESTFacade restf;

    final List<Path> paths = new ArrayList<>();
    // 步骤开始的时间 --ovason
    final Map<String, Long> msgStartTime = new HashMap<>();

    private enum Type {
        Message,
        HttpRPC,
    }

    private static class Path {
        Type type;
        String path;
    }

    public List<String> getApiPath() {
        synchronized (paths) {
            return paths.stream().map(p -> String.format("(%s) %s", p.type, p.path)).collect(Collectors.toList());
        }
    }

    public ApiPathTracker(String apiId) {
        bus = Platform.getComponentLoader().getComponent(CloudBus.class);
        restf = Platform.getComponentLoader().getComponent(RESTFacade.class);

        bus.installBeforeSendMessageInterceptor(new AbstractBeforeSendMessageInterceptor() {
            @Override
            public void beforeSendMessage(Message msg) {
                if (msg instanceof MessageReply) {
                    // 计算步骤耗时 --ovason
                    Long startTime = msgStartTime.get(msg.getId());
                    Date endDate = new Date();
                    long msgSpendTime = endDate.getTime() - startTime;
                    // 用于计算api耗时的数据头
                    Map<String, Object> apiCostHeader = new HashMap<>();
                    apiCostHeader.put("apicost", msgSpendTime);
                    msg.setHeaders(apiCostHeader);
                    return;
                }

                if (msg instanceof CarrierMessage)
                    return;

                String id = ThreadContext.get(Constants.THREAD_CONTEXT_API);

                if (id == null || !id.equals(apiId)) {
                    return;
                }

                Path p = new Path();
                p.type = Type.Message;
                p.path = msg.getClass().getName();
                paths.add(p);

                // 写入步骤开始的时间 --ovason
                msgStartTime.put(msg.getId(), msg.getCreatedTime());
            }
        });

        restf.installBeforeAsyncJsonPostInterceptor(new BeforeAsyncJsonPostInterceptor() {
            @Override
            public void beforeAsyncJsonPost(String url, Object body, TimeUnit unit, long timeout) {
                String id = ThreadContext.get(Constants.THREAD_CONTEXT_API);

                if (id == null || !id.equals(apiId)) {
                    return;
                }

                Path p = new Path();
                p.type = Type.HttpRPC;
                p.path = String.format("[url:%s, cmd: %s]", url, body.getClass().getName());
                paths.add(p);
            }

            @Override
            public void beforeAsyncJsonPost(String url, String body, TimeUnit unit, long timeout) {
                String id = ThreadContext.get(Constants.THREAD_CONTEXT_API);

                if (id == null || !id.equals(apiId)) {
                    return;
                }

                Path p = new Path();
                p.type = Type.HttpRPC;
                p.path = String.format("[url:%s, cmd body: %s]", url, body);
                paths.add(p);
            }
        });
    }
}
