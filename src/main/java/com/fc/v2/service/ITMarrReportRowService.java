package com.fc.v2.service;

import java.util.Date;
import java.util.List;

import com.fc.v2.model.auto.TMarrReportBill;
import com.fc.v2.model.auto.TMarrReportRow;
import com.fc.v2.model.auto.TMarrReportRowHis;
import com.fc.v2.model.custom.ReportReceipt;

/**
 * 登记统计报送册核收转存行 Service接口（batch-process 形状：整册提交，无单条增删改）。
 *
 * 业务口径：
 * 1. 整册核收逐行过：合法行汇纳、非法行只退本行并写明一句一事实的缘故，不搞整册连坐；
 * 2. 同一册号只认头一遍，重发不盖先收的行、不给已退回行补缘故，改数走差异清单；
 * 3. 一册之内同报表项多行目合并为一条，对数相加只算一回，行次留先出现的；
 * 4. 越过行数上限整册不接、一行不落；白册子不出“核讫”；
 * 5. 计数与库内同口径、同一回计算：汇纳 + 挂驳回 + 候着转史 = 原册行数；
 * 6. 收下满两个年度的行先挂“候着转史”，再整行挪入史册，现行页只留册号可对。
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITMarrReportRowService {

    /** 按主键回查单行 */
    TMarrReportRow selectTMarrReportRowById(Long id);

    /**
     * 整批提交（原签名，继续可用）：逐行校验，合法的入库、非法的只退本行（保留原始行号）。
     * 返回汇纳（收下）行数；同一册号重发认头一遍，返回头遍汇纳数；
     * 行数越过单册上限返回 -1 且整册一行不落；空册返回 0 且不置核讫。
     */
    int submitBatch(String batchNo, List<TMarrReportRow> rows);

    /**
     * 整册核收（同名重载，多一种入参形式）：册头带报送期与县区，回执的合计与库内合计
     * 出自同一回计算，退回行逐行带原册行次与一句一事实的缘故。
     */
    ReportReceipt submitBatch(TMarrReportBill bill, List<TMarrReportRow> rows);

    /** 该册号的退回行明细（按原册行次升序） */
    List<TMarrReportRow> listErrors(String batchNo);

    /** 翻账：按册号拉现行页各行；status 取 0候着转史/1已汇纳/2挂驳回，null 为各堆全拉 */
    List<TMarrReportRow> listCurrentByBatch(String batchNo, Integer status);

    /** 翻账：按报表项代号拉现行页各行；status 口径同上 */
    List<TMarrReportRow> listCurrentByItem(String itemCode, Integer status);

    /** 翻账：按册号拉史册各行（现行页已翻不着，只留册号可对） */
    List<TMarrReportRowHis> listHistoryByBatch(String batchNo);

    /** 翻账：按报表项代号拉史册各行 */
    List<TMarrReportRowHis> listHistoryByItem(String itemCode);

    /** 按册号回查册头（回执计数以此为准，页面不许自己数） */
    TMarrReportBill getBill(String batchNo);

    /**
     * 把收下满两个年度的行挂成“候着转史”（以当日为截止），返回挂候行数。
     */
    int markMaturedForArchive();

    /**
     * 把收下满两个年度的行挂成“候着转史”（以 asOf 为截止，便于按期核算），返回挂候行数。
     */
    int markMaturedForArchive(Date asOf);

    /**
     * 把“候着转史”的行整行挪入史册并从现行页删除，返回挪档行数；
     * 挂候与挪档与册头计数在同一事务内完成。
     */
    int movePendingToHistory();

    /** 挂候加挪档一气做完，返回挪档行数。 */
    int archiveMatured();
}
