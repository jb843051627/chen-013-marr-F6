package com.fc.v2.service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.fc.v2.model.auto.TMarrReportRow;
import com.fc.v2.model.auto.TMarrReportRowHis;
import com.fc.v2.model.custom.ReportBatchReceipt;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

/**
 * 登记统计报送册核收转存行 Service接口（batch-process 形状：整册提交，逐行处理，无单条增删改）
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITMarrReportRowService {

    /** 按主键回查单行 */
    TMarrReportRow selectTMarrReportRowById(Long id);

    /** 只读条件查询（台账分页用；不开放任何写入） */
    List<TMarrReportRow> selectList(QueryWrapper<TMarrReportRow> queryWrapper);

    /**
     * 整批提交（原签名，继续可用）：逐行校验，合法的入库、非法的挂驳回并写明缘故，返回**收下行数**。
     * 同一册号重复提交只认头一遍，返回头遍收下行数；行数超过单册上限返回 -1 且整册一行不落；白册返回 0。
     */
    int submitBatch(String batchNo, List<TMarrReportRow> rows);

    /**
     * 整册核收（同名重载）：册号取头一行所载，较上月翻数十倍一条不查（无上月底数）。
     * 收几行退几行以本回执为准，页面不许自己数。
     */
    ReportBatchReceipt submitBatch(List<TMarrReportRow> rows);

    /**
     * 整册核收（同名重载，多一种入参形式）：priorMonthQty 给出各报表项上月对数底数，
     * 本行对数较上月翻出 30 倍及以上的，整行退回并写明倍数缘故。底数可由翻账口径从账上拉。
     */
    ReportBatchReceipt submitBatch(List<TMarrReportRow> rows, Map<String, BigDecimal> priorMonthQty);

    /**
     * 整册核收（同名重载，多一种入参形式）：册号显式给出并盖到每一行（行里自带的号不作数），
     * 底数同上一重载。原 {@link #submitBatch(String, List)} 两参方法仍可用、语义不变。
     */
    ReportBatchReceipt submitBatch(String batchNo, List<TMarrReportRow> rows, Map<String, BigDecimal> priorMonthQty);

    /** 该册退回（挂驳回）行明细，按原册行次升序 */
    List<TMarrReportRow> listErrors(String batchNo);

    /** 翻账·凭册号拉现行行（收下、退回各归各堆） */
    List<TMarrReportRow> listByBatch(String batchNo);

    /** 翻账·凭报表项拉现行行 */
    List<TMarrReportRow> listByItem(String itemCode);

    /** 翻账·按行进展堆拉：1收下 2退回（0候着转史为转史瞬态） */
    List<TMarrReportRow> listByStatus(int status);

    /** 翻账·凭册号拉史册行（现行页翻不着之后的去处） */
    List<TMarrReportRowHis> listHisByBatch(String batchNo);

    /** 翻账·凭报表项拉史册行 */
    List<TMarrReportRowHis> listHisByItem(String itemCode);

    /** 册头回执：合计三数以核收当回落库者为准；白册/超上限/未递过册号返回 null */
    ReportBatchReceipt getReceipt(String batchNo);

    /**
     * 满两个年度转史：头遍核收时间届满两个年度的册，其收下行整行挪入史册、现行页删除（册头册号留对）。
     * 返回挪走行数；可由定时任务周期调用。
     */
    int archiveDue(Date asOf);

    /** 同口径核对：按行表重算合计与册头落库合计是否一致（挂+汇=原册行数） */
    boolean reconcile(String batchNo);
}
