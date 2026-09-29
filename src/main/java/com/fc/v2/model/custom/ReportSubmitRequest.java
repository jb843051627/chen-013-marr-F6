package com.fc.v2.model.custom;

import com.fc.v2.model.auto.TMarrReportRow;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 整册核收提交入参：一册的全部行 + （可选）各报表项上月对数底数（查"翻出几十倍"用）。
 * 册号可由头一行携带，也可由本包装显式给出；行次、代号、对数之外不许多带任何手敲字段。
 *
 * @author fuce
 * @date 2026-09-29
 */
public class ReportSubmitRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 登记统计报送册号（可空，空则取头一行所载） */
    private String batchNo;

    /** 整册行（进门照原册抄入行次，不得手敲） */
    private List<TMarrReportRow> rows = new ArrayList<>();

    /** 各报表项上月对数底数：报表项代号 -> 上月对数（可空，空则不查数十倍） */
    private Map<String, BigDecimal> priorMonthQty;

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public List<TMarrReportRow> getRows() {
        return rows;
    }

    public void setRows(List<TMarrReportRow> rows) {
        this.rows = rows;
    }

    public Map<String, BigDecimal> getPriorMonthQty() {
        return priorMonthQty;
    }

    public void setPriorMonthQty(Map<String, BigDecimal> priorMonthQty) {
        this.priorMonthQty = priorMonthQty;
    }
}
