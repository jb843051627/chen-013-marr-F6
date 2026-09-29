package com.fc.v2.common.quartz.task;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fc.v2.service.ITMarrReportRowService;

/**
 * 登记统计报送册转史定时工作：收下的行满两个年度整行挪入史册。
 * 在定时任务里以 bean 名 marrReportArchiveTask.run 配置调用（参 V2Task 的调用约定）。
 *
 * @author fuce
 * @date 2026-09-29
 */
@Component("marrReportArchiveTask")
public class MarrReportArchiveTask {

    private static final Logger logger = LoggerFactory.getLogger(MarrReportArchiveTask.class);

    @Autowired
    private ITMarrReportRowService marrReportRowService;

    /** 按当前墙钟转史，返回挪走行数。 */
    public void run() {
        int moved = marrReportRowService.archiveDue(new Date());
        logger.info("报送册转史完成，本回挪入史册 {} 行", moved);
    }
}
