package com.yupi.springbootinit.bizmq;

import com.rabbitmq.client.Channel;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.constant.CommonConstant;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.manager.AiManager;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.service.ChartService;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
@Slf4j
public class BiConsumer {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private ChartService chartService;

    @Resource
    private AiManager aiManager;

    @RabbitListener(queues = {BiMqConstant.BI_QUEUE_NAME},ackMode = "MANUAL")
    @SneakyThrows
    public void receiveMessage(String message, Channel channel,@Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag){

        if (StringUtils.isBlank(message)){
            channel.basicNack(deliveryTag,false,false);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"消息为空");
        }
        long chartId = Long.parseLong(message);
        Chart chart = chartService.getById(chartId);

        if (chart == null){
            channel.basicNack(deliveryTag,false,false);
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"图表为空");
        }

        Chart updateChart = new Chart();
        updateChart.setId(chart.getId());
        updateChart.setStatus("running");
        boolean b = chartService.updateById(updateChart);
        if (!b){
            channel.basicNack(deliveryTag,false,false);
            handlerChartUpdateErr(chart.getId(),"更新图表执行中状态失败");
            return;
        }
        // 调用ai
        String result = aiManager.doChart(CommonConstant.BI_MODEL_ID,buildUserInput(chart));
        String[] splits = result.split("【【【【【");
        if (splits.length < 3){
            channel.basicNack(deliveryTag,false,false);
            handlerChartUpdateErr(chart.getId(),"ai生成错误");
            return;
        }
        String genChart = splits[1].trim();
        String genResult = splits[2].trim();

        Chart updateChartResult = new Chart();
        updateChartResult.setId(chart.getId());
        updateChartResult.setGenChart(genChart);
        updateChartResult.setGenResult(genResult);
        updateChartResult.setStatus("success");
        boolean updateResult = chartService.updateById(updateChartResult);
        if (!updateResult){
            channel.basicNack(deliveryTag,false,false);
            handlerChartUpdateErr(chart.getId(),"更新图表成功状态失败");
        }
        channel.basicAck(deliveryTag,false);
    }

    private  String buildUserInput(Chart chart){
        String goal = chart.getGoal();
        String chartType = chart.getChartType();
        String csvData = chart.getChartData();

        // 用户输入
        StringBuilder userInput = new StringBuilder();
        userInput.append("分析需求：").append("\n");

        // 拼接目标
        String userGoal = goal;
        if (StringUtils.isNoneBlank(chartType)){
            userGoal += (", 请使用" + chartType);
        }
        userInput.append(userGoal).append("\n");
        userInput.append("原始数据：").append("\n");
        // 压缩后的原始数据
        userInput.append(csvData).append("\n");
        return userInput.toString();
    }

    private void handlerChartUpdateErr(long chartId,String message){
        Chart chart = new Chart();
        chart.setId(chartId);
        chart.setStatus("failed");
        chart.setExecMessage(message);
        boolean b = chartService.updateById(chart);
        if (!b){
            log.error("更新图表失败状态失败",chartId+","+ message);
        }
    }
}
