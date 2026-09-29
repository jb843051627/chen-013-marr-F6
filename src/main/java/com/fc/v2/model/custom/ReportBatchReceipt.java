package com.fc.v2.model.custom;

import com.fc.v2.model.auto.TMarrReportRow;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 整册核收回执：收了几行、退回几行一律以服务层逐行处理的给回为准，页面不得自己数。
 * 三计数在核收当回一次算定：acceptedCount + rejectedCount = rawCount（恒等），
 * 册头落库与本回执同口径、同源；超上限/白册/重发不认时 sealed=false，回执不得出现"核讫"。
 *
 * @author fuce
 * @date 2026-09-29
 */
public class ReportBatchReceipt implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 回执情形 0核讫 1部分退回 2白册 3超上限整册不接 4重复递送认头遍(不落册头) */
    public static final int RESULT_CLOSED = 0;
    public static final int RESULT_PARTIAL = 1;
    public static final int RESULT_EMPTY = 2;
    public static final int RESULT_OVERFLOW = 3;
    public static final int RESULT_DUPLICATE = 4;

    /** 册号 */
    private String batchNo;

    /** 回执情形码 */
    private int result;

    /** 是否重复递送（第二回，账上只认头一遍） */
    private boolean duplicate;

    /** 原册行数（同项并条后的记账行数） */
    private int rawCount;

    /** 收下（已汇纳）行数 */
    private int acceptedCount;

    /** 退回（挂驳回）行数 */
    private int rejectedCount;

    /** 收下各行对数合计（对） */
    private BigDecimal acceptedQty;

    /** 退回行明细（带原册行次与一句话缘故），按原册行次升序 */
    private List<TMarrReportRow> rejectedRows = new ArrayList<>();

    /** 回执上的一句话（部分退回/白册/超限/重发各有定辞；核讫字样以 sealed 为准） */
    private String message;

    /** 回执字样：仅头一遍、有行、且无一退回才允许"核讫" */
    public boolean isSealed() {
        return result == RESULT_CLOSED;
    }

    /** 恒等式：挂着的 + 汇纳的 = 原册行数 */
    public boolean isBalanced() {
        return acceptedCount + rejectedCount == rawCount;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public int getResult() {
        return result;
    }

    public void setResult(int result) {
        this.result = result;
    }

    public boolean isDuplicate() {
        return duplicate;
    }

    public void setDuplicate(boolean duplicate) {
        this.duplicate = duplicate;
    }

    public int getRawCount() {
        return rawCount;
    }

    public void setRawCount(int rawCount) {
        this.rawCount = rawCount;
    }

    public int getAcceptedCount() {
        return acceptedCount;
    }

    public void setAcceptedCount(int acceptedCount) {
        this.acceptedCount = acceptedCount;
    }

    public int getRejectedCount() {
        return rejectedCount;
    }

    public void setRejectedCount(int rejectedCount) {
        this.rejectedCount = rejectedCount;
    }

    public BigDecimal getAcceptedQty() {
        return acceptedQty;
    }

    public void setAcceptedQty(BigDecimal acceptedQty) {
        this.acceptedQty = acceptedQty;
    }

    public List<TMarrReportRow> getRejectedRows() {
        return rejectedRows;
    }

    public void setRejectedRows(List<TMarrReportRow> rejectedRows) {
        this.rejectedRows = rejectedRows;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
